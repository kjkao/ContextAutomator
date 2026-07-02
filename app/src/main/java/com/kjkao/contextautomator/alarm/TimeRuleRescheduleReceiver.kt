package com.kjkao.contextautomator.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kjkao.contextautomator.ContextAutomatorApp
import com.kjkao.contextautomator.service.ServiceKeepAliveReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimeRuleRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val app = context.applicationContext as ContextAutomatorApp
                val allRules = app.repository.getAllRules()
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    // After reboot, automation remains off until user manually starts service again.
                    app.automationStateStore.setAutomationEnabled(false)
                    app.timeRuleAlarmScheduler.cancelAllTimeRules(allRules)
                    ServiceKeepAliveReceiver.cancelHealthCheck(context)
                    return@runCatching
                }
                if (app.automationStateStore.isAutomationEnabled()) {
                    app.timeRuleAlarmScheduler.syncAllRules(allRules)
                } else {
                    app.timeRuleAlarmScheduler.cancelAllTimeRules(allRules)
                }
            }
            pendingResult.finish()
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
