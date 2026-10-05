package com.alertwatcher.alarm

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.alertwatcher.App
import com.alertwatcher.AppLog
import com.alertwatcher.AppState
import com.alertwatcher.R
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.service.WatcherService
import com.alertwatcher.ui.AlarmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Аналог trigger_alarm() из alarm.py: красный экран + звук, пока не подтвердят.
 *
 * Одновременно показывается один алерт; если новый пришёл, пока текущий не
 * подтверждён, он встаёт в очередь и показывается после подтверждения
 * (как очередь окон на ПК). Звук играет без перерыва, пока очередь не пуста.
 * Очередь сохраняется на диск: если процесс убьют или телефон перезагрузится,
 * неподтверждённый алерт зазвучит снова.
 */
object AlarmController {

    data class State(val current: AlarmEvent? = null, val queued: List<AlarmEvent> = emptyList())

    private const val NOTIFICATION_ID = 1001
    private const val PREF_KEY = "alarm_queue"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())

    fun trigger(context: Context, event: AlarmEvent) {
        val app = context.applicationContext
        val wasIdle: Boolean
        synchronized(this) {
            val s = _state.value
            wasIdle = s.current == null
            _state.value = if (wasIdle) State(event) else s.copy(queued = s.queued + event)
            persist()
        }
        main.post {
            syncEffects(app)
            if (wasIdle) launchAlarmScreen(app)
        }
    }

    /** Кнопка «Подтвердить» (пробел на ПК): закрыть текущий и показать следующий. */
    fun confirmCurrent(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            val s = _state.value
            _state.value = if (s.queued.isEmpty()) State() else State(s.queued.first(), s.queued.drop(1))
            persist()
        }
        AppLog.log("Алерт подтверждён")
        main.post { syncEffects(app) }
    }

    fun confirmAll(context: Context) {
        val app = context.applicationContext
        val count: Int
        synchronized(this) {
            val s = _state.value
            count = (if (s.current != null) 1 else 0) + s.queued.size
            _state.value = State()
            persist()
        }
        AppLog.log("Подтверждены все алерты ($count)")
        main.post { syncEffects(app) }
    }

    /** Есть ли показанный или ожидающий в очереди алерт о потере связи. */
    fun hasConnectionAlarm(): Boolean {
        val s = _state.value
        return s.current?.kind == AlarmEvent.Kind.CONNECTION ||
            s.queued.any { it.kind == AlarmEvent.Kind.CONNECTION }
    }

    /** Уведомление смахнули, а алерт не подтверждён — показываем снова. */
    fun renotify(context: Context) {
        val app = context.applicationContext
        main.post { syncEffects(app) }
    }

    /** При старте процесса: поднять алерты, которые не успели подтвердить. */
    fun restore(context: Context) {
        val app = context.applicationContext
        val saved = try {
            AppState.preferences.getString(PREF_KEY, null)?.let { s ->
                val arr = JSONArray(s)
                (0 until arr.length()).map { AlarmEvent.fromJson(arr.getJSONObject(it)) }
            }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        // Уведомление могло остаться от убитого процесса. Убираем его: без алерта оно
        // не нужно, а с алертом его надо опубликовать заново — иначе это будет
        // «обновление» без звука и без открытия красного экрана.
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        if (saved.isEmpty()) return
        synchronized(this) { _state.value = State(saved.first(), saved.drop(1)) }
        AppLog.log("Восстановлены неподтверждённые алерты: ${saved.size}")
        main.post {
            syncEffects(app)
            launchAlarmScreen(app)
        }
    }

    private fun persist() {
        val s = _state.value
        val all = listOfNotNull(s.current) + s.queued
        AppState.preferences.edit {
            if (all.isEmpty()) remove(PREF_KEY)
            else putString(PREF_KEY, JSONArray(all.map { it.toJson() }).toString())
        }
    }

    /** Приводит звук/вибрацию/уведомление в соответствие с текущим состоянием. Только main-поток. */
    private fun syncEffects(app: Context) {
        val s = _state.value
        if (s.current == null) {
            AlarmSoundPlayer.stop(app)
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            return
        }
        // Фоновая служба повышает приоритет процесса, пока играет звук.
        WatcherService.startIfEnabled(app)
        AlarmSoundPlayer.start(app, ConfigStore.config.value)
        showNotification(app, s)
    }

    private fun alarmScreenIntent(app: Context) =
        Intent(app, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    /**
     * Прямой запуск экрана. Срабатывает, если приложение открыто или выдано
     * «Поверх других приложений»; иначе Android его молча блокирует, и экран
     * откроется через полноэкранное уведомление.
     */
    private fun launchAlarmScreen(app: Context) {
        try {
            app.startActivity(alarmScreenIntent(app))
        } catch (e: Exception) {
            // Ничего страшного — остаётся полноэкранное уведомление.
        }
    }

    private fun showNotification(app: Context, s: State) {
        val event = s.current ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.log("Нет разрешения на уведомления — красный экран может не открыться на заблокированном телефоне")
            return
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(app, 1, alarmScreenIntent(app), flags)
        val confirm = PendingIntent.getBroadcast(
            app, 2,
            Intent(app, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_CONFIRM),
            flags,
        )
        val deleted = PendingIntent.getBroadcast(
            app, 3,
            Intent(app, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_RENOTIFY),
            flags,
        )
        val more = if (s.queued.isNotEmpty()) " (+ещё ${s.queued.size})" else ""
        val body = (event.chat?.let { "Чат: $it\n" } ?: "") + event.text

        val notification = NotificationCompat.Builder(app, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setColor(0xFFD50000.toInt())
            .setContentTitle(event.title + more)
            .setContentText(event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // Обновление (новый алерт в очереди) не должно всплывать поверх красного экрана.
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setDeleteIntent(deleted)
            .addAction(0, "Подтвердить", confirm)
            .build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
    }
}
