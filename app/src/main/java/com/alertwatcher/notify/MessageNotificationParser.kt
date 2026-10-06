package com.alertwatcher.notify

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat

/**
 * Достаёт из уведомления мессенджера название чата/канала и сообщения.
 *
 * Telegram для каждого чата показывает отдельное уведомление в стиле
 * MessagingStyle: заголовок — название чата, внутри — все непрочитанные
 * сообщения с полным текстом и временем отправки.
 * Mattermost показывает отдельное уведомление на каждое сообщение, тоже в
 * MessagingStyle (заголовок — название канала) и кладёт в extras post_id и
 * server_url.
 * Сводные уведомления групп пропускаем: у Telegram текст там обрезан, а у
 * Mattermost это копия обычного уведомления.
 */
object MessageNotificationParser {

    data class Message(val text: String, val timestamp: Long, val sender: String)

    data class Parsed(
        /** Название чата/канала для отображения/сопоставления. */
        val chatTitle: String,
        val messages: List<Message>,
        /** ID сообщения на сервере (Mattermost), не меняется при перерисовке уведомления. */
        val postId: String? = null,
        /** Адрес сервера (Mattermost) — подсказка для проверки связи. */
        val serverUrl: String? = null,
    )

    fun parse(sbn: StatusBarNotification): Parsed? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: return null

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()
        if (title.isNullOrEmpty()) return null

        val messages = fromCompatStyle(n)
            ?: fromRawMessages(extras)
            ?: fromPlainText(n, extras)
        if (messages.isEmpty()) return null
        return Parsed(
            chatTitle = title,
            messages = messages,
            postId = extras.getString("post_id")?.takeIf { it.isNotBlank() },
            serverUrl = extras.getString("server_url")?.takeIf { it.isNotBlank() },
        )
    }

    private fun fromCompatStyle(n: Notification): List<Message>? = try {
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ?.messages
            ?.mapNotNull { m ->
                val text = m.text?.toString() ?: return@mapNotNull null
                Message(text, m.timestamp, m.person?.name?.toString().orEmpty())
            }
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Запасной вариант: разбираем android.messages вручную. */
    private fun fromRawMessages(extras: Bundle): List<Message>? {
        val arr = BundleCompat.getParcelableArray(extras, Notification.EXTRA_MESSAGES, Parcelable::class.java)
            ?: return null
        return arr.filterIsInstance<Bundle>().mapNotNull { b ->
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
                ?: if (Build.VERSION.SDK_INT >= 28) {
                    BundleCompat.getParcelable(b, "sender_person", Person::class.java)?.name?.toString()
                } else null
            Message(text, b.getLong("time"), sender.orEmpty())
        }.takeIf { it.isNotEmpty() }
    }

    /** Последний вариант: обычный текст уведомления. */
    private fun fromPlainText(n: Notification, extras: Bundle): List<Message> {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines.map { Message(it.toString(), n.`when`, "") }
        }
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: return emptyList()
        return listOf(Message(text, n.`when`, ""))
    }
}
