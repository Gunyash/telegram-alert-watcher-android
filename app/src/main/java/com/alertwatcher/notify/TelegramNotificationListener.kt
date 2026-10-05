package com.alertwatcher.notify

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alertwatcher.AppLog
import com.alertwatcher.AppState
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.alarm.AlarmEvent
import com.alertwatcher.config.AlertMatcher
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.service.WatcherService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Замена handler() из watcher.py. Вместо собственного подключения к Telegram
 * (Telethon) читаем уведомления официального приложения Telegram на телефоне:
 * не нужны api_id/api_hash/сессия, а доставку сообщений обеспечивает сам Telegram.
 */
class TelegramNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        _connected.value = true
        AppLog.log("Доступ к уведомлениям активен, слушаю Telegram")
        WatcherService.startIfEnabled(this)

        val active = try {
            activeNotifications.orEmpty()
        } catch (e: Exception) {
            emptyArray()
        }
        if (!AppState.baselineDone) {
            // Самый первый запуск: то, что уже висит в шторке, — старые сообщения.
            // Запоминаем их без алерта, чтобы не будить из-за вчерашних уведомлений.
            var skipped = 0
            active.forEach { sbn -> skipped += handle(sbn, silent = true) }
            AppState.baselineDone = true
            if (skipped > 0) AppLog.log("Первый запуск: пропущено старых сообщений из уведомлений: $skipped")
        } else {
            // Перезапуск: обрабатываем то, что пришло, пока нас не было.
            active.forEach { handle(it, silent = false) }
        }
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        AppLog.log("Доступ к уведомлениям отключён системой — пробую переподключиться")
        NotificationListenerService.requestRebind(ComponentName(this, TelegramNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn, silent = false)
        } catch (e: Exception) {
            AppLog.log("Ошибка обработки уведомления: $e")
        }
    }

    /** Возвращает число новых сообщений из отслеживаемого чата. */
    private fun handle(sbn: StatusBarNotification, silent: Boolean): Int {
        val config = ConfigStore.config.value
        if (sbn.packageName !in config.telegramPackages) return 0
        val parsed = TelegramNotificationParser.parse(sbn) ?: return 0

        val tracked = config.isTrackedChat(parsed.chatTitle)
        if (AppState.noteChat(parsed.chatTitle) && !tracked && !silent) {
            AppLog.log("Замечен чат «${parsed.chatTitle}» (не отслеживается)")
        }
        if (!tracked) return 0
        // Пока слежение выключено, сообщения только запоминаем — чтобы после
        // включения не сработали алерты по старым непрочитанным.
        val quiet = silent || !AppState.enabled.value

        var newCount = 0
        for (msg in parsed.messages) {
            if (!SeenMessages.markIfNew(SeenMessages.keyOf(parsed.chatTitle, msg))) continue
            newCount++
            if (quiet) continue

            AppLog.log("[${parsed.chatTitle}] ${msg.text.take(200).replace('\n', ' ')}")
            when (val verdict = ConfigStore.matcher().check(msg.text)) {
                is AlertMatcher.Verdict.Alert -> {
                    AppLog.log("!!! АЛЕРТ ОБНАРУЖЕН !!! (паттерн ${verdict.pattern})")
                    AlarmController.trigger(
                        this,
                        AlarmEvent.message(chat = parsed.chatTitle, text = msg.text, sentAt = msg.timestamp),
                    )
                }
                is AlertMatcher.Verdict.Ignored ->
                    AppLog.log("   пропущено по ignore-паттерну ${verdict.pattern}")
                AlertMatcher.Verdict.NoMatch -> Unit
            }
        }
        return newCount
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        /** Система подключила наш слушатель (доступ выдан и сервис работает). */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun requestRebind(context: Context) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, TelegramNotificationListener::class.java)
                )
            } catch (e: Exception) {
                // Доступ не выдан — переподключать нечего.
            }
        }
    }
}
