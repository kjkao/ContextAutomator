package com.kjkao.contextautomator.automation

import android.content.Context

class AutomationStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutomationEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTOMATION_ENABLED, false)
    }

    fun setAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "automation_prefs"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
    }
}
