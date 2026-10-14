package com.alertwatcher.config

/**
 * Приводит текст к единому виду перед проверкой паттернами.
 *
 * Одно и то же сообщение Zabbix в Telegram и Mattermost может отличаться деталями,
 * которых не видно глазами: где-то перенос строки, а где-то пробел, неразрывный
 * пробел вместо обычного, символ нулевой ширины. Регулярное выражение из-за такой
 * разницы не совпадает. Поэтому любые пробелы и переносы строк считаем одним
 * обычным пробелом, а невидимые символы убираем — и в сообщении, и в паттернах.
 */
object TextNormalizer {

    /**
     * Невидимые символы, которые просто удаляем. Соединитель U+200D не трогаем:
     * из него собраны составные эмодзи.
     */
    private val INVISIBLE = setOf(
        '​', '‌', '⁠', '﻿', '­', '᠎',  // нулевой ширины, мягкий перенос
        '‎', '‏', '‪', '‫', '‬', '‭', '‮',  // метки направления текста
        '⁦', '⁧', '⁨', '⁩',
    )

    /** Неразрывный дефис выглядит как обычный «-». */
    private const val NB_HYPHEN = '‑'

    /** Пробелы, которые выглядят как обычный: неразрывный, узкий и т.п. */
    private fun isOddSpace(c: Char) =
        c != ' ' && Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** Текст сообщения: пробелы и переносы строк подряд → один пробел, невидимые символы убраны. */
    fun forMatching(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            when {
                c in INVISIBLE -> Unit
                c.isWhitespace() || isOddSpace(c) -> pendingSpace = true
                else -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    sb.append(if (c == NB_HYPHEN) '-' else c)
                }
            }
        }
        return sb.toString()
    }

    /** Паттерн: особые пробелы → обычные, невидимые символы убраны, несколько пробелов подряд → один. */
    fun forPattern(pattern: String): String {
        val sb = StringBuilder(pattern.length)
        for (c in pattern) {
            if (c in INVISIBLE) continue
            val ch = when {
                isOddSpace(c) -> ' '
                c == NB_HYPHEN -> '-'
                else -> c
            }
            // «\ » — экранированный пробел, после него обычный пробел оставляем.
            if (ch == ' ' && sb.endsWith(' ') && !sb.endsWith("\\ ")) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Для журнала: какие необычные символы были в тексте (кроме обычных пробелов и
     * переносов строк), например «U+00A0×2, U+200B×1». null — ничего такого не было.
     */
    fun oddChars(text: String): String? {
        val counts = linkedMapOf<Char, Int>()
        for (c in text) {
            if (c in INVISIBLE || isOddSpace(c) || c == NB_HYPHEN || c == '\t' || c == '\r') {
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return null
        return counts.entries.joinToString(", ") { (c, n) -> "U+%04X×%d".format(c.code, n) }
    }

    /** Текст для журнала: переносы строк видны как «⏎». */
    fun forLog(text: String): String = text.replace("\r\n", "\n").replace("\n", " ⏎ ")
}
