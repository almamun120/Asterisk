package com.asterisk.voip.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "extensions")
data class Extension(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val extensionNumber: String,
    val name: String,
    val secret: String,
    val callerID: String,
    val context: String = "default",
    val transport: String = "udp",
    val codecs: String = "ulaw,alaw,gsm,g729",
    val canReinvite: Boolean = false,
    val nat: String = "force_rport,comedia",
    val qualify: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable