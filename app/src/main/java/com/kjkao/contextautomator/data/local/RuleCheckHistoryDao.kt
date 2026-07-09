package com.kjkao.contextautomator.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RuleCheckHistoryDao {

    @Insert
    suspend fun insert(entry: RuleCheckHistoryEntity): Long

    @Query("SELECT * FROM rule_check_history WHERE checkedAt >= :since ORDER BY checkedAt DESC")
    suspend fun getCheckedSince(since: Long): List<RuleCheckHistoryEntity>

    @Query("SELECT * FROM rule_check_history WHERE ruleId = :ruleId ORDER BY checkedAt DESC LIMIT 1")
    suspend fun getLatestForRule(ruleId: Long): RuleCheckHistoryEntity?

    @Query("DELETE FROM rule_check_history WHERE checkedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}