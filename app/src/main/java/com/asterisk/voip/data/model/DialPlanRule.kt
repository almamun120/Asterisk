// FILE: ./app/src/main/java/com/asterisk/voip/data/model/DialPlanRule.kt
package com.asterisk.voip.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "dialplan_rules")
data class DialPlanRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val pattern: String, // Extension pattern (e.g., "1XXX", "2000", "*")
    val condition: DialPlanCondition,
    val action: DialPlanAction,
    val actionData: String = "", // Voice prompt ID, extension number, etc.
    val priority: Int = 0,
    val timeout: Int = 30, // Timeout in seconds
    val maxRetries: Int = 3,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

enum class DialPlanCondition {
    OFFLINE,                // Extension is offline/unregistered
    BUSY,                   // Extension is busy (on another call)
    NO_ANSWER,              // Extension doesn't answer
    REJECTED,               // Call rejected by user
    NETWORK_ERROR,          // Network/connection problem
    INVALID_NUMBER,         // Invalid extension number
    FORBIDDEN,              // Extension disabled or forbidden
    TIMEOUT,                // Call timeout
    UNAVAILABLE,            // Temporarily unavailable
    DO_NOT_DISTURB,         // DND enabled
    CALL_FORWARDING,        // Call forwarding active
    AFTER_HOURS,            // Outside business hours
    ALWAYS                  // Always execute (unconditional)
}

enum class DialPlanAction {
    PLAY_ANNOUNCEMENT,      // Play voice prompt
    VOICEMAIL,              // Send to voicemail
    FORWARD_TO_EXTENSION,   // Forward to another extension
    FORWARD_TO_QUEUE,       // Forward to call queue
    FORWARD_TO_IVR,         // Forward to IVR menu
    HANGUP,                 // Hang up the call
    CALLBACK,               // Schedule callback
    RETRY,                  // Retry calling
    CUSTOM                  // Custom action
}