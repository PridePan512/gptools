package com.example.gptest.ui.alert

import com.example.gptest.business.QuoteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private val tongding = AlertStockOption("sz002491", "通鼎互联")
    private val pufa = AlertStockOption("sh600000", "浦发银行")

    @Test
    fun gte_matchesWhenCurrentReachesThreshold() {
        val condition = priceAbove(21.0)
        val current = mapOf(tongding.code to quote(tongding.code, price = "21.00"))
        assertTrue(AlertEvaluator.isSatisfied(condition, current, emptyMap()))
        assertFalse(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "20.99")),
                emptyMap()
            )
        )
    }

    @Test
    fun crossUp_needsPreviousTickBelowThreshold() {
        val condition = AlertCondition(
            id = "c1",
            stock = tongding,
            metric = AlertMetric.PRICE,
            operator = AlertOperator.CROSS_UP,
            numberValue = "21"
        )
        val previous = mapOf(tongding.code to quote(tongding.code, price = "20.80"))
        val current = mapOf(tongding.code to quote(tongding.code, price = "21.10"))
        assertTrue(AlertEvaluator.isSatisfied(condition, current, previous))
        assertFalse(AlertEvaluator.isSatisfied(condition, current, emptyMap()))
        assertFalse(AlertEvaluator.isSatisfied(condition, current, current))
    }

    @Test
    fun allMode_requiresEveryCondition() {
        val rule = rule(
            matchMode = AlertMatchMode.ALL,
            conditions = listOf(
                priceAbove(21.0),
                AlertCondition(
                    id = "c2",
                    stock = pufa,
                    metric = AlertMetric.CHANGE_PERCENT,
                    operator = AlertOperator.LTE,
                    numberValue = "-2"
                )
            )
        )
        val quotes = listOf(
            quote(tongding.code, price = "21.50"),
            quote(pufa.code, price = "10", change = "-2.1")
        )
        val result = AlertEvaluator.evaluate(listOf(rule), quotes, emptyList(), nowMs = 1_000L)
        assertEquals(1, result.fires.size)
        assertEquals(AlertRuleStatus.FIRED, result.updatedRules.single().status)
    }

    @Test
    fun once_notifiesOnlyFirstTime() {
        val rule = rule(notifyMode = AlertNotifyMode.ONCE, conditions = listOf(priceAbove(21.0)))
        val quotes = listOf(quote(tongding.code, price = "22"))
        val first = AlertEvaluator.evaluate(listOf(rule), quotes, emptyList(), 1_000L)
        assertEquals(1, first.fires.size)
        assertTrue(first.updatedRules.single().onceConsumed)
        val second = AlertEvaluator.evaluate(first.updatedRules, quotes, emptyList(), 2_000L)
        assertTrue(second.fires.isEmpty())
        assertEquals(AlertRuleStatus.FIRED, second.updatedRules.single().status)
    }

    @Test
    fun cooldown_blocksUntilTenMinutes() {
        val rule = rule(
            notifyMode = AlertNotifyMode.COOLDOWN,
            conditions = listOf(priceAbove(21.0)),
            lastTriggeredMs = 1_000L
        )
        val quotes = listOf(quote(tongding.code, price = "22"))
        val blocked = AlertEvaluator.evaluate(listOf(rule), quotes, emptyList(), 1_000L + AlertEvaluator.COOLDOWN_MS - 1)
        assertTrue(blocked.fires.isEmpty())
        val allowed = AlertEvaluator.evaluate(listOf(rule), quotes, emptyList(), 1_000L + AlertEvaluator.COOLDOWN_MS)
        assertEquals(1, allowed.fires.size)
    }

    @Test
    fun disabledRule_isOffAndDoesNotFire() {
        val rule = rule(enabled = false, conditions = listOf(priceAbove(21.0)))
        val quotes = listOf(quote(tongding.code, price = "22"))
        val result = AlertEvaluator.evaluate(listOf(rule), quotes, emptyList(), 1_000L)
        assertTrue(result.fires.isEmpty())
        assertEquals(AlertRuleStatus.OFF, result.updatedRules.single().status)
    }

    @Test
    fun compareStock_usesOtherQuoteMetric() {
        val condition = AlertCondition(
            id = "c1",
            stock = tongding,
            metric = AlertMetric.CHANGE_PERCENT,
            operator = AlertOperator.GTE,
            compareStock = pufa
        )
        val quotes = mapOf(
            tongding.code to quote(tongding.code, change = "3.0"),
            pufa.code to quote(pufa.code, change = "1.5")
        )
        assertTrue(AlertEvaluator.isSatisfied(condition, quotes, emptyMap()))
        val weaker = quotes + (tongding.code to quote(tongding.code, change = "1.0"))
        assertFalse(AlertEvaluator.isSatisfied(condition, weaker, emptyMap()))
    }

    @Test
    fun formatTriggered_todayYesterdayAndDate() {
        val now = java.time.ZonedDateTime.of(2026, 9, 4, 15, 0, 0, 0, com.example.gptest.business.TradingSession.SHANGHAI)
        val today = now.withHour(10).withMinute(21).toInstant().toEpochMilli()
        val yesterday = now.minusDays(1).withHour(14).withMinute(8).toInstant().toEpochMilli()
        val earlier = now.minusDays(3).withHour(9).withMinute(5).toInstant().toEpochMilli()
        assertEquals("今天 10:21", AlertEvaluator.formatTriggered(today, now))
        assertEquals("昨天 14:08", AlertEvaluator.formatTriggered(yesterday, now))
        assertEquals("9/1 09:05", AlertEvaluator.formatTriggered(earlier, now))
    }

    @Test
    fun limitUp_matchesWhenPriceReachesLimit() {
        val condition = board(AlertOperator.LIMIT_UP)
        assertTrue(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "10.00", limitUp = "10.00", limitDown = "8.00")),
                emptyMap()
            )
        )
        assertFalse(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "9.99", limitUp = "10.00", limitDown = "8.00")),
                emptyMap()
            )
        )
        assertFalse(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "10.00")),
                emptyMap()
            )
        )
    }

    @Test
    fun limitDown_matchesWhenPriceReachesLimit() {
        val condition = board(AlertOperator.LIMIT_DOWN)
        assertTrue(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "8.00", limitUp = "10.00", limitDown = "8.00")),
                emptyMap()
            )
        )
        assertFalse(
            AlertEvaluator.isSatisfied(
                condition,
                mapOf(tongding.code to quote(tongding.code, price = "8.01", limitUp = "10.00", limitDown = "8.00")),
                emptyMap()
            )
        )
    }

    @Test
    fun openLimitUp_needsPreviousTickAtLimit() {
        val condition = board(AlertOperator.OPEN_LIMIT_UP)
        val sealed = quote(tongding.code, price = "10.00", limitUp = "10.00", limitDown = "8.00")
        val opened = quote(tongding.code, price = "9.80", limitUp = "10.00", limitDown = "8.00")
        assertTrue(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to opened), mapOf(tongding.code to sealed)))
        assertFalse(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to opened), emptyMap()))
        assertFalse(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to sealed), mapOf(tongding.code to sealed)))
        assertFalse(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to opened), mapOf(tongding.code to opened)))
    }

    @Test
    fun openLimitDown_needsPreviousTickAtLimit() {
        val condition = board(AlertOperator.OPEN_LIMIT_DOWN)
        val sealed = quote(tongding.code, price = "8.00", limitUp = "10.00", limitDown = "8.00")
        val opened = quote(tongding.code, price = "8.20", limitUp = "10.00", limitDown = "8.00")
        assertTrue(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to opened), mapOf(tongding.code to sealed)))
        assertFalse(AlertEvaluator.isSatisfied(condition, mapOf(tongding.code to sealed), mapOf(tongding.code to sealed)))
    }

    @Test
    fun boardSentence_omitsThreshold() {
        assertEquals("通鼎互联  撬开涨停", board(AlertOperator.OPEN_LIMIT_UP).sentence())
        assertEquals("通鼎互联  跌停", board(AlertOperator.LIMIT_DOWN).sentence())
    }

    private fun priceAbove(threshold: Double): AlertCondition {
        return AlertCondition(
            id = "c1",
            stock = tongding,
            metric = AlertMetric.PRICE,
            operator = AlertOperator.GTE,
            numberValue = threshold.toString()
        )
    }

    private fun rule(
        enabled: Boolean = true,
        matchMode: AlertMatchMode = AlertMatchMode.ALL,
        notifyMode: AlertNotifyMode = AlertNotifyMode.ALWAYS,
        conditions: List<AlertCondition>,
        lastTriggeredMs: Long? = null,
        onceConsumed: Boolean = false
    ): AlertRule {
        return AlertRule(
            id = "r1",
            name = "测试",
            enabled = enabled,
            matchMode = matchMode,
            notifyMode = notifyMode,
            conditions = conditions,
            status = if (enabled) AlertRuleStatus.WAITING else AlertRuleStatus.OFF,
            lastTriggeredMs = lastTriggeredMs,
            onceConsumed = onceConsumed
        )
    }

    private fun board(operator: AlertOperator): AlertCondition {
        return AlertCondition(
            id = "c1",
            stock = tongding,
            metric = AlertMetric.PRICE,
            operator = operator
        )
    }

    private fun quote(
        code: String,
        price: String = "10",
        change: String = "0",
        limitUp: String = "",
        limitDown: String = ""
    ): QuoteSnapshot {
        val fields = MutableList(50) { "" }
        fields[2] = code.takeLast(6)
        fields[3] = price
        fields[32] = change
        fields[37] = "100"
        fields[38] = "1"
        fields[43] = "2"
        fields[47] = limitUp
        fields[48] = limitDown
        return QuoteSnapshot(code, code, price, change, fields)
    }
}
