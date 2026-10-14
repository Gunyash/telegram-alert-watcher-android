package com.alertwatcher.notify

/**
 * Mattermost кладёт в уведомление текст сообщения как есть, в Markdown, а Zabbix
 * экранирует в нём спецсимволы: вместо «#my_host_01» приходит «#my\_host\_01».
 * Telegram же показывает уже отрендеренный текст. Чтобы одни и те же паттерны
 * работали для обоих мессенджеров, у текста из Mattermost убираем экранирование.
 */
object MarkdownText {
    // Обратный слэш перед любым из символов, которые Markdown позволяет экранировать.
    private val ESCAPED = Regex("""\\([\\`*_{}\[\]()#+\-.!|~<>])""")

    /** «#my\_host\_01» → «#my_host_01»; «\\» → «\». */
    fun unescape(text: String): String = text.replace(ESCAPED, "$1")
}
