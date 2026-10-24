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