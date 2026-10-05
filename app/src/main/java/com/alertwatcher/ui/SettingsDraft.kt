package com.alertwatcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.alertwatcher.config.AlertMatcher
import com.alertwatcher.config.AppConfig
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.config.ConnectionMonitorConfig

/**
 * Черновик настроек на экране «Настройки». В ConfigStore попадает только по
 * кнопке «Сохранить» и только если всё корректно (паттерны компилируются и т.п.).
 * ViewModel — чтобы правки не терялись при повороте экрана.
 */
class SettingsDraft : ViewModel() {
    var chats by mutableStateOf("")
    var alertPatterns by mutableStateOf("")
    var ignorePatterns by mutableStateOf("")
    var monitorEnabled by mutableStateOf(true)
    var checkInterval by mutableStateOf("")
    var checkTimeout by mutableStateOf("")
    var failThreshold by mutableStateOf("")
    var realertInterval by mutableStateOf("")
    var checkUrl by mutableStateOf("")
    var keepCpuAwake by mutableStateOf(false)
    var maxVolume by mutableStateOf(true)
    var vibrate by mutableStateOf(true)
    var soundUri by mutableStateOf<String?>(null)
    var packages by mutableStateOf("")
    var errors by mutableStateOf<List<String>>(emptyList())
    var testText by mutableStateOf("")

    init {
        load(ConfigStore.config.value)
    }

    fun load(cfg: AppConfig) {
        chats = cfg.targetChats.joinToString("\n")
        alertPatterns = cfg.alertPatterns.joinToString("\n")
        ignorePatterns = cfg.ignorePatterns.joinToString("\n")
        val cm = cfg.connectionMonitor
        monitorEnabled = cm.enabled
        checkInterval = cm.checkIntervalSec.toString()
        checkTimeout = cm.checkTimeoutSec.toString()
        failThreshold = cm.failThreshold.toString()
        realertInterval = cm.realertIntervalSec.toString()
        checkUrl = cm.checkUrl
        keepCpuAwake = cm.keepCpuAwake
        maxVolume = cfg.maxVolume
        vibrate = cfg.vibrate
        soundUri = cfg.soundUri
        packages = cfg.telegramPackages.joinToString("\n")
        errors = emptyList()
    }

    /** Конфиг из полей формы или null + список ошибок. */
    fun build(): Pair<AppConfig?, List<String>> {
        val errs = mutableListOf<String>()
        fun number(value: String, name: String): Int =
            value.trim().toIntOrNull() ?: run { errs += "$name: нужно целое число"; 0 }

        val cm = ConnectionMonitorConfig(
            enabled = monitorEnabled,
            checkIntervalSec = number(checkInterval, "check_interval_sec"),
            checkTimeoutSec = number(checkTimeout, "check_timeout_sec"),
            failThreshold = number(failThreshold, "fail_threshold"),
            realertIntervalSec = number(realertInterval, "realert_interval_sec"),
            checkUrl = checkUrl.trim(),
            keepCpuAwake = keepCpuAwake,
        )
        if (errs.isEmpty()) errs += cm.validate()

        val cfg = AppConfig(
            targetChats = lines(chats),
            alertPatterns = lines(alertPatterns),
            ignorePatterns = lines(ignorePatterns),
            connectionMonitor = cm,
            soundUri = soundUri,
            maxVolume = maxVolume,
            vibrate = vibrate,
            telegramPackages = lines(packages),
        )
        errs += AlertMatcher.validate(cfg.alertPatterns)
        errs += AlertMatcher.validate(cfg.ignorePatterns)
        if (cfg.alertPatterns.isEmpty()) errs += "alert_patterns пуст — алерты не будут срабатывать"
        if (cfg.telegramPackages.isEmpty()) errs += "Список пакетов Telegram пуст"
        return (if (errs.isEmpty()) cfg else null) to errs
    }

    /** true — сохранено. */
    fun save(): Boolean {
        val (cfg, errs) = build()
        errors = errs
        if (cfg == null) return false
        ConfigStore.save(cfg)
        return true
    }

    fun hasChanges(): Boolean = build().first != ConfigStore.config.value

    fun addChat(name: String) {
        if (lines(chats).any { AppConfig.normalizeChatName(it) == AppConfig.normalizeChatName(name) }) return
        chats = (lines(chats) + name).joinToString("\n")
    }

    /** Для поля «Проверить текст»: матчер по НЕсохранённым паттернам из формы. */
    fun testMatcher(): AlertMatcher = AlertMatcher.build(
        AppConfig(alertPatterns = lines(alertPatterns), ignorePatterns = lines(ignorePatterns))
    )

    private fun lines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
