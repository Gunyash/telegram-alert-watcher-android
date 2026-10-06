package com.alertwatcher.config

import org.json.JSONArray
import org.json.JSONObject

/** Откуда пришло уведомление. Паттерны общие, а чаты/каналы у каждого источника свои. */
enum class Source(val title: String) {
    TELEGRAM("Telegram"),
    MATTERMOST("Mattermost"),
}

/**
 * Настройки приложения. Формат JSON совместим с config.json от версии для ПК:
 * alert_patterns / ignore_patterns / connection_monitor читаются как есть.
 * Добавлены поля, которых на ПК не было (там они жили в secrets.json или
 * не были нужны): target_chats, mattermost_channels, *_packages, max_volume,
 * vibrate, sound_uri.
 */
data class AppConfig(
    /** Точные названия чатов Telegram, за которыми следим (как в шапке чата). */
    val targetChats: List<String> = emptyList(),
    /** Названия каналов Mattermost, за которыми следим (регистр не важен). */
    val mattermostChannels: List<String> = emptyList(),
    val alertPatterns: List<String> = DEFAULT_ALERT_PATTERNS,
    val ignorePatterns: List<String> = DEFAULT_IGNORE_PATTERNS,
    val connectionMonitor: ConnectionMonitorConfig = ConnectionMonitorConfig(),
    /** content:// звукового файла, выбранного пользователем. null — встроенный alarm_sound.mp3. */
    val soundUri: String? = null,
    /** На время алерта выкручивать громкость будильника на максимум. */
    val maxVolume: Boolean = true,
    val vibrate: Boolean = true,
    /** Пакеты приложений Telegram, уведомления которых читаем. */
    val telegramPackages: List<String> = DEFAULT_TELEGRAM_PACKAGES,
    /** Пакеты приложений Mattermost (у корпоративных сборок бывает свой). */
    val mattermostPackages: List<String> = DEFAULT_MATTERMOST_PACKAGES,
) {

    /** Источник по имени пакета приложения, null — уведомление не наше. */
    fun sourceOf(packageName: String): Source? = when (packageName) {
        in telegramPackages -> Source.TELEGRAM
        in mattermostPackages -> Source.MATTERMOST
        else -> null
    }

    fun hasTargets(): Boolean = targetChats.isNotEmpty() || mattermostChannels.isNotEmpty()

    fun isTracked(source: Source, title: String): Boolean = when (source) {
        Source.TELEGRAM -> isTrackedChat(title)
        Source.MATTERMOST -> isTrackedMattermostChannel(title)
    }

    /** Канал Mattermost: имя можно писать и как в списке каналов, и с «~», как в ссылке. */
    fun isTrackedMattermostChannel(title: String): Boolean {
        val t = normalizeMattermostChannel(title)
        return t.isNotEmpty() && mattermostChannels.any { normalizeMattermostChannel(it) == t }
    }

    /**
     * Подходит ли уведомление из чата с таким заголовком.
     * Учитываем, что Telegram пишет «Тема in Группа» для тем форума и
     * «Чат (3)» на старых Android, если непрочитанных чатов несколько.
     */
    fun isTrackedChat(chatTitle: String): Boolean {
        val title = normalizeChatName(chatTitle.replace(COUNTER_SUFFIX, ""))
        return targetChats.any { target ->
            val t = normalizeChatName(target)
            t.isNotEmpty() && (title == t || title.endsWith(" in $t"))
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("_comment", "Ключевые слова/паттерны, при которых сообщение считается алертом. Регистр не важен, можно использовать регулярки (экранировать обратный слэш как \\).")
        put("target_chats", JSONArray(targetChats))
        put("mattermost_channels", JSONArray(mattermostChannels))
        put("alert_patterns", JSONArray(alertPatterns))
        put("_comment_ignore", "Паттерны, при которых алерт НЕ должен срабатывать, даже если совпал alert_patterns выше. Проверяются ПЕРВЫМИ и имеют приоритет.")
        put("ignore_patterns", JSONArray(ignorePatterns))
        put("connection_monitor", connectionMonitor.toJson())
        put("max_volume", maxVolume)
        put("vibrate", vibrate)
        put("telegram_packages", JSONArray(telegramPackages))
        put("mattermost_packages", JSONArray(mattermostPackages))
        if (soundUri != null) put("sound_uri", soundUri)
    }

    /** JSON для файла: красивый отступ и без экранирования «/» (org.json пишет «\/»). */
    fun toJsonText(): String = toJson().toString(2).replace("\\/", "/")

    companion object {
        val DEFAULT_ALERT_PATTERNS = listOf(
            "#PROBLEM\\b",
            "🔴",
            "\\bALERT\\b",
            "\\bCRITICAL\\b",
            "\\bDOWN\\b",
            "\\bFAILED\\b",
        )

        val DEFAULT_IGNORE_PATTERNS = listOf(
            "(?=[\\s\\S]*#OK\\b)",
            "🟢",
            "(?=[\\s\\S]*Problem has been resolved)",
            "(?=[\\s\\S]*\\bRESOLVED\\b)",
        )

        val DEFAULT_TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",      // Telegram из Google Play
            "org.telegram.messenger.web",  // Telegram с сайта telegram.org
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram",   // Telegram X
        )

        val DEFAULT_MATTERMOST_PACKAGES = listOf(
            "com.mattermost.rn",      // Mattermost из Google Play
            "com.mattermost.rnbeta",  // бета-версия
        )

        private val COUNTER_SUFFIX = Regex("""\s\(\d+\)$""")

        fun normalizeChatName(name: String): String =
            name.trim().replace(Regex("""\s+"""), " ").lowercase()

        fun normalizeMattermostChannel(name: String): String =
            normalizeChatName(name).removePrefix("~").trim()

        /**
         * Разбирает JSON. Поля, которых нет, берутся из [base] — так можно
         * импортировать config.json с ПК, не теряя уже указанные чаты.
         * Возвращает конфиг и список предупреждений для пользователя.
         */
        fun fromJson(text: String, base: AppConfig = AppConfig()): Pair<AppConfig, List<String>> {
            val json = JSONObject(text)
            val warnings = mutableListOf<String>()

            var cfg = base
            val known = setOf(
                "target_chats", "target_chat", "alert_patterns", "ignore_patterns",
                "connection_monitor", "sound_path", "sound_uri", "max_volume",
                "vibrate", "telegram_packages", "mattermost_channels", "mattermost_packages",
            )
            json.keys().asSequence()
                .filter { !it.startsWith("_") && it !in known }
                .forEach { warnings += "Неизвестное поле «$it» — пропущено." }

            if (json.has("target_chats")) {
                cfg = cfg.copy(targetChats = json.getJSONArray("target_chats").toStringList())
            } else if (json.has("target_chat")) {
                val v = json.get("target_chat")
                if (v is String) {
                    cfg = cfg.copy(targetChats = listOf(v))
                } else {
                    warnings += "target_chat = $v — это ID чата из версии для ПК. На телефоне " +
                        "чат указывается названием (target_chats), оставлены текущие чаты."
                }
            }
            if (json.has("mattermost_channels")) {
                cfg = cfg.copy(mattermostChannels = json.getJSONArray("mattermost_channels").toStringList())
            }
            if (json.has("alert_patterns")) {
                cfg = cfg.copy(alertPatterns = json.getJSONArray("alert_patterns").toStringList())
            }
            if (json.has("ignore_patterns")) {
                cfg = cfg.copy(ignorePatterns = json.getJSONArray("ignore_patterns").toStringList())
            }
            if (json.has("connection_monitor")) {
                val (cm, w) = ConnectionMonitorConfig.fromJson(
                    json.getJSONObject("connection_monitor"), cfg.connectionMonitor
                )
                cfg = cfg.copy(connectionMonitor = cm)
                warnings += w
            }
            if (json.has("sound_path")) {
                warnings += "sound_path пропущен: на телефоне звук выбирается в настройках приложения."
            }
            if (json.has("sound_uri")) cfg = cfg.copy(soundUri = json.optString("sound_uri").ifBlank { null })
            if (json.has("max_volume")) cfg = cfg.copy(maxVolume = json.getBoolean("max_volume"))
            if (json.has("vibrate")) cfg = cfg.copy(vibrate = json.getBoolean("vibrate"))
            if (json.has("telegram_packages")) {
                cfg = cfg.copy(telegramPackages = json.getJSONArray("telegram_packages").toStringList())
            }
            if (json.has("mattermost_packages")) {
                cfg = cfg.copy(mattermostPackages = json.getJSONArray("mattermost_packages").toStringList())
            }
            return cfg to warnings
        }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}

/** Мониторинг связи — те же поля и значения по умолчанию, что на ПК, плюс новые. */
data class ConnectionMonitorConfig(
    val enabled: Boolean = true,
    /** Как часто проверять связь. */
    val checkIntervalSec: Int = 15,
    /** Сколько ждать ответа на проверку. */
    val checkTimeoutSec: Int = 10,
    /** Сколько проверок подряд должно провалиться. */
    val failThreshold: Int = 3,
    /** Повторить алерт, если связи всё ещё нет (0 = не повторять). */
    val realertIntervalSec: Int = 600,
    /** Куда стучимся при проверке Telegram. Любой HTTP-ответ = связь есть. Пусто — не проверять. */
    val checkUrl: String = "https://api.telegram.org",
    /** Адрес сервера Mattermost для проверки. Пусто — не проверять. */
    val mattermostUrl: String = "",
    /** Не давать процессору засыпать: проверки идут и в глубоком сне, но больше расход батареи. */
    val keepCpuAwake: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("check_interval_sec", checkIntervalSec)
        put("check_timeout_sec", checkTimeoutSec)
        put("fail_threshold", failThreshold)
        put("realert_interval_sec", realertIntervalSec)
        put("check_url", checkUrl)
        put("mattermost_check_url", mattermostUrl)
        put("keep_cpu_awake", keepCpuAwake)
    }

    /** Ошибки в значениях (те же правила, что в config_loader.py). */
    fun validate(): List<String> = buildList {
        if (checkIntervalSec <= 0) add("check_interval_sec должно быть числом > 0")
        if (checkTimeoutSec <= 0) add("check_timeout_sec должно быть числом > 0")
        if (failThreshold <= 0) add("fail_threshold должно быть числом > 0")
        if (realertIntervalSec < 0) add("realert_interval_sec должно быть числом >= 0")
        if (checkUrl.isNotEmpty() && !checkUrl.startsWith("https://") && !checkUrl.startsWith("http://")) {
            add("check_url должен начинаться с https://")
        }
        if (mattermostUrl.isNotEmpty() && !mattermostUrl.startsWith("https://") && !mattermostUrl.startsWith("http://")) {
            add("mattermost_check_url должен начинаться с https://")
        }
    }

    companion object {
        fun fromJson(json: JSONObject, base: ConnectionMonitorConfig): Pair<ConnectionMonitorConfig, List<String>> {
            val warnings = mutableListOf<String>()
            val known = setOf(
                "enabled", "check_interval_sec", "check_timeout_sec", "fail_threshold",
                "realert_interval_sec", "check_url", "mattermost_check_url", "keep_cpu_awake",
            )
            json.keys().asSequence()
                .filter { !it.startsWith("_") && it !in known }
                .forEach { warnings += "Неизвестное поле connection_monitor.$it — пропущено." }

            // На ПК секунды могли быть дробными — округляем до целых.
            fun int(key: String, def: Int) = if (json.has(key)) Math.round(json.getDouble(key)).toInt() else def

            return ConnectionMonitorConfig(
                enabled = json.optBoolean("enabled", base.enabled),
                checkIntervalSec = int("check_interval_sec", base.checkIntervalSec),
                checkTimeoutSec = int("check_timeout_sec", base.checkTimeoutSec),
                failThreshold = int("fail_threshold", base.failThreshold),
                realertIntervalSec = int("realert_interval_sec", base.realertIntervalSec),
                checkUrl = json.optString("check_url", base.checkUrl),
                mattermostUrl = json.optString("mattermost_check_url", base.mattermostUrl),
                keepCpuAwake = json.optBoolean("keep_cpu_awake", base.keepCpuAwake),
            ) to warnings
        }
    }
}
