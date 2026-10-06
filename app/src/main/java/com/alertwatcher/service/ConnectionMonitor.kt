package com.alertwatcher.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.alertwatcher.AppLog
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.alarm.AlarmEvent
import com.alertwatcher.config.AppConfig
import com.alertwatcher.config.ConnectionMonitorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * подключения к мессенджерам нет (сообщения приносят их приложения), поэтому
 * проверяем, что у телефона есть сеть и что сервер отвечает по HTTPS.
 * Проверяются Telegram и сервер Mattermost — у каждого свой счётчик неудач и
 * свой алерт. Логика порогов и повторов — та же, что на ПК.
 */
object ConnectionMonitor {

    /** Что проверяем: имя («Telegram» / «Mattermost») и адрес. */
    data class Target(val name: String, val url: String)

    data class Status(
        /** null — ещё не было проверок. */
        val ok: Boolean? = null,
        val fails: Int = 0,
        val lastCheckAt: Long = 0,
    )

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    /** Состояние по каждой цели; пусто — мониторинг выключен. */
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /** Что проверять: только включённые мессенджеры с непустым адресом. */
    fun targets(config: AppConfig): List<Target> {
        val cm = config.connectionMonitor
        if (!cm.enabled) return emptyList()
        return listOfNotNull(
            cm.checkUrl.takeIf { config.telegramEnabled && it.isNotBlank() }?.let { Target("Telegram", it) },
            cm.mattermostUrl.takeIf { config.mattermostEnabled && it.isNotBlank() }?.let { Target("Mattermost", it) },
        )
    }

    /** Счётчики одной цели между проверками. */
    private class TargetState {
        var fails = 0
        var outageSince: Long? = null  // elapsedRealtime() начала текущего обрыва
        var lastAlarmAt: Long? = null  // когда в последний раз поднимали алерт о связи
    }

    /** Бесконечный цикл проверок; останавливается отменой корутины. */
    suspend fun run(context: Context, cfg: ConnectionMonitorConfig, targets: List<Target>) {
        _status.value = targets.associate { it.name to Status() }
        if (targets.isEmpty()) return

        val states = targets.associate { it.name to TargetState() }
        try {
            while (true) {
                // Сначала ждём: сразу после запуска сеть могла ещё не подняться.
                delay(cfg.checkIntervalSec * 1000L)
                val results = checkAll(context, cfg, targets)
                val now = SystemClock.elapsedRealtime()
                for ((target, ok) in results) {
                    update(context, cfg, target, states.getValue(target.name), ok, now)
                }
                _status.value = results.associate { (target, ok) ->
                    target.name to Status(ok, states.getValue(target.name).fails, System.currentTimeMillis())
                }
            }
        } finally {
            _status.value = emptyMap()
        }
    }

    private fun update(
        context: Context,
        cfg: ConnectionMonitorConfig,
        target: Target,
        st: TargetState,
        ok: Boolean,
        now: Long,
    ) {
        if (ok) {
            st.outageSince?.let {
                AppLog.log("Связь с ${target.name} восстановлена (не было ~${(now - it) / 1000} с)")
            }
            st.fails = 0
            st.outageSince = null
            st.lastAlarmAt = null
            return
        }

        st.fails++
        val since = st.outageSince ?: now.also { st.outageSince = it }
        // На ПК писалась каждая неудача; при долгом обрыве это сотни строк,
        // поэтому после порога пишем только каждую 20-ю.
        if (st.fails <= cfg.failThreshold || st.fails % 20 == 0) {
            AppLog.log("Нет ответа от ${target.name} (неудачных проверок подряд: ${st.fails}, алерт после ${cfg.failThreshold})")
        }

        val lastAlarmAt = st.lastAlarmAt
        val needAlarm = st.fails >= cfg.failThreshold &&
            !AlarmController.hasConnectionAlarm(target.name) &&
            (lastAlarmAt == null ||
                (cfg.realertIntervalSec > 0 && now - lastAlarmAt >= cfg.realertIntervalSec * 1000L))

        if (needAlarm) {
            val downFor = (now - since) / 1000
            AppLog.log("!!! АЛЕРТ: ПОТЕРЯНА СВЯЗЬ С ${target.name.uppercase()} !!!")
            st.lastAlarmAt = now
            AlarmController.trigger(
                context,
                AlarmEvent.connection(
                    target.name,
                    "Потеряна связь с ${target.name}!\n" +
                        "Нет ответа уже ~$downFor с.\n" +
                        "Проверь VPN / интернет — пока связи нет, алерты из ${target.name} не приходят.",
                ),
            )
        }
    }

    /** Проверяет все цели параллельно: зависший сервер не задерживает проверку другого. */
    suspend fun checkAll(
        context: Context,
        cfg: ConnectionMonitorConfig,
        targets: List<Target>,
    ): List<Pair<Target, Boolean>> {
        val network = try {
            hasNetwork(context)
        } catch (e: Exception) {
            false
        }
        return coroutineScope {
            targets.map { target ->
                async {
                    val ok = try {
                        network && httpCheck(target.url, cfg.checkTimeoutSec * 1000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.log("Ошибка проверки связи с ${target.name}: $e")
                        false
                    }
                    target to ok
                }
            }.awaitAll()
        }
    }

    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
