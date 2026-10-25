package com.asterisk.voip.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * One RTP audio stream terminated on the server itself (G.711 only).
 *
 *  - [playPcm] streams prompts to the far end with 20 ms pacing
 *  - [startRecording]/[stopRecording] capture what the far end says (voicemail)
 *  - RFC 2833 DTMF events are decoded into [dtmf]
 *  - [bridge] forwards every received datagram to another RtpAudio, which turns two
 *    server-terminated legs into a normal two-party call (IVR / queue / callback)
 *
 * The far end address starts from the SDP and is replaced by the real packet source
 * as soon as the first packet arrives (symmetric RTP), so NAT'd clients work.
 */
class RtpAudio private constructor(
    private val socket: DatagramSocket,
    val port: Int
) {
    companion object {
        /** Opens a socket on an even port in [start]..[end], or returns null. */
        fun open(start: Int, end: Int, tos: Int): RtpAudio? {
            val lo = start.coerceAtLeast(1024)
            val first = if (lo % 2 == 0) lo else lo + 1
            val span = ((end - first) / 2).coerceAtLeast(1)
            repeat(60) {
                val p = first + 2 * Random.nextInt(span)
                try {
                    val s = DatagramSocket(null)
                    s.reuseAddress = false
                    s.bind(InetSocketAddress(p))
                    try { if (tos > 0) s.trafficClass = tos } catch (_: Exception) {}
                    return RtpAudio(s, p)
                } catch (_: Exception) { /* port busy, try another */ }
            }
            return null
        }
    }

    @Volatile var remote: InetSocketAddress? = null
    @Volatile var alaw = false
    @Volatile var dtmfPt = 101
    @Volatile var bridge: RtpAudio? = null
    @Volatile var closed = false
    @Volatile var latched = false
    @Volatile var lastRxAt = System.currentTimeMillis()
    val rxPackets = AtomicLong()
    val rxBytes = AtomicLong()
    val dtmf = Channel<Char>(Channel.UNLIMITED)

    private var seq = Random.nextInt(0, 0xFFFF)
    private var ts = Random.nextLong(0, 0xFFFFFFFFL)
    private val ssrc = Random.nextInt()
    private var lastDtmfTs = -1L
    @Volatile private var rec: G711.PcmBuffer? = null
    private var thread: Thread? = null

    fun setRemoteHint(ip: String?, p: Int) {
        if (ip == null || ip == "0.0.0.0" || p <= 0) return
        try { if (!latched) remote = InetSocketAddress(ip, p) } catch (_: Exception) {}
    }

    fun startReceiver() {
        if (thread != null) return
        thread = Thread({ receiveLoop() }, "rtp-audio-$port").apply { isDaemon = true; start() }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        try { socket.soTimeout = 500 } catch (_: Exception) {}
        while (!closed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val len = pkt.length
                if (len < 12) continue
                val src = InetSocketAddress(pkt.address, pkt.port)
                if (!latched) { remote = src; latched = true }
                else if (remote != src) continue // stray packet
                lastRxAt = System.currentTimeMillis()
                rxPackets.incrementAndGet()
                rxBytes.addAndGet(len.toLong())

                val b0 = buf[0].toInt() and 0xFF
                if ((b0 shr 6) != 2) continue
                val pt = buf[1].toInt() and 0x7F

                // forward to the other leg of a bridged call (audio + DTMF unchanged)
                val br = bridge
                if (br != null) {
                    br.sendRaw(buf, len)
                    continue
                }

                val cc = b0 and 0x0F
                var off = 12 + cc * 4
                if ((b0 and 0x10) != 0 && len >= off + 4) { // extension header
                    off += 4 + (((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)) * 4
                }
                var end = len
                if ((b0 and 0x20) != 0) end -= (buf[len - 1].toInt() and 0xFF) // padding
                if (off >= end) continue

                if (pt == dtmfPt && end - off >= 4) {
                    val stamp = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
                        ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)
                    if (stamp != lastDtmfTs) {
                        lastDtmfTs = stamp
                        val ev = buf[off].toInt() and 0xFF
                        val c = when (ev) { in 0..9 -> '0' + ev; 10 -> '*'; 11 -> '#'; else -> null }
                        if (c != null) dtmf.trySend(c)
                    }
                    continue
                }
                val r = rec
                if (r != null && (pt == 0 || pt == 8)) {
                    val a = pt == 8
                    for (i in off until end) r.add(G711.decodeByte(buf[i], a))
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (closed) break
            }
        }
    }

    fun sendRaw(data: ByteArray, len: Int) {
        val dst = remote ?: return
        try { socket.send(DatagramPacket(data, len, dst)) } catch (_: Exception) {}
    }

    private fun sendFrame(payload: ByteArray, off: Int, n: Int, marker: Boolean) {
        val dst = remote ?: return
        val pkt = ByteArray(12 + n)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0) or (if (alaw) 8 else 0)).toByte()
        pkt[2] = (seq shr 8).toByte(); pkt[3] = seq.toByte()
        pkt[4] = (ts shr 24).toByte(); pkt[5] = (ts shr 16).toByte(); pkt[6] = (ts shr 8).toByte(); pkt[7] = ts.toByte()
        pkt[8] = (ssrc shr 24).toByte(); pkt[9] = (ssrc shr 16).toByte(); pkt[10] = (ssrc shr 8).toByte(); pkt[11] = ssrc.toByte()
        System.arraycopy(payload, off, pkt, 12, n)
        try { socket.send(DatagramPacket(pkt, pkt.size, dst)) } catch (_: Exception) {}
    }

    /** Plays 8 kHz mono PCM. Returns false when aborted (hang-up / [stop] true / closed). */
    suspend fun playPcm(pcm: ShortArray, stop: () -> Boolean = { false }): Boolean {
        val data = G711.encode(pcm, alaw)
        var off = 0
        var next = System.nanoTime()
        var first = true
        while (off < data.size) {
            if (closed || stop()) return false
            if (bridge != null) return false
            sendFrame(data, off, G711.FRAME, first)
            first = false
            off += G711.FRAME
            seq = (seq + 1) and 0xFFFF
            ts = (ts + G711.FRAME) and 0xFFFFFFFFL
            next += 20_000_000L
            val waitMs = (next - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) delay(waitMs) else if (waitMs < -200) next = System.nanoTime()
        }
        return !closed
    }

    fun startRecording() { rec = G711.PcmBuffer() }
    fun recordedSamples(): Int = rec?.sizeSamples() ?: 0
    fun stopRecording(): ShortArray {
        val r = rec
        rec = null
        return r?.toShorts() ?: ShortArray(0)
    }

    fun close() {
        closed = true
        bridge = null
        dtmf.close()
        try { socket.close() } catch (_: Exception) {}
    }
}
