package com.alertwatcher.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alertwatcher.AppState
import com.alertwatcher.config.AlertMatcher
import com.alertwatcher.config.AppConfig
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.config.Source

@Composable
fun SettingsTab(draft: SettingsDraft = viewModel()) {
    val context = LocalContext.current
    val saved by ConfigStore.config.collectAsStateWithLifecycle()
    val mattermostServer by AppState.mattermostServer.collectAsStateWithLifecycle()
    var dialogText by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        dialogText = try {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val (cfg, warnings) = AppConfig.fromJson(text, base = draft.build().first ?: saved)
            draft.load(cfg)
            "Настройки загружены в форму. Проверьте их и нажмите «Сохранить»." +
                warnings.joinToString("") { "\n\n• $it" }
        } catch (e: Exception) {
            "Не удалось прочитать файл: ${e.message}"
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cfg = draft.build().first ?: saved
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(cfg.toJsonText().toByteArray()) }
            Toast.makeText(context, "Сохранено в файл", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            dialogText = "Не удалось записать файл: ${e.message}"
        }
    }
    val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // Чтобы доступ к файлу сохранился после перезапуска телефона.
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        draft.soundUri = uri.toString()
    }

    val changed = draft.hasChanges()
    val mono = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = changed, onClick = {
                if (draft.save()) Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
            }) { Text("Сохранить") }
            OutlinedButton(enabled = changed, onClick = { draft.load(saved) }) { Text("Отменить") }
            if (changed) Text("есть изменения", style = MaterialTheme.typography.bodySmall)
        }
        if (draft.errors.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Не сохранено:", fontWeight = FontWeight.Bold)
                    draft.errors.forEach { Text("• $it") }
                }
            }
        }

        Section("Чаты Telegram")
        SwitchRow("Следить за Telegram", draft.telegramEnabled) { draft.telegramEnabled = it }
        if (!draft.telegramEnabled) OffHint("Telegram")
        TargetsEditor(
            source = Source.TELEGRAM,
            value = draft.chats,
            onValueChange = { draft.chats = it },
            label = "Названия чатов, по одному в строке",
            hint = "Точно как в шапке чата в Telegram (регистр не важен)",
            emptyHint = "Замеченных чатов пока нет. Выдайте доступ к уведомлениям и дождитесь любого " +
                "сообщения в Telegram — чат появится здесь.",
            normalize = AppConfig::normalizeChatName,
            onAdd = { draft.addChat(Source.TELEGRAM, it) },
        )

        Section("Каналы Mattermost")
        SwitchRow("Следить за Mattermost", draft.mattermostEnabled) { draft.mattermostEnabled = it }
        if (!draft.mattermostEnabled) OffHint("Mattermost")
        TargetsEditor(
            source = Source.MATTERMOST,
            value = draft.mattermostChannels,
            onValueChange = { draft.mattermostChannels = it },
            label = "Названия каналов, по одному в строке",
            hint = "Как в списке каналов, например zabbix (регистр и «~» не важны)",
            emptyHint = "Замеченных каналов пока нет. В Mattermost у канала должны быть включены " +
                "мобильные уведомления обо всех сообщениях — после первого сообщения канал появится здесь.",
            normalize = AppConfig::normalizeMattermostChannel,
            onAdd = { draft.addChat(Source.MATTERMOST, it) },
        )

        Section("Паттерны")
        Text(
            "Общие для Telegram и Mattermost. " +
                "Регулярные выражения, по одному в строке, регистр не важен. Сначала проверяются " +
                "ignore_patterns. В отличие от config.json, обратный слэш здесь пишется один раз: " +
                "\\bDOWN\\b, а не \\\\bDOWN\\\\b.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.alertPatterns,
            onValueChange = { draft.alertPatterns = it },
            label = { Text("alert_patterns — что считать алертом") },
            textStyle = mono,
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.ignorePatterns,
            onValueChange = { draft.ignorePatterns = it },
            label = { Text("ignore_patterns — что игнорировать") },
            textStyle = mono,
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.testText,
            onValueChange = { draft.testText = it },
            label = { Text("Проверить текст сообщения") },
            supportingText = { Text("Вставьте текст алерта — увидите, сработает ли он") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.testText.isNotBlank()) {
            val verdict = remember(draft.testText, draft.alertPatterns, draft.ignorePatterns) {
                draft.testMatcher().check(draft.testText)
            }
            Text(
                when (verdict) {
                    is AlertMatcher.Verdict.Alert -> "🔴 АЛЕРТ — совпал паттерн ${verdict.pattern}"
                    is AlertMatcher.Verdict.Ignored -> "🟢 Игнорируется — совпал ${verdict.pattern}"
                    AlertMatcher.Verdict.NoMatch -> "⚪ Не алерт — ни один паттерн не совпал"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        Section("Звук и вибрация")
        Text(
            "Звук: " + (draft.soundUri?.let { SystemChecks.displayName(context, it) }
                ?: "встроенный (alarm_sound.mp3)")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { soundLauncher.launch(arrayOf("audio/*")) }) { Text("Выбрать файл") }
            if (draft.soundUri != null) {
                TextButton(onClick = { draft.soundUri = null }) { Text("Встроенный") }
            }
        }
        SwitchRow("Максимальная громкость будильника на время алерта", draft.maxVolume) { draft.maxVolume = it }
        SwitchRow("Вибрация", draft.vibrate) { draft.vibrate = it }
        Text(
            "Звук идёт через громкость будильника — он слышен даже в беззвучном режиме. " +
                "Проверить: вкладка «Статус» → «Тестовый алерт».",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Алерт о потере связи")
        SwitchRow("Включён", draft.monitorEnabled) { draft.monitorEnabled = it }
        NumberField("Проверять каждые, сек (check_interval_sec)", draft.checkInterval) { draft.checkInterval = it }
        NumberField("Ждать ответа, сек (check_timeout_sec)", draft.checkTimeout) { draft.checkTimeout = it }
        NumberField("Алерт после стольких неудач подряд (fail_threshold)", draft.failThreshold) { draft.failThreshold = it }
        NumberField("Повторять через, сек; 0 — не повторять (realert_interval_sec)", draft.realertInterval) {
            draft.realertInterval = it
        }
        OutlinedTextField(
            value = draft.checkUrl,
            onValueChange = { draft.checkUrl = it },
            label = { Text("Telegram: адрес для проверки (check_url)") },
            supportingText = { Text("Пусто — связь с Telegram не проверять") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.mattermostUrl,
            onValueChange = { draft.mattermostUrl = it },
            label = { Text("Mattermost: адрес сервера (mattermost_check_url)") },
            placeholder = { Text("https://mattermost.example.com") },
            supportingText = { Text("Пусто — связь с Mattermost не проверять") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        mattermostServer?.takeIf { it.trimEnd('/') != draft.mattermostUrl.trim().trimEnd('/') }?.let { url ->
            TextButton(onClick = { draft.mattermostUrl = url }) { Text("Подставить сервер из уведомлений: $url") }
        }
        SwitchRow("Не давать телефону засыпать (keep_cpu_awake)", draft.keepCpuAwake) { draft.keepCpuAwake = it }
        Text(
            "Без этого в глубоком сне (экран выключен, телефон не на зарядке и лежит неподвижно) " +
                "проверки связи приостанавливаются. Сообщения при этом всё равно приходят. " +
                "Включение заметно увеличит расход батареи.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Дополнительно")
        OutlinedTextField(
            value = draft.packages,
            onValueChange = { draft.packages = it },
            label = { Text("Пакеты приложений Telegram, по одному в строке") },
            textStyle = mono,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.mattermostPackages,
            onValueChange = { draft.mattermostPackages = it },
            label = { Text("Пакеты приложений Mattermost, по одному в строке") },
            supportingText = { Text("Если у вас корпоративная сборка Mattermost — допишите её пакет") },
            textStyle = mono,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Файл настроек")
        Text(
            "Можно загрузить config.json от версии для ПК — паттерны и настройки связи подтянутся. " +
                "Экспорт сохраняет текущие настройки в файл, чтобы передать коллеге.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Импорт config.json") }
            OutlinedButton(onClick = { exportLauncher.launch("config.json") }) { Text("Экспорт") }
        }
        TextButton(onClick = {
            // Чаты и каналы оставляем, остальное — как в исходном config.json.
            draft.load(
                AppConfig(
                    targetChats = saved.targetChats,
                    mattermostChannels = saved.mattermostChannels,
                    telegramEnabled = saved.telegramEnabled,
                    mattermostEnabled = saved.mattermostEnabled,
                )
            )
            Toast.makeText(context, "Стандартные значения в форме — нажмите «Сохранить»", Toast.LENGTH_LONG).show()
        }) { Text("Сбросить к стандартным") }
    }

    dialogText?.let { text ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            confirmButton = { TextButton(onClick = { dialogText = null }) { Text("OK") } },
            text = { Text(text) },
        )
    }
}

/** Поле со списком чатов/каналов и кнопками «+ замеченный чат». */
@Composable
private fun TargetsEditor(
    source: Source,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    emptyHint: String,
    normalize: (String) -> String,
    onAdd: (String) -> Unit,
) {
    val recent by AppState.recentChats(source).collectAsStateWithLifecycle()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    val current = value.split('\n').map(normalize).toSet()
    val suggestions = recent.filter { normalize(it) !in current }
    if (recent.isEmpty()) {
        Text(emptyHint, style = MaterialTheme.typography.bodySmall)
    } else if (suggestions.isNotEmpty()) {
        Text("Замеченные — нажмите, чтобы добавить:", style = MaterialTheme.typography.bodySmall)
        suggestions.forEach { name ->
            TextButton(onClick = { onAdd(name) }) { Text("+ $name") }
        }
    }
}

@Composable
private fun OffHint(name: String) {
    Text(
        "Выключено: сообщения $name не будят, связь с $name не проверяется. Список ниже сохранится.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
