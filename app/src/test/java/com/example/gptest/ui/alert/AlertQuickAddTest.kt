package com.example.gptest.ui.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertQuickAddTest {

    private val stock = AlertStockOption("sz002491", "通鼎互联")

    @Test
    fun operators_areValuelessBoardAndRapid() {
        assertEquals(
            listOf(
                AlertOperator.LIMIT_UP,
                AlertOperator.LIMIT_DOWN,
                AlertOperator.OPEN_LIMIT_UP,
                AlertOperator.OPEN_LIMIT_DOWN,
                AlertOperator.VOLUME_SURGE,
                AlertOperator.VOLUME_SHRINK,
                AlertOperator.PRICE_SURGE,
                AlertOperator.PRICE_DROP
            ),
            AlertQuickAdd.operators
        )
    }

    @Test
    fun createRule_usesCooldownAndSentenceName() {
        val rule = AlertQuickAdd.createRule(stock, AlertOperator.PRICE_SURGE)
        assertEquals("通鼎互联  快速拉升", rule.name)
        assertTrue(rule.enabled)
        assertEquals(AlertNotifyMode.COOLDOWN, rule.notifyMode)
        assertEquals(AlertMatchMode.ALL, rule.matchMode)
        assertEquals(AlertRuleStatus.WAITING, rule.status)
        val condition = rule.conditions.single()
        assertEquals(stock, condition.stock)
        assertEquals(AlertOperator.PRICE_SURGE, condition.operator)
        assertEquals(AlertMetric.PRICE, condition.metric)
        assertEquals("", condition.numberValue)
        assertEquals(null, condition.compareStock)
    }

    @Test
    fun hasCondition_matchesStockAndOperator() {
        val rule = AlertQuickAdd.createRule(stock, AlertOperator.LIMIT_UP)
        assertTrue(AlertQuickAdd.hasCondition(listOf(rule), stock.code, AlertOperator.LIMIT_UP))
        assertFalse(AlertQuickAdd.hasCondition(listOf(rule), stock.code, AlertOperator.LIMIT_DOWN))
        assertFalse(AlertQuickAdd.hasCondition(listOf(rule), "sz000001", AlertOperator.LIMIT_UP))
    }

    @Test
    fun operatorsFor_collectsValuelessOperatorsOfStock() {
        val limitUp = AlertQuickAdd.createRule(stock, AlertOperator.LIMIT_UP)
        val surge = AlertQuickAdd.createRule(stock, AlertOperator.PRICE_SURGE)
        val other = AlertQuickAdd.createRule(AlertStockOption("sz000001", "平安银行"), AlertOperator.LIMIT_DOWN)
        assertEquals(
            setOf(AlertOperator.LIMIT_UP, AlertOperator.PRICE_SURGE),
            AlertQuickAdd.operatorsFor(listOf(limitUp, surge, other), stock.code)
        )
    }

    @Test
    fun matchingRuleIds_returnsRulesWithStockAndOperator() {
        val limitUp = AlertQuickAdd.createRule(stock, AlertOperator.LIMIT_UP)
        val surge = AlertQuickAdd.createRule(stock, AlertOperator.PRICE_SURGE)
        val other = AlertQuickAdd.createRule(AlertStockOption("sz000001", "平安银行"), AlertOperator.LIMIT_DOWN)
        assertEquals(
            listOf(limitUp.id),
            AlertQuickAdd.matchingRuleIds(listOf(limitUp, surge, other), stock.code, AlertOperator.LIMIT_UP)
        )
    }
}
