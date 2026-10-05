package com.alertwatcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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

    private val _recentChats = MutableStateFlow<List<String>>(emptyList())
    /** Чаты, из которых недавно приходили уведомления Telegram (замена list_chats.py). */
    val recentChats: StateFlow<List<String>> = _recentChats.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean("enabled", true)
        _recentChats.value = prefs.getString("recent_chats", null)?.let { s ->
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit { putBoolean("enabled", value) }
    }

    /** Запоминает чат. Возвращает true, если раньше его не видели. */
    @Synchronized
    fun noteChat(title: String): Boolean {
        val current = _recentChats.value
        val isNew = title !in current
        if (current.firstOrNull() == title) return false
        val updated = (listOf(title) + current.filter { it != title }).take(MAX_RECENT_CHATS)
        _recentChats.value = updated
        prefs.edit { putString("recent_chats", JSONArray(updated).toString()) }
        return isNew
    }

    /** Первый запуск слушателя уведомлений уже был (см. TelegramNotificationListener). */
    var baselineDone: Boolean
        get() = prefs.getBoolean("baseline_done", false)
        set(value) = prefs.edit { putBoolean("baseline_done", value) }

    internal val preferences: SharedPreferences get() = prefs
}
