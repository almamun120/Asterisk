package com.asterisk.voip.core

import com.asterisk.voip.data.model.DialPlanCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything where the server itself is a SIP endpoint with audio:
 *  - answers a caller (200 OK + RTP) to play prompts / record voicemail / run IVR + queues
 *  - originates calls to extensions (queue members, IVR targets, call-back)
 *  - bridges two such legs into a normal two-party call
 *  - decodes DTMF (RFC 2833 and SIP INFO)
 */
class LegManager(private val srv: SipServer) {

    companion object {
        private const val HELD = 0       // caller not answered yet, failure response held back
        private const val ANSWERED = 1   // server owns the caller's audio
        private const val FORWARDED = 2  // call re-routed by the proxy
        private const val FINISHED = 3
    }

    class Leg(
        val callId: String,
        val isUac: Boolean,
        val ext: String,
        val remoteIp: String,
        val remotePort: Int,
        val rtp: RtpAudio
    ) {
        @Volatile var byeUri = ""
        @Volatile var fromHdr = ""
        @Volatile var toHdr = ""
        @Volatile var cseq = 100
        @Volatile var acked = false
        @Volatile var closed = false
        @Volatile var sdp = ""
        // UAC only
        @Volatile var state = 0          // 0 calling, 1 ringing, 2 answered, 3 failed
        @Volatile var code = 0
        @Volatile var cancelled = false
        @Volatile var inviteBranch = ""
        @Volatile var inviteUri = ""
        @Volatile var inviteTo = ""
        val closedSignal = CompletableDeferred<Unit>()
        /** Optional hook for non-audio legs (MSRP file delivery): called with the answer SDP. */
        @Volatile var onAnswer: ((String) -> Unit)? = null
    }

    class Session(val fromExt: String, @Volatile var toExt: String, val callId: String, val call: SipServer.Call?) {
        @Volatile var leg: Leg? = null
        @Volatile var state = HELD
        @Volatile var cancelled = false
        @Volatile var failureCode = 0
        @Volatile var retries = 0
        @Volatile var hops = 0
        @Volatile var passThrough: (() -> Unit)? = null
        val fired: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet<Long>())
    }

    private class PendingCallback(val from: String, val to: String, val max: Int) {
        @Volatile var tries = 0
        @Volatile var running = false
        val created = System.currentTimeMillis()
    }

    private val legs = ConcurrentHashMap<String, Leg>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val busy: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val callbacks = CopyOnWriteArrayList<PendingCallback>()

    // ------------------------------------------------------------ SDP helpers

    private class AudioOffer(
        val index: Int, val ip: String?, val port: Int,
        val pts: List<Int>, val dtmfPt: Int, val mLines: List<String>
    )

    private fun splitLines(s: String) = s.split("\r\n", "\n").filter { it.isNotEmpty() }

    private fun parseOffer(sdp: String): AudioOffer? {
        var sessionIp: String? = null
        var mediaIp: String? = null
        var idx = -1
        var chosen = -1
        var port = 0
        var pts: List<Int> = emptyList()
        var dtmf = -1
        val mLines = ArrayList<String>()
        var inChosen = false
        for (line in splitLines(sdp)) {
            if (line.startsWith("m=")) {
                idx++
                mLines.add(line)
                inChosen = false
                val p = line.substring(2).split(" ")
                if (chosen < 0 && p.size >= 4 && p[0] == "audio" && (p[1].substringBefore('/').toIntOrNull() ?: 0) != 0) {
                    chosen = idx
                    inChosen = true
                    port = p[1].substringBefore('/').toInt()
                    pts = p.drop(3).mapNotNull { it.toIntOrNull() }
                }
            } else if (line.startsWith("c=")) {
                val ip = line.substring(2).split(" ").getOrNull(2)?.substringBefore('/')
                if (idx < 0) sessionIp = ip else if (inChosen) mediaIp = ip
            } else if (inChosen && line.startsWith("a=rtpmap:")) {
                val rest = line.substring(9)
                val pt = rest.substringBefore(' ').toIntOrNull()
                if (pt != null && rest.substringAfter(' ').lowercase().startsWith("telephone-event")) dtmf = pt
            }
        }
        if (chosen < 0) return null
        return AudioOffer(chosen, mediaIp ?: sessionIp, port, pts, dtmf, mLines)
    }

    private fun audioSdp(ip: String, port: Int, alaw: Boolean, dtmfPt: Int): String {
        val pt = if (alaw) 8 else 0
        val sb = StringBuilder()
        sb.append("v=0\r\no=asterisk ${System.currentTimeMillis() / 1000} 1 IN IP4 $ip\r\ns=Asterisk\r\nc=IN IP4 $ip\r\nt=0 0\r\n")
        sb.append("m=audio $port RTP/AVP $pt${if (dtmfPt >= 0) " $dtmfPt" else ""}\r\n")
        sb.append("a=rtpmap:$pt ${if (alaw) "PCMA" else "PCMU"}/8000\r\n")
        if (dtmfPt >= 0) sb.append("a=rtpmap:$dtmfPt telephone-event/8000\r\na=fmtp:$dtmfPt 0-16\r\n")
        sb.append("a=ptime:20\r\na=sendrecv\r\n")
        return sb.toString()
    }

    private fun answerSdp(offer: AudioOffer, ip: String, port: Int, alaw: Boolean): String {
        val base = audioSdp(ip, port, alaw, offer.dtmfPt)
        if (offer.mLines.size <= 1) return base
        // keep m-line order; reject every other media
        val head = base.substringBefore("m=audio")
        val ours = "m=audio" + base.substringAfter("m=audio")
        val sb = StringBuilder(head)
        for (i in offer.mLines.indices) {
            if (i == offer.index) sb.append(ours)
            else {
                val p = offer.mLines[i].split(" ").toMutableList()
                if (p.size >= 2) p[1] = "0"
                sb.append(p.joinToString(" ")).append("\r\n")
            }
        }
        return sb.toString()
    }

    private fun contactUri(h: String?): String? {
        if (h == null) return null
        val m = Regex("<(sips?:[^>]+)>").find(h) ?: Regex("(sips?:[^;\\s>]+)").find(h) ?: return null
        return m.groupValues[1]
    }

    // ------------------------------------------------------------ answering a caller (UAS)

    private fun answerCall(s: Session, call: SipServer.Call): Leg? {
        val offer = parseOffer(call.invite.body)
        if (offer == null) { srv.slog("CALL", "✗ Cannot answer ${call.id}: no usable audio offer"); return null }
        val alaw = when {
            0 in offer.pts -> false
            8 in offer.pts -> true
            else -> { srv.slog("CALL", "✗ Caller offers no G.711 (PCMU/PCMA); cannot play prompts"); return null }
        }
        val cfg = srv.config
        val rtp = RtpAudio.open(cfg.rtpStartPort, cfg.rtpEndPort, if (cfg.qosEnabled) cfg.tos else 0)
        if (rtp == null) { srv.slog("ERR", "No free RTP port for prompt playback"); return null }
        rtp.alaw = alaw
        rtp.dtmfPt = if (offer.dtmfPt >= 0) offer.dtmfPt else 101
        rtp.setRemoteHint(offer.ip, offer.port)
        rtp.startReceiver()

        val ip = srv.ownIp(call.callerIp)
        val sdp = answerSdp(offer, ip, rtp.port, alaw)
        val ok = srv.makeReply(call.invite, 200, "OK", call.callerIp, call.callerPort,
            "Contact" to "<sip:asterisk@$ip:${srv.sipPortNow}>",
            "Allow" to "INVITE,ACK,CANCEL,BYE,OPTIONS,INFO,UPDATE,MESSAGE",
            "Content-Type" to "application/sdp")
        ok.body = sdp

        val leg = Leg(call.id, false, call.fromExt, call.callerIp, call.callerPort, rtp)
        leg.sdp = sdp
        leg.byeUri = contactUri(call.invite.header("Contact")) ?: "sip:${call.fromExt}@${call.callerIp}:${call.callerPort}"
        leg.fromHdr = ok.header("To") ?: ""
        leg.toHdr = call.invite.header("From") ?: ""
        legs[call.id] = leg
        s.leg = leg
        s.state = ANSWERED
        call.state = "ANNOUNCE"
        call.answeredAt = System.currentTimeMillis()
        srv.send(ok.toBytes(), call.callerIp, call.callerPort)
        srv.slog("CALL", "📣 Server answered ${call.fromExt}'s call (${if (alaw) "PCMA" else "PCMU"}) for prompts")

        // retransmit the 200 until the ACK arrives
        srv.scope.launch {
            var wait = 500L
            var n = 0
            while (!leg.acked && !leg.closed && n < 7) {
                delay(wait)
                if (leg.acked || leg.closed) break
                srv.send(ok.toBytes(), call.callerIp, call.callerPort)
                wait = minOf(wait * 2, 4000L); n++
            }
            if (!leg.acked && !leg.closed) { srv.slog("CALL", "⚠ No ACK from ${call.fromExt}, dropping"); closeLeg(leg, true) }
        }
        return leg
    }

    // ------------------------------------------------------------ originating a call (UAC)

    private fun newLegCallId() = "srv-${System.nanoTime()}-${(Math.random() * 1e6).toInt()}"

    /** Sends an INVITE with an audio offer to a registered extension. Null if unreachable. */
    private fun startOut(fromUser: String, ext: String, alaw: Boolean, dtmfPt: Int): Leg? {
        val dest = srv.registeredMap[ext] ?: return null
        val cfg = srv.config
        val rtp = RtpAudio.open(cfg.rtpStartPort, cfg.rtpEndPort, if (cfg.qosEnabled) cfg.tos else 0) ?: return null
        rtp.alaw = alaw
        rtp.dtmfPt = dtmfPt
        rtp.startReceiver()

        val ip = srv.ownIp(dest.ip)
        val callId = newLegCallId() + "@" + ip
        val leg = Leg(callId, true, ext, dest.ip, dest.port, rtp)
        val branch = srv.branchPrefix + "u" + System.nanoTime()
        val tag = "s" + (System.nanoTime() % 1000000)
        leg.inviteBranch = branch
        leg.inviteUri = "sip:$ext@${dest.ip}:${dest.port}"
        leg.inviteTo = "<sip:$ext@${dest.ip}:${dest.port}>"
        leg.fromHdr = "<sip:$fromUser@$ip>;tag=$tag"
        leg.sdp = audioSdp(ip, rtp.port, alaw, dtmfPt)
        leg.cseq = 1

        val m = SipMessage("INVITE ${leg.inviteUri} SIP/2.0")
        m.add("Via", "SIP/2.0/${srv.transport(dest.ip, dest.port)} $ip:${srv.sipPortNow};branch=$branch;rport")
        m.add("Max-Forwards", "70")
        m.add("From", leg.fromHdr)
        m.add("To", leg.inviteTo)
        m.add("Call-ID", callId)
        m.add("CSeq", "1 INVITE")
        m.add("Contact", "<sip:asterisk@$ip:${srv.sipPortNow}>")
        m.add("Allow", "INVITE,ACK,CANCEL,BYE,OPTIONS,INFO,UPDATE,MESSAGE")
        m.add("User-Agent", "Asterisk-Android")
        m.add("Content-Type", "application/sdp")
        m.body = leg.sdp
        legs[callId] = leg
        srv.send(m.toBytes(), dest.ip, dest.port)

        // UDP retransmits until the first response
        srv.scope.launch {
            var wait = 500L
            var n = 0
            while (leg.state == 0 && !leg.cancelled && !leg.closed && n < 5) {
                delay(wait)
                if (leg.state != 0 || leg.cancelled || leg.closed) break
                srv.send(m.toBytes(), dest.ip, dest.port)
                wait = minOf(wait * 2, 4000L); n++
            }
        }
        return leg
    }

    private fun sendAck(leg: Leg, resp: SipMessage, forSuccess: Boolean) {
        val uri = if (forSuccess) (contactUri(resp.header("Contact")) ?: leg.inviteUri) else leg.inviteUri
        val ip = srv.ownIp(leg.remoteIp)
        val a = SipMessage("ACK $uri SIP/2.0")
        val branch = if (forSuccess) srv.branchPrefix + "a" + System.nanoTime() else leg.inviteBranch
        a.add("Via", "SIP/2.0/${srv.transport(leg.remoteIp, leg.remotePort)} $ip:${srv.sipPortNow};branch=$branch;rport")
        a.add("Max-Forwards", "70")
        a.add("From", leg.fromHdr)
        a.add("To", resp.header("To") ?: leg.inviteTo)
        a.add("Call-ID", leg.callId)
        a.add("CSeq", "1 ACK")
        a.add("User-Agent", "Asterisk-Android")
        srv.send(a.toBytes(), leg.remoteIp, leg.remotePort)
    }

    private fun sendCancel(leg: Leg) {
        if (leg.state >= 2 || leg.cancelled) return
        leg.cancelled = true
        val ip = srv.ownIp(leg.remoteIp)
        val c = SipMessage("CANCEL ${leg.inviteUri} SIP/2.0")
        c.add("Via", "SIP/2.0/${srv.transport(leg.remoteIp, leg.remotePort)} $ip:${srv.sipPortNow};branch=${leg.inviteBranch};rport")
        c.add("Max-Forwards", "70")
        c.add("From", leg.fromHdr)
        c.add("To", leg.inviteTo)
        c.add("Call-ID", leg.callId)
        c.add("CSeq", "1 CANCEL")
        c.add("User-Agent", "Asterisk-Android")
        srv.send(c.toBytes(), leg.remoteIp, leg.remotePort)
    }

    /** Sends BYE and closes the leg. */
    fun closeLeg(leg: Leg, sendBye: Boolean) {
        if (leg.closed) return
        if (sendBye && (leg.acked || leg.isUac) && leg.byeUri.isNotEmpty()) {
            val ip = srv.ownIp(leg.remoteIp)
            val b = SipMessage("BYE ${leg.byeUri} SIP/2.0")
            b.add("Via", "SIP/2.0/${srv.transport(leg.remoteIp, leg.remotePort)} $ip:${srv.sipPortNow};branch=${srv.branchPrefix}b${System.nanoTime()};rport")
            b.add("Max-Forwards", "70")
            b.add("From", leg.fromHdr)
            b.add("To", leg.toHdr)
            b.add("Call-ID", leg.callId)
            leg.cseq += 1
            b.add("CSeq", "${leg.cseq} BYE")
            b.add("User-Agent", "Asterisk-Android")
            srv.send(b.toBytes(), leg.remoteIp, leg.remotePort)
        }
        markClosed(leg)
    }

    private fun markClosed(leg: Leg) {
        if (leg.closed) return
        leg.closed = true
        leg.rtp.close()
        leg.closedSignal.complete(Unit)
        // keep the entry briefly so responses to our BYE are swallowed
        srv.scope.launch { delay(5000); legs.remove(leg.callId) }
        if (!leg.isUac) {
            srv.callMap.remove(leg.callId)
            sessions.remove(leg.callId)
        }
    }

    // ------------------------------------------------------------ incoming traffic hooks

    /** Responses to requests the server itself sent. True if consumed. */
    fun onResponse(msg: SipMessage): Boolean {
        val leg = legs[msg.callId] ?: return false
        if (!leg.isUac) return true
        if (msg.cseqMethod != "INVITE") return true
        val code = msg.statusCode
        when {
            code == 100 -> {}
            code in 180..199 -> if (leg.state == 0) leg.state = 1
            code in 200..299 -> {
                if (leg.state >= 3 || leg.closed) return true
                if (leg.state < 2) {
                    leg.toHdr = msg.header("To") ?: leg.inviteTo
                    leg.byeUri = contactUri(msg.header("Contact")) ?: leg.inviteUri
                    leg.acked = true
                    val body = msg.body
                    val h = leg.onAnswer
                    if (h != null) {
                        leg.state = 2
                        sendAck(leg, msg, true)
                        try { h(body) } catch (e: Exception) { srv.slog("ERR", "answer hook: ${e.message}") }
                        return true
                    }
                    val a = parseOffer(body)
                    if (a != null) leg.rtp.setRemoteHint(a.ip, a.port)
                    leg.state = 2
                }
                sendAck(leg, msg, true)
                if (leg.cancelled) closeLeg(leg, true) // answered just as we gave up
            }
            code >= 300 -> {
                if (leg.state < 2) { leg.state = 3; leg.code = code }
                sendAck(leg, msg, false)
            }
        }
        return true
    }

    /** In-dialog requests for legs the server owns. True if consumed. */
    fun onRequest(msg: SipMessage, ip: String, port: Int): Boolean {
        val leg = legs[msg.callId] ?: return false
        when (msg.requestMethod) {
            "ACK" -> leg.acked = true
            "BYE" -> {
                srv.sendReply(msg, 200, "OK", ip, port)
                srv.slog("CALL", "📴 ${leg.ext} hung up (server-handled call)")
                markClosed(leg)
            }
            "INFO" -> {
                val m = Regex("(?i)signal\\s*=\\s*([0-9A-D*#])").find(msg.body)
                val c = m?.groupValues?.get(1)?.get(0) ?: msg.body.trim().takeIf { it.length == 1 }?.get(0)
                if (c != null) leg.rtp.dtmf.trySend(c)
                srv.sendReply(msg, 200, "OK", ip, port)
            }
            "INVITE" -> srv.makeReply(msg, 200, "OK", ip, port, "Contact" to "<sip:asterisk@${srv.ownIp(ip)}:${srv.sipPortNow}>",
                "Content-Type" to "application/sdp").also { it.body = leg.sdp; srv.send(it.toBytes(), ip, port) }
            else -> srv.sendReply(msg, 200, "OK", ip, port)
        }
        return true
    }

    fun hasLeg(callId: String) = legs.containsKey(callId)

    // ------------------------------------------------------------ rule execution entry points

    /** The callee failed (or is offline): run the rules while the caller is still waiting. */
    fun handleFailure(call: SipServer.Call, code: Int, conds: List<DialPlanCondition>, passThrough: () -> Unit) {
        val ex = srv.executor ?: run { passThrough(); return }
        val s = sessions.getOrPut(call.id) { Session(call.fromExt, call.toExt, call.id, call) }
        s.toExt = call.toExt
        s.failureCode = code
        s.passThrough = passThrough
        s.state = HELD
        call.state = "HANDLING"
        srv.scope.launch {
            val ctx = Ctx(s)
            try {
                ex.execute(conds, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                srv.slog("ERR", "Dial plan error: ${e.message}")
            }
            finishSession(s, ctx)
        }
    }

    private fun finishSession(s: Session, ctx: Ctx) {
        when (s.state) {
            HELD -> ctx.end()                    // nothing handled it: original failure goes to the caller
            ANSWERED -> ctx.end()                // hang up politely
            else -> {}
        }
    }

    /** Dial *97 */
    fun runVoicemailMenu(call: SipServer.Call) {
        val ex = srv.executor ?: return
        val s = sessions.getOrPut(call.id) { Session(call.fromExt, call.fromExt, call.id, call) }
        s.passThrough = { srv.sendReply(call.invite, 480, "Temporarily Unavailable", call.callerIp, call.callerPort); srv.callMap.remove(call.id); sessions.remove(call.id) }
        call.state = "HANDLING"
        srv.scope.launch {
            val ctx = Ctx(s)
            try { ex.voicemailMenu(call.fromExt, ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                srv.slog("ERR", "Voicemail menu error: ${e.message}")
            }
            finishSession(s, ctx)
        }
    }

    /** The caller sent CANCEL while rules were running. */
    fun callerCancelled(callId: String) {
        val s = sessions[callId] ?: return
        s.cancelled = true
        if (s.state == HELD) s.state = FINISHED // nothing more to send to this caller
        s.leg?.let { closeLeg(it, false) }
    }

    fun firedRules(callId: String): Set<Long> {
        val f = sessions[callId]?.fired ?: return emptySet()
        synchronized(f) { return HashSet(f) }
    }

    fun endCall(callId: String) {
        sessions.remove(callId)
    }

    fun isAvailable(ext: String): Boolean =
        srv.registeredMap.containsKey(ext) && !busy.contains(ext) &&
            srv.callMap.values.none { (it.fromExt == ext || it.toExt == ext) && (it.state == "CONNECTED" || it.state == "HELD" || it.state == "RINGING") }

    fun stop() {
        legs.values.forEach { try { it.rtp.close() } catch (_: Exception) {} }
        legs.clear(); sessions.clear(); busy.clear(); callbacks.clear()
    }

    // ------------------------------------------------------------ call-back

    /** Rings the requested pairs back once both are available. Call every few seconds and on registration. */
    fun callbackTick() {
        val now = System.currentTimeMillis()
        for (pc in callbacks) {
            if (now - pc.created > 24L * 3600 * 1000) { callbacks.remove(pc); continue }
            if (pc.running) continue
            if (isAvailable(pc.from) && isAvailable(pc.to)) {
                pc.running = true
                srv.scope.launch { runCallback(pc) }
            }
        }
    }

    private suspend fun runCallback(pc: PendingCallback) {
        try {
            pc.tries++
            srv.slog("CALL", "↩ Call-back ${pc.to} ↔ ${pc.from} (try ${pc.tries}/${pc.max})")
            val first = startOut(pc.to, pc.from, false, 101)
            if (first == null) return
            val s = Session(pc.from, pc.to, first.callId, null)
            s.leg = first
            s.state = ANSWERED
            val end = System.currentTimeMillis() + 30_000
            while (first.state < 2 && first.state != 3 && System.currentTimeMillis() < end) delay(100)
            if (first.state != 2) { sendCancel(first); closeLeg(first, false); return }
            val ctx = Ctx(s)
            val say = srv.prompts?.renderTts("Your call back to extension ${pc.to.toList().joinToString(" ")} is being connected.", "en-US")
            if (say != null) ctx.play(say)
            val who = ctx.bridge(listOf(pc.to), 30_000L, null)
            closeLeg(first, true)
            if (who != null) { callbacks.remove(pc); srv.slog("CALL", "✅ Call-back connected") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            srv.slog("ERR", "Call-back error: ${e.message}")
        } finally {
            pc.running = false
            if (pc.tries >= pc.max) callbacks.remove(pc)
        }
    }

    // ------------------------------------------------------------ MSRP file delivery leg

    /** Originates an INVITE carrying [sdp] to [ext]; [onAnswer] gets the 200 OK's SDP. Returns the leg or null. */
    fun originateRaw(fromUser: String, ext: String, sdp: String, onAnswer: (String) -> Unit): Leg? {
        val dest = srv.registeredMap[ext] ?: return null
        val ip = srv.ownIp(dest.ip)
        val callId = newLegCallId() + "@" + ip
        // a dummy RTP object keeps Leg's type uniform; never used for MSRP
        val dummy = RtpAudio.open(srv.config.rtpStartPort, srv.config.rtpEndPort, 0) ?: return null
        val leg = Leg(callId, true, ext, dest.ip, dest.port, dummy)
        val branch = srv.branchPrefix + "u" + System.nanoTime()
        leg.inviteBranch = branch
        leg.inviteUri = "sip:$ext@${dest.ip}:${dest.port}"
        leg.inviteTo = "<${leg.inviteUri}>"
        leg.fromHdr = "<sip:$fromUser@$ip>;tag=s${System.nanoTime() % 1000000}"
        leg.sdp = sdp
        leg.cseq = 1
        leg.onAnswer = onAnswer
        val m = SipMessage("INVITE ${leg.inviteUri} SIP/2.0")
        m.add("Via", "SIP/2.0/${srv.transport(dest.ip, dest.port)} $ip:${srv.sipPortNow};branch=$branch;rport")
        m.add("Max-Forwards", "70")
        m.add("From", leg.fromHdr)
        m.add("To", leg.inviteTo)
        m.add("Call-ID", callId)
        m.add("CSeq", "1 INVITE")
        m.add("Contact", "<sip:asterisk@$ip:${srv.sipPortNow}>")
        m.add("User-Agent", "Asterisk-Android")
        m.add("Content-Type", "application/sdp")
        m.body = sdp
        legs[callId] = leg
        srv.send(m.toBytes(), dest.ip, dest.port)
        return leg
    }

    /** Answers a caller's INVITE locally with a ready-made SDP (MSRP receive). Returns the leg. */
    fun answerRaw(call: SipServer.Call, sdp: String): Leg {
        val dummy = RtpAudio.open(srv.config.rtpStartPort, srv.config.rtpEndPort, 0)
            ?: throw IllegalStateException("no free port")
        val ip = srv.ownIp(call.callerIp)
        val ok = srv.makeReply(call.invite, 200, "OK", call.callerIp, call.callerPort,
            "Contact" to "<sip:asterisk@$ip:${srv.sipPortNow}>", "Content-Type" to "application/sdp")
        ok.body = sdp
        val leg = Leg(call.id, false, call.fromExt, call.callerIp, call.callerPort, dummy)
        leg.sdp = sdp
        leg.byeUri = contactUri(call.invite.header("Contact")) ?: "sip:${call.fromExt}@${call.callerIp}:${call.callerPort}"
        leg.fromHdr = ok.header("To") ?: ""
        leg.toHdr = call.invite.header("From") ?: ""
        legs[call.id] = leg
        call.state = "ANNOUNCE"
        srv.send(ok.toBytes(), call.callerIp, call.callerPort)
        srv.scope.launch {
            var wait = 500L; var n = 0
            while (!leg.acked && !leg.closed && n < 7) {
                delay(wait)
                if (leg.acked || leg.closed) break
                srv.send(ok.toBytes(), call.callerIp, call.callerPort)
                wait = minOf(wait * 2, 4000L); n++
            }
        }
        return leg
    }

    // ------------------------------------------------------------ the CallContext implementation

    inner class Ctx(private val s: Session) : CallContext {
        override val fromExt get() = s.fromExt
        override val toExt get() = s.toExt
        override val callId get() = s.callId
        override val failureCode get() = s.failureCode
        override val answered get() = s.state == ANSWERED
        override val alive: Boolean
            get() = !s.cancelled && s.state != FINISHED && (s.leg?.closed != true)

        override var retries: Int
            get() = s.retries
            set(v) { s.retries = v }
        override var hops: Int
            get() = s.hops
            set(v) { s.hops = v }

        override suspend fun answer(): Boolean {
            val existing = s.leg
            if (existing != null) return !existing.closed && s.state == ANSWERED
            if (s.state != HELD || s.cancelled) return false
            val call = s.call ?: return false
            return answerCall(s, call) != null
        }

        override suspend fun play(pcm: ShortArray): Boolean {
            val leg = s.leg ?: return false
            if (leg.closed) return false
            return leg.rtp.playPcm(pcm) { leg.closed || s.cancelled }
        }

        override suspend fun waitDigit(timeoutMs: Long): Char? {
            val leg = s.leg ?: return null
            return withTimeoutOrNull(timeoutMs) { leg.rtp.dtmf.receiveCatching().getOrNull() }
        }

        override suspend fun record(maxMs: Long): ShortArray {
            val leg = s.leg ?: return ShortArray(0)
            leg.rtp.startRecording()
            val start = System.currentTimeMillis()
            while (!leg.closed && !s.cancelled) {
                val now = System.currentTimeMillis()
                if (now - start >= maxMs) break
                val d = withTimeoutOrNull(500) { leg.rtp.dtmf.receiveCatching().getOrNull() }
                if (d == '#') break
                if (now - leg.rtp.lastRxAt > 10_000 && now - start > 3000) break
            }
            return leg.rtp.stopRecording()
        }

        override fun available(ext: String) = isAvailable(ext)

        override suspend fun bridge(targets: List<String>, ringMs: Long, moh: ShortArray?): String? {
            val caller = s.leg ?: return null
            if (caller.closed) return null
            val outs = ArrayList<Leg>()
            for (t in targets) {
                if (t == s.fromExt) continue
                val l = startOut(s.fromExt, t, caller.rtp.alaw, caller.rtp.dtmfPt)
                if (l != null) outs.add(l)
            }
            if (outs.isEmpty()) return null
            srv.slog("CALL", "🔔 Ringing ${outs.joinToString(", ") { it.ext }} for ${s.fromExt}")

            var mohJob: Job? = null
            if (moh != null) {
                mohJob = srv.scope.launch {
                    while (isActive && !caller.closed) {
                        if (!caller.rtp.playPcm(moh) { caller.closed }) break
                    }
                }
            }
            val deadline = System.currentTimeMillis() + ringMs
            var winner: Leg? = null
            while (System.currentTimeMillis() < deadline && !caller.closed && !s.cancelled) {
                winner = outs.firstOrNull { it.state == 2 && !it.closed }
                if (winner != null) break
                if (outs.all { it.state == 3 || it.closed }) break
                delay(50)
            }
            mohJob?.cancelAndJoin()
            for (o in outs) {
                if (o === winner) continue
                if (o.state < 2) { sendCancel(o); srv.scope.launch { delay(4000); markClosed(o) } }
                else closeLeg(o, true)
            }
            if (winner == null) return null
            if (caller.closed) { closeLeg(winner, true); return null }

            val w: Leg = winner
            caller.rtp.bridge = w.rtp
            w.rtp.bridge = caller.rtp
            busy.add(w.ext); busy.add(s.fromExt)
            s.call?.let { it.state = "CONNECTED"; it.answeredAt = System.currentTimeMillis() }
            srv.slog("CALL", "✅ ${s.fromExt} ↔ ${w.ext} connected through the server")
            try {
                while (!caller.closed && !w.closed && !s.cancelled) delay(200)
            } finally {
                caller.rtp.bridge = null
                w.rtp.bridge = null
                busy.remove(w.ext); busy.remove(s.fromExt)
            }
            if (!w.closed) closeLeg(w, true)
            srv.slog("CALL", "📴 Bridged call ${s.fromExt} ↔ ${w.ext} ended")
            return w.ext
        }

        override suspend fun forwardTo(ext: String): Boolean {
            val call = s.call ?: return false
            if (s.state != HELD || s.cancelled) return false
            val ok = srv.retarget(call, ext)
            if (ok) { s.state = FORWARDED; s.toExt = ext }
            return ok
        }

        override fun end() {
            when (s.state) {
                HELD -> { s.state = FINISHED; s.passThrough?.invoke() }
                ANSWERED -> {
                    s.state = FINISHED
                    s.leg?.let { closeLeg(it, true) }
                    s.call?.let { srv.callMap.remove(it.id) }
                }
                else -> {}
            }
        }

        override fun reject(code: Int) {
            if (s.state == HELD) {
                val call = s.call
                s.state = FINISHED
                if (call != null) {
                    srv.sendReply(call.invite, code, "Rejected", call.callerIp, call.callerPort)
                    call.state = "FAILED"; call.endedAt = System.currentTimeMillis()
                }
            } else end()
        }

        override fun sendText(toExt: String, text: String) = srv.sendSystemText(toExt, text)

        override fun markFired(ruleId: Long): Boolean = s.fired.add(ruleId)

        override fun scheduleCallback(maxTries: Int) {
            if (callbacks.any { it.from == s.fromExt && it.to == s.toExt }) return
            callbacks.add(PendingCallback(s.fromExt, s.toExt, maxTries))
            srv.slog("CALL", "📝 Call-back requested: ${s.toExt} will ring ${s.fromExt} when available")
        }
    }
}
