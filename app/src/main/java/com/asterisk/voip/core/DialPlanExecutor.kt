// FILE: ./app/src/main/java/com/asterisk/voip/core/DialPlanExecutor.kt
package com.asterisk.voip.core

import com.asterisk.voip.data.model.*
import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.*

class DialPlanExecutor(
    private val repository: AsteriskRepository,
    private val voicePromptManager: VoicePromptManager,
    private val logger: (LogLevel, String, String) -> Unit
) {

    /**
     * Execute dial plan based on condition
     */
    suspend fun execute(
        condition: DialPlanCondition,
        fromExtension: String,
        toExtension: String,
        callId: String
    ): DialPlanResult {
        return withContext(Dispatchers.IO) {
            try {
                // Get matching rules
                val rules = repository.getRulesByCondition(condition)
                    .filter { it.enabled }
                    .sortedBy { it.priority }

                if (rules.isEmpty()) {
                    log("DIALPLAN", "No rules found for condition: $condition")
                    return@withContext DialPlanResult(false, "No matching rules")
                }

                // Execute first matching rule
                for (rule in rules) {
                    if (matchesPattern(toExtension, rule.pattern)) {
                        log("DIALPLAN", "Executing rule: ${rule.name} for $condition")
                        return@withContext executeRule(rule, fromExtension, toExtension, callId)
                    }
                }

                DialPlanResult(false, "No matching pattern")
            } catch (e: Exception) {
                log("DIALPLAN", "Execution error: ${e.message}")
                DialPlanResult(false, "Error: ${e.message}")
            }
        }
    }

    private suspend fun executeRule(
        rule: DialPlanRule,
        fromExt: String,
        toExt: String,
        callId: String
    ): DialPlanResult {
        return when (rule.action) {
            DialPlanAction.PLAY_ANNOUNCEMENT -> {
                playAnnouncement(rule.actionData, callId)
            }
            DialPlanAction.VOICEMAIL -> {
                sendToVoicemail(toExt, callId)
            }
            DialPlanAction.FORWARD_TO_EXTENSION -> {
                forwardToExtension(rule.actionData, callId)
            }
            DialPlanAction.FORWARD_TO_QUEUE -> {
                forwardToQueue(rule.actionData, callId)
            }
            DialPlanAction.FORWARD_TO_IVR -> {
                forwardToIVR(rule.actionData, callId)
            }
            DialPlanAction.HANGUP -> {
                hangup(callId)
            }
            DialPlanAction.CALLBACK -> {
                scheduleCallback(fromExt, toExt, callId)
            }
            DialPlanAction.RETRY -> {
                retry(rule, fromExt, toExt, callId)
            }
            DialPlanAction.CUSTOM -> {
                customAction(rule.actionData, callId)
            }
        }
    }

    private suspend fun playAnnouncement(promptId: String, callId: String): DialPlanResult {
        return try {
            val promptIdLong = promptId.toLongOrNull() ?: return DialPlanResult(false, "Invalid prompt ID")
            val prompt = repository.getVoicePromptById(promptIdLong)

            if (prompt == null) {
                log("DIALPLAN", "✗ Voice prompt not found: $promptId")
                return DialPlanResult(false, "Prompt not found")
            }

            if (!prompt.enabled) {
                log("DIALPLAN", "✗ Voice prompt disabled: ${prompt.name}")
                return DialPlanResult(false, "Prompt disabled")
            }

            log("DIALPLAN", "🔊 Playing announcement: ${prompt.name}")
            val success = voicePromptManager.playPrompt(prompt, callId)

            DialPlanResult(success, if (success) "Announcement played" else "Playback failed")
        } catch (e: Exception) {
            log("DIALPLAN", "✗ Announcement error: ${e.message}")
            DialPlanResult(false, "Error: ${e.message}")
        }
    }

    private fun sendToVoicemail(extension: String, callId: String): DialPlanResult {
        log("DIALPLAN", "📧 Sending to voicemail: $extension")
        // TODO: Implement voicemail logic
        return DialPlanResult(true, "Sent to voicemail")
    }

    private fun forwardToExtension(targetExtension: String, callId: String): DialPlanResult {
        log("DIALPLAN", "➡️ Forwarding to extension: $targetExtension")
        // TODO: Implement call forwarding
        return DialPlanResult(true, "Forwarded to $targetExtension")
    }

    private fun forwardToQueue(queueId: String, callId: String): DialPlanResult {
        log("DIALPLAN", "📋 Forwarding to queue: $queueId")
        // TODO: Implement queue forwarding
        return DialPlanResult(true, "Forwarded to queue")
    }

    private fun forwardToIVR(ivrId: String, callId: String): DialPlanResult {
        log("DIALPLAN", "🎙️ Forwarding to IVR: $ivrId")
        // TODO: Implement IVR forwarding
        return DialPlanResult(true, "Forwarded to IVR")
    }

    private fun hangup(callId: String): DialPlanResult {
        log("DIALPLAN", "📴 Hanging up call: $callId")
        // TODO: Implement hangup
        return DialPlanResult(true, "Call terminated")
    }

    private fun scheduleCallback(fromExt: String, toExt: String, callId: String): DialPlanResult {
        log("DIALPLAN", "📞 Scheduling callback: $fromExt -> $toExt")
        // TODO: Implement callback scheduling
        return DialPlanResult(true, "Callback scheduled")
    }

    private suspend fun retry(rule: DialPlanRule, fromExt: String, toExt: String, callId: String): DialPlanResult {
        log("DIALPLAN", "🔄 Retrying call (max: ${rule.maxRetries})")
        // TODO: Implement retry logic
        return DialPlanResult(true, "Retry initiated")
    }

    private fun customAction(actionData: String, callId: String): DialPlanResult {
        log("DIALPLAN", "⚙️ Custom action: $actionData")
        // TODO: Implement custom actions
        return DialPlanResult(true, "Custom action executed")
    }

    private fun matchesPattern(extension: String, pattern: String): Boolean {
        return when {
            pattern == "*" -> true // Match all
            pattern == extension -> true // Exact match
            pattern.contains("X") -> {
                // X represents any digit
                val regex = pattern.replace("X", "\\d").toRegex()
                regex.matches(extension)
            }
            pattern.contains("[") -> {
                // Range pattern, e.g., [1-5]XXX
                val regex = pattern.replace("X", "\\d").toRegex()
                regex.matches(extension)
            }
            else -> false
        }
    }

    private fun log(tag: String, message: String) {
        logger(LogLevel.INFO, tag, message)
    }
}

data class DialPlanResult(
    val success: Boolean,
    val message: String,
    val nextAction: String? = null
)