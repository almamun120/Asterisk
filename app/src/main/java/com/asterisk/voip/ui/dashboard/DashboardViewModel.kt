package com.asterisk.voip.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.ServerStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val asteriskManager = app.asteriskManager
    private val repository = app.repository

    val serverStatus: StateFlow<ServerStatus> = asteriskManager.serverStatus
    val isRunning: StateFlow<Boolean> = asteriskManager.isRunning

    val extensionCount: StateFlow<Int> = repository.allExtensions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun toggleServer(start: Boolean) {
        viewModelScope.launch {
            if (start) asteriskManager.startServer() else asteriskManager.stopServer()
        }
    }

    fun setMediaMode(mode: String) = asteriskManager.setMediaMode(mode)

    fun reloadConfiguration() {
        asteriskManager.reloadConfiguration()
    }
}
