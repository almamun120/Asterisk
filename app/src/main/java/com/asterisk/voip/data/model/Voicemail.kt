package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A voicemail left by a caller; the audio is an 8 kHz mono WAV at [filePath]. */
@Entity(tableName = "voicemails")
data class Voicemail(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mailbox: String,
    val fromExt: String,
    val filePath: String,
    val durationSec: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val heard: Boolean = false
)
