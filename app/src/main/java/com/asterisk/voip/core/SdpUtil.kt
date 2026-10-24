package com.asterisk.voip.core

/**
 * Minimal, multi-media aware SDP helper used by the SIP server.
 *
 * It understands every "m=" section (audio, video, ...), not just the first
 * audio one, which is what makes video calls (and audio+video calls) work
 * through the media relay.
 */
object SdpUtil {

    data class Media(
        val index: Int,
        val type: String,
        val port: Int,
        val proto: String,
        val connIp: String?,
        val rtcpPort: Int?
    ) {
        val rejected get() = port == 0
        val relayable get() = !rejected && isRelayableProto(proto)
        val effectiveRtcpPort get() = rtcpPort ?: (port + 1)
    }

    private val ICE_PREFIXES = listOf(
        "a=candidate", "a=remote-candidates", "a=end-of-candidates",
        "a=ice-ufrag", "a=ice-pwd", "a=ice-options", "a=ice-lite", "a=ice-mismatch"
    )

    fun isRelayableProto(proto: String): Boolean {
        val p = proto.uppercase()
        return p.contains("RTP") || p == "UDP" || p == "UDPTL" || p.startsWith("UDP/")
    }

    private fun splitLines(sdp: String): List<String> =
        sdp.split("\r\n", "\n").filter { it.isNotEmpty() }

    fun parseMedia(sdp: String): List<Media> {
        if (sdp.isBlank()) return emptyList()
        val result = mutableListOf<Media>()
        var sessionIp: String? = null
        var cur: MutableMap<String, Any?>? = null

        fun flush() {
            cur?.let {
                result.add(
                    Media(
                        index = result.size,
                        type = it["type"] as String,
                        port = it["port"] as Int,
                        proto = it["proto"] as String,
                        connIp = (it["ip"] as String?) ?: sessionIp,
                        rtcpPort = it["rtcp"] as Int?
                    )
                )
            }
            cur = null
        }

        for (line in splitLines(sdp)) {
            when {
                line.startsWith("m=") -> {
                    flush()
                    val parts = line.substring(2).split(" ")
                    if (parts.size >= 3) {
                        cur = mutableMapOf(
                            "type" to parts[0],
                            "port" to (parts[1].substringBefore('/').toIntOrNull() ?: 0),
                            "proto" to parts[2],
                            "ip" to null,
                            "rtcp" to null
                        )
                    }
                }
                line.startsWith("c=") -> {
                    val ip = line.substring(2).split(" ").getOrNull(2)?.substringBefore('/')
                    if (cur == null) sessionIp = ip else cur!!["ip"] = ip
                }
                line.startsWith("a=rtcp:") && cur != null -> {
                    cur!!["rtcp"] = line.substring(7).trim().split(" ")[0].toIntOrNull()
                }
            }
        }
        flush()
        return result
    }

    /**
     * Rewrites [sdp] so every relayable media section points at [ip] and the
     * ports returned by [portsFor] (rtp, rtcp) for that media index.
     * Returning null from [portsFor] rejects that media (port 0).
     * ICE candidates are stripped so clients cannot bypass the relay.
     */
    fun rewriteForRelay(
        sdp: String,
        ip: String,
        videoEnabled: Boolean,
        portsFor: (Media) -> Pair<Int, Int>?
    ): String {
        if (sdp.isBlank()) return sdp
        val media = parseMedia(sdp)
        val out = StringBuilder()
        var idx = -1
        var skipping = false

        for (line in splitLines(sdp)) {
            if (line.startsWith("m=")) {
                idx++
                skipping = false
                val m = media.getOrNull(idx)
                val parts = line.substring(2).split(" ")
                if (m == null || parts.size < 3) {
                    out.append(line).append("\r\n"); continue
                }
                val tail = parts.drop(3).joinToString(" ")
                val disabledVideo = m.type.equals("video", true) && !videoEnabled
                val ports = if (m.relayable && !disabledVideo) portsFor(m) else null
                if (m.rejected) {
                    out.append(line).append("\r\n")
                } else if (ports == null) {
                    out.append("m=${m.type} 0 ${m.proto} $tail".trimEnd()).append("\r\n")
                    skipping = true
                } else {
                    out.append("m=${m.type} ${ports.first} ${m.proto} $tail".trimEnd()).append("\r\n")
                }
                continue
            }
            if (skipping) continue
            when {
                line.startsWith("c=") -> out.append("c=IN IP4 $ip\r\n")
                line.startsWith("a=rtcp:") -> {
                    val m = media.getOrNull(idx)
                    val p = m?.let { portsFor(it) }
                    if (p != null) out.append("a=rtcp:${p.second} IN IP4 $ip\r\n")
                }
                ICE_PREFIXES.any { line.startsWith(it) } -> {}
                else -> out.append(line).append("\r\n")
            }
        }
        return out.toString()
    }

    /** Removes a=candidate etc. is NOT done here: direct mode keeps the SDP untouched. */
    fun hasVideo(sdp: String) = parseMedia(sdp).any { it.type.equals("video", true) && !it.rejected }
}
