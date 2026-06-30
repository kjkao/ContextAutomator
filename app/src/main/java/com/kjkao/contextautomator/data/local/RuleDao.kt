package com.kjkao.contextautomator.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RuleDao {

    @Query("SELECT * FROM wifi_rules ORDER BY updatedAt DESC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM wifi_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<RuleEntity>

    @Query("SELECT * FROM wifi_rules WHERE enabled = 1 AND triggerType = :triggerType")
    suspend fun getEnabledRulesByTriggerType(triggerType: String): List<RuleEntity>

    @Query("SELECT * FROM wifi_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RuleEntity?

    @Query("SELECT COUNT(*) FROM wifi_rules WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM wifi_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

