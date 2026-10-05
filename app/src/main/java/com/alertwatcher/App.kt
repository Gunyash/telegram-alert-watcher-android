package com.alertwatcher

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppState.init(this)
        ConfigStore.init(this)
        createNotificationChannels()
        AlarmController.restore(this)
    }

    private fun createNotificationChannels() {
        val alarm = NotificationChannel(CHANNEL_ALARM, "Алерты", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Красный экран при алерте. Звук и вибрацию приложение включает само."
            // Звук играет само приложение (через поток будильника), у канала его нет —
            // иначе было бы два звука одновременно.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val status = NotificationChannel(CHANNEL_STATUS, "Фоновая работа", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Постоянное уведомление, пока приложение следит за Telegram."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(alarm, status))
    }

    companion object {
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_STATUS = "status"

        /** Для задач, которые должны пережить закрытие экрана (например, тест с задержкой). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
