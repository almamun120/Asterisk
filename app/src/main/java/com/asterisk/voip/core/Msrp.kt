package com.asterisk.voip.core

import com.asterisk.voip.data.model.OfflineFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** MSRP (RFC 4975) message framing. */
internal object MsrpWire {
    private val CANON = listOf(
        "To-Path", "From-Path", "Message-ID", "Byte-Range", "Content-Type", "Content-Disposition",
        "Failure-Report", "Success-Report", "Status", "Use-Path", "Content-Description"
    )

    fun canonical(n: String): String = CANON.firstOrNull { it.equals(n, true) } ?: n

    class Msg(
        val tid: String,
        val method: String,           // "SEND", "REPORT" ... or "" for a response
        val status: Int,              // 0 for requests
        val statusText: String,
        val headers: LinkedHashMap<String, String>,
        val body: ByteArray,
        val flag: Char                // '$' complete, '+' more chunks follow, '#' aborted
    )

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()

    class Reader(private val input: InputStream) {
        private var buf = ByteArray(65536)
        private var len = 0

        private fun fill(): Boolean {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) return false
            len += n
            return true
        }

        private fun crlf(from: Int): Int {
            var i = from
            while (i < len - 1) {
                if (buf[i] == CR && buf[i + 1] == LF) return i
                i++
            }
            return -1
        }

        private fun indexOf(pat: ByteArray, from: Int): Int {
            var i = from
            while (i <= len - pat.size) {
                var k = 0
                while (k < pat.size && buf[i + k] == pat[k]) k++
                if (k == pat.size) return i
                i++
            }
            return -1
        }

        /** Blocks for the next message; null on EOF, protocol error or a body above [maxBody]. */
        fun read(maxBody: Int): Msg? {
            var p: Int
            while (true) {
                p = crlf(0)
                if (p >= 0) break
                if (len > 8192 || !fill()) return null
            }
            val start = String(buf, 0, p, Charsets.ISO_8859_1)
            val parts = start.split(' ')
            if (parts.size < 3 || parts[0] != "MSRP") return null
            val tid = parts[1]
            val endMarker = "-------$tid"
            var pos = p + 2
            val headers = LinkedHashMap<String, String>()
            var flag = '$'
            var bodyStart = -1
            while (true) {
                var q: Int
                while (true) {
                    q = crlf(pos)
                    if (q >= 0) break
                    if (len > 65536 + maxBody || !fill()) return null
                }
                val line = String(buf, pos, q - pos, Charsets.ISO_8859_1)
                pos = q + 2
                if (line.startsWith(endMarker)) {
                    flag = if (line.length > endMarker.length) line[endMarker.length] else '$'
                    bodyStart = -2
                    break
                }
                if (line.isEmpty()) { bodyStart = pos; break }
                val c = line.indexOf(':')
                if (c > 0) headers[canonical(line.substring(0, c).trim())] = line.substring(c + 1).trim()
            }
            var body = ByteArray(0)
            var consumed = pos
            if (bodyStart >= 0) {
                val term = ("\r\n$endMarker").toByteArray(Charsets.ISO_8859_1)
                var scan = bodyStart
                var idx: Int
                while (true) {
                    idx = indexOf(term, scan)
                    if (idx >= 0 && len >= idx + term.size + 3) break
                    if (idx < 0) scan = maxOf(bodyStart, len - term.size)
                    if (len - bodyStart > maxBody + 1024) return null
                    if (!fill()) return null
                }
                body = buf.copyOfRange(bodyStart, idx)
                flag = (buf[idx + term.size].toInt() and 0xFF).toChar()
                consumed = idx + term.size + 3
            }
            System.arraycopy(buf, consumed, buf, 0, len - consumed)
            len -= consumed
            val status = parts[2].toIntOrNull() ?: 0
            return Msg(tid, if (status == 0) parts[2] else "", status,
                if (status != 0) parts.drop(3).joinToString(" ") else "", headers, body, flag)
        }
    }

    fun request(tid: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?, flag: Char): ByteArray {
        val sb = StringBuilder()
        sb.append("MSRP ").append(tid).append(' ').append(method).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        val tail = "-------$tid$flag\r\n".toByteArray(Charsets.ISO_8859_1)
        return if (body != null && body.isNotEmpty()) {
            head + "\r\n".toByteArray(Charsets.ISO_8859_1) + body + "\r\n".toByteArray(Charsets.ISO_8859_1) + tail
        } else head + tail
    }

    fun response(tid: String, code: Int, text: String, toPath: String, fromPath: String): ByteArray {
        val s = "MSRP $tid $code $text\r\nTo-Path: $toPath\r\nFrom-Path: $fromPath\r\n-------$tid$\r\n"
        return s.toByteArray(Charsets.ISO_8859_1)
    }

    fun rid(n: Int = 12): String = UUID.randomUUID().toString().replace("-", "").take(n)

    /** Byte-Range "start-end/total": start, end (-1 = *), total (-1 = *). */
    fun range(br: String?): Triple<Long, Long, Long> {
        val m = Regex("(\\d+)-(\\d+|\\*)/(\\d+|\\*)").find(br ?: "") ?: return Triple(1L, -1L, -1L)
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLongOrNull() ?: -1L, m.groupValues[3].toLongOrNull() ?: -1L)
    }
}

/**
 * File sharing / messaging over SIP + MSRP.
 *
 *  1. Gateway: when media is relayed, MSRP sessions between two extensions pass through the
 *     server (both sides connect to the server, so NAT'd clients work; paths are rewritten).
 *  2. Store-and-forward: an MSRP file offer to an offline extension is accepted by the server,
 *     the file is stored and later pushed with a normal SIP INVITE + MSRP when it registers.
 */
class MsrpManager(private val srv: SipServer, private val portStart: Int, private val portEnd: Int) {

    private class Fwd(val conn: Conn, val origTid: String, val origFrom: String, val origTo: String)

    private inner class Conn(val socket: Socket, val onMsg: (Conn, MsrpWire.Msg) -> Unit, val onClose: (Conn) -> Unit) {
        @Volatile var closed = false
        private val out = socket.getOutputStream()

        fun start() {
            srv.scope.launch {
                try {
                    val reader = MsrpWire.Reader(socket.getInputStream())
                    val max = maxBody()
                    while (!closed) {
                        val m = reader.read(max) ?: break
                        onMsg(this@Conn, m)
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                    try { onClose(this@Conn) } catch (_: Exception) {}
                }
            }
        }

        fun write(b: ByteArray) {
            synchronized(this) { out.write(b); out.flush() }
        }

        fun close() {
            closed = true
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private class Attrs(val path: String?, val setup: String?, val fileSelector: String?, val transferId: String?)

    private inner class Gateway(val callId: String) {
        @Volatile var aPath = ""
        @Volatile var bPath = ""
        @Volatile var pathToB = ""
        @Volatile var pathToA = ""
        @Volatile var portToB = 0
        @Volatile var portToA = 0
        @Volatile var aActive = false
        @Volatile var connA: Conn? = null
        @Volatile var connB: Conn? = null
        var listenA: ServerSocket? = null
        var listenB: ServerSocket? = null
        val fwd = ConcurrentHashMap<String, Fwd>()
        val queueToA = ArrayList<MsrpWire.Msg>()
        val queueToB = ArrayList<MsrpWire.Msg>()

        fun close() {
            try { listenA?.close() } catch (_: Exception) {}
            try { listenB?.close() } catch (_: Exception) {}
            connA?.close(); connB?.close()
        }
    }

    private val gateways = ConcurrentHashMap<String, Gateway>()
    private val delivering: MutableSet<Long> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    @Volatile private var running = false

    fun start() { running = true }

    fun stop() {
        running = false
        gateways.values.forEach { it.close() }
        gateways.clear()
    }

    fun closeSession(callId: String) { gateways.remove(callId)?.close() }

    private fun maxBody(): Int = (srv.config.fileMaxSizeMb.coerceIn(1, 500)) * 1024 * 1024

    // ------------------------------------------------------------ sockets

    private fun openListener(): ServerSocket? {
        val span = maxOf(1, portEnd - portStart)
        repeat(40) {
            val p = portStart + Random.nextInt(span)
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                return ss
            } catch (_: Exception) {}
        }
        return null
    }

    private fun acceptOnce(ss: ServerSocket, timeoutMs: Int, cb: (Socket) -> Unit) {
        srv.scope.launch {
            try {
                ss.soTimeout = timeoutMs
                val s = ss.accept()
                s.tcpNoDelay = true
                cb(s)
            } catch (_: Exception) {
            } finally {
                try { ss.close() } catch (_: Exception) {}
            }
        }
    }

    private fun connectTo(path: String, fallbackHost: String?): Socket? {
        val m = Regex("msrps?://(\\[[^\\]]+\\]|[^:/;\\s]+)(?::(\\d+))?").find(path) ?: return null
        val host = m.groupValues[1].trim('[', ']')
        val port = m.groupValues[2].toIntOrNull() ?: 2855
        for (h in listOfNotNull(host, fallbackHost).distinct()) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(h, port), 4000)
                s.tcpNoDelay = true
                return s
            } catch (_: Exception) {}
        }
        return null
    }

    // ------------------------------------------------------------ SDP

    private fun attrs(sdp: String): Attrs {
        var inMsrp = false
        var seen = false
        var path: String? = null; var setup: String? = null; var fsel: String? = null; var tid: String? = null
        for (line in sdp.split("\r\n", "\n")) {
            if (line.startsWith("m=")) {
                val p = line.substring(2).split(" ")
                inMsrp = !seen && p.size >= 3 && p[2].uppercase().contains("MSRP") && p[1] != "0"
                if (inMsrp) seen = true
            } else if (inMsrp) {
                when {
                    line.startsWith("a=path:") -> path = line.substring(7).trim()
                    line.startsWith("a=setup:") -> setup = line.substring(8).trim().lowercase()
                    line.startsWith("a=file-selector:") -> fsel = line.substring(16).trim()
                    line.startsWith("a=file-transfer-id:") -> tid = line.substring(19).trim()
                }
            }
        }
        return Attrs(path, setup, fsel, tid)
    }

    private fun patchMsrp(sdp: String, port: Int, path: String, setup: String?): String {
        val out = StringBuilder()
        var inMsrp = false
        for (line in sdp.split("\r\n", "\n")) {
            if (line.isEmpty()) continue
            if (line.startsWith("m=")) {
                val p = line.substring(2).split(" ")
                inMsrp = p.size >= 3 && p[2].uppercase().contains("MSRP") && p[1] != "0"
                if (inMsrp) {
                    val mp = p.toMutableList()
                    mp[1] = port.toString()
                    out.append("m=").append(mp.joinToString(" ")).append("\r\n")
                    continue
                }
            } else if (inMsrp) {
                if (line.startsWith("a=path:")) { out.append("a=path:").append(path).append("\r\n"); continue }
                if (setup != null && line.startsWith("a=setup:")) { out.append("a=setup:").append(setup).append("\r\n"); continue }
            }
            out.append(line).append("\r\n")
        }
        return out.toString()
    }

    private fun newPath(ip: String, port: Int) = "msrp://$ip:$port/${MsrpWire.rid(10)};tcp"

    // ------------------------------------------------------------ 1. gateway between two extensions

    /**
     * Called by the proxy for relayed calls. [originSide]: 0 = INVITE from the caller (offer),
     * 1 = answer from the callee. Returns the SDP with MSRP sections pointing at this server.
     */
    fun rewriteSdp(call: SipServer.Call, sdp: String, originSide: Int, ip: String): String {
        if (!running) return sdp
        val gw = gateways.getOrPut(call.id) { Gateway(call.id) }
        val a = attrs(sdp)
        val aPathNow: String = a.path ?: return sdp
        return synchronized(gw) {
            if (originSide == MediaRelay.SIDE_A) {
                gw.aPath = aPathNow
                gw.aActive = a.setup == "active"
                if (gw.portToB == 0) {
                    val ss = openListener() ?: return sdp
                    gw.listenB = ss
                    gw.portToB = ss.localPort
                    gw.pathToB = newPath(ip, gw.portToB)
                    acceptOnce(ss, 60_000) { s -> attachB(gw, s) }
                    srv.slog("MSRP", "⇄ Gateway for ${call.fromExt} → ${call.toExt}: callee side on port ${gw.portToB}")
                }
                patchMsrp(sdp, gw.portToB, gw.pathToB, if (a.setup != null) "passive" else null)
            } else {
                gw.bPath = aPathNow
                val bPassive = a.setup == "passive"
                if (gw.portToA == 0) {
                    val ss = openListener() ?: return sdp
                    gw.listenA = ss
                    gw.portToA = ss.localPort
                    gw.pathToA = newPath(ip, gw.portToA)
                    if (gw.aActive) acceptOnce(ss, 60_000) { s -> attachA(gw, s) }
                    else {
                        try { ss.close() } catch (_: Exception) {}
                        srv.scope.launch {
                            delay(150)
                            val s = connectTo(gw.aPath, call.callerIp)
                            if (s != null) attachA(gw, s) else srv.slog("MSRP", "✗ Could not connect to the sender at ${gw.aPath}")
                        }
                    }
                    if (bPassive) {
                        try { gw.listenB?.close() } catch (_: Exception) {}
                        srv.scope.launch {
                            val s = connectTo(gw.bPath, call.calleeIp)
                            if (s != null) attachB(gw, s) else srv.slog("MSRP", "✗ Could not connect to the receiver at ${gw.bPath}")
                        }
                    }
                }
                patchMsrp(sdp, gw.portToA, gw.pathToA, if (a.setup != null) (if (gw.aActive) "passive" else "active") else null)
            }
        }
    }

    private fun handshake(c: Conn, toPath: String, fromPath: String) {
        // the connecting side announces the session with an empty SEND (RFC 4975 §7.1)
        try {
            c.write(MsrpWire.request(MsrpWire.rid(), "SEND", listOf(
                "To-Path" to toPath, "From-Path" to fromPath,
                "Message-ID" to MsrpWire.rid(), "Byte-Range" to "1-0/0"), null, '$'))
        } catch (_: Exception) {}
    }

    private fun attachA(gw: Gateway, s: Socket) {
        val c = Conn(s, { from, m -> onGwMsg(gw, from, true, m) }, { })
        gw.connA = c
        c.start()
        if (!gw.aActive) handshake(c, gw.aPath, gw.pathToA)
        flush(gw, true)
    }

    private fun attachB(gw: Gateway, s: Socket) {
        val c = Conn(s, { from, m -> onGwMsg(gw, from, false, m) }, { })
        gw.connB = c
        c.start()
        flush(gw, false)
    }

    private fun flush(gw: Gateway, toA: Boolean) {
        val list = synchronized(gw) {
            val q = if (toA) gw.queueToA else gw.queueToB
            val copy = ArrayList(q); q.clear(); copy
        }
        val dst = if (toA) gw.connA else gw.connB
        if (dst == null) return
        for (m in list) forward(gw, if (toA) gw.connB else gw.connA, !toA, m, dst)
    }

    private fun onGwMsg(gw: Gateway, from: Conn, fromA: Boolean, m: MsrpWire.Msg) {
        if (m.method.isEmpty()) { // a response to something we forwarded
            val rec = gw.fwd.remove(m.tid) ?: return
            try {
                rec.conn.write(MsrpWire.response(rec.origTid, m.status, m.statusText, rec.origFrom, rec.origTo))
            } catch (_: Exception) {}
            return
        }
        val emptySend = m.method == "SEND" && m.body.isEmpty() && m.headers["Content-Type"] == null
        if (emptySend) {
            try { from.write(MsrpWire.response(m.tid, 200, "OK", m.headers["From-Path"] ?: "", m.headers["To-Path"] ?: "")) } catch (_: Exception) {}
            return
        }
        val dst = if (fromA) gw.connB else gw.connA
        if (dst == null) {
            synchronized(gw) { (if (fromA) gw.queueToB else gw.queueToA).add(m) }
            // re-check in case the other side attached meanwhile
            val d2 = if (fromA) gw.connB else gw.connA
            if (d2 != null) flush(gw, !fromA)
            return
        }
        forward(gw, from, fromA, m, dst)
    }

    private fun forward(gw: Gateway, from: Conn?, fromA: Boolean, m: MsrpWire.Msg, dst: Conn) {
        val newTid = MsrpWire.rid()
        val toPath = if (fromA) gw.bPath else gw.aPath
        val fromPath = if (fromA) gw.pathToB else gw.pathToA
        val hs = ArrayList<Pair<String, String>>()
        hs.add("To-Path" to toPath)
        hs.add("From-Path" to fromPath)
        for ((k, v) in m.headers) if (k != "To-Path" && k != "From-Path" && k != "Use-Path") hs.add(k to v)
        try {
            dst.write(MsrpWire.request(newTid, m.method, hs, m.body, m.flag))
            if (m.method == "SEND" && from != null) {
                gw.fwd[newTid] = Fwd(from, m.tid, m.headers["From-Path"] ?: "", m.headers["To-Path"] ?: "")
            }
        } catch (e: Exception) {
            srv.slog("MSRP", "✗ Forward failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ 2. store and forward

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "file" }

    /**
     * An MSRP offer arrived for an extension that is offline. Accept it, keep the file (or text)
     * and deliver later. Returns false if the offer could not be accepted.
     */
    fun acceptOfflineFile(call: SipServer.Call): Boolean {
        if (!running) return false
        val a = attrs(call.invite.body)
        val senderPath = a.path ?: return false
        val fsel = a.fileSelector
        val name = if (fsel != null) Regex("name:\"([^\"]*)\"").find(fsel)?.groupValues?.get(1) ?: "file" else ""
        val mime = if (fsel != null) Regex("type:(\\S+)").find(fsel)?.groupValues?.get(1) ?: "application/octet-stream" else "text/plain"
        val size = if (fsel != null) Regex("size:(\\d+)").find(fsel)?.groupValues?.get(1)?.toLongOrNull() ?: -1L else -1L
        val maxBytes = maxBody().toLong()
        if (size > maxBytes) { srv.slog("MSRP", "✗ File too large ($size > $maxBytes)"); return false }

        val ss = openListener() ?: return false
        val ip = srv.ownIp(call.callerIp)
        val myPath = newPath(ip, ss.localPort)
        val senderActive = a.setup == "active"

        val sb = StringBuilder()
        sb.append("v=0\r\no=- ${System.currentTimeMillis() / 1000} 1 IN IP4 $ip\r\ns=-\r\nc=IN IP4 $ip\r\nt=0 0\r\n")
        sb.append("m=message ${ss.localPort} TCP/MSRP *\r\na=accept-types:*\r\na=path:$myPath\r\na=recvonly\r\n")
        if (a.setup != null) sb.append("a=setup:${if (senderActive) "passive" else "active"}\r\n")
        if (fsel != null) sb.append("a=file-selector:$fsel\r\n")
        if (a.transferId != null) sb.append("a=file-transfer-id:${a.transferId}\r\n")

        val leg = try { srv.legManager.answerRaw(call, sb.toString()) } catch (e: Exception) {
            try { ss.close() } catch (_: Exception) {}
            return false
        }
        srv.slog("MSRP", "📥 ${call.toExt} offline: receiving ${if (fsel != null) "file \"$name\"" else "message"} from ${call.fromExt} for later delivery")

        val rx = Receiver(call.fromExt, call.toExt, name, mime, size, maxBytes)
        if (senderActive) {
            acceptOnce(ss, 60_000) { s -> rx.attach(s, senderPath, myPath, false) }
        } else {
            try { ss.close() } catch (_: Exception) {}
            srv.scope.launch {
                delay(150)
                val s = connectTo(senderPath, call.callerIp)
                if (s != null) rx.attach(s, senderPath, myPath, true)
                else { srv.slog("MSRP", "✗ Could not connect to the sender"); srv.legManager.closeLeg(leg, true) }
            }
        }
        return true
    }

    private inner class Receiver(
        val from: String, val to: String, val name: String, val mime: String, val total: Long, val maxBytes: Long
    ) {
        private var tmp: File? = null
        private var raf: RandomAccessFile? = null
        private var received = 0L
        private val text = java.io.ByteArrayOutputStream()
        private var finished = false

        fun attach(s: Socket, senderPath: String, myPath: String, weConnected: Boolean) {
            val c = Conn(s, { conn, m -> onMsg(conn, m) }, { cleanup() })
            c.start()
            if (weConnected) handshake(c, senderPath, myPath)
        }

        @Synchronized
        private fun onMsg(c: Conn, m: MsrpWire.Msg) {
            if (m.method.isEmpty() || m.method == "REPORT") return
            val respOk = { c.write(MsrpWire.response(m.tid, 200, "OK", m.headers["From-Path"] ?: "", m.headers["To-Path"] ?: "")) }
            if (m.body.isEmpty() && m.headers["Content-Type"] == null) { respOk(); return }
            if (m.flag == '#') { respOk(); cleanup(); return }
            val (start, _, rangeTotal) = MsrpWire.range(m.headers["Byte-Range"])
            val expected = if (total > 0) total else rangeTotal
            if (received + m.body.size > maxBytes) {
                c.write(MsrpWire.response(m.tid, 413, "Too large", m.headers["From-Path"] ?: "", m.headers["To-Path"] ?: ""))
                cleanup(); return
            }
            try {
                if (name.isEmpty()) {
                    text.write(m.body)
                } else {
                    if (raf == null) {
                        val f = File(srv.storageDir, "files/$to/${System.currentTimeMillis()}_${safeName(name)}")
                        f.parentFile?.mkdirs()
                        tmp = f
                        raf = RandomAccessFile(f, "rw")
                    }
                    raf!!.seek(maxOf(0L, start - 1))
                    raf!!.write(m.body)
                }
                received += m.body.size
                respOk()
            } catch (e: Exception) {
                srv.slog("MSRP", "✗ Receive error: ${e.message}")
                cleanup(); return
            }
            val done = m.flag == '$' && (expected <= 0 || received >= expected)
            if (done) complete()
        }

        private fun complete() {
            if (finished) return
            finished = true
            val now = System.currentTimeMillis()
            val keepMs = srv.config.fileKeepDays.coerceIn(1, 365) * 24L * 3600 * 1000
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            srv.scope.launch {
                try {
                    if (name.isEmpty()) {
                        srv.queueText(from, to, mime, SipMessage.bodyToText(String(text.toByteArray(), Charsets.ISO_8859_1)))
                    } else {
                        val f = tmp ?: return@launch
                        srv.repo.insertOfflineFile(OfflineFile(fromExt = from, toExt = to, fileName = name, mimeType = mime,
                            size = received, path = f.absolutePath, timestamp = now, expiresAt = now + keepMs))
                        srv.slog("MSRP", "✓ File \"$name\" (${received / 1024} KiB) from $from stored for $to")
                        srv.sendSystemText(to, "📎 $from sent you a file: $name (${received / 1024} KB). It will be delivered when you are online.")
                    }
                } catch (e: Exception) {
                    srv.slog("MSRP", "✗ Could not store file: ${e.message}")
                }
            }
        }

        private fun cleanup() {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            if (!finished) { try { tmp?.delete() } catch (_: Exception) {} }
        }
    }

    // ------------------------------------------------------------ 3. delivery of stored files

    /** Called when [ext] registers: push every stored file with SIP INVITE + MSRP. */
    fun deliverQueuedFiles(ext: String) {
        if (!running) return
        srv.scope.launch {
            try {
                val pending = srv.repo.getPendingFiles(ext)
                for (f in pending) {
                    if (!srv.registeredMap.containsKey(ext)) break
                    if (!delivering.add(f.id)) continue
                    try { deliverOne(f) } finally { delivering.remove(f.id) }
                    delay(300)
                }
            } catch (e: Exception) {
                srv.slog("MSRP", "✗ Delivery error for $ext: ${e.message}")
            }
        }
    }

    private suspend fun deliverOne(f: OfflineFile) {
        val file = File(f.path)
        if (!file.exists()) { srv.repo.deleteOfflineFile(f.id); return }
        val dest = srv.registeredMap[f.toExt] ?: return
        val ss = openListener() ?: return
        val ip = srv.ownIp(dest.ip)
        val myPath = newPath(ip, ss.localPort)
        val total = file.length()

        val sdp = "v=0\r\no=- ${System.currentTimeMillis() / 1000} 1 IN IP4 $ip\r\ns=-\r\nc=IN IP4 $ip\r\nt=0 0\r\n" +
            "m=message ${ss.localPort} TCP/MSRP *\r\na=accept-types:${f.mimeType}\r\na=path:$myPath\r\na=sendonly\r\na=setup:passive\r\n" +
            "a=file-selector:name:\"${f.fileName}\" type:${f.mimeType} size:$total\r\na=file-transfer-id:${MsrpWire.rid(16)}\r\n"

        val answer = CompletableDeferred<Attrs>()
        val connDone = CompletableDeferred<Conn>()
        val acks = ConcurrentHashMap<String, Int>()
        var failed = false

        val onMsg: (Conn, MsrpWire.Msg) -> Unit = { c, m ->
            if (m.method.isEmpty()) {
                if (acks.remove(m.tid) != null && m.status != 200) failed = true
            } else if (m.method == "SEND" && m.body.isEmpty()) {
                try { c.write(MsrpWire.response(m.tid, 200, "OK", m.headers["From-Path"] ?: "", m.headers["To-Path"] ?: "")) } catch (_: Exception) {}
            }
        }
        acceptOnce(ss, 40_000) { s ->
            val c = Conn(s, onMsg, { })
            c.start()
            connDone.complete(c)
        }

        val leg = srv.legManager.originateRaw("files", f.toExt, sdp) { body -> answer.complete(attrs(body)) }
        if (leg == null) { try { ss.close() } catch (_: Exception) {}; return }
        srv.slog("MSRP", "📤 Delivering \"${f.fileName}\" to ${f.toExt}")

        val a = withTimeoutOrNull(30_000L) { answer.await() }
        val bPath: String? = a?.path
        if (a == null || bPath == null) {
            srv.slog("MSRP", "✗ ${f.toExt} did not accept the file transfer, will retry later")
            try { ss.close() } catch (_: Exception) {}
            srv.legManager.closeLeg(leg, true)
            return
        }
        var conn: Conn? = withTimeoutOrNull(if (a.setup == "passive") 2_000L else 30_000L) { connDone.await() }
        if (conn == null && a.setup == "passive") {
            val s = connectTo(bPath, dest.ip)
            if (s != null) {
                try { ss.close() } catch (_: Exception) {}
                val c = Conn(s, onMsg, { })
                c.start()
                handshake(c, bPath, myPath)
                conn = c
            }
        }
        val out: Conn = conn ?: run {
            srv.slog("MSRP", "✗ ${f.toExt} never connected for the file transfer, will retry later")
            srv.legManager.closeLeg(leg, true)
            return
        }

        // send the file in chunks
        val mid = MsrpWire.rid(16)
        val chunk = 16 * 1024
        try {
            file.inputStream().use { ins ->
                val buf = ByteArray(chunk)
                var sent = 0L
                while (sent < total) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    val last = sent + n >= total
                    val tid = MsrpWire.rid()
                    acks[tid] = 1
                    out.write(MsrpWire.request(tid, "SEND", listOf(
                        "To-Path" to bPath, "From-Path" to myPath,
                        "Message-ID" to mid,
                        "Byte-Range" to "${sent + 1}-${sent + n}/$total",
                        "Content-Type" to f.mimeType,
                        "Content-Disposition" to "attachment; filename=\"${f.fileName}\""),
                        buf.copyOf(n), if (last) '$' else '+'))
                    sent += n
                }
            }
            var waited = 0
            while (acks.isNotEmpty() && !failed && waited < 30_000) { delay(100); waited += 100 }
            if (acks.isEmpty() && !failed) {
                srv.repo.markFileDelivered(f.id)
                try { file.delete() } catch (_: Exception) {}
                srv.slog("MSRP", "✅ \"${f.fileName}\" delivered to ${f.toExt}")
            } else {
                srv.slog("MSRP", "✗ Delivery of \"${f.fileName}\" not confirmed, will retry later")
            }
        } catch (e: Exception) {
            srv.slog("MSRP", "✗ Delivery error: ${e.message}")
        } finally {
            out.close()
            srv.legManager.closeLeg(leg, true)
        }
    }

    /** Hourly: remove expired / old delivered files. */
    suspend fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            for (f in srv.repo.getStaleFiles(now, now - 24L * 3600 * 1000)) {
                try { File(f.path).delete() } catch (_: Exception) {}
                srv.repo.deleteOfflineFile(f.id)
            }
        } catch (_: Exception) {}
    }
}
