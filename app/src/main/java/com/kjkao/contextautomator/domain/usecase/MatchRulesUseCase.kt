package com.kjkao.contextautomator.domain.usecase

import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.domain.model.TriggerType

class MatchRulesUseCase {

    fun match(scannedSsids: Set<String>, rules: List<RuleEntity>): RuleEntity? {
        val normalizedScanned = scannedSsids.map { it.trim().lowercase() }.toSet()
        return rules.firstOrNull { rule ->
            rule.triggerType == TriggerType.WIFI_SSID.name &&
                normalizedScanned.contains(rule.triggerValue.trim().lowercase())
        }
    }
}

