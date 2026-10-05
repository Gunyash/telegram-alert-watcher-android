package com.alertwatcher.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alertwatcher.alarm.AlarmController
import com.alertwatcher.alarm.AlarmEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Красный экран на весь экран (аналог окна tkinter из alarm.py).
 * Показывается поверх экрана блокировки, будит экран и не закрывается
 * кнопкой «Назад» — только кнопкой «Подтвердить».
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Белые значки строки состояния на красном фоне.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Специально ничего: закрыть можно только подтверждением.
            }
        })

        setContent {
            val state by AlarmController.state.collectAsState()
            val current = state.current
            LaunchedEffect(current) {
                if (current == null) finish()
            }
            if (current != null) {
                AlarmScreen(
                    event = current,
                    queued = state.queued.size,
                    onConfirm = { AlarmController.confirmCurrent(this) },
                    onConfirmAll = { AlarmController.confirmAll(this) },
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    event: AlarmEvent,
    queued: Int,
    onConfirm: () -> Unit,
    onConfirmAll: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "blink")
    val background by transition.animateColor(
        initialValue = Color(0xFFD50000),
        targetValue = Color(0xFF8E0000),
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "background",
    )
    val time = remember(event.time) {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(event.time))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = event.title,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 40.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        event.chat?.let {
            Text("Чат: $it", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = event.text.take(3000),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 28.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        if (queued > 0) {
            Text("Ещё алертов в очереди: $queued", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFFD50000),
            ),
        ) {
            Text("ПОДТВЕРДИТЬ", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        if (queued > 0) {
            TextButton(onClick = onConfirmAll) {
                Text("Подтвердить все (${queued + 1})", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}
