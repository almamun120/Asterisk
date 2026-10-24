// FILE: ./app/src/main/java/com/asterisk/voip/AsteriskApplication.kt (UPDATED)
package com.asterisk.voip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.asterisk.voip.core.AsteriskManager
import com.asterisk.voip.data.database.AsteriskDatabase
import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.flow.first

class AsteriskApplication : Application() {

    companion object {
        const val CHANNEL_ID = "asterisk_service_channel"
        const val CHANNEL_NAME = "Asterisk VoIP Server"

        lateinit var instance: AsteriskApplication
            private set

        fun applyTheme(mode: String) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    lateinit var database: AsteriskDatabase
        private set

    lateinit var repository: AsteriskRepository
        private set

    lateinit var asteriskManager: AsteriskManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val mode = kotlinx.coroutines.runBlocking {
                com.asterisk.voip.utils.PreferencesManager(this@AsteriskApplication).themeMode.first()
            }
            applyTheme(mode)
        } catch (_: Exception) {}
        initializeDependencies()
        createNotificationChannels()
    }

    private fun initializeDependencies() {
        database = AsteriskDatabase.getInstance(this)
        repository = AsteriskRepository(
            database.extensionDao(),
            database.dialPlanDao(),
            database.offlineMessageDao(),
            database.voicePromptDao(),
            database.dialPlanRuleDao(),
            database.dialPlanScheduleDao(),
            database.registrationDao()
        )
        asteriskManager = AsteriskManager(this, repository)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Asterisk VoIP Server Status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }
}