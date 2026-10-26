// FILE: ./app/src/main/java/com/asterisk/voip/core/AsteriskManager.kt
package com.asterisk.voip.core

import android.content.Context
import com.asterisk.voip.data.model.*
import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.asterisk.voip.utils.PreferencesManager

class AsteriskManager(
    private val context: Context,
    private val repository: AsteriskRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<ConsoleLog>>(emptyList())
    val consoleLogs: StateFlow<List<ConsoleLog>> = _consoleLogs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val prefs = PreferencesManager(context)
    private var serverConfig = ServerConfig()
    private var stunTurnConfig = StunTurnConfig()
    private var startTime: Long = 0

    private val sipServer = SipServer(repository) { level, tag, message ->
        log(level, tag, message)
    }

    private val stunServer = StunServer { level, tag, message ->
        log(level, tag, message)
    }

    private val turnServer = TurnServer { level, tag, message ->
        log(level, tag, message)
    }

    // Dial Plan Components
    private val voicePromptManager = VoicePromptManager(context) { level, tag, message ->
        log(level, tag, message)
    }

    private val dialPlanExecutor = DialPlanExecutor(repository, voicePromptManager, context.filesDir) { level, tag, message ->
        log(level, tag, message)
    }

    init {
        // Initialize dial plan in SIP server
        sipServer.initializeDialPlan(dialPlanExecutor, voicePromptManager, context.filesDir)
        log(LogLevel.INFO, "INIT", "✓ AsteriskManager initialized with dial plan support")
    }

    fun configure(config: ServerConfig, stunTurn: StunTurnConfig = StunTurnConfig()) {
        serverConfig = config
        stunTurnConfig = stunTurn
        sipServer.configure(config)
        stunServer.configure(stunTurn.stunPort)
        turnServer.configure(
            stunTurn.turnPort,
            stunTurn.turnUsername,
            stunTurn.turnPassword,
            stunTurn.turnRealm
        )
        log(LogLevel.INFO, "CFG", "Configuration updated")
    }

    suspend fun startServer(): Boolean {
        if (_isRunning.value) {
            log(LogLevel.WARNING, "SRV", "Already running")
            return false
        }

        return try {
            log(LogLevel.INFO, "SRV", "Starting server...")

            // Always start with the *saved* configuration (not defaults)
            try {
                configure(prefs.serverConfig.first(), prefs.stunTurnConfig.first())
            } catch (e: Exception) {
                log(LogLevel.WARNING, "CFG", "Could not load saved config, using current: ${e.message}")
            }

            val started = withContext(Dispatchers.IO) { sipServer.start() }

            if (started) {
                // Start STUN if enabled
                if (stunTurnConfig.stunEnabled) {
                    val stunStarted = stunServer.start()
                    if (stunStarted) {
                        log(LogLevel.INFO, "SRV", "✓ STUN enabled on port ${stunTurnConfig.stunPort}")
                    } else {
                        log(LogLevel.WARNING, "SRV", "⚠ STUN failed to start")
                    }
                }

                // Start TURN if enabled
                if (stunTurnConfig.turnEnabled) {
                    val turnStarted = turnServer.start()
                    if (turnStarted) {
                        log(LogLevel.INFO, "SRV", "✓ TURN enabled on port ${stunTurnConfig.turnPort}")
                    } else {
                        log(LogLevel.WARNING, "SRV", "⚠ TURN failed to start")
                    }
                }

                startTime = System.currentTimeMillis()
                _isRunning.value = true
                prefs.setServerEnabled(true)
                startStatusMonitor()
                log(LogLevel.INFO, "SRV", "════════════════════════════════════")
                log(LogLevel.INFO, "SRV", "✅ Server is ready!")
                log(LogLevel.INFO, "SRV", "   SIP: ${sipServer.getServerIp()}:${serverConfig.sipPort}")
                log(LogLevel.INFO, "SRV", "   RTP: ${serverConfig.rtpStartPort}-${serverConfig.rtpEndPort}")
                log(LogLevel.INFO, "SRV", "   Media: ${serverConfig.mediaMode.uppercase()}")
                log(LogLevel.INFO, "SRV", "════════════════════════════════════")
            }

            started
        } catch (e: Exception) {
            log(LogLevel.ERROR, "SRV", "Start failed: ${e.message}")
            false
        }
    }

    /** @param userRequested false when the service is just being torn down (keeps "should run" flag). */
    suspend fun stopServer(userRequested: Boolean = true): Boolean {
        if (!_isRunning.value) {
            if (userRequested) prefs.setServerEnabled(false)
            return false
        }
        if (userRequested) prefs.setServerEnabled(false)

        return try {
            log(LogLevel.INFO, "SRV", "Stopping server...")

            withContext(Dispatchers.IO) { sipServer.stop() }

            if (stunTurnConfig.stunEnabled) {
                stunServer.stop()
            }

            if (stunTurnConfig.turnEnabled) {
                turnServer.stop()
            }

            voicePromptManager.shutdown()

            _isRunning.value = false
            _serverStatus.value = ServerStatus(mediaMode = serverConfig.mediaMode, sipPort = serverConfig.sipPort)
            startTime = 0

            log(LogLevel.INFO, "SRV", "✓ Server stopped")
            true
        } catch (e: Exception) {
            log(LogLevel.ERROR, "SRV", "Stop failed: ${e.message}")
            false
        }
    }

    private fun startStatusMonitor() {
        scope.launch {
            var lastBytes = sipServer.getRelayBytes()
            var lastCpuTicks = readCpuTicks()
            var lastWall = System.nanoTime()
            var kbps = 0
            var cpu = 0f
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            while (_isRunning.value) {
                val now = System.nanoTime()
                val dt = (now - lastWall) / 1e9
                if (dt >= 1.0) {
                    val bytes = sipServer.getRelayBytes()
                    kbps = (((bytes - lastBytes).coerceAtLeast(0)) * 8 / 1000 / dt).toInt()
                    lastBytes = bytes
                    val ticks = readCpuTicks()
                    if (ticks >= 0 && lastCpuTicks >= 0) {
                        cpu = ((ticks - lastCpuTicks) / 100.0 / dt / cores * 100).toFloat().coerceIn(0f, 100f)
                    }
                    lastCpuTicks = ticks
                    lastWall = now
                }
                val uptime = if (startTime > 0) System.currentTimeMillis() - startTime else 0
                _serverStatus.value = ServerStatus(
                    isRunning = _isRunning.value,
                    uptime = uptime,
                    activeCalls = sipServer.getActiveCallCount(),
                    registeredPeers = sipServer.getRegisteredCount(),
                    cpuUsage = cpu,
                    memoryUsage = getMemUsage(),
                    sipPort = serverConfig.sipPort,
                    bindAddress = sipServer.getServerIp(),
                    mediaMode = serverConfig.mediaMode,
                    relayKbps = kbps,
                    rtpRange = "${serverConfig.rtpStartPort}-${serverConfig.rtpEndPort}",
                    peers = sipServer.getPeers(),
                    calls = sipServer.getCalls(),
                    trunks = sipServer.getTrunks()
                )
                delay(1000)
            }
        }
    }

    /** utime+stime of this process in clock ticks (100/s), or -1 if unreadable. */
    private fun readCpuTicks(): Long = try {
        val f = java.io.File("/proc/self/stat").readText().substringAfterLast(')').trim().split(" ")
        f[11].toLong() + f[12].toLong()
    } catch (e: Exception) { -1L }

    private fun getMemUsage(): Float {
        return try {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            (used.toFloat() / rt.maxMemory().toFloat()) * 100
        } catch (e: Exception) {
            0f
        }
    }

    /** Changes how media is routed (relay / direct / auto). Applies to the next call, no restart needed. */
    fun setMediaMode(mode: String) {
        serverConfig = serverConfig.copy(mediaMode = mode)
        sipServer.configure(serverConfig)
        scope.launch { prefs.setServerConfig(serverConfig) }
        _serverStatus.value = _serverStatus.value.copy(mediaMode = mode)
        log(LogLevel.INFO, "CFG", "Media mode → ${mode.uppercase()} (applies to new calls)")
    }

    /** Brings the server back up after the process/service was killed, if the user had it on. */
    suspend fun resumeIfWanted() {
        if (_isRunning.value) return
        if (prefs.serverEnabled.first()) startServer()
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = ConsoleLog(System.currentTimeMillis(), level, tag, message)
        _consoleLogs.value = (_consoleLogs.value + entry).takeLast(500)

        when (level) {
            LogLevel.ERROR -> android.util.Log.e("AST-$tag", message)
            LogLevel.WARNING -> android.util.Log.w("AST-$tag", message)
            LogLevel.DEBUG -> android.util.Log.d("AST-$tag", message)
            LogLevel.VERBOSE -> android.util.Log.v("AST-$tag", message)
            else -> android.util.Log.i("AST-$tag", message)
        }
    }

    fun clearLogs() {
        _consoleLogs.value = emptyList()
    }

    fun reloadConfiguration() {
        scope.launch {
            try {
                configure(prefs.serverConfig.first(), prefs.stunTurnConfig.first())
                sipServer.reloadTrunks()
                log(LogLevel.INFO, "CFG", "Configuration reloaded" +
                    if (_isRunning.value) " (ports/transport changes need a server restart)" else "")
            } catch (e: Exception) {
                log(LogLevel.ERROR, "CFG", "Reload failed: ${e.message}")
            }
        }
    }

    fun retryTrunk(id: Long) = sipServer.retryTrunk(id)

    fun getLocalIp() = sipServer.getServerIp()

    fun shutdown() {
        scope.cancel()
        voicePromptManager.shutdown()
    }
}