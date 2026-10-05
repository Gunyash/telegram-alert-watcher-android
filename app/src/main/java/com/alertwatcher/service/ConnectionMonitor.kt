package com.alertwatcher.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.alertwatcher.AppLog
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.alarm.AlarmEvent
import com.alertwatcher.config.ConnectionMonitorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Аналог connection_watchdog() из watcher.py.
 *
 * На ПК проверка шла запросом к API Telegram через Telethon. Здесь своего
 * подключения к Telegram нет (сообщения приносит приложение Telegram), поэтому
 * проверяем, что у телефона есть сеть и что сервер Telegram отвечает по HTTPS.
 * Логика порогов и повторов — та же, что на ПК.
 */
object ConnectionMonitor {

    data class Status(
        /** null — мониторинг выключен или ещё не было проверок. */
        val ok: Boolean? = null,
        val fails: Int = 0,
        val lastCheckAt: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Бесконечный цикл проверок; останавливается отменой корутины. */
    suspend fun run(context: Context, cfg: ConnectionMonitorConfig) {
        _status.value = Status()
        if (!cfg.enabled) return

        var fails = 0
        var outageSince: Long? = null  // elapsedRealtime() начала текущего обрыва
        var lastAlarmAt: Long? = null  // когда в последний раз поднимали алерт о связи

        try {
            while (true) {
                // Сначала ждём: сразу после запуска сеть могла ещё не подняться.
                delay(cfg.checkIntervalSec * 1000L)
                val ok = try {
                    check(context, cfg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.log("Ошибка проверки связи: $e")
                    false
                }
                val now = SystemClock.elapsedRealtime()

                if (ok) {
                    outageSince?.let {
                        AppLog.log("Связь с Telegram восстановлена (не было ~${(now - it) / 1000} с)")
                    }
                    fails = 0
                    outageSince = null
                    lastAlarmAt = null
                } else {
                    fails++
                    val since = outageSince ?: now.also { outageSince = it }
                    // На ПК писалась каждая неудача; при долгом обрыве это сотни строк,
                    // поэтому после порога пишем только каждую 20-ю.
                    if (fails <= cfg.failThreshold || fails % 20 == 0) {
                        AppLog.log("Нет ответа от Telegram (неудачных проверок подряд: $fails, алерт после ${cfg.failThreshold})")
                    }

                    val needAlarm = fails >= cfg.failThreshold &&
                        !AlarmController.hasConnectionAlarm() &&
                        (lastAlarmAt == null ||
                            (cfg.realertIntervalSec > 0 && now - lastAlarmAt >= cfg.realertIntervalSec * 1000L))

                    if (needAlarm) {
                        val downFor = (now - since) / 1000
                        AppLog.log("!!! АЛЕРТ: ПОТЕРЯНА СВЯЗЬ С TELEGRAM !!!")
                        lastAlarmAt = now
                        AlarmController.trigger(
                            context,
                            AlarmEvent.connection(
                                "Потеряна связь с Telegram!\n" +
                                    "Нет ответа уже ~$downFor с.\n" +
                                    "Проверь VPN / интернет — пока связи нет, алерты из чата не приходят."
                            ),
                        )
                    }
                }
                _status.value = Status(ok, fails, System.currentTimeMillis())
            }
        } finally {
            _status.value = Status()
        }
    }

    /** Одна проверка: есть сеть и сервер отвечает за check_timeout_sec. */
    suspend fun check(context: Context, cfg: ConnectionMonitorConfig): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        return httpCheck(cfg.checkUrl, cfg.checkTimeoutSec * 1000)
    }

    /**
     * HTTP-запрос в отдельном потоке: у HttpURLConnection нет таймаута на DNS,
     * и зависший запрос не должен задерживать цикл проверок дольше таймаута.
     */
    private suspend fun httpCheck(url: String, timeoutMs: Int): Boolean {
        val result = CompletableDeferred<Boolean>()
        thread(isDaemon = true, name = "connection-check") {
            result.complete(
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = timeoutMs
                        conn.readTimeout = timeoutMs
                        conn.instanceFollowRedirects = false
                        conn.useCaches = false
                        conn.requestMethod = "HEAD"
                        conn.responseCode  // любой HTTP-ответ = сервер доступен
                        true
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    false
                }
            )
        }
        return withTimeoutOrNull(timeoutMs.toLong()) { result.await() } ?: false
    }
}
