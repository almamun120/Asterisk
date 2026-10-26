package com.asterisk.voip.core

import com.asterisk.voip.data.model.CallInfo
import com.asterisk.voip.data.model.Trunk
import com.asterisk.voip.data.model.TrunkInfo
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** SIP digest (RFC 2617 / 7616) client helper. */
internal object Digest {
    fun parse(h: String): Map<String, String> {
        val p = mutableMapOf<String, String>()
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(h.substringAfter("Digest", h)).forEach {
            p[it.groupValues[1].lowercase()] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        return p
    }

    private fun hash(alg: String, s: String): String {
        val name = if (alg.uppercase().startsWith("SHA-256")) "SHA-256" else "MD5"
        return MessageDigest.getInstance(name).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun respond(ch: Map<String, String>, user: String, pass: String, method: String, uri: String, nc: Int = 1): String {
        val realm = ch["realm"] ?: ""
        val nonce = ch["nonce"] ?: ""
        val alg = ch["algorithm"] ?: "MD5"
        val qop = ch["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val ncs = "%08x".format(nc)
        val ha1 = hash(alg, "$user:$realm:$pass")
        val ha2 = hash(alg, "$method:$uri")
        val resp = if (qop != null) hash(alg, "$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else hash(alg, "$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\", algorithm=$alg")
        if (qop != null) sb.append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
        ch["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }
}

/**
 * Gateway / SIP-trunk subsystem.
 *
 *  - keeps any number of external VoIP accounts registered (digest auth, refresh, retry with
 *    back-off, NAT keep-alive, public address learned from the provider's reply),
 *  - outbound: an extension dials a number that matches a trunk's prefix -> B2BUA call to the
 *    provider (own dialog, digest-authenticated INVITE, failover to the next matching trunk),
 *    audio always goes through the media relay,
 *  - inbound: [inboundTrunk]/[mapInbound] let SipServer treat a provider's INVITE like a call
 *    from an (authorised) caller to the configured extension, so dial plan, voicemail etc. apply.
 */
class GatewayManager(private val srv: SipServer) {

    companion object {
        private const val BR = "z9hG4bKgw"
        private const val UA = "Asterisk-Android"
        private val STATIC_PT = mapOf("0" to "PCMU", "3" to "GSM", "8" to "PCMA", "9" to "G722", "18" to "G729")
        private val CODEC_NAMES = mapOf("ulaw" to "PCMU", "pcmu" to "PCMU", "alaw" to "PCMA", "pcma" to "PCMA",
            "gsm" to "GSM", "g722" to "G722", "g729" to "G729")

        /** Keeps only [allowed] audio codecs (+ telephone-event). Null when nothing is left. */
        fun filterCodecs(sdp: String, allowed: Set<String>): String? {
            val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }
            val blocks = mutableListOf(mutableListOf<String>())
            for (l in lines) { if (l.startsWith("m=")) blocks.add(mutableListOf()); blocks.last().add(l) }
            val out = StringBuilder()
            for ((bi, b) in blocks.withIndex()) {
                if (b.isEmpty()) continue
                val parts = b[0].split(" ")
                if (bi == 0 || !b[0].startsWith("m=audio") || parts.size < 4 || parts[1] == "0") {
                    b.forEach { out.append(it).append("\r\n") }; continue
                }
                val rtpmap = b.filter { it.startsWith("a=rtpmap:") }.associate {
                    val v = it.substring(9); v.substringBefore(' ') to v.substringAfter(' ').substringBefore('/').uppercase()
                }
                fun nameOf(pt: String) = rtpmap[pt] ?: STATIC_PT[pt]
                val kept = parts.drop(3).filter { pt -> val n = nameOf(pt); n != null && (n in allowed || n == "TELEPHONE-EVENT") }
                if (kept.none { nameOf(it) != "TELEPHONE-EVENT" }) return null
                out.append(parts.take(3).joinToString(" ")).append(' ').append(kept.joinToString(" ")).append("\r\n")
                for (l in b.drop(1)) {
                    val pt = when {
                        l.startsWith("a=rtpmap:") -> l.substring(9).substringBefore(' ')
                        l.startsWith("a=fmtp:") -> l.substring(7).substringBefore(' ')
                        else -> null
                    }
                    if (pt != null && pt !in kept) continue
                    out.append(l).append("\r\n")
                }
            }
            return out.toString()
        }

        private fun rnd(n: Int = 10) = UUID.randomUUID().toString().replace("-", "").take(n)
        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ runtime state

    inner class Rt(@Volatile var t: Trunk) {
        @Volatile var state = "STARTING"
        @Volatile var detail = ""
        @Volatile var lastOk = 0L
        @Volatile var ip = ""
        @Volatile var port = 5060
        @Volatile var addrs: Set<String> = emptySet()
        @Volatile var pubIp: String? = null
        @Volatile var pubPort = 0
        @Volatile var challenge: Map<String, String>? = null
        @Volatile var wantQuick = false
        val cid = "gw${t.id}-${rnd(12)}"
        val tag = rnd(8)
        val cseq = AtomicInteger(0)
        val active = AtomicInteger(0)
        var job: Job? = null
    }

    private class GwCall(
        val id: String,
        val ext: String,
        val dialed: String,
        val invite: SipMessage,
        val callerIp: String,
        val callerPort: Int,
        val callerSdp: String
    ) {
        val time = System.currentTimeMillis()
        @Volatile var state = "CALLING"       // CALLING, RINGING, CONNECTED, ENDED
        @Volatile var endedAt = 0L
        @Volatile var attemptAt = System.currentTimeMillis()
        @Volatile var cancelled = false
        @Volatile var acked = false
        @Volatile var rt: Rt? = null
        @Volatile var target = ""
        val candidates = mutableListOf<Trunk>()
        // trunk-side dialog
        @Volatile var tCid = ""
        @Volatile var tFromTag = ""
        @Volatile var tToTag = ""
        @Volatile var tCseq = 1
        @Volatile var tBranch = ""
        @Volatile var tReqUri = ""
        @Volatile var tRemote = ""
        @Volatile var tRoutes: List<String> = emptyList()
        @Volatile var tIp = ""
        @Volatile var tPort = 0
        @Volatile var authTried = false
        @Volatile var offerSdp = ""
        @Volatile var okBytes: ByteArray? = null
        @Volatile var srvCseq = 0
        @Volatile var reinviteMsg: SipMessage? = null
        @Volatile var reinviteCseq = 0
        @Volatile var counted = false
        val uasTag: String = md5(id + "srv").take(10)
        val callerContact: String? = Regex("<?(sips?:[^>;\\s]+)", RegexOption.IGNORE_CASE)
            .find(invite.header("Contact") ?: "")?.groupValues?.get(1)
        val ringTimeoutMs = 90_000L
    }

    private val rts = ConcurrentHashMap<Long, Rt>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<SipMessage>>()
    private val calls = ConcurrentHashMap<String, GwCall>()
    private val byTrunkCid = ConcurrentHashMap<String, GwCall>()
    @Volatile private var running = false

    private val cfg get() = srv.config

    // ------------------------------------------------------------ lifecycle

    suspend fun start() {
        running = true
        reload()
    }

    /** Sends REGISTER Expires:0 (fire and forget) and stops everything. Call before the sockets close. */
    fun stop() {
        running = false
        rts.values.forEach { rt ->
            rt.job?.cancel()
            if (rt.t.register && rt.state == "REGISTERED") try { sendRegister(rt, 0, true) } catch (_: Exception) {}
        }
        rts.clear()
        waiters.values.forEach { it.cancel() }
        waiters.clear()
        calls.clear()
        byTrunkCid.clear()
    }

    /** Re-reads the trunk table; starts, restarts or stops accounts whose settings changed. */
    suspend fun reload() {
        if (!running) return
        val list = try { srv.repo.getTrunksNow() } catch (e: Exception) { srv.slog("GW", "Cannot read trunks: ${e.message}"); return }
        val ids = list.map { it.id }.toSet()
        for ((id, rt) in rts.toMap()) if (id !in ids) { shutdown(rt); rts.remove(id) }
        for (t in list) {
            val old = rts[t.id]
            if (old != null && old.t == t) continue
            if (old != null) shutdown(old)
            val rt = Rt(t)
            rts[t.id] = rt
            if (!t.enabled) { rt.state = "DISABLED"; continue }
            rt.job = srv.scope.launch { runTrunk(rt) }
            srv.slog("GW", "▶ Gateway '${t.name}' (${t.host}) started")
        }
    }

    private fun shutdown(rt: Rt) {
        rt.job?.cancel()
        if (rt.t.register && rt.state == "REGISTERED") try { sendRegister(rt, 0, true) } catch (_: Exception) {}
    }

    fun retryNow(id: Long) { rts[id]?.let { it.wantQuick = true } }

    // ------------------------------------------------------------ status for the UI

    fun trunkInfos(): List<TrunkInfo> = rts.values.sortedBy { it.t.priority }.map {
        TrunkInfo(it.t.id, it.t.name, it.t.host, it.t.username, it.state, it.detail, it.lastOk, it.active.get(),
            if (it.pubIp != null) "${it.pubIp}:${it.pubPort}" else "")
    }

    fun activeCount() = calls.values.count { it.state != "ENDED" }

    fun callInfos(): List<CallInfo> = calls.values.filter { it.state != "ENDED" }.sortedBy { it.time }.map {
        CallInfo(it.id, it.ext, "${it.target.ifEmpty { it.dialed }} via ${it.rt?.t?.name ?: "gateway"}", it.state, it.time,
            true, false, srv.mediaRelay?.session(it.id)?.packets?.get() ?: 0L)
    }

    // ------------------------------------------------------------ addressing

    private fun resolve(rt: Rt): Boolean {
        val t = rt.t
        val proxy = t.outboundProxy.trim()
        var host = if (proxy.isNotEmpty()) proxy.substringBefore(':') else t.host.trim()
        var port = if (proxy.contains(':')) proxy.substringAfter(':').toIntOrNull() ?: t.port else t.port
        if (host.isEmpty()) return false
        return try {
            val all = (InetAddress.getAllByName(host).toList() + InetAddress.getAllByName(t.host.trim()).toList())
                .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            if (all.isEmpty()) return false
            val first = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: all.first()
            rt.ip = first; rt.port = port; rt.addrs = all.toSet() + first
            true
        } catch (e: Exception) { false }
    }

    private fun domain(t: Trunk) = t.domain.trim().ifEmpty { t.host.trim() }
    private fun authUser(t: Trunk) = t.authUser.trim().ifEmpty { t.username }

    /** Address we put in Via. */
    private fun viaAddr(peerIp: String) = srv.ownIp(peerIp)

    /** Address the provider should reach us at (Contact / SDP): configured external IP, else learned, else local. */
    private fun contactAddr(rt: Rt): Pair<String, Int> {
        val c = cfg
        val ext = c.externalIP.trim()
        if (c.natEnabled && ext.isNotEmpty()) return Pair(ext, srv.sipPortNow)
        val p = rt.pubIp
        if (p != null) return Pair(p, if (rt.pubPort > 0) rt.pubPort else srv.sipPortNow)
        return Pair(srv.ownIp(rt.ip), srv.sipPortNow)
    }

    private fun mediaAddr(rt: Rt?, peerIp: String): String {
        val c = cfg
        val ext = c.externalIP.trim()
        if (rt != null) {
            if (c.natEnabled && ext.isNotEmpty()) return ext
            rt.pubIp?.let { return it }
        }
        return srv.ownIp(peerIp)
    }

    // ------------------------------------------------------------ registration

    private suspend fun runTrunk(rt: Rt) {
        var backoff = 15_000L
        while (srv.scope.isActive && running) {
            try {
                rt.state = "RESOLVING"
                if (!resolve(rt)) {
                    rt.state = "FAILED"; rt.detail = "DNS: cannot resolve ${rt.t.host}"
                    sleepOrWake(rt, backoff); backoff = (backoff * 2).coerceAtMost(300_000L); continue
                }
                if (!rt.t.register) { pingLoop(rt); return }
                val granted = registerOnce(rt)
                if (granted > 0) {
                    backoff = 15_000L
                    val refreshMs = (granted * 800L).coerceAtLeast(10_000L)
                    holdRegistration(rt, if (rt.wantQuick) 1000L else refreshMs)
                } else {
                    sleepOrWake(rt, backoff); backoff = (backoff * 2).coerceAtMost(300_000L)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                rt.state = "FAILED"; rt.detail = e.message ?: "error"
                delay(backoff)
            }
        }
    }

    private suspend fun sleepOrWake(rt: Rt, ms: Long) {
        var left = ms
        rt.wantQuick = false
        while (left > 0 && !rt.wantQuick) { delay(500); left -= 500 }
    }

    /** Waits [ms] sending NAT keep-alives; returns early when a retry was requested. */
    private suspend fun holdRegistration(rt: Rt, ms: Long) {
        var left = ms
        var sinceKa = 0L
        rt.wantQuick = false
        val ka = rt.t.keepAlive * 1000L
        while (left > 0 && !rt.wantQuick) {
            delay(1000); left -= 1000; sinceKa += 1000
            if (ka > 0 && sinceKa >= ka) { sinceKa = 0; srv.send("\r\n\r\n".toByteArray(), rt.ip, rt.port) }
        }
    }

    private suspend fun pingLoop(rt: Rt) {
        rt.state = "NO-REG"; rt.detail = "IP-authenticated trunk"
        while (srv.scope.isActive && running) {
            val m = baseRequest(rt, "OPTIONS", "sip:${domain(rt.t)}", rt.cid + "-opt", rt.cseq.incrementAndGet())
            val r = transact(m, rt.ip, rt.port)
            if (r != null) { rt.state = "REACHABLE"; rt.detail = "OPTIONS ${r.statusCode}"; rt.lastOk = System.currentTimeMillis() }
            else { rt.state = "UNREACHABLE"; rt.detail = "no answer to OPTIONS" }
            holdRegistration(rt, 60_000L)
        }
    }

    private fun baseRequest(rt: Rt, method: String, uri: String, callId: String, cseq: Int): SipMessage {
        val t = rt.t
        val dom = domain(t)
        val m = SipMessage("$method $uri SIP/2.0")
        m.add("Via", "SIP/2.0/UDP ${viaAddr(rt.ip)}:${srv.sipPortNow};branch=$BR${rnd(12)};rport")
        m.add("Max-Forwards", "70")
        m.add("From", "<sip:${t.username}@$dom>;tag=${rt.tag}")
        m.add("To", "<sip:${t.username}@$dom>")
        m.add("Call-ID", callId)
        m.add("CSeq", "$cseq $method")
        m.add("User-Agent", UA)
        return m
    }

    private fun sendRegister(rt: Rt, expires: Int, withCachedAuth: Boolean = false) {
        val m = buildRegister(rt, expires, rt.cseq.incrementAndGet())
        if (withCachedAuth) rt.challenge?.let { ch ->
            val hdr = if (ch["__proxy"] == "1") "Proxy-Authorization" else "Authorization"
            m.add(hdr, Digest.respond(ch, authUser(rt.t), rt.t.password, "REGISTER", "sip:${domain(rt.t)}", 1))
        }
        srv.send(m.toBytes(), rt.ip, rt.port)
    }

    private fun buildRegister(rt: Rt, expires: Int, cseq: Int): SipMessage {
        val t = rt.t
        val m = baseRequest(rt, "REGISTER", "sip:${domain(t)}", rt.cid, cseq)
        val (cIp, cPort) = contactAddr(rt)
        m.add("Contact", "<sip:${t.username}@$cIp:$cPort>;expires=$expires")
        m.add("Expires", expires.toString())
        m.add("Allow", "INVITE,ACK,CANCEL,OPTIONS,BYE,INFO")
        return m
    }

    /** Returns granted seconds (>0) or -1 on failure. */
    private suspend fun registerOnce(rt: Rt): Int {
        val t = rt.t
        rt.state = "REGISTERING"; rt.detail = ""
        var expires = t.expiry.coerceIn(30, 3600)
        var authHeader: Pair<String, String>? = null
        var attempts = 0
        val uri = "sip:${domain(t)}"
        while (attempts < 4) {
            attempts++
            val m = buildRegister(rt, expires, rt.cseq.incrementAndGet())
            authHeader?.let { m.add(it.first, it.second) }
            val r = transact(m, rt.ip, rt.port)
            if (r == null) { rt.state = "FAILED"; rt.detail = "no response from ${rt.ip}:${rt.port}"; srv.slog("GW", "✗ ${t.name}: no response"); return -1 }
            when (r.statusCode) {
                in 200..299 -> {
                    val via = r.header("Via")
                    val rec = SipMessage.paramOf(via, "received")
                    val rp = SipMessage.paramOf(via, "rport")?.toIntOrNull()
                    val changed = rec != null && (rec != rt.pubIp || (rp ?: 0) != rt.pubPort)
                    if (rec != null) { rt.pubIp = rec; rt.pubPort = rp ?: 0 }
                    val granted = Regex("expires=(\\d+)", RegexOption.IGNORE_CASE).findAll(r.headerList("Contact").joinToString(","))
                        .firstOrNull()?.groupValues?.get(1)?.toIntOrNull()
                        ?: r.header("Expires")?.trim()?.toIntOrNull() ?: expires
                    rt.state = "REGISTERED"; rt.detail = "expires in ${granted}s"; rt.lastOk = System.currentTimeMillis()
                    srv.slog("GW", "✅ ${t.name}: registered as ${t.username}@${domain(t)} (${granted}s)${if (rec != null) " public $rec:${rp ?: "?"}" else ""}")
                    if (changed && cfg.externalIP.isBlank()) rt.wantQuick = true // re-register once with the learned Contact
                    return granted.coerceAtLeast(30)
                }
                401, 407 -> {
                    val proxy = r.statusCode == 407
                    val h = r.header(if (proxy) "Proxy-Authenticate" else "WWW-Authenticate") ?: run {
                        rt.state = "FAILED"; rt.detail = "challenge without header"; return -1
                    }
                    val ch = Digest.parse(h)
                    if (authHeader != null && ch["stale"]?.equals("true", true) != true) {
                        rt.state = "AUTH FAILED"; rt.detail = "wrong username/password (${r.statusCode})"
                        srv.slog("GW", "✗ ${t.name}: authentication failed"); return -1
                    }
                    rt.challenge = ch + ("__proxy" to if (proxy) "1" else "0")
                    authHeader = (if (proxy) "Proxy-Authorization" else "Authorization") to
                        Digest.respond(ch, authUser(t), t.password, "REGISTER", uri, 1)
                }
                423 -> expires = r.header("Min-Expires")?.trim()?.toIntOrNull() ?: (expires * 2)
                else -> {
                    rt.state = "FAILED"; rt.detail = "${r.statusCode} ${r.startLine.substringAfter(' ').substringAfter(' ')}"
                    srv.slog("GW", "✗ ${t.name}: REGISTER → ${rt.detail}"); return -1
                }
            }
        }
        rt.state = "FAILED"; rt.detail = "registration loop"; return -1
    }

    /** Sends [m] over UDP with retransmissions and waits for a final response. */
    private suspend fun transact(m: SipMessage, ip: String, port: Int): SipMessage? {
        val key = "${m.callId}|${m.cseqNum}"
        val d = CompletableDeferred<SipMessage>()
        waiters[key] = d
        try {
            val bytes = m.toBytes()
            for (wait in longArrayOf(500, 1000, 2000, 4000, 8000)) {
                srv.send(bytes, ip, port)
                val r = withTimeoutOrNull(wait) { d.await() }
                if (r != null) return r
            }
            return null
        } finally { waiters.remove(key) }
    }

    // ------------------------------------------------------------ inbound (provider -> us)

    private fun parseMap(s: String): Map<String, String> = s.split(',', ';', '\n').mapNotNull {
        val p = it.split('=', ':'); if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0].trim() to p[1].trim() else null
    }.toMap()

    /** The trunk an INVITE belongs to when it comes from a provider, else null. */
    fun inboundTrunk(msg: SipMessage, ip: String, port: Int): Trunk? {
        // a registered extension is never a gateway, even if it shares an address
        if (srv.registeredMap.values.any { it.ip == ip && it.port == port }) return null
        val active = rts.values.filter { it.t.enabled }
        active.firstOrNull { ip in it.addrs }?.let { return it.t }
        val ruriUser = SipMessage.userOf(msg.requestUri)
        val fromHost = Regex("sips?:[^@>;\\s]*@([^;>:\\s]+)", RegexOption.IGNORE_CASE).find(msg.header("From") ?: "")?.groupValues?.get(1)
        return active.firstOrNull { rt ->
            rt.state == "REGISTERED" && ruriUser != null && ruriUser == rt.t.username &&
                fromHost != null && (fromHost.equals(rt.t.host, true) || fromHost.equals(domain(rt.t), true))
        }?.t
    }

    /** Local extension an inbound call from [t] should ring, or null. */
    fun mapInbound(t: Trunk, msg: SipMessage): String? {
        val did = SipMessage.userOf(msg.requestUri) ?: SipMessage.userOf(msg.header("To")) ?: ""
        parseMap(t.inboundMap)[did]?.let { return it }
        parseMap(t.inboundMap)[did.removePrefix("+")]?.let { return it }
        return t.inboundTarget.trim().ifEmpty { null }
    }

    // ------------------------------------------------------------ outbound (extension -> provider)

    private fun normalize(n: String) = n.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun matches(t: Trunk, num: String): Int? {
        var best: Int? = null
        for (p in t.outPrefixes.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val len = if (p == "*") 0 else if (num.startsWith(p)) p.length else continue
            if (best == null || len > best) best = len
        }
        return best
    }

    private fun targetNumber(t: Trunk, num: String) = t.addPrefix + num.drop(t.stripDigits.coerceAtLeast(0))

    private fun candidatesFor(num: String): List<Trunk> {
        data class C(val t: Trunk, val len: Int, val ok: Boolean)
        val c = rts.values.filter { it.t.enabled }.mapNotNull { rt ->
            matches(rt.t, num)?.let { C(rt.t, it, rt.state == "REGISTERED" || rt.state == "REACHABLE" || rt.state == "NO-REG") }
        }
        return c.sortedWith(compareBy<C>({ !it.ok }, { -it.len }, { it.t.priority })).map { it.t }
    }

    /** True when a trunk route exists and the call is now handled here. */
    fun startOutbound(msg: SipMessage, ip: String, port: Int, ext: String, dialed: String): Boolean {
        val num = normalize(dialed)
        if (num.isEmpty()) return false
        val cands = candidatesFor(num)
        if (cands.isEmpty()) return false
        val id = msg.callId
        val call = GwCall(id, ext, num, msg.clone(), ip, port, msg.body)
        call.candidates.addAll(cands)
        calls[id] = call
        srv.sendReply(msg, 100, "Trying", ip, port)
        srv.slog("GW", "📤 $ext → $num via gateway (${cands.joinToString { it.name }})")
        tryNext(call, 503, "Service Unavailable")
        return true
    }

    /** Starts an attempt on the next candidate. Returns false (after replying to the caller) when none are left. */
    private fun tryNext(call: GwCall, failCode: Int, failReason: String): Boolean {
        while (call.candidates.isNotEmpty()) {
            val t = call.candidates.removeAt(0)
            val rt = rts[t.id] ?: continue
            if (rt.ip.isEmpty()) continue
            if (t.maxChannels > 0 && rt.active.get() >= t.maxChannels) continue
            call.rt?.let { old -> if (call.counted) { old.active.decrementAndGet(); call.counted = false } }
            call.rt = rt
            rt.active.incrementAndGet(); call.counted = true
            if (sendInvite(call, rt)) return true
        }
        failCaller(call, failCode, failReason)
        return false
    }

    private fun sendInvite(call: GwCall, rt: Rt): Boolean {
        val t = rt.t
        val dom = domain(t)
        val sdp = offerFor(call, rt) ?: return false
        call.offerSdp = sdp
        call.target = targetNumber(t, call.dialed)
        call.tCid = "${rnd(16)}@${viaAddr(rt.ip)}"
        call.tFromTag = rnd(8); call.tToTag = ""; call.tCseq = 1; call.authTried = false
        call.tReqUri = "sip:${call.target}@$dom"
        call.tIp = rt.ip; call.tPort = rt.port
        byTrunkCid[call.tCid] = call
        call.state = "CALLING"; call.attemptAt = System.currentTimeMillis()
        srv.send(buildInvite(call, rt, null).toBytes(), rt.ip, rt.port)
        srv.slog("GW", "→ INVITE ${call.tReqUri} via ${t.name}")
        return true
    }

    private fun buildInvite(call: GwCall, rt: Rt, auth: Pair<String, String>?): SipMessage {
        val t = rt.t
        val dom = domain(t)
        val from = t.callerId.trim().ifEmpty { t.username }
        val m = SipMessage("INVITE ${call.tReqUri} SIP/2.0")
        call.tBranch = BR + rnd(12)
        m.add("Via", "SIP/2.0/UDP ${viaAddr(rt.ip)}:${srv.sipPortNow};branch=${call.tBranch};rport")
        m.add("Max-Forwards", "70")
        m.add("From", "\"$from\" <sip:${t.username}@$dom>;tag=${call.tFromTag}")
        m.add("To", "<sip:${call.target}@$dom>")
        m.add("Call-ID", call.tCid)
        m.add("CSeq", "${call.tCseq} INVITE")
        val (cIp, cPort) = contactAddr(rt)
        m.add("Contact", "<sip:${t.username}@$cIp:$cPort>")
        m.add("Allow", "INVITE,ACK,CANCEL,OPTIONS,BYE,INFO,UPDATE")
        m.add("User-Agent", UA)
        auth?.let { m.add(it.first, it.second) }
        m.add("Content-Type", "application/sdp")
        m.body = call.offerSdp
        return m
    }

    /** Caller's SDP, relayed (audio only), with the trunk's codec restriction. */
    private fun offerFor(call: GwCall, rt: Rt): String? {
        val relayed = relaySdp(call, call.callerSdp, MediaRelay.SIDE_A, rt) ?: return null
        val names = rt.t.codecs.split(',', ';', ' ').mapNotNull { CODEC_NAMES[it.trim().lowercase()] }.toSet()
        if (names.isEmpty()) return relayed
        return filterCodecs(relayed, names)
    }

    /** Rewrites [sdp] coming from [originSide] so media goes through the relay. */
    private fun relaySdp(call: GwCall, sdp: String, originSide: Int, rt: Rt?): String? {
        if (!sdp.startsWith("v=")) return sdp
        val r = srv.mediaRelay ?: return sdp
        val session = r.open(call.id)
        val trunkIp = rt?.ip ?: call.tIp
        val originIp = if (originSide == MediaRelay.SIDE_A) call.callerIp else trunkIp
        val target = 1 - originSide
        var any = false
        for (m in SdpUtil.parseMedia(sdp)) {
            if (!m.relayable || !m.type.equals("audio", true)) continue
            any = true
            val st = r.ensureStream(session, m.index, m.type) ?: continue
            if (originSide == MediaRelay.SIDE_B || m.connIp == originIp) r.hint(st, originSide, m.connIp, m.port, m.effectiveRtcpPort)
        }
        if (!any || session.streams.isEmpty()) return null
        val ip = if (originSide == MediaRelay.SIDE_A) mediaAddr(rt, trunkIp) else srv.ownIp(call.callerIp)
        return SdpUtil.rewriteForRelay(sdp, ip, false) { m ->
            if (!m.type.equals("audio", true)) null else session.streams.firstOrNull { it.index == m.index }?.ports(target)
        }
    }

    // ------------------------------------------------------------ responses from the provider

    fun onResponse(msg: SipMessage, ip: String, port: Int): Boolean {
        val cid = msg.callId
        val w = waiters[cid + "|" + msg.cseqNum]
        if (w != null) { if (msg.statusCode >= 200) w.complete(msg); return true }
        val call = byTrunkCid[cid] ?: return false
        if (msg.cseqMethod == "INVITE") onInviteResponse(call, msg, ip, port)
        return true
    }

    private fun onInviteResponse(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        val code = msg.statusCode
        val rt = call.rt ?: return
        if (msg.cseqNum != call.tCseq) { // stale transaction
            if (code >= 300) sendAckNon2xx(call, msg, rt, ip, port)
            else if (code in 200..299 && call.reinviteCseq == msg.cseqNum) onReinviteOk(call, msg, ip, port)
            return
        }
        when {
            code == 100 -> {}
            code in 101..199 -> {
                if (call.state == "ENDED" || call.cancelled) return
                msg.toTag?.let { call.tToTag = it }
                call.state = "RINGING"
                if (msg.body.startsWith("v=") && code == 183) {
                    val sdp = relaySdp(call, msg.body, MediaRelay.SIDE_B, rt)
                    if (sdp != null) replyCaller(call, 183, "Session Progress", sdp) else replyCaller(call, 180, "Ringing")
                } else replyCaller(call, 180, "Ringing")
            }
            code in 200..299 -> onInviteOk(call, msg, ip, port)
            code == 401 || code == 407 -> {
                sendAckNon2xx(call, msg, rt, ip, port)
                if (call.cancelled) return
                val h = msg.header(if (code == 407) "Proxy-Authenticate" else "WWW-Authenticate")
                if (call.authTried || h == null) {
                    srv.slog("GW", "✗ ${rt.t.name}: INVITE authentication rejected")
                    failover(call, 502, "Bad Gateway")
                    return
                }
                call.authTried = true
                call.tCseq++
                val auth = (if (code == 407) "Proxy-Authorization" else "Authorization") to
                    Digest.respond(Digest.parse(h), authUser(rt.t), rt.t.password, "INVITE", call.tReqUri, 1)
                srv.send(buildInvite(call, rt, auth).toBytes(), call.tIp, call.tPort)
            }
            else -> {
                sendAckNon2xx(call, msg, rt, ip, port)
                if (call.state == "ENDED") return
                if (call.cancelled) return
                val retryable = code == 408 || code in 500..599 || code == 403
                srv.slog("GW", "✗ ${rt.t.name}: INVITE → $code")
                if (retryable && call.candidates.isNotEmpty()) failover(call, code, "Service Unavailable")
                else {
                    val c = when (code) { 408 -> 408; in 500..599 -> 503; else -> code }
                    val reason = msg.startLine.substringAfter(' ').substringAfter(' ').ifBlank { "Failed" }
                    byTrunkCid.remove(call.tCid)
                    failCaller(call, c, reason)
                }
            }
        }
    }

    private fun failover(call: GwCall, code: Int, reason: String) {
        byTrunkCid.remove(call.tCid)
        if (call.candidates.isEmpty()) { failCaller(call, code, reason); return }
        srv.slog("GW", "↪ failover to next gateway")
        tryNext(call, code, reason)
    }

    private fun failCaller(call: GwCall, code: Int, reason: String) {
        if (call.state != "ENDED" && !call.cancelled) replyCaller(call, code, reason)
        finish(call)
    }

    private fun onInviteOk(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        val rt = call.rt ?: return
        if (call.state == "CONNECTED") { sendAck2xx(call, rt); return }
        call.tToTag = msg.toTag ?: call.tToTag
        call.tRemote = Regex("<?(sips?:[^>;\\s]+)", RegexOption.IGNORE_CASE).find(msg.header("Contact") ?: "")?.groupValues?.get(1) ?: call.tReqUri
        call.tRoutes = msg.headerList("Record-Route").reversed()
        call.tIp = ip; call.tPort = port
        sendAck2xx(call, rt)
        if (call.cancelled || call.state == "ENDED") { sendBye(call); finish(call); return }
        val sdp = relaySdp(call, msg.body, MediaRelay.SIDE_B, rt)
        if (sdp == null) {
            srv.slog("GW", "✗ ${rt.t.name}: answer without usable audio")
            sendBye(call); failCaller(call, 488, "Not Acceptable Here"); return
        }
        call.state = "CONNECTED"
        srv.slog("GW", "✓ answered: ${call.ext} ↔ ${call.target} (${rt.t.name})")
        val ok = srv.makeReply(call.invite, 200, "OK", call.callerIp, call.callerPort,
            "Contact" to ownContact(call), "Content-Type" to "application/sdp")
        ok.body = sdp
        val bytes = ok.toBytes()
        call.okBytes = bytes
        srv.send(bytes, call.callerIp, call.callerPort)
        srv.scope.launch { // retransmit the 200 until the caller's ACK arrives
            var w = 500L
            var spent = 0L
            while (!call.acked && call.state == "CONNECTED" && spent < 32_000) {
                delay(w); spent += w; w = (w * 2).coerceAtMost(4000)
                if (!call.acked && call.state == "CONNECTED") srv.send(bytes, call.callerIp, call.callerPort)
            }
            if (!call.acked && call.state == "CONNECTED") { srv.slog("GW", "caller never ACKed, hanging up"); hangup(call, true) }
        }
    }

    private fun ownContact(call: GwCall) = "<sip:asterisk@${srv.ownIp(call.callerIp)}:${srv.sipPortNow}>"

    private fun replyCaller(call: GwCall, code: Int, reason: String, sdp: String? = null) {
        val extra = mutableListOf("Contact" to ownContact(call))
        if (sdp != null) extra.add("Content-Type" to "application/sdp")
        val r = srv.makeReply(call.invite, code, reason, call.callerIp, call.callerPort, *extra.toTypedArray())
        if (sdp != null) r.body = sdp
        srv.send(r.toBytes(), call.callerIp, call.callerPort)
    }

    // ------------------------------------------------------------ trunk-side requests we send

    private fun viaTrunk(rt: Rt, branch: String) = "SIP/2.0/UDP ${viaAddr(rt.ip)}:${srv.sipPortNow};branch=$branch;rport"

    private fun dialogRequest(call: GwCall, method: String, cseq: Int, branch: String = BR + rnd(12)): SipMessage {
        val rt = call.rt!!
        val dom = domain(rt.t)
        val from = rt.t.callerId.trim().ifEmpty { rt.t.username }
        val m = SipMessage("$method ${call.tRemote.ifEmpty { call.tReqUri }} SIP/2.0")
        call.tRoutes.forEach { m.add("Route", it) }
        m.add("Via", viaTrunk(rt, branch))
        m.add("Max-Forwards", "70")
        m.add("From", "\"$from\" <sip:${rt.t.username}@$dom>;tag=${call.tFromTag}")
        m.add("To", "<sip:${call.target}@$dom>" + if (call.tToTag.isNotEmpty()) ";tag=${call.tToTag}" else "")
        m.add("Call-ID", call.tCid)
        m.add("CSeq", "$cseq $method")
        m.add("User-Agent", UA)
        return m
    }

    private fun routeDest(call: GwCall): Pair<String, Int> = Pair(call.tIp, call.tPort)

    private fun sendAck2xx(call: GwCall, rt: Rt) {
        val m = dialogRequest(call, "ACK", call.tCseq)
        val (ip, port) = routeDest(call)
        srv.send(m.toBytes(), ip, port)
    }

    private fun sendAckNon2xx(call: GwCall, resp: SipMessage, rt: Rt, ip: String, port: Int) {
        val m = SipMessage("ACK ${call.tReqUri} SIP/2.0")
        m.add("Via", viaTrunk(rt, call.tBranch))
        m.add("Max-Forwards", "70")
        m.add("From", resp.header("From") ?: "")
        m.add("To", resp.header("To") ?: "")
        m.add("Call-ID", resp.callId)
        m.add("CSeq", "${resp.cseqNum} ACK")
        m.add("User-Agent", UA)
        srv.send(m.toBytes(), ip, port)
    }

    private fun sendBye(call: GwCall) {
        if (call.rt == null || call.tToTag.isEmpty()) return
        val m = dialogRequest(call, "BYE", ++call.tCseq)
        val (ip, port) = routeDest(call)
        srv.send(m.toBytes(), ip, port)
    }

    private fun sendCancel(call: GwCall) {
        val rt = call.rt ?: return
        val dom = domain(rt.t)
        val from = rt.t.callerId.trim().ifEmpty { rt.t.username }
        val m = SipMessage("CANCEL ${call.tReqUri} SIP/2.0")
        m.add("Via", viaTrunk(rt, call.tBranch))
        m.add("Max-Forwards", "70")
        m.add("From", "\"$from\" <sip:${rt.t.username}@$dom>;tag=${call.tFromTag}")
        m.add("To", "<sip:${call.target}@$dom>")
        m.add("Call-ID", call.tCid)
        m.add("CSeq", "${call.tCseq} CANCEL")
        srv.send(m.toBytes(), call.tIp, call.tPort)
    }

    /** BYE (or other in-dialog request) from us to the caller. */
    private fun requestToCaller(call: GwCall, method: String, body: String = "", ctype: String? = null): ByteArray {
        val uri = call.callerContact ?: "sip:${call.ext}@${call.callerIp}:${call.callerPort}"
        val dest = srv.registeredMap[call.ext]
        val m = SipMessage("$method $uri SIP/2.0")
        m.add("Via", "SIP/2.0/UDP ${srv.ownIp(call.callerIp)}:${srv.sipPortNow};branch=$BR${rnd(12)};rport")
        m.add("Max-Forwards", "70")
        val to = call.invite.header("To") ?: ""
        m.add("From", if (to.contains("tag=", true)) to else "$to;tag=${call.uasTag}")
        m.add("To", call.invite.header("From") ?: "")
        m.add("Call-ID", call.id)
        m.add("CSeq", "${++call.srvCseq} $method")
        m.add("User-Agent", UA)
        if (ctype != null) m.add("Content-Type", ctype)
        m.body = body
        val ip = dest?.ip ?: call.callerIp
        val port = dest?.port ?: call.callerPort
        srv.send(m.toBytes(), ip, port)
        return m.toBytes()
    }

    // ------------------------------------------------------------ ending calls

    private fun finish(call: GwCall) {
        if (call.state == "ENDED" && call.endedAt != 0L) return
        call.state = "ENDED"; call.endedAt = System.currentTimeMillis()
        srv.mediaRelay?.close(call.id)
        if (call.counted) { call.rt?.active?.decrementAndGet(); call.counted = false }
    }

    private fun hangup(call: GwCall, notifyCaller: Boolean) {
        if (call.state == "ENDED") return
        sendBye(call)
        if (notifyCaller) requestToCaller(call, "BYE")
        finish(call)
    }

    // ------------------------------------------------------------ requests

    /** Requests that belong to a gateway call (either side). */
    fun onRequest(msg: SipMessage, ip: String, port: Int): Boolean {
        calls[msg.callId]?.let { onCallerRequest(it, msg, ip, port); return true }
        byTrunkCid[msg.callId]?.let { onTrunkRequest(it, msg, ip, port); return true }
        return false
    }

    private fun onCallerRequest(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        when (msg.requestMethod) {
            "INVITE" -> {
                if (msg.cseqNum == call.invite.cseqNum) { // retransmission
                    val ok = call.okBytes
                    if (call.state == "CONNECTED" && ok != null) srv.send(ok, ip, port) else srv.sendReply(msg, 100, "Trying", ip, port)
                } else if (call.state == "CONNECTED") onCallerReinvite(call, msg, ip, port)
                else srv.sendReply(msg, 491, "Request Pending", ip, port)
            }
            "ACK" -> call.acked = true
            "CANCEL" -> {
                srv.sendReply(msg, 200, "OK", ip, port)
                if (call.state == "CALLING" || call.state == "RINGING") {
                    call.cancelled = true
                    sendCancel(call)
                    srv.makeReply(call.invite, 487, "Request Terminated", call.callerIp, call.callerPort)
                        .let { srv.send(it.toBytes(), call.callerIp, call.callerPort) }
                    srv.slog("GW", "❌ caller cancelled ${call.ext} → ${call.dialed}")
                    finish(call)
                }
            }
            "BYE" -> {
                srv.sendReply(msg, 200, "OK", ip, port)
                if (call.state != "ENDED") {
                    srv.slog("GW", "📴 ${call.ext} hung up (${(System.currentTimeMillis() - call.time) / 1000}s)")
                    sendBye(call); finish(call)
                }
            }
            "INFO" -> {
                srv.sendReply(msg, 200, "OK", ip, port)
                if (call.state == "CONNECTED") {
                    val m = dialogRequest(call, "INFO", ++call.tCseq)
                    msg.header("Content-Type")?.let { m.add("Content-Type", it) }
                    m.body = msg.body
                    srv.send(m.toBytes(), call.tIp, call.tPort)
                }
            }
            "REFER" -> srv.sendReply(msg, 501, "Not Implemented", ip, port)
            else -> srv.sendReply(msg, 200, "OK", ip, port)
        }
    }

    private fun onCallerReinvite(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        val rt = call.rt ?: return
        val sdp = relaySdp(call, msg.body, MediaRelay.SIDE_A, rt)
        if (sdp == null && msg.body.isNotEmpty()) { srv.sendReply(msg, 488, "Not Acceptable Here", ip, port); return }
        srv.sendReply(msg, 100, "Trying", ip, port)
        call.reinviteMsg = msg
        val m = dialogRequest(call, "INVITE", ++call.tCseq)
        call.reinviteCseq = call.tCseq
        val (cIp, cPort) = contactAddr(rt)
        m.add("Contact", "<sip:${rt.t.username}@$cIp:$cPort>")
        if (sdp != null && sdp.isNotEmpty()) {
            val names = rt.t.codecs.split(',', ';', ' ').mapNotNull { CODEC_NAMES[it.trim().lowercase()] }.toSet()
            val out = if (names.isEmpty()) sdp else (filterCodecs(sdp, names) ?: sdp)
            m.add("Content-Type", "application/sdp"); m.body = out
        }
        srv.send(m.toBytes(), call.tIp, call.tPort)
    }

    private fun onReinviteOk(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        val rt = call.rt ?: return
        val orig = call.reinviteMsg ?: return
        // ACK for the re-INVITE
        val ack = dialogRequest(call, "ACK", msg.cseqNum)
        srv.send(ack.toBytes(), call.tIp, call.tPort)
        call.reinviteMsg = null; call.reinviteCseq = 0
        val sdp = if (msg.body.startsWith("v=")) relaySdp(call, msg.body, MediaRelay.SIDE_B, rt) else null
        val hdrs = mutableListOf("Contact" to ownContact(call))
        if (sdp != null) hdrs.add("Content-Type" to "application/sdp")
        val r = srv.makeReply(orig, 200, "OK", call.callerIp, call.callerPort, *hdrs.toTypedArray())
        if (sdp != null) r.body = sdp
        srv.send(r.toBytes(), call.callerIp, call.callerPort)
    }

    private fun onTrunkRequest(call: GwCall, msg: SipMessage, ip: String, port: Int) {
        when (msg.requestMethod) {
            "BYE" -> {
                srv.sendReply(msg, 200, "OK", ip, port)
                if (call.state == "CONNECTED") {
                    srv.slog("GW", "📴 ${call.rt?.t?.name} ended call ${call.ext} ↔ ${call.target}")
                    requestToCaller(call, "BYE"); finish(call)
                } else if (call.state != "ENDED") finish(call)
            }
            "INVITE", "UPDATE" -> { // provider refresh: keep the session as it is
                val hdrs = mutableListOf("Contact" to "<sip:${call.rt?.t?.username}@${srv.ownIp(ip)}:${srv.sipPortNow}>")
                if (msg.requestMethod == "INVITE") hdrs.add("Content-Type" to "application/sdp")
                val r = srv.makeReply(msg, 200, "OK", ip, port, *hdrs.toTypedArray())
                if (msg.requestMethod == "INVITE") r.body = call.offerSdp
                srv.send(r.toBytes(), ip, port)
            }
            "ACK" -> {}
            else -> srv.sendReply(msg, 200, "OK", ip, port)
        }
    }

    // ------------------------------------------------------------ periodic

    fun tick(now: Long) {
        for (call in calls.values.toList()) {
            when (call.state) {
                "CALLING", "RINGING" -> if (!call.cancelled && now - call.attemptAt > call.ringTimeoutMs) {
                    srv.slog("GW", "⏱ no answer from provider: ${call.ext} → ${call.dialed}")
                    call.cancelled = true
                    sendCancel(call)
                    replyCaller(call, 408, "Request Timeout")
                    finish(call)
                }
                "CONNECTED" -> {
                    val idle = cfg.relayIdleTimeout
                    val s = srv.mediaRelay?.session(call.id)
                    if (idle > 0 && s != null && now - s.lastActivity > idle * 1000L && now - call.time > idle * 1000L) {
                        srv.slog("GW", "⏱ no media for ${idle}s, closing ${call.ext} ↔ ${call.target}")
                        hangup(call, true)
                    }
                }
                "ENDED" -> if (now - call.endedAt > 32_000L) { calls.remove(call.id); byTrunkCid.remove(call.tCid) }
            }
        }
    }
}
