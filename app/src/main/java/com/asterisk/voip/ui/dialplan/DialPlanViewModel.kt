// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/DialPlanViewModel.kt
package com.asterisk.voip.ui.dialplan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.DialPlanRule
import com.asterisk.voip.data.model.VoicePrompt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DialPlanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val repository = app.repository
    private val asteriskManager = app.asteriskManager

    val dialPlanRules: StateFlow<List<DialPlanRule>> = repository.allDialPlanRules
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val voicePrompts: StateFlow<List<VoicePrompt>> = repository.allVoicePrompts
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // DialPlan Rules
    fun addRule(rule: DialPlanRule) {
        viewModelScope.launch {
            repository.insertDialPlanRule(rule)
            asteriskManager.reloadConfiguration()
        }
    }

    fun updateRule(rule: DialPlanRule) {
        viewModelScope.launch {
            repository.updateDialPlanRule(rule)
            asteriskManager.reloadConfiguration()
        }
    }

    fun deleteRule(rule: DialPlanRule) {
        viewModelScope.launch {
            repository.deleteDialPlanRule(rule)
            asteriskManager.reloadConfiguration()
        }
    }

    fun toggleRule(rule: DialPlanRule, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDialPlanRule(rule.copy(enabled = enabled))
            asteriskManager.reloadConfiguration()
        }
    }

    // Voice Prompts
    fun addPrompt(prompt: VoicePrompt) {
        viewModelScope.launch {
            repository.insertVoicePrompt(prompt)
        }
    }

    fun updatePrompt(prompt: VoicePrompt) {
        viewModelScope.launch {
            repository.updateVoicePrompt(prompt)
        }
    }

    fun deletePrompt(prompt: VoicePrompt) {
        viewModelScope.launch {
            repository.deleteVoicePrompt(prompt)
        }
    }

    fun togglePrompt(prompt: VoicePrompt, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateVoicePrompt(prompt.copy(enabled = enabled))
        }
    }

    fun playPrompt(prompt: VoicePrompt) {
        viewModelScope.launch {
            // TODO: Implement preview playback
            asteriskManager.log(com.asterisk.voip.data.model.LogLevel.INFO, "PROMPT", "Playing preview: ${prompt.name}")
        }
    }
}