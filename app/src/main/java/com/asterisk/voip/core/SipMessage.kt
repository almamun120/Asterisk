package com.asterisk.voip.core

import java.nio.charset.StandardCharsets

/**
 * Lossless SIP message model: keeps repeated headers (several Via / Route /
 * Record-Route) in order, normalises compact header names, and recomputes
 * Content-Length in bytes on serialisation.
 *
 * The wire text is handled as ISO-8859-1 so arbitrary body bytes (for example
 * UTF-8 MESSAGE text such as Bangla) survive a parse/serialise round trip.
 */
class SipMessage(
    var startLine: String,
    val headers: MutableList<Pair<String, String>> = mutableListOf(),
    var body: String = ""
) {
    val isResponse get() = startLine.startsWith("SIP/2.0")
    val statusCode get() = if (isResponse) startLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0 else 0
    val requestMethod get() = if (isResponse) "" else startLine.substringBefore(' ').uppercase()
    val requestUri get() = if (isResponse) "" else startLine.split(" ").getOrNull(1) ?: ""

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    fun headerList(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
    fun removeFirst(name: String) {
        val i = headers.indexOfFirst { it.first.equals(name, true) }
        if (i >= 0) headers.removeAt(i)
    }
    fun removeAll(name: String) { headers.removeAll { it.first.equals(name, true) } }
    fun addFirst(name: String, value: String) { headers.add(0, name to value) }
    fun add(name: String, value: String) { headers.add(name to value) }
    fun set(name: String, value: String) {
        val i = headers.indexOfFirst { it.first.equals(name, true) }
        if (i >= 0) headers[i] = name to value else headers.add(name to value)
    }

    val callId get() = header("Call-ID") ?: ""
    val cseqNum get() = header("CSeq")?.trim()?.split(Regex("\\s+"))?.getOrNull(0)?.toIntOrNull() ?: 0
    val cseqMethod get() = header("CSeq")?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.uppercase() ?: ""
    val method get() = if (isResponse) cseqMethod else requestMethod
    val fromTag get() = tagOf(header("From"))
    val toTag get() = tagOf(header("To"))
    val topViaBranch get() = paramOf(header("Via"), "branch")

    fun clone() = SipMessage(startLine, headers.toMutableList(), body)

    fun toBytes(): ByteArray {
        val bodyBytes = body.toByteArray(StandardCharsets.ISO_8859_1)
        val sb = StringBuilder()
        sb.append(startLine).append("\r\n")
        for ((k, v) in headers) {
            if (k.equals("Content-Length", true)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        return sb.toString().toByteArray(StandardCharsets.ISO_8859_1) + bodyBytes
    }

    override fun toString() = String(toBytes(), StandardCharsets.ISO_8859_1)

    companion object {
        private val COMPACT = mapOf(
            "v" to "Via", "f" to "From", "t" to "To", "i" to "Call-ID", "m" to "Contact",
            "l" to "Content-Length", "c" to "Content-Type", "k" to "Supported",
            "e" to "Content-Encoding", "s" to "Subject", "o" to "Event", "r" to "Refer-To"
        )

        fun tagOf(h: String?) = paramOf(h, "tag")

        fun paramOf(h: String?, name: String): String? {
            if (h == null) return null
            val m = Regex(";\\s*$name=([^;>\\s,]+)", RegexOption.IGNORE_CASE).find(h) ?: return null
            return m.groupValues[1].trim('"')
        }

        /** Extracts the user part of a SIP URI inside a header value (From/To/Contact/request-URI). */
        fun userOf(h: String?): String? {
            if (h == null) return null
            val m = Regex("sips?:([^@;>:\\s\"]+)@", RegexOption.IGNORE_CASE).find(h) ?: return null
            return m.groupValues[1]
        }

        fun parse(raw: String): SipMessage? {
            val headEnd = raw.indexOf("\r\n\r\n").let { if (it >= 0) it else raw.indexOf("\n\n") }
            val head = if (headEnd >= 0) raw.substring(0, headEnd) else raw
            val sepLen = if (raw.startsWith("\r\n\r\n", maxOf(headEnd, 0))) 4 else 2
            var body = if (headEnd >= 0 && headEnd + sepLen <= raw.length) raw.substring(headEnd + sepLen) else ""

            val lines = head.split("\r\n", "\n")
            val start = lines.firstOrNull()?.trim().orEmpty()
            if (start.isEmpty() || !(start.startsWith("SIP/2.0") || start.endsWith("SIP/2.0"))) return null

            val headers = mutableListOf<Pair<String, String>>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                if ((line[0] == ' ' || line[0] == '\t') && headers.isNotEmpty()) {
                    val last = headers.removeAt(headers.size - 1)
                    headers.add(last.first to (last.second + " " + line.trim()))
                    continue
                }
                val i = line.indexOf(':')
                if (i <= 0) continue
                var k = line.substring(0, i).trim()
                COMPACT[k.lowercase()]?.let { k = it }
                val v = line.substring(i + 1).trim()
                if (k.equals("Via", true) && v.contains(',')) {
                    v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { headers.add(k to it) }
                } else headers.add(k to v)
            }

            // honour Content-Length when the datagram carries trailing garbage
            headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull()?.let { cl ->
                if (cl in 0 until body.length) body = body.substring(0, cl)
            }
            return SipMessage(start, headers, body)
        }

        /** Decodes the UTF-8 text carried in a (latin-1 mapped) body. */
        fun bodyToText(body: String) = String(body.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        fun textToBody(text: String) = String(text.toByteArray(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)
    }
}
