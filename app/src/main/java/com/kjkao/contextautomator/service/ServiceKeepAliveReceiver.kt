package com.kjkao.contextautomator.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.kjkao.contextautomator.automation.AutomationStateStore

class ServiceKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_NOTIFICATION_DISMISSED && action != ACTION_HEALTH_CHECK) return

        restartServiceIfNeeded(context.applicationContext)
    }

    private fun restartServiceIfNeeded(appContext: Context) {
        val automationStateStore = AutomationStateStore(appContext)
        val automationEnabled = automationStateStore.isAutomationEnabled()
        val running = isServiceRunning(appContext)
        if (!automationEnabled) return
        if (running) {
            val restoreIntent = Intent(appContext, AutomationService::class.java).apply {
                action = AutomationService.ACTION_RESTORE_NOTIFICATION
            }
            runCatching { ContextCompat.startForegroundService(appContext, restoreIntent) }
            return
        }

        val serviceIntent = Intent(appContext, AutomationService::class.java)
        runCatching { ContextCompat.startForegroundService(appContext, serviceIntent) }
            .onFailure {
                scheduleFallbackRestart(appContext, serviceIntent)
            }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == AutomationService::class.java.name
        }
    }

    private fun scheduleFallbackRestart(context: Context, serviceIntent: Intent) {
        val pendingIntent = PendingIntent.getService(
            context,
            RESTART_REQUEST_CODE,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_DISMISSED = "com.kjkao.contextautomator.action.NOTIFICATION_DISMISSED"
        const val ACTION_HEALTH_CHECK = "com.kjkao.contextautomator.action.HEALTH_CHECK"
        private const val RESTART_REQUEST_CODE = 3001
        private const val HEALTH_CHECK_REQUEST_CODE = 3002
        private const val RESTART_DELAY_MS = 2_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L

        fun scheduleHealthCheck(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ServiceKeepAliveReceiver::class.java).apply {
                action = ACTION_HEALTH_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                HEALTH_CHECK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS,
                pendingIntent
            )
        }

        fun cancelHealthCheck(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ServiceKeepAliveReceiver::class.java).apply {
                action = ACTION_HEALTH_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                HEALTH_CHECK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
        }
    }
}
