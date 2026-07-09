package com.kjkao.contextautomator.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RuleEntity::class, RuleExecutionHistoryEntity::class, RuleCheckHistoryEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun ruleExecutionHistoryDao(): RuleExecutionHistoryDao

    abstract fun ruleCheckHistoryDao(): RuleCheckHistoryDao
}

