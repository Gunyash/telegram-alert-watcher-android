package com.alertwatcher.config

import android.content.Context
import com.alertwatcher.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Хранит настройки в files/config.json (тот же формат, что и экспорт). */
object ConfigStore {
    private lateinit var file: File
    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    @Volatile
    private var matcherCache: Pair<AppConfig, AlertMatcher>? = null

    fun init(context: Context) {
        file = File(context.filesDir, "config.json")
        if (file.exists()) {
            try {
                val (cfg, _) = AppConfig.fromJson(file.readText())
                _config.value = cfg
            } catch (e: Exception) {
                AppLog.log("Не удалось прочитать сохранённые настройки, взяты стандартные: ${e.message}")
            }
        }
    }

    fun save(config: AppConfig) {
        file.writeText(config.toJsonText())
        _config.value = config
    }

    /** Матчер для текущего конфига; пересобирается только при изменении настроек. */
    fun matcher(): AlertMatcher {
        val cfg = _config.value
        matcherCache?.let { (c, m) -> if (c === cfg) return m }
        return AlertMatcher.build(cfg).also { matcherCache = cfg to it }
    }
}
