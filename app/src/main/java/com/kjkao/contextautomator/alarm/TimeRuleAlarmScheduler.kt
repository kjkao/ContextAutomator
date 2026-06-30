package com.kjkao.contextautomator.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.domain.model.TriggerType
import java.time.LocalDateTime
import java.time.ZoneId

class TimeRuleAlarmScheduler(
    private val context: Context
) {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun syncAllRules(rules: List<RuleEntity>) {
        rules.forEach { rule ->
            if (rule.triggerType == TriggerType.TIME_RANGE.name && rule.enabled) {
                scheduleRule(rule)
            } else {
                cancelRule(rule.id)
            }
        }
    }

    fun syncEnabledTimeRules(rules: List<RuleEntity>) {
        rules.forEach { scheduleRule(it) }
    }

    fun cancelAllTimeRules(rules: List<RuleEntity>) {
        rules
            .asSequence()
            .filter { it.triggerType == TriggerType.TIME_RANGE.name }
            .forEach { cancelRule(it.id) }
    }

    fun scheduleRule(rule: RuleEntity) {
        if (!rule.enabled || rule.triggerType != TriggerType.TIME_RANGE.name) {
            cancelRule(rule.id)
            return
        }

        val minuteOfDay = rule.timeStartMinutes ?: rule.timeEndMinutes ?: run {
            cancelRule(rule.id)
            return
        }
        val triggerAtMillis = nextTriggerAtMillis(minuteOfDay)
        val pendingIntent = requireNotNull(buildPendingIntent(rule.id, PendingIntent.FLAG_UPDATE_CURRENT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancelRule(ruleId: Long) {
        val pendingIntent = buildPendingIntent(ruleId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun buildPendingIntent(ruleId: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, TimeRuleAlarmReceiver::class.java).apply {
            action = ACTION_TIME_RULE_ALARM
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        val mutableFlags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCodeForRule(ruleId), intent, mutableFlags)
    }

    private fun requestCodeForRule(ruleId: Long): Int {
        return ruleId.hashCode()
    }

    private fun nextTriggerAtMillis(minuteOfDay: Int): Long {
        val now = LocalDateTime.now()
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        const val ACTION_TIME_RULE_ALARM = "com.kjkao.contextautomator.action.TIME_RULE_ALARM"
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
