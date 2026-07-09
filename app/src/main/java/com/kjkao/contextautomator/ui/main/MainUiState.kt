package com.kjkao.contextautomator.ui.main

import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.data.local.RuleCheckHistoryEntity
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity

data class MainUiState(
	val ruleCount: Int = 0,
	val rules: List<RuleEntity> = emptyList(),
	val executionHistory: List<RuleExecutionHistoryEntity> = emptyList(),
	val checkHistory: List<RuleCheckHistoryEntity> = emptyList(),
	val status: String = "Ready"
)
