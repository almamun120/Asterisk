package com.asterisk.voip.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.asterisk.voip.data.model.ServerConfig
import com.asterisk.voip.data.model.StunTurnConfig
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "asterisk_settings")

class PreferencesManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val SERVER_CONFIG = stringPreferencesKey("server_config")
        val STUN_TURN_CONFIG = stringPreferencesKey("stun_turn_config")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val SERVER_ENABLED = booleanPreferencesKey("server_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode") // system | light | dark
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { it[START_ON_BOOT] ?: false }

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { preferences ->
        val json = preferences[SERVER_CONFIG]
        if (json != null) gson.fromJson(json, ServerConfig::class.java) else ServerConfig()
    }

    val stunTurnConfig: Flow<StunTurnConfig> = context.dataStore.data.map { preferences ->
        val json = preferences[STUN_TURN_CONFIG]
        if (json != null) gson.fromJson(json, StunTurnConfig::class.java) else StunTurnConfig()
    }

    /** Whether the user wants the server running (restored after the app/service is killed). */
    val serverEnabled: Flow<Boolean> = context.dataStore.data.map { it[SERVER_ENABLED] ?: true }
    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "system" }

    val darkMode: Flow<Boolean> = context.dataStore.data.map { it[DARK_MODE] ?: false }

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[START_ON_BOOT] = enabled }
    }

    suspend fun setServerConfig(config: ServerConfig) {
        context.dataStore.edit { it[SERVER_CONFIG] = gson.toJson(config) }
    }

    suspend fun setStunTurnConfig(config: StunTurnConfig) {
        context.dataStore.edit { it[STUN_TURN_CONFIG] = gson.toJson(config) }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[DARK_MODE] = enabled }
    }

    suspend fun setServerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SERVER_ENABLED] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }
}
