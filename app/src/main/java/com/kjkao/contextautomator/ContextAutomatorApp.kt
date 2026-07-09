package com.kjkao.contextautomator

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kjkao.contextautomator.alarm.TimeRuleAlarmScheduler
import com.kjkao.contextautomator.automation.AutomationStateStore
import com.kjkao.contextautomator.automation.RuleCooldownBypassStore
import com.kjkao.contextautomator.data.local.AppDatabase
import com.kjkao.contextautomator.data.repo.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ContextAutomatorApp : Application() {

    lateinit var repository: RuleRepository
        private set

    lateinit var timeRuleAlarmScheduler: TimeRuleAlarmScheduler
        private set

    lateinit var automationStateStore: AutomationStateStore
        private set

    lateinit var ruleCooldownBypassStore: RuleCooldownBypassStore
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "context_automator.db"
        )
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

        repository = RuleRepository(
            ruleDao = db.ruleDao(),
            ruleExecutionHistoryDao = db.ruleExecutionHistoryDao(),
            ruleCheckHistoryDao = db.ruleCheckHistoryDao()
        )
        timeRuleAlarmScheduler = TimeRuleAlarmScheduler(applicationContext)
        automationStateStore = AutomationStateStore(applicationContext)
        ruleCooldownBypassStore = RuleCooldownBypassStore(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            val allRules = repository.getAllRules()
            if (automationStateStore.isAutomationEnabled()) {
                timeRuleAlarmScheduler.syncAllRules(allRules)
            } else {
                timeRuleAlarmScheduler.cancelAllTimeRules(allRules)
            }
        }
    }

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rule_execution_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ruleId` INTEGER NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `triggerValue` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `actionValue` INTEGER NOT NULL,
                        `executedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_execution_history_ruleId` ON `rule_execution_history` (`ruleId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_execution_history_executedAt` ON `rule_execution_history` (`executedAt`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rule_check_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ruleId` INTEGER NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `triggerValue` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `actionValue` INTEGER NOT NULL,
                        `matched` INTEGER NOT NULL,
                        `checkedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_check_history_ruleId` ON `rule_check_history` (`ruleId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_check_history_checkedAt` ON `rule_check_history` (`checkedAt`)"
                )
            }
        }
    }
}

