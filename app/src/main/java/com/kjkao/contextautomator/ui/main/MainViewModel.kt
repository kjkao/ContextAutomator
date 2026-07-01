package com.kjkao.contextautomator.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kjkao.contextautomator.alarm.TimeRuleAlarmScheduler
import com.kjkao.contextautomator.automation.RuleCooldownBypassStore
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.data.repo.RuleRepository
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.domain.model.TriggerCondition
import com.kjkao.contextautomator.domain.model.TriggerType
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: RuleRepository,
    private val timeRuleAlarmScheduler: TimeRuleAlarmScheduler,
    private val ruleCooldownBypassStore: RuleCooldownBypassStore
) : ViewModel() {

    private val _uiState = MutableLiveData(MainUiState())
    val uiState: LiveData<MainUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            val count = repository.getEnabledRulesCount()
            val rules = repository.getAllRules()
            val history = loadExecutionHistory()
            _uiState.value = _uiState.value?.copy(ruleCount = count, rules = rules, executionHistory = history)
        }
    }

    suspend fun refreshExecutionHistory(): List<com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity> {
        val history = loadExecutionHistory()
        _uiState.postValue(_uiState.value?.copy(executionHistory = history))
        return history
    }

    fun addRule(
        triggerType: TriggerType,
        triggerCondition: TriggerCondition,
        triggerValue: String,
        timeStart: String,
        actionType: ActionType,
        actionValueRaw: String
    ) {
        viewModelScope.launch {
            val triggerText = triggerValue.trim()
            val (startMinutes, endMinutes) = if (triggerType == TriggerType.TIME_RANGE) {
                val triggerMinute = parseMinuteOfDay(timeStart)
                if (triggerMinute == null) {
                    _uiState.value = _uiState.value?.copy(status = "Time format must be HH:mm")
                    return@launch
                }
                triggerMinute to triggerMinute
            } else {
                null to null
            }

            if (requiresTriggerValue(triggerType) && triggerText.isEmpty()) {
                _uiState.value = _uiState.value?.copy(status = "Trigger value cannot be empty")
                return@launch
            }

            if (triggerType == TriggerType.GEOFENCE && !isGeofenceFormatValid(triggerText)) {
                _uiState.value = _uiState.value?.copy(status = "Geofence format must be lat,lng,radiusMeters")
                return@launch
            }

            val actionValue = actionValueRaw.trim().toIntOrNull()
            if (actionValue == null) {
                _uiState.value = _uiState.value?.copy(status = "Action value must be a number")
                return@launch
            }

            repository.upsertRule(
                RuleEntity(
                    triggerType = triggerType.name,
                    triggerCondition = triggerCondition.name,
                    triggerValue = triggerText,
                    timeStartMinutes = startMinutes,
                    timeEndMinutes = endMinutes,
                    actionType = actionType.name,
                    actionValue = actionValue,
                    enabled = true
                )
            )
            syncTimeRuleAlarms()
            _uiState.value = _uiState.value?.copy(status = "Rule added")
            refresh()
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            repository.deleteRule(id)
            timeRuleAlarmScheduler.cancelRule(id)
            syncTimeRuleAlarms()
            _uiState.value = _uiState.value?.copy(status = "Rule deleted")
            refresh()
        }
    }

    fun updateRule(
        rule: RuleEntity,
        triggerType: TriggerType,
        triggerCondition: TriggerCondition,
        triggerValue: String,
        timeStart: String,
        actionType: ActionType,
        actionValueRaw: String,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            val triggerText = triggerValue.trim()
            val (startMinutes, endMinutes) = if (triggerType == TriggerType.TIME_RANGE) {
                val triggerMinute = parseMinuteOfDay(timeStart)
                if (triggerMinute == null) {
                    _uiState.value = _uiState.value?.copy(status = "Time format must be HH:mm")
                    return@launch
                }
                triggerMinute to triggerMinute
            } else {
                null to null
            }

            if (requiresTriggerValue(triggerType) && triggerText.isEmpty()) {
                _uiState.value = _uiState.value?.copy(status = "Trigger value cannot be empty")
                return@launch
            }

            if (triggerType == TriggerType.GEOFENCE && !isGeofenceFormatValid(triggerText)) {
                _uiState.value = _uiState.value?.copy(status = "Geofence format must be lat,lng,radiusMeters")
                return@launch
            }

            val actionValue = actionValueRaw.trim().toIntOrNull()
            if (actionValue == null) {
                _uiState.value = _uiState.value?.copy(status = "Action value must be a number")
                return@launch
            }

            val now = System.currentTimeMillis()
            val updatedRule = rule.copy(
                triggerType = triggerType.name,
                triggerCondition = triggerCondition.name,
                triggerValue = triggerText,
                timeStartMinutes = startMinutes,
                timeEndMinutes = endMinutes,
                actionType = actionType.name,
                actionValue = actionValue,
                enabled = enabled,
                updatedAt = now
            )

            if (hasRuleContentChanged(rule, updatedRule)) {
                // Persist as a new rule to force a new rule ID when definition changes.
                repository.upsertRule(
                    updatedRule.copy(
                        id = 0L,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                repository.deleteRule(rule.id)
                timeRuleAlarmScheduler.cancelRule(rule.id)
            } else {
                repository.updateRule(updatedRule)
                if (!rule.enabled && enabled) {
                    ruleCooldownBypassStore.requestRuleBypass(rule.id)
                }
            }
            syncTimeRuleAlarms()
            _uiState.value = _uiState.value?.copy(status = "Rule updated")
            refresh()
        }
    }

    fun setRuleEnabled(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateRule(
                rule.copy(
                    enabled = enabled,
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (enabled && !rule.enabled) {
                ruleCooldownBypassStore.requestRuleBypass(rule.id)
            }
            if (!enabled) {
                timeRuleAlarmScheduler.cancelRule(rule.id)
            }
            syncTimeRuleAlarms()
            _uiState.value = _uiState.value?.copy(
                status = if (enabled) "Rule enabled: ${rule.triggerValue}" else "Rule disabled: ${rule.triggerValue}"
            )
            refresh()
        }
    }

    fun setStatus(status: String) {
        _uiState.value = _uiState.value?.copy(status = status)
    }

    private fun parseMinuteOfDay(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun requiresTriggerValue(triggerType: TriggerType): Boolean {
        return triggerType != TriggerType.TIME_RANGE && triggerType != TriggerType.CHARGING_STATE
    }

    private fun isGeofenceFormatValid(value: String): Boolean {
        val parts = value.split(",").map { it.trim() }
        if (parts.size != 3) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lng = parts[1].toDoubleOrNull() ?: return false
        val radius = parts[2].toFloatOrNull() ?: return false
        return lat in -90.0..90.0 && lng in -180.0..180.0 && radius > 0f
    }

    private fun hasRuleContentChanged(old: RuleEntity, updated: RuleEntity): Boolean {
        return old.triggerType != updated.triggerType ||
            old.triggerCondition != updated.triggerCondition ||
            old.triggerValue != updated.triggerValue ||
            old.timeStartMinutes != updated.timeStartMinutes ||
            old.timeEndMinutes != updated.timeEndMinutes ||
            old.actionType != updated.actionType ||
            old.actionValue != updated.actionValue ||
            old.enabled != updated.enabled
    }

    private suspend fun syncTimeRuleAlarms() {
        timeRuleAlarmScheduler.syncAllRules(repository.getAllRules())
    }

    companion object {
        private const val HISTORY_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    private suspend fun loadExecutionHistory() =
        repository.getRuleExecutionHistorySince(System.currentTimeMillis() - HISTORY_WINDOW_MS)
}

