package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted SIP registration binding. Survives server restarts so that
 * extensions stay "online" immediately after the server is started again.
 */
@Entity(tableName = "registrations")
data class Registration(
    @PrimaryKey val extension: String,
    val ip: String,
    val port: Int,
    val expiresAt: Long,
    val transport: String,
    val userAgent: String,
    val lastSeen: Long
)
