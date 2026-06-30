package com.kjkao.contextautomator.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wifi_rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerType: String,
    val triggerCondition: String = "DETECTED",
    val triggerValue: String = "",
    val timeStartMinutes: Int? = null,
    val timeEndMinutes: Int? = null,
    val actionType: String,
    val actionValue: Int,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

