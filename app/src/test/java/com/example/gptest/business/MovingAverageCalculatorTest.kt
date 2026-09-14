package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MovingAverageCalculatorTest {
    private fun rows(count: Int) = (1..count).map {
        DailyClose(LocalDate.of(2025, 1, 1).plusDays(it.toLong()), it.toDouble())
    }

    @Test fun calculatesLatestTradingRowsRegardlessOfInputOrder() {
        val input = rows(260).reversed()
        val result = MovingAverageCalculator.calculate(input)
        assertEquals(input.first().date, result.date)
        assertEquals(258.0, result.values.getValue(5)!!, 0.000001)
        assertEquals(255.5, result.values.getValue(10)!!, 0.000001)
        assertEquals(250.5, result.values.getValue(20)!!, 0.000001)
        assertEquals(135.5, result.values.getValue(250)!!, 0.000001)
    }

    @Test fun shortHistoryDoesNotProduceMisleadingLongAverages() {
        val result = MovingAverageCalculator.calculate(rows(5))
        assertEquals(3.0, result.values.getValue(5)!!, 0.000001)
        assertNull(result.values.getValue(10))
        assertNull(result.values.getValue(20))
        assertNull(result.values.getValue(250))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingHistory() { MovingAverageCalculator.calculate(emptyList()) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateTradingDates() { MovingAverageCalculator.calculate(rows(5) + rows(1)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPrices() {
        MovingAverageCalculator.calculate(listOf(DailyClose(LocalDate.now(), Double.NaN)))
    }
}
