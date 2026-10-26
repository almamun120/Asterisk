package com.asterisk.voip.core

import com.asterisk.voip.data.model.*
import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SIP registrar + proxy + media relay.
 *
 *  - Registrations are persisted (Room) and restored on start, so extensions
 *    stay online across server restarts.
 *  - Signalling always passes through the server (Record-Route), so the server
 *    sees every BYE and always cleans up.
 *  - Media can be relayed through the server (audio + video + RTCP) or sent
 *    directly between the two devices; "auto" picks direct when both devices
 *    are on the same LAN and relay otherwise.
 *  - UDP and TCP transports.
 */
class SipServer(
    private val repository: AsteriskRepository,
    private val logger: (LogLevel, String, String) -> Unit
) {
    companion object {
        const val REALM = "asterisk"
        internal const val BRANCH_PREFIX = "z9hG4bKsv"
        private const val FAILED_LINGER_MS = 32_000L
        internal const val RING_TIMEOUT_MS = 120_000L
    }

    // ------------------------------------------------------------ state

    class Client(
        val ext: String,
        val ip: String,
        val port: Int,
        @Volatile var expiry: Long,
        val transport: String,
        val userAgent: String,
        @Volatile var lastSeen: Long
    )

    class Call(
        val id: String,
        val fromExt: String,
        var toExt: String,
        val callerTag: String?,
        val callerIp: String,
        val callerPort: Int,
        var calleeIp: String,
        var calleePort: Int,
        var relayed: Boolean,
        val invite: SipMessage,
        val time: Long = System.currentTimeMillis()
    ) {
        @Volatile var state = "TRYING"
        @Volatile var answeredAt = 0L
        @Volatile var endedAt = 0L
        @Volatile var video = false
        @Volatile var inviteCSeq = 0
        @Volatile var attemptAt = System.currentTimeMillis()
        @Volatile var ringTimeoutMs = RING_TIMEOUT_MS
        @Volatile var fork = 0
        @Volatile var branch = ""
        @Volatile var fwdUri = ""
    }

    private class TcpConn(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        @Synchronized fun write(b: ByteArray) { out.write(b); out.flush() }
    }

    private var udp: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val tcpConns = ConcurrentHashMap<String, TcpConn>()
    @Volatile private var running = false
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val registered = ConcurrentHashMap<String, Client>()
    private val calls = ConcurrentHashMap<String, Call>()
    private var relay: MediaRelay? = null

    @Volatile private var cfg = ServerConfig()
    @Volatile private var sipPort = 5060
    @Volatile private var localIp = "0.0.0.0"
    private val nonceKey = UUID.randomUUID().toString()
    private val authFails = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val banned = ConcurrentHashMap<String, Long>()

    private var dialPlanExecutor: DialPlanExecutor? = null
    private val legs = LegManager(this)
    private val gateway = GatewayManager(this)
    private var msrp: MsrpManager? = null

    // accessors for LegManager / MsrpManager (same module)
    internal val config: ServerConfig get() = cfg
    internal val sipPortNow: Int get() = sipPort
    internal val branchPrefix: String get() = BRANCH_PREFIX
    internal val registeredMap: ConcurrentHashMap<String, Client> get() = registered
    internal val callMap: ConcurrentHashMap<String, Call> get() = calls
    internal val executor: DialPlanExecutor? get() = dialPlanExecutor
    internal val prompts: VoicePromptManager? get() = voicePromptManager
    internal val legManager: LegManager get() = legs
    internal val repo: AsteriskRepository get() = repository
    internal val mediaRelay: MediaRelay? get() = relay
    @Volatile internal var storageDir: java.io.File = java.io.File("/data/local/tmp")
    internal suspend fun queueText(fromExt: String, toExt: String, contentType: String, text: String) =
        storeOfflineMessage(fromExt, toExt, contentType, text)
    internal val scope: CoroutineScope get() = this.serverScope
    internal fun slog(tag: String, msg: String) = log(tag, msg)
    internal fun send(bytes: ByteArray, ip: String, port: Int) = sendBytes(bytes, ip, port)
    internal fun ownIp(peerIp: String) = ownIpFor(peerIp)
    internal fun transport(ip: String, port: Int) = transportFor(ip, port)
    internal fun makeReply(req: SipMessage, code: Int, reason: String, ip: String, port: Int, vararg extra: Pair<String, String>): SipMessage =
        buildReply(req, code, reason, ip, port, *extra)
    internal fun sendReply(req: SipMessage, code: Int, reason: String, ip: String, port: Int) = reply(req, code, reason, ip, port)
    private var voicePromptManager: VoicePromptManager? = null

    // ------------------------------------------------------------ lifecycle

    fun configure(config: ServerConfig) {
        cfg = config
        sipPort = config.sipPort
        dialPlanExecutor?.configure(config)
    }

    fun initializeDialPlan(executor: DialPlanExecutor, promptManager: VoicePromptManager, storage: java.io.File) {
        dialPlanExecutor = executor
        storageDir = storage
        voicePromptManager = promptManager
        log("DIALPLAN", "✓ Dial plan executor initialized")
    }

    suspend fun start(): Boolean {
        if (running) return false
        return try {
            val c = cfg
            val bind = c.bindAddress.ifBlank { "0.0.0.0" }
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(bind, sipPort))
            try { s.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
            udp = s
            localIp = getIp()
            running = true

            val rStart = c.rtpStartPort.coerceIn(1024, 65000)
            val rEnd = c.rtpEndPort.coerceIn(rStart + 20, 65535)
            relay = MediaRelay(rStart, rEnd, if (c.qosEnabled) c.tos else 0) { log("RTP", it) }.also { it.start() }

            if (c.sipTransport.contains("TCP", true)) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(bind, sipPort))
                    tcpServer = ss
                    scope.launch { tcpAcceptLoop(ss) }
                } catch (e: Exception) {
                    log("ERR", "TCP listener failed: ${e.message} (UDP still works)")
                }
            }

            restoreRegistrations()
            scope.launch { gateway.start() }
            dialPlanExecutor?.configure(c)
            dialPlanExecutor?.refresh()
            if (c.fileTransferEnabled) {
                msrp = MsrpManager(this, rStart, rEnd).also { it.start() }
            }

            log("SRV", "════════════════════════════════════")
            log("SRV", "  SIP SERVER + MEDIA RELAY STARTED")
            log("SRV", "  IP: $localIp:$sipPort  (${if (tcpServer != null) "UDP+TCP" else "UDP"})")
            log("SRV", "  Media mode: ${c.mediaMode.uppercase()}  |  RTP ports: $rStart-$rEnd")
            log("SRV", "  Video: ${if (c.videoEnabled) "ENABLED" else "DISABLED"}")
            log("SRV", "  Dial Plan: ${if (dialPlanExecutor != null) "ENABLED" else "DISABLED"}")
            log("SRV", "════════════════════════════════════")

            scope.launch { receiveLoop(s) }
            scope.launch { maintenanceLoop() }
            scope.launch { messageCleanupLoop() }
            true
        } catch (e: Exception) {
            running = false
            try { udp?.close() } catch (_: Exception) {}
            log("ERR", "Start failed: ${e.message}")
            false
        }
    }

    fun stop() {
        try { gateway.stop() } catch (_: Exception) {}
        running = false
        try { udp?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        tcpConns.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        tcpConns.clear()
        relay?.stop()
        relay = null
        legs.stop()
        msrp?.stop()
        msrp = null
        // Registrations stay in the database on purpose: they are restored on next start.
        registered.clear()
        calls.clear()
        scope.coroutineContext.cancelChildren()
        log("SRV", "Server stopped")
    }

    private suspend fun restoreRegistrations() {
        try {
            val now = System.currentTimeMillis()
            val grace = (cfg.registerExpiry.coerceAtLeast(30) + 60) * 1000L
            val saved = repository.getRegistrations()
            var n = 0
            for (r in saved) {
                if (now - r.lastSeen > 24L * 3600 * 1000) { repository.deleteRegistration(r.extension); continue }
                registered[r.extension] = Client(r.extension, r.ip, r.port, now + grace, r.transport, r.userAgent, r.lastSeen)
                n++
            }
            if (n > 0) log("REG", "♻ Restored $n registration(s): ${registered.keys.sorted().joinToString(", ")}")
        } catch (e: Exception) {
            log("REG", "Could not restore registrations: ${e.message}")
        }
    }

    // ------------------------------------------------------------ loops

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                if (pkt.length < 4) continue // keep-alive CRLF
                val raw = String(pkt.data, 0, pkt.length, StandardCharsets.ISO_8859_1)
                val ip = pkt.address.hostAddress ?: continue
                val port = pkt.port
                scope.launch { safeHandle(raw, ip, port, null) }
            } catch (e: Exception) {
                if (running && !sock.isClosed) delay(50)
                if (sock.isClosed) break
            }
        }
    }

    private suspend fun tcpAcceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val sock = ss.accept()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                scope.launch { tcpReadLoop(sock) }
            } catch (e: Exception) {
                if (running && !ss.isClosed) delay(50) else break
            }
        }
    }

    private suspend fun tcpReadLoop(sock: Socket) {
        val ip = sock.inetAddress.hostAddress ?: return
        val port = sock.port
        val key = "$ip:$port"
        val conn = TcpConn(sock)
        tcpConns[key] = conn
        log("SRV", "TCP connected: $key")
        try {
            val input = BufferedInputStream(sock.getInputStream())
            while (running && !sock.isClosed) {
                val head = ByteArrayOutputStream()
                var tail = 0
                var started = false
                while (true) {
                    val b = input.read()
                    if (b < 0) return
                    if (!started && (b == '\r'.code || b == '\n'.code)) continue // keep-alives
                    started = true
                    head.write(b)
                    tail = ((tail shl 8) or b) and 0x7fffffff.toInt()
                    if (tail == 0x0d0a0d0a || (tail and 0xffff) == 0x0a0a) break
                    if (head.size() > 32768) return
                }
                val headText = String(head.toByteArray(), StandardCharsets.ISO_8859_1)
                val cl = Regex("(?im)^(?:content-length|l)\\s*:\\s*(\\d+)").find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (cl > 65535) return
                val body = ByteArray(cl)
                var off = 0
                while (off < cl) {
                    val n = input.read(body, off, cl - off)
                    if (n < 0) return
                    off += n
                }
                val raw = headText + String(body, StandardCharsets.ISO_8859_1)
                scope.launch { safeHandle(raw, ip, port, conn) }
            }
        } catch (_: Exception) {
        } finally {
            tcpConns.remove(key)
            try { sock.close() } catch (_: Exception) {}
            log("SRV", "TCP closed: $key")
        }
    }

    private suspend fun maintenanceLoop() {
        var tick = 0
        while (running) {
            delay(5000)
            tick++
            val now = System.currentTimeMillis()
            try {
                // expired registrations
                registered.entries.removeIf { (ext, c) ->
                    val dead = c.expiry < now
                    if (dead) {
                        log("REG", "⏱ Registration expired: $ext")
                        scope.launch { try { repository.deleteRegistration(ext) } catch (_: Exception) {} }
                    }
                    dead
                }
                // old failed auth counters / bans
                authFails.entries.removeIf { now - it.value.second > 600_000 }
                banned.entries.removeIf { it.value < now }

                sweepCalls(now)
                gateway.tick(now)
                dialPlanExecutor?.refresh()
                legs.callbackTick()

                if (tick % 6 == 0) { // every 30s
                    val ip = getIp()
                    if (ip != localIp && ip != "0.0.0.0") {
                        log("SRV", "🌐 Server IP changed: $localIp → $ip")
                        localIp = ip
                    }
                    if (registered.isNotEmpty()) log("SRV", "📱 Online: ${registered.keys.sorted().joinToString(", ")}")
                }
                val q = cfg.qualifyFrequency
                if (q > 0 && tick % maxOf(1, q / 5) == 0) qualifyAll()
            } catch (e: Exception) {
                log("ERR", "Maintenance error: ${e.message}")
            }
        }
    }

    private fun sweepCalls(now: Long) {
        for (call in calls.values.toList()) {
            when (call.state) {
                "TRYING", "RINGING" -> if (now - call.attemptAt > call.ringTimeoutMs) {
                    log("CALL", "⏱ No answer: ${call.fromExt} → ${call.toExt}")
                    cancelCallee(call)
                    relay?.close(call.id)
                    val conds = listOf(DialPlanCondition.NO_ANSWER, DialPlanCondition.TIMEOUT)
                    val ex = dialPlanExecutor
                    if (ex != null && ex.hasRules(conds, call.toExt, legs.firedRules(call.id))) {
                        legs.handleFailure(call, 408, conds) {
                            try { reply(call.invite, 408, "Request Timeout", call.callerIp, call.callerPort) } catch (_: Exception) {}
                            call.state = "FAILED"; call.endedAt = System.currentTimeMillis()
                        }
                    } else {
                        try { reply(call.invite, 408, "Request Timeout", call.callerIp, call.callerPort) } catch (_: Exception) {}
                        endCall(call, "timeout")
                    }
                }
                "FAILED", "CANCELLING" -> if (now - call.endedAt > FAILED_LINGER_MS) calls.remove(call.id)
                "CONNECTED" -> {
                    val idle = cfg.relayIdleTimeout
                    val s = relay?.session(call.id)
                    if (call.relayed && idle > 0 && s != null &&
                        now - s.lastActivity > idle * 1000L && now - call.answeredAt > idle * 1000L) {
                        log("CALL", "⏱ No media for ${idle}s, closing ${call.fromExt} ↔ ${call.toExt}")
                        endCall(call, "idle")
                    }
                }
            }
        }
    }

    private fun qualifyAll() {
        val now = System.currentTimeMillis()
        for (c in registered.values) {
            if (c.transport == "TCP") continue
            val m = SipMessage("OPTIONS sip:${c.ext}@${c.ip}:${c.port} SIP/2.0")
            val host = ownIpFor(c.ip)
            m.add("Via", "SIP/2.0/UDP $host:$sipPort;branch=${BRANCH_PREFIX}q${now}${c.ext};rport")
            m.add("Max-Forwards", "70")
            m.add("From", "<sip:asterisk@$host>;tag=q${now % 100000}")
            m.add("To", "<sip:${c.ext}@${c.ip}:${c.port}>")
            m.add("Call-ID", "qual-${c.ext}-$now@$host")
            m.add("CSeq", "1 OPTIONS")
            m.add("User-Agent", "Asterisk-Android")
            sendBytes(m.toBytes(), c.ip, c.port)
        }
    }

    private suspend fun messageCleanupLoop() {
        while (running) {
            delay(3_600_000)
            try {
                val now = System.currentTimeMillis()
                repository.deleteExpiredMessages(now)
                repository.deleteDeliveredOlderThan(now - 30L * 24 * 60 * 60 * 1000)
                repository.deleteStaleRegistrations(now - 24L * 60 * 60 * 1000)
                msrp?.cleanup()
                log("MSG", "✓ Message cleanup completed")
            } catch (e: Exception) {
                log("MSG", "Cleanup error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ dispatch

    private suspend fun safeHandle(raw: String, ip: String, port: Int, tcp: TcpConn?) {
        try { handle(raw, ip, port) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("ERR", "Error handling message from $ip:$port: ${e.message}") }
    }

    private suspend fun handle(raw: String, ip: String, port: Int) {
        val msg = SipMessage.parse(raw) ?: return
        if ((banned[ip] ?: 0L) > System.currentTimeMillis()) return
        touch(ip, port)

        if (msg.isResponse) { if (gateway.onResponse(msg, ip, port)) return; onResponse(msg); return }

        if (cfg.sipDebug) log("SIP", "← ${msg.startLine}")
        log("←", "${msg.requestMethod} from $ip:$port")

        msg.header("Route")?.let { if (isOurRoute(it)) msg.removeFirst("Route") }

        // dialogs the server itself terminates (prompts, voicemail, IVR, queue legs, file transfer)
        if (legs.onRequest(msg, ip, port)) return
        // calls bridged to / from external VoIP gateways
        if (gateway.onRequest(msg, ip, port)) return

        when (msg.requestMethod) {
            "REGISTER" -> onRegister(msg, ip, port)
            "INVITE" -> onInvite(msg, ip, port)
            "ACK" -> onAck(msg, ip, port)
            "BYE" -> onBye(msg, ip, port)
            "CANCEL" -> onCancel(msg, ip, port)
            "OPTIONS" -> reply(msg, 200, "OK", ip, port,
                "Allow" to "INVITE,ACK,CANCEL,OPTIONS,BYE,REGISTER,INFO,MESSAGE,REFER,NOTIFY,SUBSCRIBE,UPDATE,PRACK",
                "Accept" to "application/sdp",
                "Supported" to "replaces,100rel")
            "MESSAGE" -> onMessage(msg, ip, port)
            "INFO", "UPDATE", "REFER", "NOTIFY", "PRACK", "SUBSCRIBE" -> onInDialog(msg, ip, port)
            else -> reply(msg, 200, "OK", ip, port)
        }
    }

    private fun touch(ip: String, port: Int) {
        val now = System.currentTimeMillis()
        registered.values.firstOrNull { it.ip == ip && it.port == port }?.lastSeen = now
    }

    // ------------------------------------------------------------ REGISTER

    private suspend fun onRegister(msg: SipMessage, ip: String, port: Int) {
        val ext = SipMessage.userOf(msg.header("To")) ?: SipMessage.userOf(msg.header("From"))
        if (ext == null) {
            log("REG", "❌ Cannot find extension"); reply(msg, 400, "Bad Request", ip, port); return
        }
        log("REG", "Extension: $ext from $ip:$port")

        val data = repository.getExtensionByNumber(ext)
        if (data == null) {
            log("REG", "❌ Extension '$ext' not found! Add it in Extensions tab")
            reply(msg, 404, "Not Found", ip, port); return
        }
        if (!data.enabled) {
            log("REG", "❌ Extension '$ext' is disabled")
            reply(msg, 403, "Forbidden", ip, port); return
        }

        val auth = msg.header("Authorization")
        if (auth == null) { challenge(msg, ip, port, 401, "Unauthorized", "WWW-Authenticate", false); return }
        when (verifyAuth(auth, data.secret, "REGISTER")) {
            AuthResult.OK -> authFails.remove(ip)
            AuthResult.STALE -> { challenge(msg, ip, port, 401, "Unauthorized", "WWW-Authenticate", true); return }
            AuthResult.BAD -> {
                log("REG", "❌ Wrong password for $ext from $ip")
                recordAuthFail(ip)
                challenge(msg, ip, port, 401, "Unauthorized", "WWW-Authenticate", false); return
            }
        }

        val contact = msg.header("Contact")
        val reqExpires = SipMessage.paramOf(contact, "expires")?.toIntOrNull()
            ?: msg.header("Expires")?.trim()?.toIntOrNull() ?: 3600

        if (contact?.trim() == "*" || reqExpires == 0) {
            registered.remove(ext)
            scope.launch { try { repository.deleteRegistration(ext) } catch (_: Exception) {} }
            log("REG", "📴 Unregistered: $ext")
            reply(msg, 200, "OK", ip, port, "Expires" to "0")
            return
        }

        val granted = reqExpires.coerceAtMost(cfg.registerExpiry.coerceAtLeast(30)).coerceAtLeast(30)
        val now = System.currentTimeMillis()
        val transport = if (tcpConns.containsKey("$ip:$port")) "TCP" else "UDP"
        val ua = msg.header("User-Agent") ?: ""
        val old = registered[ext]
        val client = Client(ext, ip, port, now + granted * 1000L, transport, ua, now)
        registered[ext] = client
        if (old == null || old.ip != ip || old.port != port) {
            log("REG", "✅ REGISTERED: $ext  $ip:$port ($transport)  refresh ${granted}s")
            log("REG", "📱 Online: ${registered.keys.sorted().joinToString(", ")}")
        }
        scope.launch {
            try {
                repository.saveRegistration(Registration(ext, ip, port, client.expiry, transport, ua, now))
                if (repository.getPendingMessageCount(ext) > 0) {
                    log("MSG", "📬 $ext has pending message(s)")
                    deliverQueuedMessages(ext)
                }
                legs.callbackTick()
                msrp?.deliverQueuedFiles(ext)
            } catch (e: Exception) {
                log("REG", "Persist/pending check failed: ${e.message}")
            }
        }

        val contactOut = (contact ?: "<sip:$ext@$ip:$port>")
            .replace(Regex(";\\s*expires=\\d+", RegexOption.IGNORE_CASE), "") + ";expires=$granted"
        reply(msg, 200, "OK", ip, port, "Contact" to contactOut, "Expires" to "$granted",
            "Date" to java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date()))
    }

    // ------------------------------------------------------------ INVITE

    private suspend fun onInvite(msg: SipMessage, ip: String, port: Int) {
        val callId = msg.callId
        if (callId.isEmpty()) { reply(msg, 400, "Bad Request", ip, port); return }

        // retransmission / re-INVITE of a known call
        val known = calls[callId]
        if (known != null) {
            if (known.state == "TRYING" || known.state == "RINGING" || known.state == "HANDLING" || known.state == "ANNOUNCE") {
                if (msg.cseqNum == known.inviteCSeq && isFromCaller(known, msg, ip, port)) {
                    reply(msg, 100, "Trying", ip, port); return
                }
            } else if (known.state == "CONNECTED" || known.state == "HELD") {
                onInDialog(msg, ip, port); return
            } else {
                calls.remove(callId)
            }
        }

        // a call coming in from an external VoIP gateway (trusted by its address)
        val inTrunk = gateway.inboundTrunk(msg, ip, port)

        val fromExt = SipMessage.userOf(msg.header("From")) ?: if (inTrunk != null) "anonymous" else null
        val toExt = if (inTrunk != null) gateway.mapInbound(inTrunk, msg)
        else SipMessage.userOf(msg.requestUri) ?: SipMessage.userOf(msg.header("To"))
        if (inTrunk != null && toExt == null) {
            log("GW", "❌ inbound call from ${inTrunk.name}: no target extension configured")
            reply(msg, 404, "Not Found", ip, port); return
        }
        if (fromExt == null || toExt == null) { reply(msg, 400, "Bad Request", ip, port); return }

        log("CALL", "════════════════════════════════════")
        log("CALL", "📞 INCOMING CALL  $fromExt → $toExt   (from $ip:$port)${if (inTrunk != null) "  [gateway ${inTrunk.name}]" else ""}")

        // who is calling? registered from this address -> trusted, otherwise digest challenge
        val me = if (inTrunk != null) null else registered[fromExt]
        if (me == null && inTrunk == null && !cfg.allowGuest) {
            log("CALL", "❌ $fromExt is not registered")
            reply(msg, 403, "Forbidden", ip, port); return
        }
        if (me != null && me.ip != ip) {
            val data = repository.getExtensionByNumber(fromExt)
            val auth = msg.header("Proxy-Authorization")
            if (data == null) { reply(msg, 403, "Forbidden", ip, port); return }
            if (auth == null) { challenge(msg, ip, port, 407, "Proxy Authentication Required", "Proxy-Authenticate", false); return }
            when (verifyAuth(auth, data.secret, "INVITE")) {
                AuthResult.OK -> {}
                AuthResult.STALE -> { challenge(msg, ip, port, 407, "Proxy Authentication Required", "Proxy-Authenticate", true); return }
                AuthResult.BAD -> { recordAuthFail(ip); reply(msg, 403, "Forbidden", ip, port); return }
            }
        }

        // *97 : listen to your own voicemail
        if (toExt.replace("%2A", "*", true) == "*97") {
            val vm = Call(id = callId, fromExt = fromExt, toExt = "*97", callerTag = msg.fromTag,
                callerIp = ip, callerPort = port, calleeIp = "", calleePort = 0, relayed = false, invite = msg.clone())
            vm.inviteCSeq = msg.cseqNum
            calls[callId] = vm
            reply(msg, 100, "Trying", ip, port)
            legs.runVoicemailMenu(vm)
            return
        }

        // number that is not a local extension -> send it out through a gateway (SIP trunk)
        if (inTrunk == null && registered[toExt] == null && repository.getExtensionByNumber(toExt) == null) {
            if (gateway.startOutbound(msg, ip, port, fromExt, toExt)) return
        }

        val dest = registered[toExt]
        if (dest == null) {
            val known = repository.getExtensionByNumber(toExt)
            log("CALL", if (known == null) "❌ $toExt does not exist" else "❌ $toExt NOT ONLINE!")

            // file transfer (MSRP) to an offline user: accept and keep the file for later
            val m = msrp
            if (m != null && known != null && SdpUtil.hasMsrp(msg.body)) {
                val c = Call(id = callId, fromExt = fromExt, toExt = toExt, callerTag = msg.fromTag,
                    callerIp = ip, callerPort = port, calleeIp = "", calleePort = 0, relayed = false, invite = msg.clone())
                c.inviteCSeq = msg.cseqNum
                calls[callId] = c
                reply(msg, 100, "Trying", ip, port)
                if (m.acceptOfflineFile(c)) return
                calls.remove(callId)
            }

            val conds = if (known == null) listOf(DialPlanCondition.INVALID_NUMBER, DialPlanCondition.OFFLINE)
            else listOf(DialPlanCondition.OFFLINE, DialPlanCondition.UNAVAILABLE)
            val ex = dialPlanExecutor
            if (ex != null && ex.hasRules(conds, toExt, emptySet())) {
                val c = Call(id = callId, fromExt = fromExt, toExt = toExt, callerTag = msg.fromTag,
                    callerIp = ip, callerPort = port, calleeIp = "", calleePort = 0, relayed = false, invite = msg.clone())
                c.inviteCSeq = msg.cseqNum
                calls[callId] = c
                reply(msg, 100, "Trying", ip, port)
                legs.handleFailure(c, if (known == null) 404 else 480, conds) {
                    reply(msg, if (known == null) 404 else 480, if (known == null) "Not Found" else "Temporarily Unavailable", ip, port)
                    c.state = "FAILED"; c.endedAt = System.currentTimeMillis()
                }
                return
            }
            reply(msg, 480, "Temporarily Unavailable", ip, port)
            return
        }
        if (calls.count { it.value.state != "FAILED" && it.value.state != "CANCELLING" } >= cfg.maxCalls) {
            log("CALL", "❌ Max calls reached"); reply(msg, 503, "Service Unavailable", ip, port); return
        }

        val useRelay = inTrunk != null || shouldRelay(ip, dest.ip) // gateway calls always go through the relay
        val call = Call(
            id = callId, fromExt = fromExt, toExt = toExt, callerTag = msg.fromTag,
            callerIp = ip, callerPort = port, calleeIp = dest.ip, calleePort = dest.port,
            relayed = useRelay, invite = msg.clone()
        )
        call.inviteCSeq = msg.cseqNum
        call.video = SdpUtil.hasVideo(msg.body)
        call.ringTimeoutMs = dialPlanExecutor?.ringTimeoutMs(toExt) ?: RING_TIMEOUT_MS
        call.fwdUri = msg.requestUri
        calls[callId] = call
        log("CALL", "✓ Found: $toExt @ ${dest.ip}:${dest.port}")
        log("CALL", "   Media: ${if (useRelay) "RELAY via server" else "DIRECT device↔device"}${if (call.video) " (audio+video)" else " (audio)"}")

        reply(msg, 100, "Trying", ip, port)

        val out = forwardRequest(msg, ip, port, dest.ip, dest.port)
        if (out == null) { calls.remove(callId); reply(msg, 483, "Too Many Hops", ip, port); return }
        if (inTrunk != null) out.startLine = "INVITE sip:$toExt@${ownIpFor(dest.ip)}:$sipPort SIP/2.0"
        val rr = if (tcpConns.containsKey("${dest.ip}:${dest.port}")) ";transport=tcp" else ""
        out.addFirst("Record-Route", "<sip:${ownIpFor(dest.ip)}:$sipPort$rr;lr>")

        if (useRelay && msg.body.startsWith("v=")) {
            val sdp = rewriteSdpFor(call, msg.body, MediaRelay.SIDE_A)
            if (sdp == null) {
                log("ERR", "No free RTP ports for call $callId")
                calls.remove(callId); relay?.close(callId)
                reply(msg, 503, "Service Unavailable", ip, port); return
            }
            out.body = sdp
        }
        call.branch = SipMessage.paramOf(out.header("Via"), "branch") ?: ""
        sendBytes(out.toBytes(), dest.ip, dest.port)
        log("CALL", "→ Forwarded to ${dest.ip}:${dest.port}")
    }

    // ------------------------------------------------------------ in-dialog requests

    private fun onInDialog(msg: SipMessage, ip: String, port: Int) {
        val call = calls[msg.callId]
        if (call == null) {
            when (msg.requestMethod) {
                "INVITE", "INFO", "UPDATE", "PRACK" -> reply(msg, 481, "Call/Transaction Does Not Exist", ip, port)
                "SUBSCRIBE" -> reply(msg, 200, "OK", ip, port, "Expires" to (msg.header("Expires") ?: "3600"))
                else -> reply(msg, 200, "OK", ip, port)
            }
            return
        }
        val fromCaller = isFromCaller(call, msg, ip, port)
        val (dIp, dPort) = peerAddr(call, !fromCaller)

        if (msg.requestMethod == "INVITE") {
            val body = msg.body
            when {
                body.contains("a=sendonly") || body.contains("a=inactive") -> { call.state = "HELD"; log("CALL", "⏸️ HOLD") }
                body.contains("a=sendrecv") || body.isNotEmpty() -> { call.state = "CONNECTED"; log("CALL", "▶️ RESUME / media update") }
            }
            if (SdpUtil.hasVideo(body)) call.video = true
        }

        val out = forwardRequest(msg, ip, port, dIp, dPort)
        if (out == null) { reply(msg, 483, "Too Many Hops", ip, port); return }
        if (msg.requestMethod == "INVITE") {
            val rr = if (tcpConns.containsKey("$dIp:$dPort")) ";transport=tcp" else ""
            out.addFirst("Record-Route", "<sip:${ownIpFor(dIp)}:$sipPort$rr;lr>")
        }
        if (call.relayed && msg.body.startsWith("v=")) {
            val sdp = rewriteSdpFor(call, msg.body, if (fromCaller) MediaRelay.SIDE_A else MediaRelay.SIDE_B)
            if (sdp == null) { reply(msg, 488, "Not Acceptable Here", ip, port); return }
            out.body = sdp
        }
        sendBytes(out.toBytes(), dIp, dPort)
    }

    private fun onAck(msg: SipMessage, ip: String, port: Int) {
        val call = calls[msg.callId] ?: return
        val fromCaller = isFromCaller(call, msg, ip, port)
        val (dIp, dPort) = peerAddr(call, !fromCaller)
        val out = forwardRequest(msg, ip, port, dIp, dPort, forkSalt(call)) ?: return
        if (call.relayed && msg.body.startsWith("v=")) {
            rewriteSdpFor(call, msg.body, if (fromCaller) MediaRelay.SIDE_A else MediaRelay.SIDE_B)?.let { out.body = it }
        }
        sendBytes(out.toBytes(), dIp, dPort)
    }

    private fun onBye(msg: SipMessage, ip: String, port: Int) {
        val call = calls[msg.callId]
        if (call == null) { reply(msg, 481, "Call/Transaction Does Not Exist", ip, port); return }
        val fromCaller = isFromCaller(call, msg, ip, port)
        val (dIp, dPort) = peerAddr(call, !fromCaller)

        reply(msg, 200, "OK", ip, port) // answer immediately; the other side's 200 is absorbed
        forwardRequest(msg, ip, port, dIp, dPort)?.let { sendBytes(it.toBytes(), dIp, dPort) }

        val dur = (System.currentTimeMillis() - call.time) / 1000
        log("CALL", "════════════════════════════════════")
        log("CALL", "📴 CALL ENDED  ${call.fromExt} ↔ ${call.toExt}  (${dur}s)")
        log("CALL", "════════════════════════════════════")
        endCall(call, "bye")
    }

    private fun onCancel(msg: SipMessage, ip: String, port: Int) {
        val call = calls[msg.callId]
        if (call == null) { reply(msg, 481, "Call/Transaction Does Not Exist", ip, port); return }
        reply(msg, 200, "OK", ip, port)
        if (call.state == "HANDLING") { // rules are running; the caller has not been answered
            legs.callerCancelled(call.id)
            reply(call.invite, 487, "Request Terminated", call.callerIp, call.callerPort)
            log("CALL", "❌ CANCELLED while handling: ${call.fromExt} → ${call.toExt}")
            call.state = "CANCELLING"
            call.endedAt = System.currentTimeMillis()
            return
        }
        if (call.state != "TRYING" && call.state != "RINGING") return

        val (dIp, dPort) = peerAddr(call, false)
        forwardRequest(msg, ip, port, dIp, dPort, forkSalt(call))?.let { sendBytes(it.toBytes(), dIp, dPort) }
        reply(call.invite, 487, "Request Terminated", call.callerIp, call.callerPort)
        log("CALL", "❌ CANCELLED: ${call.fromExt} → ${call.toExt}")
        call.state = "CANCELLING"
        call.endedAt = System.currentTimeMillis()
        relay?.close(call.id)
    }

    private fun forkSalt(call: Call) = if (call.fork > 0) "f${call.fork}" else ""

    private fun endCall(call: Call, why: String) {
        relay?.close(call.id)
        legs.endCall(call.id)
        msrp?.closeSession(call.id)
        calls.remove(call.id)
    }

    // ------------------------------------------------------------ responses

    private fun onResponse(msg: SipMessage) {
        val callId = msg.callId
        if (callId.startsWith("qual-")) {
            val ext = callId.removePrefix("qual-").substringBeforeLast('-')
            registered[ext]?.lastSeen = System.currentTimeMillis()
            return
        }
        if (legs.onResponse(msg)) return // answers to requests the server itself sent (prompt legs, queue legs)
        val method = msg.cseqMethod
        if (method == "BYE" || method == "MESSAGE" || method == "CANCEL") return // already answered locally

        val topVia = msg.header("Via") ?: return
        if (!topVia.contains(BRANCH_PREFIX)) return // not ours
        val respBranch = SipMessage.paramOf(topVia, "branch")
        msg.removeFirst("Via")
        val next = msg.header("Via") ?: return
        val (dIp, dPort) = viaDest(next) ?: return

        val code = msg.statusCode
        val call = calls[callId]

        if (call != null && method == "INVITE") {
            val toCaller = isRespToCaller(call, msg, dIp, dPort)
            val initial = call.state == "TRYING" || call.state == "RINGING"

            if (call.state == "CANCELLING" && toCaller) return // caller already got our 487
            if (code == 100) return
            if (toCaller && call.branch.isNotEmpty() && respBranch != null && respBranch != call.branch) return // old attempt
            if ((call.state == "HANDLING" || call.state == "ANNOUNCE") && toCaller) {
                // the callee leg was already cancelled / failed: absorb its late responses
                if (code >= 300) ackCallee(call, msg)
                else if (code in 200..299) killLateAnswer(call, msg)
                return
            }

            if (code in 180..199 && initial) {
                if (call.state != "RINGING") log("CALL", "🔔 RINGING ${call.toExt}...")
                call.state = "RINGING"
            }
            if (code in 200..299 && initial) {
                call.state = "CONNECTED"
                call.answeredAt = System.currentTimeMillis()
                log("CALL", "════════════════════════════════════")
                log("CALL", "✅ ANSWERED  ${call.fromExt} ↔ ${call.toExt}")
                log("CALL", "🔊 ${if (call.relayed) "Media via server relay" else "Media direct between devices"}${if (call.video) " + VIDEO" else ""}")
                log("CALL", "════════════════════════════════════")
            }
            if (call.relayed && msg.body.startsWith("v=") && (code in 180..299)) {
                rewriteSdpFor(call, msg.body, if (toCaller) MediaRelay.SIDE_B else MediaRelay.SIDE_A)?.let { msg.body = it }
                if (SdpUtil.hasVideo(msg.body)) call.video = true
            }
            if (code >= 300 && initial) {
                val conds = failureConditions(code)
                log("CALL", "❌ Call failed: $code ${msg.startLine.substringAfter(' ').substringAfter(' ')}")
                relay?.close(call.id)
                val ex = dialPlanExecutor
                if (conds.isNotEmpty() && ex != null && ex.hasRules(conds, call.toExt, legs.firedRules(call.id))) {
                    ackCallee(call, msg) // we, not the caller, close the callee transaction
                    val held = msg
                    legs.handleFailure(call, code, conds) {
                        sendBytes(held.toBytes(), dIp, dPort)
                        call.state = "FAILED"; call.endedAt = System.currentTimeMillis()
                    }
                    return // the failure is held back while rules run
                }
                call.state = "FAILED"
                call.endedAt = System.currentTimeMillis()
            }
        } else if (call != null && msg.body.startsWith("v=") && call.relayed && code in 200..299) {
            val toCaller = isRespToCaller(call, msg, dIp, dPort)
            rewriteSdpFor(call, msg.body, if (toCaller) MediaRelay.SIDE_B else MediaRelay.SIDE_A)?.let { msg.body = it }
        }

        sendBytes(msg.toBytes(), dIp, dPort)
    }

    private fun isRespToCaller(call: Call, msg: SipMessage, dIp: String, dPort: Int): Boolean {
        val tag = msg.fromTag
        if (tag != null && call.callerTag != null) return tag == call.callerTag
        return dIp == call.callerIp && dPort == call.callerPort
    }

    private fun failureConditions(code: Int): List<DialPlanCondition> = when (code) {
        486, 600 -> listOf(DialPlanCondition.BUSY)
        603 -> listOf(DialPlanCondition.REJECTED)
        480 -> listOf(DialPlanCondition.UNAVAILABLE, DialPlanCondition.OFFLINE)
        408 -> listOf(DialPlanCondition.TIMEOUT, DialPlanCondition.NO_ANSWER)
        404 -> listOf(DialPlanCondition.OFFLINE)
        403 -> listOf(DialPlanCondition.FORBIDDEN)
        in 500..599 -> listOf(DialPlanCondition.NETWORK_ERROR)
        else -> emptyList()
    }

    /** ACKs a final response from the callee ourselves (used when the response is not passed on). */
    private fun ackCallee(call: Call, resp: SipMessage) {
        if (call.calleeIp.isEmpty()) return
        val a = SipMessage("ACK ${call.fwdUri.ifEmpty { call.invite.requestUri }} SIP/2.0")
        a.add("Via", "SIP/2.0/${transportFor(call.calleeIp, call.calleePort)} ${ownIpFor(call.calleeIp)}:$sipPort;branch=${call.branch};rport")
        a.add("Max-Forwards", "70")
        a.add("From", resp.header("From") ?: call.invite.header("From") ?: "")
        a.add("To", resp.header("To") ?: "")
        a.add("Call-ID", call.id)
        a.add("CSeq", "${call.inviteCSeq} ACK")
        sendBytes(a.toBytes(), call.calleeIp, call.calleePort)
    }

    /** CANCEL towards the callee for a still-ringing attempt. */
    private fun cancelCallee(call: Call) {
        if (call.calleeIp.isEmpty()) return
        val c = SipMessage("CANCEL ${call.fwdUri.ifEmpty { call.invite.requestUri }} SIP/2.0")
        c.add("Via", "SIP/2.0/${transportFor(call.calleeIp, call.calleePort)} ${ownIpFor(call.calleeIp)}:$sipPort;branch=${call.branch};rport")
        c.add("Max-Forwards", "70")
        c.add("From", call.invite.header("From") ?: "")
        c.add("To", call.invite.header("To") ?: "")
        c.add("Call-ID", call.id)
        c.add("CSeq", "${call.inviteCSeq} CANCEL")
        sendBytes(c.toBytes(), call.calleeIp, call.calleePort)
    }

    /** The callee answered an attempt we had already given up on: ACK it and hang up. */
    private fun killLateAnswer(call: Call, resp: SipMessage) {
        if (call.calleeIp.isEmpty()) return
        val contact = Regex("<(sips?:[^>]+)>").find(resp.header("Contact") ?: "")?.groupValues?.get(1)
            ?: call.fwdUri.ifEmpty { call.invite.requestUri }
        val host = ownIpFor(call.calleeIp)
        val tr = transportFor(call.calleeIp, call.calleePort)
        val a = SipMessage("ACK $contact SIP/2.0")
        a.add("Via", "SIP/2.0/$tr $host:$sipPort;branch=${BRANCH_PREFIX}a${System.nanoTime()};rport")
        a.add("Max-Forwards", "70")
        a.add("From", resp.header("From") ?: ""); a.add("To", resp.header("To") ?: "")
        a.add("Call-ID", call.id); a.add("CSeq", "${call.inviteCSeq} ACK")
        sendBytes(a.toBytes(), call.calleeIp, call.calleePort)
        val b = SipMessage("BYE $contact SIP/2.0")
        b.add("Via", "SIP/2.0/$tr $host:$sipPort;branch=${BRANCH_PREFIX}b${System.nanoTime()};rport")
        b.add("Max-Forwards", "70")
        b.add("From", resp.header("From") ?: ""); b.add("To", resp.header("To") ?: "")
        b.add("Call-ID", call.id); b.add("CSeq", "${call.inviteCSeq + 1} BYE")
        sendBytes(b.toBytes(), call.calleeIp, call.calleePort)
    }

    /** Re-routes an unanswered call to [ext] (used by the Forward and Retry dial-plan actions). */
    internal fun retarget(call: Call, ext: String): Boolean {
        val dest = registered[ext] ?: return false
        if (call.state != "HANDLING") return false
        call.toExt = ext
        call.calleeIp = dest.ip
        call.calleePort = dest.port
        call.relayed = shouldRelay(call.callerIp, dest.ip)
        call.fork++
        call.attemptAt = System.currentTimeMillis()
        call.ringTimeoutMs = dialPlanExecutor?.ringTimeoutMs(ext) ?: RING_TIMEOUT_MS
        val msg = call.invite
        val out = forwardRequest(msg, call.callerIp, call.callerPort, dest.ip, dest.port, "f${call.fork}") ?: return false
        out.startLine = "INVITE sip:$ext@${dest.ip}:${dest.port} SIP/2.0"
        call.fwdUri = "sip:$ext@${dest.ip}:${dest.port}"
        val rr = if (tcpConns.containsKey("${dest.ip}:${dest.port}")) ";transport=tcp" else ""
        out.addFirst("Record-Route", "<sip:${ownIpFor(dest.ip)}:$sipPort$rr;lr>")
        if (call.relayed && msg.body.startsWith("v=")) {
            val sdp = rewriteSdpFor(call, msg.body, MediaRelay.SIDE_A) ?: return false
            out.body = sdp
        }
        call.branch = SipMessage.paramOf(out.header("Via"), "branch") ?: ""
        call.state = "TRYING"
        sendBytes(out.toBytes(), dest.ip, dest.port)
        log("CALL", "↪ Re-routed ${call.fromExt} → $ext (${dest.ip}:${dest.port})")
        return true
    }

    /** System message (e.g. "new voicemail") to an extension; queued when it is offline. */
    internal fun sendSystemText(toExt: String, text: String) {
        scope.launch {
            val r = registered[toExt]
            if (r == null) {
                storeOfflineMessage("voicemail", toExt, "text/plain; charset=UTF-8", text)
            } else {
                val host = ownIpFor(r.ip)
                val out = SipMessage("MESSAGE sip:$toExt@${r.ip}:${r.port} SIP/2.0")
                out.add("Via", "SIP/2.0/${transportFor(r.ip, r.port)} $host:$sipPort;branch=${BRANCH_PREFIX}m${System.nanoTime()};rport")
                out.add("Max-Forwards", "70")
                out.add("From", "<sip:voicemail@$host>;tag=q${System.nanoTime() % 1000000}")
                out.add("To", "<sip:$toExt@$host>")
                out.add("Call-ID", "sys-${System.nanoTime()}@$host")
                out.add("CSeq", "1 MESSAGE")
                out.add("Content-Type", "text/plain; charset=UTF-8")
                out.add("User-Agent", "Asterisk-Android")
                out.body = SipMessage.textToBody(text)
                sendBytes(out.toBytes(), r.ip, r.port)
            }
        }
    }

    // ------------------------------------------------------------ media mode + SDP

    private fun shouldRelay(callerIp: String, calleeIp: String): Boolean = when (cfg.mediaMode.lowercase()) {
        "relay" -> true
        "direct" -> false
        else -> !sameNetwork(callerIp, calleeIp)
    }

    private fun sameNetwork(a: String, b: String): Boolean {
        if (a == b) return true // same router / same NAT
        val na = Net.toInt(a); val nb = Net.toInt(b)
        if (na == null || nb == null) return false
        if (!Net.isPrivate(na) || !Net.isPrivate(nb)) return false
        val nets = cfg.localNet.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (nets.any { Net.inCidr(na, it) && Net.inCidr(nb, it) }) return true
        return (na ushr 8) == (nb ushr 8) // same /24
    }

    /** Rewrites an SDP so media goes through the relay. Returns null if no port could be allocated. */
    private fun rewriteSdpFor(call: Call, sdp: String, originSide: Int): String? {
        val r = relay ?: return sdp
        val session = r.open(call.id)
        val originIp = if (originSide == MediaRelay.SIDE_A) call.callerIp else call.calleeIp
        val targetIp = if (originSide == MediaRelay.SIDE_A) call.calleeIp else call.callerIp
        val target = 1 - originSide

        val media = SdpUtil.parseMedia(sdp)
        var anyRelayable = false
        for (m in media) {
            if (!m.relayable) continue
            if (m.type.equals("video", true) && !cfg.videoEnabled) continue
            anyRelayable = true
            val st = r.ensureStream(session, m.index, m.type) ?: continue
            // only trust the SDP address when it matches where the signalling came from (no NAT)
            if (m.connIp == originIp) r.hint(st, originSide, m.connIp, m.port, m.effectiveRtcpPort)
        }
        if (anyRelayable && session.streams.isEmpty()) return null
        val ip = ownIpFor(targetIp)
        val rewritten = SdpUtil.rewriteForRelay(sdp, ip, cfg.videoEnabled) { m ->
            session.streams.firstOrNull { it.index == m.index }?.ports(target)
        }
        val m = msrp
        return if (m != null && SdpUtil.hasMsrp(sdp)) m.rewriteSdp(call, rewritten, originSide, ip) else rewritten
    }

    // ------------------------------------------------------------ MESSAGE

    private suspend fun onMessage(msg: SipMessage, ip: String, port: Int) {
        val fromExt = SipMessage.userOf(msg.header("From"))
        val toExt = SipMessage.userOf(msg.requestUri) ?: SipMessage.userOf(msg.header("To"))
        if (fromExt == null || toExt == null) { reply(msg, 400, "Bad Request", ip, port); return }
        if (registered[fromExt] == null && !cfg.allowGuest) { reply(msg, 403, "Forbidden", ip, port); return }

        log("MSG", "📨 MESSAGE: $fromExt → $toExt")
        val recipient = registered[toExt]
        if (recipient != null) {
            log("MSG", "✓ $toExt is ONLINE - delivering now")
            forwardRequest(msg, ip, port, recipient.ip, recipient.port)?.let { sendBytes(it.toBytes(), recipient.ip, recipient.port) }
            reply(msg, 200, "OK", ip, port)
            scope.launch { deliverQueuedMessages(toExt) }
        } else {
            log("MSG", "📥 $toExt is OFFLINE - queuing message")
            val ct = msg.header("Content-Type") ?: "text/plain"
            val text = SipMessage.bodyToText(msg.body)
            scope.launch { storeOfflineMessage(fromExt, toExt, ct, text) }
            reply(msg, 202, "Accepted", ip, port)
        }
    }

    private suspend fun storeOfflineMessage(fromExt: String, toExt: String, contentType: String, body: String) {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                repository.insertOfflineMessage(
                    OfflineMessage(
                        fromExt = fromExt, toExt = toExt, contentType = contentType, messageBody = body,
                        timestamp = now, expiresAt = now + cfg.offlineMessageDays.coerceIn(1, 365) * 24L * 60 * 60 * 1000
                    )
                )
                log("MSG", "✓ Message queued for $toExt (total pending: ${repository.getPendingMessageCount(toExt)})")
            } catch (e: Exception) {
                log("MSG", "❌ Failed to queue message: ${e.message}")
            }
        }
    }

    private suspend fun deliverQueuedMessages(extension: String) {
        withContext(Dispatchers.IO) {
            try {
                val pending = repository.getPendingMessagesForExtension(extension)
                if (pending.isEmpty()) return@withContext
                val recipient = registered[extension] ?: return@withContext
                log("MSG", "📨 Delivering ${pending.size} queued message(s) to $extension")
                for (m in pending) {
                    try {
                        val host = ownIpFor(recipient.ip)
                        val out = SipMessage("MESSAGE sip:$extension@${recipient.ip}:${recipient.port} SIP/2.0")
                        out.add("Via", "SIP/2.0/${transportFor(recipient.ip, recipient.port)} $host:$sipPort;branch=${BRANCH_PREFIX}m${System.nanoTime()};rport")
                        out.add("Max-Forwards", "70")
                        out.add("From", "<sip:${m.fromExt}@$host>;tag=q${System.nanoTime() % 1000000}")
                        out.add("To", "<sip:$extension@$host>")
                        out.add("Call-ID", "queued-${System.nanoTime()}@$host")
                        out.add("CSeq", "1 MESSAGE")
                        out.add("Content-Type", m.contentType)
                        out.add("User-Agent", "Asterisk-Android")
                        out.add("X-Queued-At", m.timestamp.toString())
                        out.add("X-Offline-Delivery", "true")
                        out.body = SipMessage.textToBody(m.messageBody)
                        sendBytes(out.toBytes(), recipient.ip, recipient.port)
                        repository.markMessageAsDelivered(m.id)
                        log("MSG", "✓ Delivered queued message #${m.id} to $extension")
                        delay(100)
                    } catch (e: Exception) {
                        log("MSG", "❌ Failed to deliver message #${m.id}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("MSG", "❌ Error delivering queued messages: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ forwarding helpers

    private fun forwardRequest(orig: SipMessage, srcIp: String, srcPort: Int, destIp: String, destPort: Int, salt: String = ""): SipMessage? {
        val m = orig.clone()
        m.header("Max-Forwards")?.trim()?.toIntOrNull()?.let {
            if (it <= 0) return null
            m.set("Max-Forwards", (it - 1).toString())
        }
        val i = m.headers.indexOfFirst { it.first.equals("Via", true) }
        if (i >= 0) m.headers[i] = "Via" to markReceived(m.headers[i].second, srcIp, srcPort)
        val methodForBranch = if (orig.requestMethod == "CANCEL" || orig.requestMethod == "ACK") "INVITE" else orig.requestMethod
        val branch = BRANCH_PREFIX + md5("${orig.callId}|${orig.cseqNum}|$methodForBranch|${orig.topViaBranch}$salt").take(20)
        m.addFirst("Via", "SIP/2.0/${transportFor(destIp, destPort)} ${ownIpFor(destIp)}:$sipPort;branch=$branch;rport")
        return m
    }

    private fun markReceived(via: String, ip: String, port: Int): String {
        var v = via.replace(Regex(";\\s*received=[^;,\\s]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex(";\\s*rport(=\\d*)?", RegexOption.IGNORE_CASE), "")
        v += ";received=$ip;rport=$port"
        return v
    }

    private fun viaDest(via: String): Pair<String, Int>? {
        val sentBy = via.substringAfter(' ', "").substringBefore(';').trim()
        if (sentBy.isEmpty()) return null
        val host = if (sentBy.startsWith("[")) sentBy.substringAfter('[').substringBefore(']') else sentBy.substringBefore(':')
        val defPort = if (sentBy.contains(':') && !sentBy.startsWith("[")) sentBy.substringAfter(':').toIntOrNull() ?: 5060 else 5060
        val received = SipMessage.paramOf(via, "received")
        val rport = SipMessage.paramOf(via, "rport")?.toIntOrNull()
        return Pair(received ?: host, rport ?: defPort)
    }

    private fun isFromCaller(call: Call, msg: SipMessage, ip: String, port: Int): Boolean {
        val tag = msg.fromTag
        if (tag != null && call.callerTag != null) return tag == call.callerTag
        return ip == call.callerIp
    }

    /** Current address of the caller / callee (follows re-registrations). */
    private fun peerAddr(call: Call, caller: Boolean): Pair<String, Int> {
        val reg = registered[if (caller) call.fromExt else call.toExt]
        if (reg != null) return Pair(reg.ip, reg.port)
        return if (caller) Pair(call.callerIp, call.callerPort) else Pair(call.calleeIp, call.calleePort)
    }

    private fun isOurRoute(route: String): Boolean {
        val m = Regex("sips?:([^;>:,\\s]+)(?::(\\d+))?", RegexOption.IGNORE_CASE).find(route) ?: return false
        val host = m.groupValues[1]
        val p = m.groupValues[2].toIntOrNull() ?: 5060
        if (p != sipPort) return false
        return host == localIp || host == cfg.externalIP || localAddresses().contains(host)
    }

    // ------------------------------------------------------------ responses we generate

    private fun reply(req: SipMessage, code: Int, reason: String, ip: String, port: Int, vararg extra: Pair<String, String>) {
        sendBytes(buildReply(req, code, reason, ip, port, *extra).toBytes(), ip, port)
    }

    private fun buildReply(req: SipMessage, code: Int, reason: String, ip: String, port: Int, vararg extra: Pair<String, String>): SipMessage {
        val r = SipMessage("SIP/2.0 $code $reason")
        val vias = req.headerList("Via")
        vias.forEachIndexed { i, v -> r.add("Via", if (i == 0) markReceived(v, ip, port) else v) }
        r.add("From", req.header("From") ?: "")
        var to = req.header("To") ?: ""
        if (code > 100 && !to.contains("tag=", true)) to += ";tag=" + md5(req.callId + "srv").take(10)
        r.add("To", to)
        r.add("Call-ID", req.callId)
        r.add("CSeq", req.header("CSeq") ?: "")
        r.add("Server", "Asterisk-Android")
        extra.forEach { r.add(it.first, it.second) }
        return r
    }

    private enum class AuthResult { OK, BAD, STALE }

    private fun challenge(req: SipMessage, ip: String, port: Int, code: Int, reason: String, hdr: String, stale: Boolean) {
        val ts = java.lang.Long.toHexString(System.currentTimeMillis())
        val nonce = ts + md5("$ts:$nonceKey").take(16)
        reply(req, code, reason, ip, port,
            hdr to "Digest realm=\"$REALM\", nonce=\"$nonce\", algorithm=MD5, qop=\"auth\"${if (stale) ", stale=true" else ""}")
    }

    private fun verifyAuth(auth: String, secret: String, method: String): AuthResult {
        return try {
            val p = mutableMapOf<String, String>()
            Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(auth).forEach {
                p[it.groupValues[1].lowercase()] = it.groupValues[2].ifEmpty { it.groupValues[3] }
            }
            val nonce = p["nonce"] ?: return AuthResult.BAD
            if (nonce.length < 17) return AuthResult.STALE
            val ts = nonce.dropLast(16)
            if (md5("$ts:$nonceKey").take(16) != nonce.takeLast(16)) return AuthResult.STALE
            val age = System.currentTimeMillis() - (ts.toLongOrNull(16) ?: 0L)
            if (age > 300_000 || age < -60_000) return AuthResult.STALE

            val ha1 = md5("${p["username"]}:${p["realm"]}:$secret")
            val ha2 = md5("$method:${p["uri"]}")
            val exp = if (p["qop"] != null) md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2")
            else md5("$ha1:$nonce:$ha2")
            if (p["response"].equals(exp, true)) AuthResult.OK else AuthResult.BAD
        } catch (e: Exception) { AuthResult.BAD }
    }

    private fun recordAuthFail(ip: String) {
        val now = System.currentTimeMillis()
        val prev = authFails[ip]
        val n = (if (prev != null && now - prev.second < 600_000) prev.first else 0) + 1
        authFails[ip] = Pair(n, now)
        if (n >= maxOf(cfg.maxAuthAttempts, 5)) {
            banned[ip] = now + cfg.banDuration.coerceAtLeast(60) * 1000L
            authFails.remove(ip)
            log("SEC", "🚫 $ip banned for ${cfg.banDuration}s (too many failed logins)")
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ network

    private fun transportFor(ip: String, port: Int) = if (tcpConns.containsKey("$ip:$port")) "TCP" else "UDP"

    private fun sendBytes(bytes: ByteArray, ip: String, port: Int) {
        try {
            tcpConns["$ip:$port"]?.let { it.write(bytes); return }
            udp?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port))
        } catch (e: Exception) {
            log("ERR", "Send to $ip:$port failed: ${e.message}")
        }
    }

    /** Our address as seen from [peerIp] (the interface the OS would route through). */
    private fun ownIpFor(peerIp: String): String {
        val ext = cfg.externalIP.trim()
        if (cfg.natEnabled && ext.isNotEmpty()) {
            val n = Net.toInt(peerIp)
            if (n == null || !Net.isPrivate(n)) return ext
        }
        return try {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(peerIp), 9)
                val a = s.localAddress.hostAddress
                if (a == null || a == "0.0.0.0") localIp else a
            }
        } catch (_: Exception) { localIp }
    }

    private fun localAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun getIp(): String {
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase()
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    var score = 0
                    if (a.isSiteLocalAddress) score += 2
                    if (Regex("^(wlan|ap|swlan|eth|rndis|br|en)").containsMatchIn(name)) score += 1
                    if (Regex("^(rmnet|ccmni|pdp|v4-|tun|ppp)").containsMatchIn(name)) score -= 2
                    if (score > bestScore) { bestScore = score; best = a.hostAddress }
                }
            }
        } catch (_: Exception) {}
        return best ?: "0.0.0.0"
    }

    private fun log(tag: String, msg: String) = logger(LogLevel.INFO, tag, msg)

    // ------------------------------------------------------------ public API (UI)

    fun getRegisteredCount() = registered.size
    fun getActiveCallCount() = calls.values.count { it.state != "FAILED" && it.state != "CANCELLING" } + gateway.activeCount()
    fun getServerIp() = localIp
    fun getRelayBytes() = relay?.totalBytes?.get() ?: 0L

    fun getPeers(): List<PeerInfo> {
        val now = System.currentTimeMillis()
        val limit = maxOf(150_000L, cfg.qualifyFrequency * 3000L)
        return registered.values.sortedBy { it.ext }.map {
            PeerInfo(it.ext, it.ip, it.port, it.transport, it.userAgent, it.lastSeen, now - it.lastSeen < limit)
        }
    }

    fun getTrunks() = gateway.trunkInfos()
    fun reloadTrunks() { if (running) scope.launch { gateway.reload() } }
    fun retryTrunk(id: Long) = gateway.retryNow(id)

    fun getCalls(): List<CallInfo> = calls.values
        .filter { it.state != "FAILED" && it.state != "CANCELLING" }
        .sortedBy { it.time }
        .map { CallInfo(it.id, it.fromExt, it.toExt, it.state, it.time, it.relayed, it.video, relay?.session(it.id)?.packets?.get() ?: 0L) } + gateway.callInfos()
}

/** Tiny IPv4 helpers. */
internal object Net {
    fun toInt(ip: String): Int? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var r = 0
        for (s in p) { val v = s.toIntOrNull() ?: return null; if (v !in 0..255) return null; r = (r shl 8) or v }
        return r
    }
    fun isPrivate(n: Int): Boolean {
        val a = n ushr 24; val b = (n ushr 16) and 255
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    fun inCidr(n: Int, cidr: String): Boolean {
        val base = toInt(cidr.substringBefore('/')) ?: return false
        val bits = cidr.substringAfter('/', "32").toIntOrNull() ?: return false
        if (bits !in 0..32) return false
        if (bits == 0) return true
        val mask = -1 shl (32 - bits)
        return (n and mask) == (base and mask)
    }
}
