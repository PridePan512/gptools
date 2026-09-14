package com.example.gptest.ui.alert

import com.example.gptest.business.DailyClose
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.TradingSession
import com.example.gptest.data.DailyHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MovingAverageAlertTest {
    private val date = LocalDate.of(2026, 9, 14)
    private val now = date.atTime(10, 0).atZone(TradingSession.SHANGHAI).toInstant().toEpochMilli()
    private val stock = AlertStockOption("sh600519", "茅台")
    private val rows = (0..249).map { DailyClose(date.minusDays(it.toLong()), if (it == 0) 999.0 else 10.0) }
    private val reference = MovingAverageReference.from(rows, date)

    private fun matches(operator: AlertOperator, before: String?, after: String, previousTime: Long = now - 5000,
                        data: Map<String, MovingAverageReference> = mapOf(stock.code to reference)): Boolean {
        fun quote(price: String, time: Long) = QuoteSnapshot(stock.code, stock.name, price, "", emptyList(), time)
        return AlertEvaluator.isSatisfied(
            AlertQuickAdd.createRule(stock, operator).conditions.single(),
            mapOf(stock.code to quote(after, now)),
            before?.let { mapOf(stock.code to quote(it, previousTime)) } ?: emptyMap(),
            movingAverages = data
        )
    }

    @Test fun crossesAllSixLinesWithoutRepeatingBelowThem() {
        AlertOperator.entries.filter { it.maPeriod != null }.forEach { operator ->
            assertTrue(matches(operator, "10", "9.99"))
            assertFalse(matches(operator, "9.99", "9.98"))
            assertFalse(matches(operator, "11", "10"))
            assertFalse(matches(operator, null, "9"))
            assertFalse(matches(operator, "9", "11"))
        }
    }

    @Test fun missingStaleAndInvalidDataCannotTrigger() {
        assertFalse(matches(AlertOperator.BREAK_MA5, "11", "9", data = emptyMap()))
        assertFalse(matches(AlertOperator.BREAK_MA5, "11", "9", previousTime = now - 86_400_000))
        assertFalse(matches(AlertOperator.BREAK_MA5, "NaN", "9"))
        assertFalse(matches(AlertOperator.BREAK_MA5, "11", "0"))
        assertTrue(MovingAverageReference.from(rows.drop(1), date).levels.isEmpty())
    }

    @Test fun excludesTodayAndRequiresEnoughCompletedTradingBars() {
        assertEquals(10.0, reference.levels.getValue(250), 0.0001)
        val short = MovingAverageReference.from(rows.take(5), date)
        assertEquals(setOf(5), short.levels.keys)
    }

    @Test fun evaluationUsesReferencesAndHonorsCooldown() {
        val rule = AlertQuickAdd.createRule(stock, AlertOperator.BREAK_MA5)
        val before = listOf(QuoteSnapshot(stock.code, stock.name, "11", "", emptyList(), now - 5000))
        val after = listOf(QuoteSnapshot(stock.code, stock.name, "9", "", emptyList(), now))
        val first = AlertEvaluator.evaluate(listOf(rule), after, before, now,
            movingAverages = mapOf(stock.code to reference))
        assertEquals(1, first.fires.size)
        assertEquals(AlertRuleStatus.FIRED, first.updatedRules.single().status)
        val second = AlertEvaluator.evaluate(first.updatedRules, after, before, now + 5000,
            movingAverages = mapOf(stock.code to reference))
        assertTrue(second.fires.isEmpty())
    }

    @Test fun loadsInBackgroundReusesDayAndReloadsNextDay() = runTest {
        var calls = 0
        val data = MovingAverageAlertData(backgroundScope, StandardTestDispatcher(testScheduler)) {
            calls++
            Result.success(DailyHistory(rows, true))
        }
        assertTrue(data.references(setOf(stock.code), now).isEmpty())
        data.references(setOf(stock.code), now)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(data.references(setOf(stock.code), now + 5000).isNotEmpty())
        assertTrue(data.references(setOf(stock.code), now + 86_400_000).isEmpty())
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun failedRequestsAreThrottledAndRetried() = runTest {
        var calls = 0
        val data = MovingAverageAlertData(backgroundScope, StandardTestDispatcher(testScheduler)) {
            calls++
            Result.failure(IllegalStateException("offline"))
        }
        data.references(setOf(stock.code), now)
        runCurrent()
        data.references(setOf(stock.code), now + 5000)
        runCurrent()
        assertEquals(1, calls)
        data.references(setOf(stock.code), now + 60_000)
        runCurrent()
        assertEquals(2, calls)
    }
}
