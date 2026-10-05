package com.alertwatcher.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Кнопки и смахивание уведомления об алерте. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIRM -> AlarmController.confirmCurrent(context)
            ACTION_RENOTIFY -> AlarmController.renotify(context)
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.alertwatcher.CONFIRM"
        const val ACTION_RENOTIFY = "com.alertwatcher.RENOTIFY"
    }
}
