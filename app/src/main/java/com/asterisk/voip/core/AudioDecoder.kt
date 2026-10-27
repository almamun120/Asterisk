package com.asterisk.voip.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Decodes any Android-supported audio file to 8 kHz mono PCM16, the format the RTP engine plays. */
object AudioDecoder {

    fun decodeTo8k(file: File): ShortArray? {
        if (!file.exists()) return null
        if (file.name.lowercase().endsWith(".wav")) {
            G711.readWav(file)?.let { return G711.to8k(it) }
        }
        return decodeWithCodec(file)
    }

    private fun decodeWithCodec(file: File): ShortArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val out = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 60_000
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        ob.get(chunk)
                        out.write(chunk)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val pcm = G711.bytesToShorts(out.toByteArray())
            return G711.resampleTo8k(G711.toMono(pcm, channels), rate)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }
}

/** Call-progress tones for "System Tone" voice prompts. */
object Tones {
    private const val AMP = 9000.0

    private fun tone(freq: Double, ms: Int): ShortArray {
        val n = G711.RATE * ms / 1000
        return ShortArray(n) { i ->
            val fade = minOf(1.0, minOf(i, n - 1 - i) / 40.0) // 5 ms fade avoids clicks
            (sin(2 * PI * freq * i / G711.RATE) * AMP * fade).toInt().toShort()
        }
    }

    private fun cadence(freq: Double, onMs: Int, offMs: Int, repeats: Int): ShortArray {
        val parts = ArrayList<ShortArray>()
        repeat(repeats) { parts.add(tone(freq, onMs)); parts.add(G711.silence(offMs)) }
        return G711.concat(parts)
    }

    /** [name] is matched loosely: "busy", "congestion"/"reorder"/"error", "ring". */
    fun generate(name: String): ShortArray {
        val n = name.lowercase()
        return when {
            n.contains("congest") || n.contains("reorder") || n.contains("error") || n.contains("network") ->
                cadence(425.0, 250, 250, 12)
            n.contains("ring") -> cadence(425.0, 1000, 3000, 2)
            n.contains("beep") -> G711.concat(listOf(tone(1000.0, 400), G711.silence(150)))
            else -> cadence(425.0, 500, 500, 6) // busy
        }
    }

    /** Short rising two-note chime: somebody joined the conference. */
    fun joinTone(): ShortArray = G711.concat(listOf(tone(880.0, 110), G711.silence(30), tone(1320.0, 130)))

    /** Short falling two-note chime: somebody left the conference. */
    fun leaveTone(): ShortArray = G711.concat(listOf(tone(1320.0, 110), G711.silence(30), tone(880.0, 130)))

    /** Low single beep: you are muted. */
    fun mutedTone(): ShortArray = G711.concat(listOf(tone(440.0, 250), G711.silence(40), tone(440.0, 250)))

    /** High single beep: you are unmuted. */
    fun unmutedTone(): ShortArray = tone(1000.0, 200)

    fun beep(): ShortArray = G711.concat(listOf(G711.silence(150), tone(1000.0, 350), G711.silence(150)))
}
