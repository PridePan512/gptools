package com.example.gptest.ui.alert

import java.util.UUID

object AlertPreviewStore {
    private val rules = mutableListOf(
        AlertRule(
            id = "demo-1",
            name = "半导体联动",
            enabled = true,
            matchMode = AlertMatchMode.ALL,
            notifyMode = AlertNotifyMode.COOLDOWN,
            conditions = listOf(
                AlertCondition(
                    id = "c1",
                    stock = AlertPreviewStocks.options[0],
                    metric = AlertMetric.PRICE,
                    operator = AlertOperator.CROSS_UP,
                    numberValue = "21.00"
                ),
                AlertCondition(
                    id = "c2",
                    stock = AlertPreviewStocks.options[1],
                    metric = AlertMetric.CHANGE_PERCENT,
                    operator = AlertOperator.LTE,
                    numberValue = "-2"
                )
            ),
            status = AlertRuleStatus.WAITING,
            lastTriggered = null
        ),
        AlertRule(
            id = "demo-2",
            name = "茅台破位",
            enabled = true,
            matchMode = AlertMatchMode.ALL,
            notifyMode = AlertNotifyMode.ONCE,
            conditions = listOf(
                AlertCondition(
                    id = "c3",
                    stock = AlertPreviewStocks.options[2],
                    metric = AlertMetric.PRICE,
                    operator = AlertOperator.CROSS_DOWN,
                    numberValue = "1400"
                )
            ),
            status = AlertRuleStatus.FIRED,
            lastTriggered = "今天 10:21"
        ),
        AlertRule(
            id = "demo-3",
            name = "异动共振",
            enabled = false,
            matchMode = AlertMatchMode.ANY,
            notifyMode = AlertNotifyMode.ALWAYS,
            conditions = listOf(
                AlertCondition(
                    id = "c4",
                    stock = AlertPreviewStocks.options[0],
                    metric = AlertMetric.CHANGE_PERCENT,
                    operator = AlertOperator.GTE,
                    numberValue = "5"
                ),
                AlertCondition(
                    id = "c5",
                    stock = AlertPreviewStocks.options[1],
                    metric = AlertMetric.CHANGE_PERCENT,
                    operator = AlertOperator.GTE,
                    compareStock = AlertPreviewStocks.options[0]
                )
            ),
            status = AlertRuleStatus.OFF,
            lastTriggered = "昨天 14:08"
        )
    )

    fun all(): List<AlertRule> = rules.toList()

    fun get(id: String): AlertRule? = rules.find { it.id == id }

    fun upsert(rule: AlertRule) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
        } else {
            rules.add(0, rule)
        }
    }

    fun delete(id: String) {
        rules.removeAll { it.id == id }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = rules[index]
        rules[index] = current.copy(
            enabled = enabled,
            status = if (enabled) {
                if (current.status == AlertRuleStatus.OFF) AlertRuleStatus.WAITING else current.status
            } else {
                AlertRuleStatus.OFF
            }
        )
    }

    fun newId(): String = UUID.randomUUID().toString()
}
