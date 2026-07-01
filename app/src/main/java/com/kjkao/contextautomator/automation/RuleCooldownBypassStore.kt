package com.kjkao.contextautomator.automation

import android.content.Context

class RuleCooldownBypassStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun requestBypassAllOnNextServiceStart() {
        prefs.edit().putBoolean(KEY_BYPASS_ALL_ON_NEXT_SERVICE_START, true).apply()
    }

    fun consumeBypassAllOnNextServiceStart(): Boolean {
        val enabled = prefs.getBoolean(KEY_BYPASS_ALL_ON_NEXT_SERVICE_START, false)
        if (enabled) {
            prefs.edit().putBoolean(KEY_BYPASS_ALL_ON_NEXT_SERVICE_START, false).apply()
        }
        return enabled
    }

    fun requestRuleBypass(ruleId: Long) {
        if (ruleId <= 0L) return
        prefs.edit().putBoolean(ruleKey(ruleId), true).apply()
    }

    fun consumeRuleBypass(ruleId: Long): Boolean {
        if (ruleId <= 0L) return false
        val key = ruleKey(ruleId)
        val enabled = prefs.getBoolean(key, false)
        if (enabled) {
            prefs.edit().remove(key).apply()
        }
        return enabled
    }

    private fun ruleKey(ruleId: Long): String = "$KEY_RULE_PREFIX$ruleId"

    companion object {
        private const val PREFS_NAME = "rule_cooldown_bypass_prefs"
        private const val KEY_BYPASS_ALL_ON_NEXT_SERVICE_START = "bypass_all_on_next_service_start"
        private const val KEY_RULE_PREFIX = "rule_bypass_"
    }
}