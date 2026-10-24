package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_queues")
data class CallQueue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val queueNumber: String,
    val name: String,
    val strategy: String = "ringall", // ringall, leastrecent, fewestcalls, random
    val timeout: Int = 300,
    val retry: Int = 5,
    val maxLen: Int = 0,
    val announcePosition: Boolean = true,
    val announceHoldTime: Boolean = true,
    val musicOnHold: String = "default",
    val members: String = "", // Comma-separated extension numbers
    val enabled: Boolean = true
)