package com.kjkao.contextautomator.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RuleExecutionHistoryDao {

    @Insert
    suspend fun insert(entry: RuleExecutionHistoryEntity): Long

    @Query("SELECT * FROM rule_execution_history WHERE executedAt >= :since ORDER BY executedAt DESC")
    suspend fun getExecutedSince(since: Long): List<RuleExecutionHistoryEntity>

    @Query("SELECT * FROM rule_execution_history WHERE ruleId = :ruleId ORDER BY executedAt DESC LIMIT 1")
    suspend fun getLatestForRule(ruleId: Long): RuleExecutionHistoryEntity?
}
