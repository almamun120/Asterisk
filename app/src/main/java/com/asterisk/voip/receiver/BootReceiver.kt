package com.asterisk.voip.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.asterisk.voip.service.AsteriskService
import com.asterisk.voip.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            CoroutineScope(Dispatchers.IO).launch {
                val prefsManager = PreferencesManager(context)
                val startOnBoot = prefsManager.startOnBoot.first()

                if (startOnBoot) {
                    val serviceIntent = Intent(context, AsteriskService::class.java).apply {
                        action = AsteriskService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}