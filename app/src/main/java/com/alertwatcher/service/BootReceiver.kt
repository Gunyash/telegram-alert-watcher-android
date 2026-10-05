package com.alertwatcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                WatcherService.startIfEnabled(context)
        }
    }
}
