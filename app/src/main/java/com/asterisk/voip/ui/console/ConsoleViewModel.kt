package com.asterisk.voip.ui.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.ConsoleLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val asteriskManager = app.asteriskManager

    val consoleLogs: StateFlow<List<ConsoleLog>> = asteriskManager.consoleLogs

    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    fun clearLogs() = asteriskManager.clearLogs()
    fun setAutoScroll(enabled: Boolean) { _autoScroll.value = enabled }
}