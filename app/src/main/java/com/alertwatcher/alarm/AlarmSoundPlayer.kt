package com.alertwatcher.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.alertwatcher.AppLog
import com.alertwatcher.R
import com.alertwatcher.config.AppConfig

/**
 * Аналог _play_sound_loop() из alarm.py. Звук идёт через поток БУДИЛЬНИКА:
 * он слышен и в беззвучном режиме, и при «Не беспокоить» (если там не
 * запрещены будильники). Только main-поток.
 */
object AlarmSoundPlayer {
    private var active = false
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedVolume: Int? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, config: AppConfig) {
        if (active) return
        active = true
        val audio = context.getSystemService(AudioManager::class.java)

        if (config.maxVolume) {
            try {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            } catch (e: Exception) {
                savedVolume = null
                AppLog.log("Не удалось выставить громкость: $e")
            }
        }

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
            .also { audio.requestAudioFocus(it) }

        player = createPlayer(context, config.soundUri)
        if (config.vibrate) startVibration(context)
    }

    fun stop(context: Context) {
        if (!active) return
        active = false
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null

        val audio = context.getSystemService(AudioManager::class.java)
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        savedVolume?.let {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
            } catch (_: Exception) {
            }
        }
        savedVolume = null
    }

    /** Пробуем по очереди: выбранный файл → встроенный alarm_sound.mp3 → системный будильник. */
    private fun createPlayer(context: Context, soundUri: String?): MediaPlayer? {
        val sources = buildList<Pair<String, (MediaPlayer) -> Unit>> {
            if (soundUri != null) {
                add("выбранный файл" to { mp -> mp.setDataSource(context, Uri.parse(soundUri)) })
            }
            add("встроенный звук" to { mp ->
                context.resources.openRawResourceFd(R.raw.alarm_sound).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            })
            add("системный будильник" to { mp ->
                mp.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            })
        }
        for ((name, setSource) in sources) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attributes)
                setSource(mp)
                mp.isLooping = true
                mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                mp.start()
                return mp
            } catch (e: Exception) {
                mp.release()
                AppLog.log("Не удалось воспроизвести $name: $e")
            }
        }
        return null
    }

    private fun startVibration(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, attributes)
        }
        vibrator = v
    }
}
