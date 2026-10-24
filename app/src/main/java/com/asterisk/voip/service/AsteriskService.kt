package com.asterisk.voip.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.asterisk.voip.AsteriskApplication
import com.asterisk.voip.MainActivity
import com.asterisk.voip.R
import com.asterisk.voip.core.AsteriskManager
import com.asterisk.voip.data.model.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AsteriskService : Service() {

    private val binder = AsteriskBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val asteriskManager: AsteriskManager by lazy {
        (application as AsteriskApplication).asteriskManager
    }

    inner class AsteriskBinder : Binder() {
        fun getService(): AsteriskService = this@AsteriskService
        fun getManager(): AsteriskManager = asteriskManager
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        startForeground(NOTIFICATION_ID, createNotification())
        // keep the notification in sync with what the server is doing
        serviceScope.launch {
            asteriskManager.serverStatus
                .map { Triple(it.isRunning, it.registeredPeers, it.activeCalls) }
                .distinctUntilChanged()
                .collect { updateNotification() }
        }
        asteriskManager.log(LogLevel.INFO, "Service", "Asterisk Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_TOGGLE -> toggleServer()
            // null intent = system restarted the service (START_STICKY); plain intent = app opened.
            else -> serviceScope.launch(Dispatchers.IO) { asteriskManager.resumeIfWanted() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away must not stop the server
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseLocks()
        // "false" = not a user decision, so the server comes back when the service restarts
        CoroutineScope(Dispatchers.IO).launch { asteriskManager.stopServer(userRequested = false) }
        asteriskManager.log(LogLevel.INFO, "Service", "Asterisk Service destroyed")
    }

    /** Keeps CPU and Wi-Fi awake for as long as the service lives (it used to expire after 10 minutes). */
    private fun acquireLocks() {
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Asterisk::ServiceWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Asterisk::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    fun startServer() {
        serviceScope.launch(Dispatchers.IO) {
            asteriskManager.startServer()
            withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    fun stopServer() {
        serviceScope.launch(Dispatchers.IO) {
            asteriskManager.stopServer()
            withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    fun toggleServer() {
        serviceScope.launch(Dispatchers.IO) {
            if (asteriskManager.isRunning.value) asteriskManager.stopServer() else asteriskManager.startServer()
            withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val st = asteriskManager.serverStatus.value
        val running = asteriskManager.isRunning.value
        val text = if (running) "Running • ${st.registeredPeers} online • ${st.activeCalls} call(s)" else "Stopped"

        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AsteriskService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AsteriskApplication.CHANNEL_ID)
            .setContentTitle("Asterisk VoIP Server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_server)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_power, if (running) "Stop" else "Start", toggleIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.asterisk.voip.START"
        const val ACTION_STOP = "com.asterisk.voip.STOP"
        const val ACTION_TOGGLE = "com.asterisk.voip.TOGGLE"
    }
}
