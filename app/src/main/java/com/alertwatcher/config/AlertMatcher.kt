package com.alertwatcher.config

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Аналог is_alert() из watcher.py: сначала ignore_patterns, потом alert_patterns. */
class AlertMatcher private constructor(
    private val alert: List<Pattern>,
    private val ignore: List<Pattern>,
) {
    sealed interface Verdict {
        data class Alert(val pattern: String) : Verdict
        data class Ignored(val pattern: String) : Verdict
        data object NoMatch : Verdict
    }

    fun check(text: String): Verdict {
        if (text.isEmpty()) return Verdict.NoMatch
        ignore.firstOrNull { it.matcher(text).find() }?.let { return Verdict.Ignored(it.pattern()) }
        alert.firstOrNull { it.matcher(text).find() }?.let { return Verdict.Alert(it.pattern()) }
        return Verdict.NoMatch
    }

    companion object {
        // re.IGNORECASE в Python для str работает и для кириллицы — в Java для этого нужен UNICODE_CASE.
        private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

        /** Собирает матчер; паттерны с ошибками пропускаются (их видно через [validate]). */
        fun build(config: AppConfig): AlertMatcher = AlertMatcher(
            alert = config.alertPatterns.mapNotNull { compileOrNull(it) },
            ignore = config.ignorePatterns.mapNotNull { compileOrNull(it) },
        )

        /** Ошибки компиляции паттернов — как compile_patterns() в config_loader.py. */
        fun validate(patterns: List<String>): List<String> = patterns.mapNotNull { p ->
            try {
                Pattern.compile(fromPython(p), FLAGS)
                null
            } catch (e: PatternSyntaxException) {
                "Ошибка в паттерне «$p»: ${e.description}"
            }
        }

        private fun compileOrNull(p: String): Pattern? = try {
            Pattern.compile(fromPython(p), FLAGS)
        } catch (e: PatternSyntaxException) {
            null
        }

        /** Синтаксис именованных групп Python (?P<name>…) / (?P=name) → Java. */
        private fun fromPython(p: String): String = p
            .replace("(?P<", "(?<")
            .replace(Regex("""\(\?P=(\w+)\)""")) { "\\k<${it.groupValues[1]}>" }
    }
}
