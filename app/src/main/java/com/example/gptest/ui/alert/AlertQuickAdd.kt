package com.example.gptest.ui.alert

import com.example.gptest.business.StockCatalog

object AlertQuickAdd {
    val operators: List<AlertOperator> = AlertOperator.entries.filter { !it.needsValue }

    fun availableOperators(code: String): List<AlertOperator> {
        if (StockCatalog.isIndex(code)) {
            return operators.filter { !BOARD_LIMIT_OPERATORS.contains(it) }
        }
        return operators
    }

    fun createRule(stock: AlertStockOption, operator: AlertOperator): AlertRule {
        val condition = AlertCondition(
            id = AlertIds.newId(),
            stock = stock,
            metric = AlertMetric.PRICE,
            operator = operator
        )
        return AlertRule(
            id = AlertIds.newId(),
            name = condition.sentence(),
            enabled = true,
            matchMode = AlertMatchMode.ALL,
            notifyMode = AlertNotifyMode.COOLDOWN,
            conditions = listOf(condition),
            status = AlertRuleStatus.WAITING
        )
    }

    fun hasCondition(
        rules: List<AlertRule>,
        stockCode: String,
        operator: AlertOperator
    ): Boolean {
        return rules.any { rule ->
            rule.conditions.any { it.stock.code == stockCode && it.operator == operator }
        }
    }

    fun operatorsFor(rules: List<AlertRule>, stockCode: String): Set<AlertOperator> {
        return rules.flatMap { it.conditions }
            .filter { it.stock.code == stockCode && !it.operator.needsValue }
            .map { it.operator }
            .toSet()
    }

    fun matchingRuleIds(
        rules: List<AlertRule>,
        stockCode: String,
        operator: AlertOperator
    ): List<String> {
        return rules.filter { rule ->
            rule.conditions.any { it.stock.code == stockCode && it.operator == operator }
        }.map { it.id }
    }

    private val BOARD_LIMIT_OPERATORS = setOf(
        AlertOperator.LIMIT_UP,
        AlertOperator.LIMIT_DOWN,
        AlertOperator.OPEN_LIMIT_UP,
        AlertOperator.OPEN_LIMIT_DOWN
    )
}
