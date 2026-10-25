// FILE: ./app/src/main/java/com/asterisk/voip/utils/DefaultDialPlanRules.kt
package com.asterisk.voip.utils

import com.asterisk.voip.data.model.*

object DefaultDialPlanRules {

    fun getDefaultRules(): List<DialPlanRule> {
        return listOf(
            // Rule 1: Extension Offline
            DialPlanRule(
                name = "Extension Offline - Play Announcement",
                description = "Play 'user is offline' message when extension is not registered",
                pattern = "*",
                condition = DialPlanCondition.OFFLINE,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "1", // Prompt ID - needs to be configured
                priority = 10,
                timeout = 30,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 2: Extension Busy
            DialPlanRule(
                name = "Extension Busy - Play Busy Tone",
                description = "Play busy message when extension is on another call",
                pattern = "*",
                condition = DialPlanCondition.BUSY,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "2", // Prompt ID
                priority = 10,
                timeout = 15,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 3: Call Rejected
            DialPlanRule(
                name = "Call Rejected - Play Announcement",
                description = "Caller hears a message when the person declines the call",
                pattern = "*",
                condition = DialPlanCondition.REJECTED,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "6", // Prompt ID
                priority = 10,
                timeout = 30,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 4: No Answer
            DialPlanRule(
                name = "No Answer - Play Announcement",
                description = "Caller hears a message when nobody picks up (Timeout = ring seconds)",
                pattern = "*",
                condition = DialPlanCondition.NO_ANSWER,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "7", // Prompt ID
                priority = 10,
                timeout = 30,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 5: Network Error
            DialPlanRule(
                name = "Network Error - Play Error Message",
                description = "Play error message on network failure",
                pattern = "*",
                condition = DialPlanCondition.NETWORK_ERROR,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "3", // Prompt ID
                priority = 10,
                timeout = 15,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 6: Invalid Number
            DialPlanRule(
                name = "Invalid Number - Play Error",
                description = "Play invalid number message",
                pattern = "*",
                condition = DialPlanCondition.INVALID_NUMBER,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "4", // Prompt ID
                priority = 10,
                timeout = 10,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 7: Timeout
            DialPlanRule(
                name = "Timeout - Play Announcement",
                description = "Caller hears a message on timeout",
                pattern = "*",
                condition = DialPlanCondition.TIMEOUT,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "7", // Prompt ID
                priority = 10,
                timeout = 30,
                maxRetries = 0,
                enabled = true
            ),

            // Rule 8: Do Not Disturb
            DialPlanRule(
                name = "DND - Play Announcement",
                description = "Play do not disturb message",
                pattern = "*",
                condition = DialPlanCondition.DO_NOT_DISTURB,
                action = DialPlanAction.PLAY_ANNOUNCEMENT,
                actionData = "5", // Prompt ID
                priority = 10,
                timeout = 15,
                maxRetries = 0,
                enabled = true
            )
        )
    }

    fun getDefaultVoicePrompts(): List<VoicePrompt> {
        return listOf(
            VoicePrompt(
                id = 1,
                name = "User Offline",
                description = "The user you are trying to reach is currently offline",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The user you are trying to reach is currently offline. Please try again later.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 2,
                name = "User Busy",
                description = "The user is currently on another call",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The user you are trying to reach is currently on another call. Please try again later.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 3,
                name = "Network Error",
                description = "Network connection problem",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "We are experiencing network connectivity issues. Please try your call again later.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 4,
                name = "Invalid Number",
                description = "The dialed number is invalid",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The number you have dialed is invalid. Please check the number and try again.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 6,
                name = "Call Declined",
                description = "The person declined the call",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The person you are calling is unable to take your call right now. Please try again later.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 7,
                name = "No Answer",
                description = "Nobody answered",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The person you are calling is not answering. Please try again later.",
                language = "en-US",
                enabled = true
            ),
            VoicePrompt(
                id = 5,
                name = "Do Not Disturb",
                description = "User has enabled do not disturb",
                filePath = "",
                fileType = VoicePromptType.TTS,
                ttsText = "The user you are trying to reach has enabled do not disturb mode. Please try again later.",
                language = "en-US",
                enabled = true
            )
        )
    }
}