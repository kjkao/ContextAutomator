package com.kjkao.contextautomator.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RuleEntity::class, RuleExecutionHistoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun ruleExecutionHistoryDao(): RuleExecutionHistoryDao
}

