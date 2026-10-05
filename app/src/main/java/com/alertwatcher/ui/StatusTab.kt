package com.alertwatcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alertwatcher.App
import com.alertwatcher.AppLog
import com.alertwatcher.AppState
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.alarm.AlarmEvent
import com.alertwatcher.config.ConfigStore
import com.alertwatcher.notify.TelegramNotificationListener
import com.alertwatcher.service.ConnectionMonitor
import com.alertwatcher.service.WatcherService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusTab(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val config by ConfigStore.config.collectAsStateWithLifecycle()
    val enabled by AppState.enabled.collectAsStateWithLifecycle()
    val listenerConnected by TelegramNotificationListener.connected.collectAsStateWithLifecycle()
    val serviceRunning by WatcherService.running.collectAsStateWithLifecycle()
    val connection by ConnectionMonitor.status.collectAsStateWithLifecycle()
    val alarm by AlarmController.state.collectAsStateWithLifecycle()

    // Разрешения перечитываем при каждом возврате в приложение (например, из настроек Android).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val checks = remember(refresh, config.telegramPackages) {
        SystemChecks.read(context, config.telegramPackages)
    }
    LaunchedEffect(checks, listenerConnected) {
        // Доступ выдан, но система нас не подключила (бывает после обновления) — просим переподключить.
        if (checks.listenerAccess && !listenerConnected) TelegramNotificationListener.requestRebind(context)
    }

    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    val ready = checks.listenerAccess && checks.postNotifications && checks.fullScreenIntent &&
        config.targetChats.isNotEmpty() && enabled

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (alarm.current != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сейчас звучит алерт", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = {
                        context.startActivity(
                            Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Открыть красный экран") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (ready) "✅ Работает" else "⚠ Нужна настройка",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Слежение включено", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { on ->
                        AppState.setEnabled(on)
                        if (on) WatcherService.start(context) else WatcherService.stop(context)
                        AppLog.log(if (on) "Слежение включено" else "Слежение выключено")
                    })
                }
                Text("Чаты: " + config.targetChats.joinToString(", ").ifEmpty { "не указаны" })
                Text("Фоновая служба: " + if (serviceRunning) "работает" else "не запущена")
                Text("Связь с Telegram: " + connectionText(config.connectionMonitor.enabled, connection))
            }
        }

        Text("Что нужно разрешить", style = MaterialTheme.typography.titleMedium)

        CheckRow(
            ok = checks.listenerAccess,
            title = "1. Доступ к уведомлениям",
            description = if (checks.listenerAccess && !listenerConnected) {
                "Доступ выдан, но служба не подключилась. Выключите и снова включите доступ."
            } else {
                "Главное разрешение: без него приложение не видит сообщения Telegram. " +
                    "Если переключатель серый («Ограниченная настройка») — нажмите «Сведения о приложении» → " +
                    "⋮ (вверху справа) → «Разрешить ограниченные настройки» и попробуйте снова."
            },
            action = "Открыть" to { SystemChecks.openListenerSettings(context) },
            secondary = "Сведения о приложении" to { SystemChecks.openAppDetails(context) },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            CheckRow(
                ok = checks.postNotifications,
                title = "2. Показ уведомлений",
                description = "Нужно для красного экрана на заблокированном телефоне и значка работы.",
                action = "Разрешить" to {
                    if (askedNotifications) {
                        SystemChecks.openAppNotificationSettings(context)
                    } else {
                        askedNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            CheckRow(
                ok = checks.fullScreenIntent,
                title = "3. Полноэкранные уведомления",
                description = "Чтобы красный экран сам открывался поверх экрана блокировки.",
                action = "Открыть" to { SystemChecks.openFullScreenIntentSettings(context) },
            )
        }
        CheckRow(
            ok = checks.batteryUnrestricted,
            title = "4. Без ограничений батареи",
            description = "Чтобы Android не усыплял приложение. На Xiaomi/Huawei/Samsung также " +
                "включите автозапуск и режим «Без ограничений» в настройках батареи для приложения.",
            action = "Разрешить" to { SystemChecks.requestBatteryUnrestricted(context) },
        )
        CheckRow(
            ok = checks.overlay,
            optional = true,
            title = "5. Поверх других приложений (необязательно)",
            description = "Если включить, красный экран откроется сразу, даже когда телефоном " +
                "пользуются. Без этого в такой момент будет всплывающее уведомление (звук всё равно играет).",
            action = "Открыть" to { SystemChecks.openOverlaySettings(context) },
        )
        CheckRow(
            ok = checks.installedTelegram.isNotEmpty(),
            title = "6. Telegram установлен",
            description = if (checks.installedTelegram.isEmpty()) {
                "Не найден ни один из пакетов: ${config.telegramPackages.joinToString()}"
            } else {
                "Найден: ${checks.installedTelegram.joinToString()}. В Telegram у нужного чата должны быть " +
                    "включены уведомления (можно без звука), а в настройках уведомлений — «Показывать текст»."
            },
            action = null,
        )
        CheckRow(
            ok = config.targetChats.isNotEmpty(),
            title = "7. Указан чат для слежения",
            description = "Точное название чата, как в Telegram. Удобнее выбрать из списка замеченных чатов на вкладке «Настройки».",
            action = "Настройки" to onOpenSettings,
        )

        HorizontalDivider()
        Text("Проверка", style = MaterialTheme.typography.titleMedium)
        TestButtons(context)
    }
}

@Composable
private fun TestButtons(context: Context) {
    val scope = rememberCoroutineScope()
    var checkingConnection by remember { mutableStateOf(false) }

    Button(onClick = { AlarmController.trigger(context, AlarmEvent.test()) }, Modifier.fillMaxWidth()) {
        Text("Тестовый алерт сейчас")
    }
    OutlinedButton(
        onClick = {
            Toast.makeText(context, "Через 15 секунд — заблокируйте экран", Toast.LENGTH_LONG).show()
            App.scope.launch {
                delay(15_000)
                AlarmController.trigger(context, AlarmEvent.test())
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Тестовый алерт через 15 с (проверка на заблокированном экране)") }
    OutlinedButton(
        enabled = !checkingConnection,
        onClick = {
            checkingConnection = true
            scope.launch {
                val ok = ConnectionMonitor.check(context, ConfigStore.config.value.connectionMonitor)
                checkingConnection = false
                val msg = if (ok) "Связь с Telegram есть" else "Нет связи с ${ConfigStore.config.value.connectionMonitor.checkUrl}"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (checkingConnection) "Проверяю связь…" else "Проверить связь с Telegram") }
}

@Composable
private fun CheckRow(
    ok: Boolean,
    title: String,
    description: String,
    action: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
    optional: Boolean = false,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(
                when {
                    ok -> "✅"
                    optional -> "➖"
                    else -> "❌"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
                if (!ok && (action != null || secondary != null)) {
                    Row {
                        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                        secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                    }
                }
            }
        }
    }
}

private fun connectionText(enabled: Boolean, s: ConnectionMonitor.Status): String {
    if (!enabled) return "мониторинг выключен"
    val time = if (s.lastCheckAt > 0) {
        " (проверено в " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.lastCheckAt)) + ")"
    } else ""
    return when (s.ok) {
        true -> "OK$time"
        false -> "НЕТ ОТВЕТА, неудач подряд: ${s.fails}$time"
        null -> "ещё не проверялась"
    }
}
