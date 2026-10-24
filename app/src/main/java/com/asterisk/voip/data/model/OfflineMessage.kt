package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_messages")
data class OfflineMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fromExt: String,
    val toExt: String,
    val contentType: String = "text/plain",
    val messageBody: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000), // 7 days
    val retryCount: Int = 0,
    val delivered: Boolean = false
)