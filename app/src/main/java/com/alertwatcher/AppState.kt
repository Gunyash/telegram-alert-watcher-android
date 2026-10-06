package com.alertwatcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.alertwatcher.config.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Небольшое состояние, которое переживает перезапуск: вкл/выкл и замеченные чаты. */
object AppState {
    private const val MAX_RECENT_CHATS = 30

    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(true)
    /** Слежение включено пользователем (переключатель на главном экране). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _recentChats = mapOf(
        Source.TELEGRAM to MutableStateFlow<List<String>>(emptyList()),
        Source.MATTERMOST to MutableStateFlow<List<String>>(emptyList()),
    )

    private val _mattermostServer = MutableStateFlow<String?>(null)
    /** Адрес сервера из последнего уведомления Mattermost — подсказка для проверки связи. */
    val mattermostServer: StateFlow<String?> = _mattermostServer.asStateFlow()

    private fun recentKey(source: Source) = when (source) {
        Source.TELEGRAM -> "recent_chats"
        Source.MATTERMOST -> "recent_mattermost_channels"
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean("enabled", true)
        for ((source, flow) in _recentChats) {
            flow.value = prefs.getString(recentKey(source), null)?.let { s ->
                val arr = JSONArray(s)
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        }
        _mattermostServer.value = prefs.getString("mattermost_server", null)
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit { putBoolean("enabled", value) }
    }

    /**
     * Чаты Telegram / каналы Mattermost, из которых недавно приходили уведомления
     * (замена list_chats.py).
     */
    fun recentChats(source: Source): StateFlow<List<String>> = recentChatsReadOnly.getValue(source)

    // Один и тот же объект на каждый вызов — иначе Compose перезапускал бы подписку.
    private val recentChatsReadOnly = _recentChats.mapValues { it.value.asStateFlow() }

    /** Запоминает чат. Возвращает true, если раньше его не видели. */
    @Synchronized
    fun noteChat(source: Source, title: String): Boolean {
        val flow = _recentChats.getValue(source)
        val current = flow.value
        val isNew = title !in current
        if (current.firstOrNull() == title) return false
        val updated = (listOf(title) + current.filter { it != title }).take(MAX_RECENT_CHATS)
        flow.value = updated
        prefs.edit { putString(recentKey(source), JSONArray(updated).toString()) }
        return isNew
    }

    fun noteMattermostServer(url: String) {
        if (_mattermostServer.value == url) return
        _mattermostServer.value = url
        prefs.edit { putString("mattermost_server", url) }
    }

    /** Первый запуск слушателя уведомлений уже был (см. TelegramNotificationListener). */
    var baselineDone: Boolean
        get() = prefs.getBoolean("baseline_done", false)
        set(value) = prefs.edit { putBoolean("baseline_done", value) }

    internal val preferences: SharedPreferences get() = prefs
}
