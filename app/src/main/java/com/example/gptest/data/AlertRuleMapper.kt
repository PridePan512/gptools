package com.example.gptest.data

import com.example.gptest.ui.alert.AlertCondition
import com.example.gptest.ui.alert.AlertMatchMode
import com.example.gptest.ui.alert.AlertMetric
import com.example.gptest.ui.alert.AlertNotifyMode
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertRule
import com.example.gptest.ui.alert.AlertRuleStatus
import com.example.gptest.ui.alert.AlertStockOption

object AlertRuleMapper {

    fun toRule(entity: AlertRuleEntity, conditions: List<AlertConditionEntity>): AlertRule {
        return AlertRule(
            id = entity.id,
            name = entity.name,
            enabled = entity.enabled,
            matchMode = enumValue(entity.matchMode, AlertMatchMode.ALL),
            notifyMode = enumValue(entity.notifyMode, AlertNotifyMode.COOLDOWN),
            conditions = conditions.sortedBy { it.sortOrder }.map { toCondition(it) },
            status = enumValue(entity.status, AlertRuleStatus.WAITING),
            lastTriggeredMs = entity.lastTriggeredMs,
            onceConsumed = entity.onceConsumed
        )
    }

    fun toEntity(rule: AlertRule, sortOrder: Int): AlertRuleEntity {
        return AlertRuleEntity(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            matchMode = rule.matchMode.name,
            notifyMode = rule.notifyMode.name,
            status = rule.status.name,
            lastTriggeredMs = rule.lastTriggeredMs,
            onceConsumed = rule.onceConsumed,
            sortOrder = sortOrder
        )
    }

    fun toConditionEntities(rule: AlertRule): List<AlertConditionEntity> {
        return rule.conditions.mapIndexed { index, condition ->
            AlertConditionEntity(
                id = condition.id,
                ruleId = rule.id,
                stockCode = condition.stock.code,
                stockName = condition.stock.name,
                metric = condition.metric.name,
                operator = condition.operator.name,
                numberValue = condition.numberValue,
                compareCode = condition.compareStock?.code,
                compareName = condition.compareStock?.name,
                sortOrder = index
            )
        }
    }

    private fun toCondition(entity: AlertConditionEntity): AlertCondition {
        return AlertCondition(
            id = entity.id,
            stock = AlertStockOption.fromCode(entity.stockCode, entity.stockName),
            metric = enumValue(entity.metric, AlertMetric.PRICE),
            operator = enumValue(entity.operator, AlertOperator.CROSS_UP),
            numberValue = entity.numberValue,
            compareStock = entity.compareCode?.let { AlertStockOption.fromCode(it, entity.compareName) }
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T {
        return enumValues<T>().find { it.name == raw } ?: fallback
    }
}
