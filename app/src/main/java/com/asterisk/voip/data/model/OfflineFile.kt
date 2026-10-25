package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A file sent over SIP/MSRP to an extension that was offline; delivered when it registers. */
@Entity(tableName = "offline_files")
data class OfflineFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fromExt: String,
    val toExt: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val path: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val delivered: Boolean = false
)
