package com.kjkao.contextautomator.audio

import android.content.Context
import android.media.AudioManager
import android.app.NotificationManager
import android.provider.Settings
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.domain.model.RingerProfile

class RingerController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun apply(profile: RingerProfile): Boolean {
        return applyRingerMode(profile.modeValue)
    }

    fun isActionAlreadyApplied(actionType: String, actionValue: Int): Boolean {
        return when (actionType) {
            ActionType.RINGER_MODE.name -> audioManager.ringerMode == actionValue
            ActionType.RING_VOLUME.name -> {
                val target = getTargetStreamVolume(AudioManager.STREAM_RING, actionValue)
                audioManager.getStreamVolume(AudioManager.STREAM_RING) == target
            }
            ActionType.MEDIA_VOLUME.name -> {
                val target = getTargetStreamVolume(AudioManager.STREAM_MUSIC, actionValue)
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == target
            }
            ActionType.SCREEN_BRIGHTNESS.name -> runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS) == actionValue.coerceIn(0, 255)
            }.getOrDefault(false)
            ActionType.SCREEN_TIMEOUT.name -> runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) == actionValue.coerceIn(5, 1800) * 1000
            }.getOrDefault(false)
            else -> false
        }
    }

    fun applyRingerMode(modeValue: Int): Boolean {
        if (!hasNotificationPolicyAccess()) return false
        return try {
            audioManager.ringerMode = modeValue
            // When setting to SILENT mode, also disable vibration to ensure truly silent
            if (modeValue == AudioManager.RINGER_MODE_SILENT) {
                // STREAM_VIBRATION = 7
                try {
                    audioManager.setStreamVolume(7, 0, 0)
                } catch (_: Exception) {
                    // Fallback if STREAM_VIBRATION is not supported
                }
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun hasNotificationPolicyAccess(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun applyRingVolumePercent(percent: Int) {
        val target = getTargetStreamVolume(AudioManager.STREAM_RING, percent)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
    }

    fun applyMediaVolumePercent(percent: Int) {
        val target = getTargetStreamVolume(AudioManager.STREAM_MUSIC, percent)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun applyScreenBrightness(brightness0To255: Int): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        val target = brightness0To255.coerceIn(0, 255)
        Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
    }

    fun applyScreenTimeout(timeoutSeconds: Int): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        val seconds = timeoutSeconds.coerceIn(5, 1800)
        return Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
    }

    private fun getTargetStreamVolume(streamType: Int, percent: Int): Int {
        val max = audioManager.getStreamMaxVolume(streamType)
        val clamped = percent.coerceIn(0, 100)
        return ((clamped / 100f) * max).toInt().coerceIn(0, max)
    }
}

