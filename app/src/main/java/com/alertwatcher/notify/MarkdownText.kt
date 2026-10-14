package com.alertwatcher.notify

/**
 * Mattermost кладёт в уведомление текст сообщения как есть, в Markdown, а Zabbix
 * экранирует в нём спецсимволы: вместо «#ROLF_PT_Core» приходит «#ROLF\_PT\_Core».
 * Telegram же показывает уже отрендеренный текст. Чтобы одни и те же паттерны
 * работали для обоих мессенджеров, у текста из Mattermost убираем экранирование.
 */
object MarkdownText {
    // Обратный слэш перед любым из символов, которые Markdown позволяет экранировать.
    private val ESCAPED = Regex("""\\([\\`*_{}\[\]()#+\-.!|~<>])""")

    /** «#ROLF\_PT\_Core» → «#ROLF_PT_Core»; «\\» → «\». */
    fun unescape(text: String): String = text.replace(ESCAPED, "$1")
}
