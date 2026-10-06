package com.alertwatcher.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alertwatcher.App
import com.alertwatcher.AppLog
import com.alertwatcher.AppState
import com.alertwatcher.R
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.notify.TelegramNotificationListener
import com.alertwatcher.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Фоновая служба с постоянным уведомлением. Нужна, чтобы Android не выгружал
 * приложение из памяти, и в ней крутится мониторинг связи.
 * Сами сообщения Telegram и Mattermost ловит TelegramNotificationListener.
 */
class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground вызываем на каждый запуск: так требует Android
        // после каждого startForegroundService().
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(statusText()),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            AppLog.log("Android не разрешил запустить фоновую службу: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            onFirstStart()
        }
        return START_STICKY
    }

    private fun onFirstStart() {
        _running.value = true
        AppLog.log("Фоновая служба запущена")

        // Перезапуск мониторинга связи при изменении его настроек.
        scope.launch {
            ConfigStore.config
                .map { it.connectionMonitor to ConnectionMonitor.targets(it) }
                .distinctUntilChanged()
                .collect { (cfg, targets) ->
                    monitorJob?.cancel()
                    setWakeLock(targets.isNotEmpty() && cfg.keepCpuAwake)
                    monitorJob = scope.launch(Dispatchers.Default) {
                        ConnectionMonitor.run(applicationContext, cfg, targets)
                    }
                }
        }

        // Обновление текста постоянного уведомления.
        scope.launch {
            combine(
                ConfigStore.config,
                ConnectionMonitor.status,
                TelegramNotificationListener.connected,
            ) { _, _, _ -> statusText() }
                .distinctUntilChanged()
                .collect { (title, text) ->
                    NotificationManagerCompat.from(this@WatcherService).let { nm ->
                        if (nm.areNotificationsEnabled()) {
                            try {
                                nm.notify(NOTIFICATION_ID, buildNotification(title to text))
                            } catch (_: SecurityException) {
                            }
                        }
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        setWakeLock(false)
        if (started) AppLog.log("Фоновая служба остановлена")
        _running.value = false
        super.onDestroy()
    }

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertWatcher:monitor")
                .apply { acquire() }
        } else if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun statusText(): Pair<String, String> {
        val cfg = ConfigStore.config.value
        val sources = listOfNotNull(
            "Telegram".takeIf { cfg.telegramEnabled && cfg.targetChats.isNotEmpty() },
            "Mattermost".takeIf { cfg.mattermostEnabled && cfg.mattermostChannels.isNotEmpty() },
        )
        val title = when {
            !TelegramNotificationListener.connected.value -> "⚠ Нет доступа к уведомлениям"
            sources.isEmpty() -> "⚠ Не указаны чаты для слежения"
            else -> "Слежу за " + sources.joinToString(" и ")
        }
        val lines = buildList {
            if (cfg.telegramEnabled && cfg.targetChats.isNotEmpty()) {
                add("Telegram: " + cfg.targetChats.joinToString(", "))
            }
            if (cfg.mattermostEnabled && cfg.mattermostChannels.isNotEmpty()) {
                add("Mattermost: " + cfg.mattermostChannels.joinToString(", "))
            }
            val statuses = ConnectionMonitor.status.value
            if (statuses.isNotEmpty()) {
                add("Связь: " + statuses.entries.joinToString(", ") { (name, s) ->
                    name + " " + when (s.ok) {
                        true -> "OK"
                        false -> "НЕТ (${s.fails})"
                        null -> "…"
                    }
                })
            }
        }
        return title to lines.joinToString("\n").ifEmpty { "Чаты не указаны" }
    }

    private fun buildNotification(content: Pair<String, String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(content.first)
            .setContentText(content.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.second))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))
            } catch (e: Exception) {
                // Android 12+ не даёт запускать такие службы из фона, если приложению
                // не разрешена работа без ограничений батареи.
                AppLog.log("Не удалось запустить фоновую службу: $e")
            }
        }

        fun startIfEnabled(context: Context) {
            if (AppState.enabled.value) start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
