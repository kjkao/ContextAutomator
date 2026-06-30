package com.kjkao.contextautomator.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kjkao.contextautomator.ContextAutomatorApp
import com.kjkao.contextautomator.service.AutomationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimeRuleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TimeRuleAlarmScheduler.ACTION_TIME_RULE_ALARM) return
        val ruleId = intent.getLongExtra(TimeRuleAlarmScheduler.EXTRA_RULE_ID, -1L)
        if (ruleId <= 0L) return

        val serviceIntent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_TIME_RULE_ALARM
            putExtra(AutomationService.EXTRA_RULE_ID, ruleId)
        }
        context.startForegroundService(serviceIntent)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val app = context.applicationContext as ContextAutomatorApp
                val repository = app.repository
                val scheduler = app.timeRuleAlarmScheduler
                val rule = repository.getRuleById(ruleId)
                if (rule != null) {
                    scheduler.scheduleRule(rule)
                } else {
                    scheduler.cancelRule(ruleId)
                }
            }
            pendingResult.finish()
        }
    }
}
