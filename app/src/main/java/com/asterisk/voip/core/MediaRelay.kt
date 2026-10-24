package com.asterisk.voip.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-threaded (NIO selector) RTP/RTCP relay for audio AND video.
 *
 * Every media stream of a call gets four UDP sockets:
 *   side A (caller-facing): rtp, rtcp      side B (callee-facing): rtp, rtcp
 * Packets received on one side are forwarded out of the opposite side to the
 * address learned from that side's first packet (symmetric RTP / "comedia"),
 * so it works for clients behind NAT. RTCP is relayed too, which keeps video
 * alive (PLI/FIR key-frame requests, receiver reports).
 */
class MediaRelay(
    private val rtpStart: Int,
    private val rtpEnd: Int,
    private val tos: Int,
    private val log: (String) -> Unit
) {
    companion object {
        const val SIDE_A = 0
        const val SIDE_B = 1
    }

    class Leg(
        val channel: DatagramChannel,
        val port: Int,
        val stream: Stream,
        val side: Int,
        val rtcp: Boolean
    ) {
        @Volatile var peer: InetSocketAddress? = null
        @Volatile var latched = false
        @Volatile var lastRx = 0L
    }

    class Stream(val index: Int, val type: String) {
        val legs = arrayOfNulls<Leg>(4) // side*2 + (rtcp?1:0)
        fun leg(side: Int, rtcp: Boolean) = legs[side * 2 + if (rtcp) 1 else 0]!!
        fun ports(side: Int) = Pair(leg(side, false).port, leg(side, true).port)
    }

    class Session(val callId: String) {
        val streams = CopyOnWriteArrayList<Stream>()
        val packets = AtomicLong()
        val bytes = AtomicLong()
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var closed = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionOf = ConcurrentHashMap<Leg, Session>()
    private val pending = ConcurrentLinkedQueue<Leg>()
    private var selector: Selector? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var nextPort = if (rtpStart % 2 == 0) rtpStart else rtpStart + 1
    val totalBytes = AtomicLong()

    fun start() {
        if (running) return
        selector = Selector.open()
        running = true
        thread = Thread({ loop() }, "rtp-relay").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        selector?.wakeup()
        thread?.join(1000)
        sessions.keys.toList().forEach { close(it) }
        try { selector?.close() } catch (_: Exception) {}
        selector = null
    }

    fun session(callId: String): Session? = sessions[callId]
    fun open(callId: String): Session = sessions.getOrPut(callId) { Session(callId) }

    /** Creates (if missing) the four sockets for media [index]. Null when no ports are free. */
    @Synchronized
    fun ensureStream(session: Session, index: Int, type: String): Stream? {
        session.streams.firstOrNull { it.index == index }?.let { return it }
        val stream = Stream(index, type)
        val opened = mutableListOf<DatagramChannel>()
        try {
            for (side in intArrayOf(SIDE_A, SIDE_B)) {
                val pair = allocPair() ?: throw IOException("no free RTP ports")
                opened.add(pair.first); opened.add(pair.second)
                stream.legs[side * 2] = Leg(pair.first, pair.first.localAddress.let { (it as InetSocketAddress).port }, stream, side, false)
                stream.legs[side * 2 + 1] = Leg(pair.second, pair.second.localAddress.let { (it as InetSocketAddress).port }, stream, side, true)
            }
        } catch (e: Exception) {
            opened.forEach { try { it.close() } catch (_: Exception) {} }
            log("RTP allocation failed: ${e.message}")
            return null
        }
        session.streams.add(stream)
        stream.legs.forEach { leg -> sessionOf[leg!!] = session; pending.add(leg) }
        selector?.wakeup()
        log("Relay ${stream.type}#$index: A=${stream.ports(SIDE_A)} B=${stream.ports(SIDE_B)}")
        return stream
    }

    private fun allocPair(): Pair<DatagramChannel, DatagramChannel>? {
        val span = (rtpEnd - rtpStart) / 2 + 1
        repeat(span) {
            var p = nextPort
            if (p + 1 > rtpEnd) p = if (rtpStart % 2 == 0) rtpStart else rtpStart + 1
            nextPort = p + 2
            var a: DatagramChannel? = null
            var b: DatagramChannel? = null
            try {
                a = open(p); b = open(p + 1)
                return Pair(a, b)
            } catch (_: Exception) {
                try { a?.close() } catch (_: Exception) {}
                try { b?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun open(port: Int): DatagramChannel {
        val ch = DatagramChannel.open()
        try {
            ch.configureBlocking(false)
            try { ch.setOption(StandardSocketOptions.SO_RCVBUF, 512 * 1024) } catch (_: Exception) {}
            try { ch.setOption(StandardSocketOptions.SO_SNDBUF, 512 * 1024) } catch (_: Exception) {}
            try { if (tos > 0) ch.setOption(StandardSocketOptions.IP_TOS, tos) } catch (_: Exception) {}
            ch.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            try { ch.close() } catch (_: Exception) {}
            throw e
        }
        return ch
    }

    /** Pre-seeds where [side] is expected to send from (from its SDP). Real packets override it. */
    fun hint(stream: Stream, side: Int, ip: String?, rtpPort: Int, rtcpPort: Int) {
        if (ip == null || ip == "0.0.0.0" || rtpPort <= 0) return
        try {
            val rtp = stream.leg(side, false)
            val rtcp = stream.leg(side, true)
            if (!rtp.latched) rtp.peer = InetSocketAddress(ip, rtpPort)
            if (!rtcp.latched) rtcp.peer = InetSocketAddress(ip, rtcpPort)
        } catch (_: Exception) {}
    }

    fun close(callId: String) {
        val s = sessions.remove(callId) ?: return
        s.closed = true
        s.streams.forEach { st ->
            st.legs.forEach { leg ->
                if (leg != null) {
                    sessionOf.remove(leg)
                    try { leg.channel.close() } catch (_: Exception) {}
                }
            }
        }
        log("Relay closed for $callId (${s.packets.get()} pkts, ${s.bytes.get() / 1024} KiB)")
    }

    private fun loop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocateDirect(8192)
        while (running) {
            try {
                while (true) {
                    val leg = pending.poll() ?: break
                    if (leg.channel.isOpen) leg.channel.register(sel, SelectionKey.OP_READ, leg)
                }
                sel.select(1000)
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next(); it.remove()
                    try {
                        if (key.isValid && key.isReadable) handle(key.attachment() as Leg, buf)
                    } catch (_: CancelledKeyException) {
                    } catch (_: IOException) {
                    }
                }
            } catch (e: Exception) {
                if (running) log("relay loop error: ${e.message}")
            }
        }
    }

    private fun handle(leg: Leg, buf: ByteBuffer) {
        // drain everything that is queued on this socket
        while (true) {
            buf.clear()
            val src = leg.channel.receive(buf) as? InetSocketAddress ?: return
            buf.flip()
            val session = sessionOf[leg] ?: return
            val now = System.currentTimeMillis()

            if (!leg.latched) {
                leg.peer = src; leg.latched = true
                log("Learned ${leg.stream.type} ${if (leg.side == SIDE_A) "A" else "B"}${if (leg.rtcp) "/rtcp" else ""}: $src")
            } else if (leg.peer != src) {
                if (now - leg.lastRx > 3000) {
                    leg.peer = src
                    log("Re-latched ${leg.stream.type} ${if (leg.side == SIDE_A) "A" else "B"}: $src")
                } else continue // ignore stray packets while the stream is alive
            }
            leg.lastRx = now
            session.lastActivity = now
            session.packets.incrementAndGet()
            session.bytes.addAndGet(buf.remaining().toLong())
            totalBytes.addAndGet(buf.remaining().toLong())

            val other = leg.stream.legs[(1 - leg.side) * 2 + if (leg.rtcp) 1 else 0] ?: continue
            val dst = other.peer ?: continue
            try { other.channel.send(buf, dst) } catch (_: IOException) {}
        }
    }
}
