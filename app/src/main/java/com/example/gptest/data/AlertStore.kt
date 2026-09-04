package com.example.gptest.data

import com.example.gptest.ui.alert.AlertRule
import com.example.gptest.ui.alert.AlertRuleStatus

class AlertStore(private val dao: AlertDao) : AlertDataSource {

    override fun loadRules(): List<AlertRule> {
        val conditions = dao.getConditions().groupBy { it.ruleId }
        return dao.getRules().map { entity ->
            AlertRuleMapper.toRule(entity, conditions[entity.id].orEmpty())
        }
    }

    override fun get(id: String): AlertRule? {
        val entity = dao.getRule(id) ?: return null
        return AlertRuleMapper.toRule(entity, dao.getConditions(id))
    }

    override fun saveRule(rule: AlertRule) {
        val existing = dao.getRule(rule.id)
        val sortOrder = existing?.sortOrder ?: (dao.maxSortOrder() + 1)
        dao.replaceRule(AlertRuleMapper.toEntity(rule, sortOrder), AlertRuleMapper.toConditionEntities(rule))
    }

    override fun deleteRule(id: String) {
        dao.deleteRule(id)
    }

    override fun replaceRules(rules: List<AlertRule>) {
        val existing = dao.getRules().associateBy { it.id }
        rules.forEach { rule ->
            val sortOrder = existing[rule.id]?.sortOrder ?: return@forEach
            dao.replaceRule(AlertRuleMapper.toEntity(rule, sortOrder), AlertRuleMapper.toConditionEntities(rule))
        }
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        val rule = get(id) ?: return
        saveRule(
            rule.copy(
                enabled = enabled,
                status = if (enabled) AlertRuleStatus.WAITING else AlertRuleStatus.OFF,
                onceConsumed = if (enabled) false else rule.onceConsumed
            )
        )
    }
}
