package com.asterisk.voip.utils

import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object DatabaseInitializer {

    suspend fun initializeDefaultDialPlan(repository: AsteriskRepository) {
        withContext(Dispatchers.IO) {
            try {
                // Voice prompts have fixed ids, so inserting them again just replaces the same rows
                // (existing prompts the user edited are kept).
                val prompts = repository.allVoicePrompts.first()
                DefaultDialPlanRules.getDefaultVoicePrompts().forEach { prompt ->
                    if (prompts.none { it.id == prompt.id }) {
                        try { repository.insertVoicePrompt(prompt) } catch (_: Exception) {}
                    }
                }

                // Default rules only on a fresh install (they used to be inserted on every launch,
                // which would now make an announcement play several times).
                val rules = repository.allDialPlanRules.first()
                if (rules.isEmpty()) {
                    DefaultDialPlanRules.getDefaultRules().forEach { rule ->
                        try { repository.insertDialPlanRule(rule) } catch (_: Exception) {}
                    }
                } else {
                    // clean up duplicates created by older versions
                    val seen = HashSet<String>()
                    for (r in rules) {
                        val key = "${r.name}|${r.pattern}|${r.condition}|${r.action}|${r.actionData}"
                        if (!seen.add(key)) {
                            try { repository.deleteDialPlanRule(r) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DBInit", "Error initializing defaults: ${e.message}")
            }
        }
    }
}
