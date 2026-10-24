// FILE: ./app/src/main/java/com/asterisk/voip/utils/DatabaseInitializer.kt
package com.asterisk.voip.utils

import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseInitializer {

    suspend fun initializeDefaultDialPlan(repository: AsteriskRepository) {
        withContext(Dispatchers.IO) {
            try {
                // Check if already initialized
                val existingRules = repository.allDialPlanRules
                // If no rules exist, add defaults
                // Note: You'd need to collect the flow or check differently

                // Add default voice prompts
                DefaultDialPlanRules.getDefaultVoicePrompts().forEach { prompt ->
                    try {
                        repository.insertVoicePrompt(prompt)
                    } catch (e: Exception) {
                        // Already exists, ignore
                    }
                }

                // Add default rules
                DefaultDialPlanRules.getDefaultRules().forEach { rule ->
                    try {
                        repository.insertDialPlanRule(rule)
                    } catch (e: Exception) {
                        // Already exists, ignore
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("DBInit", "Error initializing defaults: ${e.message}")
            }
        }
    }
}