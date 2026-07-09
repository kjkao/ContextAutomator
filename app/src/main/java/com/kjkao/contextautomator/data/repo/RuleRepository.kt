package com.kjkao.contextautomator.data.repo

import com.kjkao.contextautomator.data.local.RuleDao
import com.kjkao.contextautomator.data.local.RuleCheckHistoryDao
import com.kjkao.contextautomator.data.local.RuleCheckHistoryEntity
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryDao
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity
import com.kjkao.contextautomator.domain.model.TriggerType

class RuleRepository(
    private val ruleDao: RuleDao,
    private val ruleExecutionHistoryDao: RuleExecutionHistoryDao,
    private val ruleCheckHistoryDao: RuleCheckHistoryDao
) {

    suspend fun getAllRules(): List<RuleEntity> = ruleDao.getAll()

    suspend fun getEnabledRulesCount(): Int = ruleDao.getEnabledCount()

    suspend fun getEnabledRules(): List<RuleEntity> = ruleDao.getEnabledRules()

    suspend fun getEnabledTimeRules(): List<RuleEntity> =
        ruleDao.getEnabledRulesByTriggerType(TriggerType.TIME_RANGE.name)

    suspend fun getRuleById(id: Long): RuleEntity? = ruleDao.getById(id)

    suspend fun getRuleExecutionHistorySince(since: Long): List<RuleExecutionHistoryEntity> =
        ruleExecutionHistoryDao.getExecutedSince(since)

    suspend fun getRuleCheckHistorySince(since: Long): List<RuleCheckHistoryEntity> =
        ruleCheckHistoryDao.getCheckedSince(since)

    suspend fun getLatestRuleExecution(ruleId: Long): RuleExecutionHistoryEntity? =
        ruleExecutionHistoryDao.getLatestForRule(ruleId)

    suspend fun getLatestRuleCheck(ruleId: Long): RuleCheckHistoryEntity? =
        ruleCheckHistoryDao.getLatestForRule(ruleId)

    suspend fun upsertRule(rule: RuleEntity): Long = ruleDao.upsert(rule)

    suspend fun updateRule(rule: RuleEntity) = ruleDao.update(rule)

    suspend fun deleteRule(id: Long) = ruleDao.deleteById(id)

    suspend fun recordRuleExecution(rule: RuleEntity, executedAt: Long = System.currentTimeMillis()) {
        ruleExecutionHistoryDao.insert(
            RuleExecutionHistoryEntity(
                ruleId = rule.id,
                triggerType = rule.triggerType,
                triggerValue = rule.triggerValue,
                actionType = rule.actionType,
                actionValue = rule.actionValue,
                executedAt = executedAt
            )
        )
    }

    suspend fun recordRuleCheck(
        rule: RuleEntity,
        matched: Boolean,
        checkedAt: Long = System.currentTimeMillis(),
        cooldownMs: Long? = null,
        retentionCutoff: Long? = null
    ) {
        val latestCheck = ruleCheckHistoryDao.getLatestForRule(rule.id)
        val shouldRecord = latestCheck == null ||
            latestCheck.matched != matched ||
            (cooldownMs != null && checkedAt - latestCheck.checkedAt >= cooldownMs) ||
            cooldownMs == null

        if (shouldRecord) {
            ruleCheckHistoryDao.insert(
                RuleCheckHistoryEntity(
                    ruleId = rule.id,
                    triggerType = rule.triggerType,
                    triggerValue = rule.triggerValue,
                    actionType = rule.actionType,
                    actionValue = rule.actionValue,
                    matched = matched,
                    checkedAt = checkedAt
                )
            )
        }
        if (retentionCutoff != null) {
            ruleCheckHistoryDao.deleteOlderThan(retentionCutoff)
        }
    }
}

