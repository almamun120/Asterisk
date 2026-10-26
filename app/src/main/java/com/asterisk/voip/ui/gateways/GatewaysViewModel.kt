package com.asterisk.voip.ui.gateways

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.data.model.Trunk
import com.asterisk.voip.data.model.TrunkInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GatewaysViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AsteriskApplication
    private val repository = app.repository
    private val manager = app.asteriskManager

    val trunks: StateFlow<List<Trunk>> = repository.allTrunks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Live registration status keyed by trunk id. */
    val status: StateFlow<Map<Long, TrunkInfo>> = manager.serverStatus
        .map { st -> st.trunks.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val serverRunning: StateFlow<Boolean> = manager.isRunning

    fun add(t: Trunk) = viewModelScope.launch { repository.insertTrunk(t); manager.reloadConfiguration() }
    fun update(t: Trunk) = viewModelScope.launch { repository.updateTrunk(t); manager.reloadConfiguration() }
    fun delete(t: Trunk) = viewModelScope.launch { repository.deleteTrunk(t); manager.reloadConfiguration() }
    fun toggle(t: Trunk, enabled: Boolean) = viewModelScope.launch { repository.updateTrunk(t.copy(enabled = enabled)); manager.reloadConfiguration() }
    fun retry(t: Trunk) = manager.retryTrunk(t.id)
}
