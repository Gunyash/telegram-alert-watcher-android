package com.alertwatcher.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alertwatcher.notify.TelegramNotificationListener

/** Проверки системных разрешений и переходы в нужные экраны настроек Android. */
object SystemChecks {

    data class Snapshot(
        val listenerAccess: Boolean,
        val postNotifications: Boolean,
        val fullScreenIntent: Boolean,
        val batteryUnrestricted: Boolean,
        val overlay: Boolean,
        val installedTelegram: List<String>,
    )

    fun read(context: Context, telegramPackages: List<String>) = Snapshot(
        listenerAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        postNotifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        fullScreenIntent = Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        overlay = Settings.canDrawOverlays(context),
        installedTelegram = telegramPackages.filter { isInstalled(context, it) },
    )

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    fun openListenerSettings(context: Context) {
        val component = ComponentName(context, TelegramNotificationListener::class.java)
        val detailed = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else null
        open(context, detailed, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Настройки уведомлений приложения [pkg] (по умолчанию — нашего, но можно и Telegram). */
    fun openAppNotificationSettings(context: Context, pkg: String = context.packageName) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

    fun launchApp(context: Context, pkg: String) =
        open(context, context.packageManager.getLaunchIntentForPackage(pkg), null)

    /** Страница приложения в Google Play (или на сайте Play, если магазина нет). */
    fun openStore(context: Context, pkg: String) = open(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
    )

    fun openFullScreenIntentSettings(context: Context) = open(
        context,
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
        } else null,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    fun requestBatteryUnrestricted(context: Context) = open(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun openOverlaySettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    fun openAppDetails(context: Context) = open(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
        null,
    )

    private fun open(context: Context, primary: Intent?, fallback: Intent?) {
        for (intent in listOfNotNull(primary, fallback)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /** Имя файла по content:// (для показа выбранного звука). */
    fun displayName(context: Context, uri: String): String = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri
    } catch (e: Exception) {
        "недоступен ($uri)"
    }
}
