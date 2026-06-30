package com.kjkao.contextautomator.ui.main

import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity

data class MainUiState(
	val ruleCount: Int = 0,
	val rules: List<RuleEntity> = emptyList(),
	val executionHistory: List<RuleExecutionHistoryEntity> = emptyList(),
	val status: String = "Ready"
)
