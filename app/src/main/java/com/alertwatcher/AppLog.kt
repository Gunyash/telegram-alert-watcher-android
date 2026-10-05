package com.alertwatcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал событий (аналог print/log() в watcher.py). Видно во вкладке «Журнал»,
 * пишется в файл, чтобы утром можно было понять, что происходило ночью.
 */
object AppLog {
    private const val TAG = "AlertWatcher"
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 512 * 1024

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    /** Строки журнала, старые сверху. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val f = File(context.filesDir, "log.txt")
        file = f
        io.execute {
            if (f.exists()) {
                val old = f.readLines().takeLast(MAX_LINES)
                _lines.update { (old + it).takeLast(MAX_LINES) }
            }
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = synchronized(timeFormat) { "[${timeFormat.format(Date())}] $message" }
        _lines.update { (it + line).takeLast(MAX_LINES) }
        val f = file ?: return
        io.execute {
            try {
                f.appendText(line + "\n")
                if (f.length() > MAX_FILE_BYTES) {
                    f.writeText(f.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось записать журнал", e)
            }
        }
    }

    fun clear() {
        _lines.value = emptyList()
        val f = file ?: return
        io.execute { f.delete() }
    }
}
