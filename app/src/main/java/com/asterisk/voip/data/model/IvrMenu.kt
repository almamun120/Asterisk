package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ivr_menus")
data class IvrMenu(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val welcomeMessage: String,
    val timeout: Int = 10,
    val maxRetries: Int = 3,
    val invalidMessage: String = "Invalid option",
    val timeoutMessage: String = "No input received",
    val enabled: Boolean = true
)

@Entity(tableName = "ivr_options")
data class IvrOption(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val menuId: Long,
    val digit: String, // 0-9, *, #
    val action: String, // extension, submenu, voicemail, hangup, queue
    val actionData: String,
    val enabled: Boolean = true
)