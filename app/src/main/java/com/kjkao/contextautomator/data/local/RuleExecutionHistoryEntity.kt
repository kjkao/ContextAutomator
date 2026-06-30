package com.kjkao.contextautomator.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rule_execution_history",
    indices = [
        Index(value = ["ruleId"]),
        Index(value = ["executedAt"])
    ]
)
data class RuleExecutionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val triggerType: String,
    val triggerValue: String,
    val actionType: String,
    val actionValue: Int,
    val executedAt: Long = System.currentTimeMillis()
)
