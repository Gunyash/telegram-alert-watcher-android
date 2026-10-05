package com.alertwatcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alertwatcher.AppLog

/** Журнал событий — то, что на ПК печаталось в консоль. Новые записи сверху. */
@Composable
fun LogTab() {
    val context = LocalContext.current
    val lines by AppLog.lines.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Alert Watcher log", lines.joinToString("\n")))
                Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Копировать") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Очистить") }
        }
        if (lines.isEmpty()) {
            Text("Журнал пуст", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines.asReversed()) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}
