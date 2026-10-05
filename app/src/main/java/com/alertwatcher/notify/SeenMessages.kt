package com.alertwatcher.notify

import androidx.core.content.edit
import com.alertwatcher.AppState

/**
 * Какие сообщения уже обработаны. Telegram при каждом новом сообщении
 * перерисовывает уведомление чата целиком (со всеми непрочитанными), поэтому
 * без этого одно и то же сообщение поднимало бы алерт снова и снова.
 * Хранится в настройках, чтобы не было повторов после перезапуска приложения.
 */
object SeenMessages {
    private const val MAX = 1000
    private const val PREF_KEY = "seen_messages"

    private val keys = LinkedHashSet<String>()
    private var loaded = false

    fun keyOf(chat: String, msg: TelegramNotificationParser.Message): String =
        "${chat.hashCode()}:${msg.timestamp}:${msg.sender.hashCode()}:${msg.text.hashCode()}"

    /** true — сообщение новое (и теперь запомнено), false — уже видели. */
    @Synchronized
    fun markIfNew(key: String): Boolean {
        load()
        if (!keys.add(key)) return false
        while (keys.size > MAX) keys.remove(keys.first())
        AppState.preferences.edit { putString(PREF_KEY, keys.joinToString("\n")) }
        return true
    }

    private fun load() {
        if (loaded) return
        loaded = true
        AppState.preferences.getString(PREF_KEY, null)
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            ?.let { keys.addAll(it) }
    }
}
