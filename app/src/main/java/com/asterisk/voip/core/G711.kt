package com.asterisk.voip.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * G.711 (PCMU / PCMA) codec, resampling and WAV helpers.
 * Everything the server sends to / records from a caller is 8 kHz mono 16-bit PCM internally.
 */
object G711 {
    const val RATE = 8000
    const val FRAME = 160 // 20 ms @ 8 kHz

    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val ULAW_DEC = ShortArray(256) { ulawToLinear(it.toByte()).toShort() }
    private val ALAW_DEC = ShortArray(256) { alawToLinear(it.toByte()).toShort() }

    fun linearToUlaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
    }

    fun ulawToLinear(b: Byte): Int {
        val u = (b.toInt() and 0xFF).inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        var s = ((mantissa shl 3) + BIAS) shl exponent
        s -= BIAS
        return if (sign != 0) -s else s
    }

    private val ALAW_SEG_END = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    fun linearToAlaw(sample: Int): Byte {
        var pcm = sample shr 3
        val mask: Int
        if (pcm >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            pcm = -pcm - 1
        }
        var seg = 0
        while (seg < 8 && pcm > ALAW_SEG_END[seg]) seg++
        if (seg >= 8) return (0x7F xor mask).toByte()
        var aval = seg shl 4
        aval = if (seg < 2) aval or ((pcm shr 1) and 0x0F) else aval or ((pcm shr seg) and 0x0F)
        return (aval xor mask).toByte()
    }

    fun alawToLinear(b: Byte): Int {
        val a = (b.toInt() and 0xFF) xor 0x55
        var t = (a and 0x0F) shl 4
        val seg = (a and 0x70) shr 4
        when (seg) {
            0 -> t += 8
            1 -> t += 0x108
            else -> { t += 0x108; t = t shl (seg - 1) }
        }
        return if ((a and 0x80) != 0) t else -t
    }

    fun silenceByte(alaw: Boolean): Byte = if (alaw) 0xD5.toByte() else 0xFF.toByte()

    /** Encodes PCM to G.711 and pads with silence to a whole number of 20 ms frames. */
    fun encode(pcm: ShortArray, alaw: Boolean): ByteArray {
        val frames = (pcm.size + FRAME - 1) / FRAME
        val out = ByteArray(frames * FRAME)
        val sil = silenceByte(alaw)
        for (i in out.indices) {
            out[i] = if (i < pcm.size) {
                if (alaw) linearToAlaw(pcm[i].toInt()) else linearToUlaw(pcm[i].toInt())
            } else sil
        }
        return out
    }

    fun decodeByte(b: Byte, alaw: Boolean): Short {
        val i = b.toInt() and 0xFF
        return if (alaw) ALAW_DEC[i] else ULAW_DEC[i]
    }

    // ------------------------------------------------------------ resampling

    /** Mixes interleaved PCM down to mono. */
    fun toMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val n = pcm.size / channels
        val out = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** Box-filter resampler to 8 kHz (good enough for telephony prompts). */
    fun resampleTo8k(pcm: ShortArray, srcRate: Int): ShortArray {
        if (srcRate == RATE || srcRate <= 0 || pcm.isEmpty()) return pcm
        val ratio = srcRate.toDouble() / RATE
        val outLen = (pcm.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val a = (i * ratio).toInt().coerceAtMost(pcm.size - 1)
            val b = ((i + 1) * ratio).toInt().coerceIn(a + 1, pcm.size)
            var sum = 0L
            for (j in a until b) sum += pcm[j]
            out[i] = (sum / (b - a)).toShort()
        }
        return out
    }

    // ------------------------------------------------------------ WAV

    class Pcm(val samples: ShortArray, val rate: Int)

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Reads a PCM16 / PCM8 / A-law / µ-law WAV file as mono samples at the file's own rate. */
    fun readWav(file: File): Pcm? {
        return try {
            val d = file.readBytes()
            if (d.size < 44 || String(d, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                String(d, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
            var pos = 12
            var fmtTag = 1; var channels = 1; var rate = 8000; var bits = 16
            var dataOff = -1; var dataLen = 0
            while (pos + 8 <= d.size) {
                val id = String(d, pos, 4, Charsets.ISO_8859_1)
                var len = le32(d, pos + 4)
                val body = pos + 8
                if (len < 0 || body + len > d.size) len = d.size - body
                if (id == "fmt " && len >= 16) {
                    fmtTag = le16(d, body); channels = le16(d, body + 2)
                    rate = le32(d, body + 4); bits = le16(d, body + 14)
                    if (fmtTag == 0xFFFE && len >= 26) fmtTag = le16(d, body + 24)
                } else if (id == "data") {
                    dataOff = body; dataLen = len
                    break
                }
                pos = body + len + (len and 1)
            }
            if (dataOff < 0 || channels < 1) return null
            val samples: ShortArray = when {
                fmtTag == 1 && bits == 16 -> {
                    val n = dataLen / 2
                    val s = ShortArray(n)
                    ByteBuffer.wrap(d, dataOff, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                    s
                }
                fmtTag == 1 && bits == 8 -> ShortArray(dataLen) { (((d[dataOff + it].toInt() and 0xFF) - 128) shl 8).toShort() }
                fmtTag == 7 -> ShortArray(dataLen) { ULAW_DEC[d[dataOff + it].toInt() and 0xFF] }
                fmtTag == 6 -> ShortArray(dataLen) { ALAW_DEC[d[dataOff + it].toInt() and 0xFF] }
                else -> return null
            }
            Pcm(toMono(samples, channels), rate)
        } catch (_: Exception) { null }
    }

    fun to8k(p: Pcm): ShortArray = resampleTo8k(p.samples, p.rate)

    /** Writes 8 kHz mono 16-bit PCM WAV. */
    fun writeWav(file: File, pcm: ShortArray) {
        file.parentFile?.mkdirs()
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.ISO_8859_1)); bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.ISO_8859_1))
        bb.put("fmt ".toByteArray(Charsets.ISO_8859_1)); bb.putInt(16)
        bb.putShort(1); bb.putShort(1); bb.putInt(RATE); bb.putInt(RATE * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.ISO_8859_1)); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        file.writeBytes(bb.array())
    }

    /** Little-endian PCM16 bytes -> samples. */
    fun bytesToShorts(b: ByteArray): ShortArray {
        val n = b.size / 2
        val s = ShortArray(n)
        ByteBuffer.wrap(b, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
        return s
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ShortArray(n)
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    fun silence(ms: Int): ShortArray = ShortArray(RATE * ms / 1000)

    /** Simple growable PCM buffer (used for recording). */
    class PcmBuffer {
        private val bos = ByteArrayOutputStream()
        @Synchronized fun add(s: Short) { bos.write(s.toInt() and 0xFF); bos.write((s.toInt() shr 8) and 0xFF) }
        @Synchronized fun sizeSamples() = bos.size() / 2
        @Synchronized fun toShorts(): ShortArray = bytesToShorts(bos.toByteArray())
    }
}
