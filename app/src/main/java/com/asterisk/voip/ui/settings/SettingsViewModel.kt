package com.asterisk.voip.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.ServerConfig
import com.asterisk.voip.data.model.StunTurnConfig
import com.asterisk.voip.utils.PreferencesManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val asteriskManager = app.asteriskManager
    private val preferencesManager = PreferencesManager(application)

    val startOnBoot: StateFlow<Boolean> = preferencesManager.startOnBoot
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val serverConfig: StateFlow<ServerConfig> = preferencesManager.serverConfig
        .stateIn(viewModelScope, SharingStarted.Lazily, ServerConfig())

    val stunTurnConfig: StateFlow<StunTurnConfig> = preferencesManager.stunTurnConfig
        .stateIn(viewModelScope, SharingStarted.Lazily, StunTurnConfig())

    val themeMode: StateFlow<String> = preferencesManager.themeMode
        .stateIn(viewModelScope, SharingStarted.Lazily, "system")

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.setThemeMode(mode)
            com.asterisk.voip.AsteriskApplication.applyTheme(mode)
        }
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setStartOnBoot(enabled) }
    }

    fun saveServerConfig(config: ServerConfig) {
        viewModelScope.launch {
            preferencesManager.setServerConfig(config)
            val stunTurn = stunTurnConfig.value
            asteriskManager.configure(config, stunTurn)
            asteriskManager.reloadConfiguration()
        }
    }

    fun saveStunTurnConfig(config: StunTurnConfig) {
        viewModelScope.launch {
            preferencesManager.setStunTurnConfig(config)
            val serverCfg = serverConfig.value
            asteriskManager.configure(serverCfg, config)
            asteriskManager.reloadConfiguration()
        }
    }

    fun setStunEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val config = stunTurnConfig.value.copy(stunEnabled = enabled)
            saveStunTurnConfig(config)
        }
    }

    fun setTurnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val config = stunTurnConfig.value.copy(turnEnabled = enabled)
            saveStunTurnConfig(config)
        }
    }

    fun resetConfig() {
        viewModelScope.launch {
            val defaultConfig = ServerConfig()
            val defaultStunTurn = StunTurnConfig()
            preferencesManager.setServerConfig(defaultConfig)
            preferencesManager.setStunTurnConfig(defaultStunTurn)
            asteriskManager.configure(defaultConfig, defaultStunTurn)
        }
    }
}