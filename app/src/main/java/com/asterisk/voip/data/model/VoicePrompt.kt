// FILE: ./app/src/main/java/com/asterisk/voip/data/model/VoicePrompt.kt
package com.asterisk.voip.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "voice_prompts")
data class VoicePrompt(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val filePath: String, // Local file path or TTS text
    val fileType: VoicePromptType = VoicePromptType.AUDIO_FILE,
    val duration: Long = 0, // Duration in milliseconds
    val language: String = "en-US",
    val ttsText: String = "", // Text for TTS
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

enum class VoicePromptType {
    AUDIO_FILE,     // Pre-recorded audio file
    TTS,            // Text-to-Speech
    SYSTEM_TONE     // System tone (busy, congestion, etc.)
}
