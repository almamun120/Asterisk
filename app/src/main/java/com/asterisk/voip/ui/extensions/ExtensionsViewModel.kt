package com.asterisk.voip.ui.extensions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.Extension
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExtensionsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val repository = app.repository
    private val asteriskManager = app.asteriskManager

    val extensions: StateFlow<List<Extension>> = repository.allExtensions
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Extension numbers that are currently registered on the server. */
    val onlineExtensions: StateFlow<Set<String>> = asteriskManager.serverStatus
        .map { st -> st.peers.map { it.extension }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    fun addExtension(extension: Extension) {
        viewModelScope.launch {
            repository.insertExtension(extension)
            asteriskManager.reloadConfiguration()
        }
    }

    fun updateExtension(extension: Extension) {
        viewModelScope.launch {
            repository.updateExtension(extension)
            asteriskManager.reloadConfiguration()
        }
    }

    fun deleteExtension(extension: Extension) {
        viewModelScope.launch {
            repository.deleteExtension(extension)
            asteriskManager.reloadConfiguration()
        }
    }

    fun toggleExtension(extension: Extension, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateExtension(extension.copy(enabled = enabled))
            asteriskManager.reloadConfiguration()
        }
    }
}