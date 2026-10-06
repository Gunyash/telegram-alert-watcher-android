package com.alertwatcher.alarm

import org.json.JSONObject

/** Один алерт, ожидающий подтверждения. */
data class AlarmEvent(
    val id: Long,
    val kind: Kind,
    val title: String,
    val text: String,
    /** Чат Telegram / канал Mattermost, из которого пришло сообщение (для алертов по сообщениям). */
    val chat: String?,
    /** Время события (для Telegram — время отправки сообщения). */
    val time: Long,
    /** «Telegram» / «Mattermost»: откуда сообщение или с чем пропала связь. */
    val source: String? = null,
) {
    /** Строка «откуда» для красного экрана и уведомления. */
    val origin: String?
        get() = when {
            chat != null && source != null -> "$source: $chat"
            chat != null -> "Чат: $chat"
            else -> null
        }

    enum class Kind { MESSAGE, CONNECTION, TEST }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("title", title)
        .put("text", text)
        .put("chat", chat ?: JSONObject.NULL)
        .put("time", time)
        .put("source", source ?: JSONObject.NULL)

    companion object {
        fun message(source: String, chat: String, text: String, sentAt: Long) = AlarmEvent(
            id = newId(), kind = Kind.MESSAGE, title = "⚠ АЛЕРТ ⚠", text = text, chat = chat,
            time = if (sentAt > 0) sentAt else System.currentTimeMillis(), source = source,
        )

        /** [source] — «Telegram» или «Mattermost». */
        fun connection(source: String, text: String) = AlarmEvent(
            id = newId(), kind = Kind.CONNECTION, title = "⚠ НЕТ СВЯЗИ С ${source.uppercase()} ⚠",
            text = text, chat = null, time = System.currentTimeMillis(), source = source,
        )

        fun test() = AlarmEvent(
            id = newId(), kind = Kind.TEST, title = "⚠ ТЕСТ ⚠",
            text = "Тестовое сообщение алерта для проверки работы окна и звука",
            chat = null, time = System.currentTimeMillis(),
        )

        fun fromJson(o: JSONObject) = AlarmEvent(
            id = o.getLong("id"),
            kind = Kind.valueOf(o.getString("kind")),
            title = o.getString("title"),
            text = o.getString("text"),
            chat = if (o.isNull("chat")) null else o.getString("chat"),
            time = o.getLong("time"),
            source = if (o.isNull("source")) null else o.optString("source"),
        )

        // Достаточно уникально для очереди и не повторяется после перезапуска процесса.
        private var lastId = 0L

        @Synchronized
        private fun newId(): Long {
            lastId = maxOf(lastId + 1, System.currentTimeMillis())
            return lastId
        }
    }
}
