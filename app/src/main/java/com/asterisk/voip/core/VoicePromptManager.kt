// FILE: ./app/src/main/java/com/asterisk/voip/core/VoicePromptManager.kt
package com.asterisk.voip.core

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.asterisk.voip.data.model.LogLevel
import com.asterisk.voip.data.model.VoicePrompt
import com.asterisk.voip.data.model.VoicePromptType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class VoicePromptManager(
    private val context: Context,
    private val logger: (LogLevel, String, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mediaPlayers = ConcurrentHashMap<String, MediaPlayer>()
    private var ttsEngine: TextToSpeech? = null
    private var ttsInitialized = false

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = ttsEngine?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    log("TTS", "Language not supported")
                } else {
                    ttsInitialized = true
                    log("TTS", "✓ Text-to-Speech initialized")
                }
            } else {
                log("TTS", "✗ TTS initialization failed")
            }
        }
    }

    /**
     * Play a voice prompt
     */
    suspend fun playPrompt(prompt: VoicePrompt, sessionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                when (prompt.fileType) {
                    VoicePromptType.AUDIO_FILE -> playAudioFile(prompt, sessionId)
                    VoicePromptType.TTS -> playTTS(prompt, sessionId)
                    VoicePromptType.SYSTEM_TONE -> playSystemTone(prompt, sessionId)
                }
            } catch (e: Exception) {
                log("PROMPT", "✗ Failed to play prompt: ${e.message}")
                false
            }
        }
    }

    private suspend fun playAudioFile(prompt: VoicePrompt, sessionId: String): Boolean {
        return try {
            val file = File(prompt.filePath)
            if (!file.exists()) {
                log("PROMPT", "✗ Audio file not found: ${prompt.filePath}")
                return false
            }

            val mediaPlayer = MediaPlayer().apply {
                setDataSource(prompt.filePath)
                prepare()
            }

            mediaPlayers[sessionId] = mediaPlayer

            val playbackComplete = CompletableDeferred<Boolean>()

            mediaPlayer.setOnCompletionListener {
                mediaPlayers.remove(sessionId)
                mediaPlayer.release()
                playbackComplete.complete(true)
            }

            mediaPlayer.setOnErrorListener { _, what, extra ->
                log("PROMPT", "✗ MediaPlayer error: what=$what, extra=$extra")
                mediaPlayers.remove(sessionId)
                mediaPlayer.release()
                playbackComplete.complete(false)
                true
            }

            mediaPlayer.start()
            log("PROMPT", "▶ Playing: ${prompt.name}")

            playbackComplete.await()
        } catch (e: Exception) {
            log("PROMPT", "✗ Audio playback error: ${e.message}")
            false
        }
    }

    private suspend fun playTTS(prompt: VoicePrompt, sessionId: String): Boolean {
        return if (ttsInitialized) {
            val result = CompletableDeferred<Boolean>()

            val utteranceId = sessionId
            ttsEngine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    log("TTS", "▶ Speaking: ${prompt.name}")
                }

                override fun onDone(utteranceId: String?) {
                    result.complete(true)
                }

                override fun onError(utteranceId: String?) {
                    log("TTS", "✗ TTS error")
                    result.complete(false)
                }
            })

            val params = Bundle()
            ttsEngine?.speak(prompt.ttsText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            result.await()
        } else {
            log("TTS", "✗ TTS not initialized")
            false
        }
    }

    private fun playSystemTone(prompt: VoicePrompt, sessionId: String): Boolean {
        // Generate system tones (busy, congestion, ring, etc.)
        log("PROMPT", "🔊 Playing system tone: ${prompt.name}")
        // TODO: Implement tone generation
        return true
    }

    /**
     * Stop playing a prompt
     */
    fun stopPrompt(sessionId: String) {
        mediaPlayers[sessionId]?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayers.remove(sessionId)
                log("PROMPT", "⏹ Stopped playback: $sessionId")
            } catch (e: Exception) {
                log("PROMPT", "Error stopping prompt: ${e.message}")
            }
        }

        ttsEngine?.stop()
    }

    /**
     * Start recording a voice prompt
     */
    fun startRecording(outputPath: String): Boolean {
        if (isRecording) {
            log("REC", "✗ Already recording")
            return false
        }

        return try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputPath)
                prepare()
                start()
            }

            isRecording = true
            log("REC", "🎙 Recording started: $outputPath")
            true
        } catch (e: Exception) {
            log("REC", "✗ Recording failed: ${e.message}")
            false
        }
    }

    /**
     * Stop recording
     */
    fun stopRecording(): String? {
        if (!isRecording) {
            return null
        }

        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            log("REC", "⏹ Recording stopped")
            // Return the file path
            null // Replace with actual path
        } catch (e: Exception) {
            log("REC", "✗ Stop recording error: ${e.message}")
            null
        }
    }

    /**
     * Get audio file duration
     */
    fun getAudioDuration(filePath: String): Long {
        return try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
            }
            val duration = mediaPlayer.duration.toLong()
            mediaPlayer.release()
            duration
        } catch (e: Exception) {
            log("PROMPT", "Error getting duration: ${e.message}")
            0L
        }
    }


    // ------------------------------------------------------------ rendering for the RTP engine

    private val renderCache = ConcurrentHashMap<String, ShortArray>()
    private val ttsMutex = Mutex()

    /** Decodes / synthesises a prompt to 8 kHz mono PCM so it can be streamed to a caller over RTP. */
    suspend fun render(prompt: VoicePrompt): ShortArray? = withContext(Dispatchers.IO) {
        try {
            when (prompt.fileType) {
                VoicePromptType.AUDIO_FILE -> {
                    val f = File(prompt.filePath)
                    if (!f.exists()) {
                        log("PROMPT", "✗ Audio file not found: ${prompt.filePath}")
                        return@withContext null
                    }
                    val key = "f:${f.absolutePath}:${f.lastModified()}:${f.length()}"
                    renderCache[key] ?: AudioDecoder.decodeTo8k(f)?.also { renderCache[key] = it }
                }
                VoicePromptType.TTS -> renderTts(prompt.ttsText, prompt.language)
                VoicePromptType.SYSTEM_TONE -> Tones.generate(prompt.name + " " + prompt.description)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("PROMPT", "✗ Render failed (${prompt.name}): ${e.message}")
            null
        }
    }

    /** Text-to-speech into 8 kHz mono PCM (cached). */
    suspend fun renderTts(text: String, language: String = "en-US"): ShortArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val key = "t:$language:${text.hashCode()}"
        renderCache[key]?.let { return@withContext it }
        try {
            // the engine initialises asynchronously
            var waited = 0
            while (!ttsInitialized && waited < 4000) { delay(100); waited += 100 }
            val engine = ttsEngine
            if (engine == null || !ttsInitialized) {
                log("TTS", "✗ TTS engine not ready")
                return@withContext null
            }
            ttsMutex.withLock {
                renderCache[key]?.let { return@withContext it }
                val loc = Locale.forLanguageTag(language.ifBlank { "en-US" })
                val lr = engine.setLanguage(loc)
                if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
                    log("TTS", "⚠ Language $language not available on this phone, using the default voice")
                }
                val out = File(context.cacheDir, "tts_${key.hashCode()}_${System.nanoTime()}.wav")
                val uid = "synth-${System.nanoTime()}"
                val done = CompletableDeferred<Boolean>()
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == uid) done.complete(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == uid) done.complete(false) }
                })
                val rc = engine.synthesizeToFile(text, Bundle(), out, uid)
                if (rc != TextToSpeech.SUCCESS) return@withContext null
                val ok = withTimeoutOrNull(25_000L) { done.await() } ?: false
                if (!ok || !out.exists()) return@withContext null
                val pcm = G711.readWav(out)?.let { G711.to8k(it) }
                out.delete()
                if (pcm != null && pcm.isNotEmpty()) renderCache[key] = pcm
                pcm
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("TTS", "✗ Synthesis failed: ${e.message}")
            null
        }
    }

    fun shutdown() {
        mediaPlayers.values.forEach { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        mediaPlayers.clear()

        if (isRecording) {
            stopRecording()
        }

        ttsEngine?.shutdown()
        scope.cancel()
    }

    private fun log(tag: String, message: String) {
        logger(LogLevel.INFO, tag, message)
    }
}