package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LimitFlipDetectorTest {

    @Test
    fun countsLimitUpClose_thenCloseLimitDown_whenThirdHoldsAndFourthTurnsRed() {
        val flips = LimitFlipDetector.find(sample(), BigDecimal("0.10"), LocalDate.parse("2026-03-01"))
        val flip = flips.single()
        assertEquals(LocalDate.parse("2026-03-11"), flip.limitUpDate)
        assertEquals(LocalDate.parse("2026-03-12"), flip.limitDownDate)
        assertEquals(LocalDate.parse("2026-03-14"), flip.fourthBar?.date)
        assertEquals(LocalDate.parse("2026-03-13"), flip.trade.entryDate)
        assertEquals(10.20, flip.trade.entryPrice, 0.0)
        assertTrue(!flip.confirmedOnFourth)
        assertEquals(9.90, flip.limitDownBar.open, 0.0)
        assertEquals(9.90, flip.limitDownBar.close, 0.0)
        assertEquals(2, flip.limitUpBoards)
    }

    @Test
    fun firstLimitUpDayMustBeAtLeastTwoBoards() {
        val oneBoard = listOf(
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 9.90),
            day("2026-03-13", 9.50),
            day("2026-03-14", 9.80)
        )
        assertTrue(LimitFlipDetector.find(oneBoard, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
    }

    @Test
    fun threeConsecutiveLimitUpsStillQualify() {
        val bars = listOf(
            day("2026-03-08", 9.09),
            day("2026-03-09", 10.00),
            day("2026-03-10", 11.00),
            day("2026-03-11", 12.10),
            day("2026-03-12", 10.89),
            day("2026-03-13", 11.20),
            day("2026-03-14", 10.80)
        )
        val flip = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single()
        assertEquals(3, flip.limitUpBoards)
        assertEquals(LocalDate.parse("2026-03-11"), flip.limitUpDate)
    }

    @Test
    fun secondDayOpenIsIgnored_butCloseMustBeLimitDown() {
        val openedHigher = listOf(
            day("2026-03-09", 9.09),
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 9.90, open = 10.20),
            day("2026-03-13", 10.20),
            day("2026-03-14", 9.80)
        )
        val closedHigher = listOf(
            day("2026-03-09", 9.09),
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 10.20, open = 9.90),
            day("2026-03-13", 9.50),
            day("2026-03-14", 9.80)
        )
        val found = LimitFlipDetector.find(openedHigher, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single()
        assertEquals(10.20, found.limitDownBar.open, 0.0)
        assertEquals(9.90, found.limitDownBar.close, 0.0)
        assertTrue(LimitFlipDetector.find(closedHigher, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
    }

    @Test
    fun thirdDayBetweenMinusNineAndLimitDown_isExcluded() {
        val bars = listOf(
            day("2026-03-09", 9.09),
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 9.90),
            day("2026-03-13", 8.96),
            day("2026-03-14", 9.20)
        )
        assertTrue(LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
    }

    @Test
    fun thirdDayStillDown_needsFourthCloseAtLeastThreePercentAboveLimitDown() {
        val short = listOf(
            day("2026-03-09", 9.09),
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 9.90),
            day("2026-03-13", 8.91),
            day("2026-03-14", 10.19)
        )
        val recovered = short.dropLast(1) + day("2026-03-14", 10.20)
        assertTrue(LimitFlipDetector.find(short, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
        val flip = LimitFlipDetector.find(recovered, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single()
        assertTrue(flip.confirmedOnFourth)
        assertEquals(10.20, flip.trade.entryPrice, 0.0)
    }

    @Test
    fun thirdDayRed_doesNotNeedARecoveredFourthDay() {
        val down = sample(fourth = 9.40)
        val flip = LimitFlipDetector.find(down, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single()
        assertEquals(LocalDate.parse("2026-03-13"), flip.trade.entryDate)
        assertTrue(!flip.confirmedOnFourth)
    }

    @Test
    fun thirdDayStillLimitDown_needsFourthCloseHigher() {
        val flat = listOf(
            day("2026-03-09", 9.09),
            day("2026-03-10", 10.00),
            day("2026-03-11", 11.00),
            day("2026-03-12", 9.90),
            day("2026-03-13", 8.91),
            day("2026-03-14", 8.91)
        )
        val lower = flat.dropLast(1) + day("2026-03-14", 8.80)
        assertTrue(LimitFlipDetector.find(flat, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
        assertTrue(LimitFlipDetector.find(lower, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).isEmpty())
    }

    @Test
    fun roundsLimitPricesToCents() {
        val bars = listOf(
            day("2026-03-09", 9.21),
            day("2026-03-10", 10.13),
            day("2026-03-11", 11.14),
            day("2026-03-12", 10.03),
            day("2026-03-13", 10.50),
            day("2026-03-14", 9.80)
        )
        assertEquals(1, LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).size)
    }

    @Test
    fun stUsesTenPercent_andOtherBoardsAreExcluded() {
        assertEquals(BigDecimal("0.10"), LimitFlipDetector.LIMIT_RATIO)
        assertTrue(LimitFlipDetector.included("sh600001"))
        assertTrue(LimitFlipDetector.included("sz000001"))
        assertTrue(!LimitFlipDetector.included("sh688001"))
        assertTrue(!LimitFlipDetector.included("sz300001"))
        assertTrue(!LimitFlipDetector.included("sz301001"))
        assertTrue(!LimitFlipDetector.included("bj830001"))
        assertEquals(1, LimitFlipDetector.find(sample(), LimitFlipDetector.LIMIT_RATIO, LocalDate.parse("2026-03-01")).size)
    }

    @Test
    fun dropsPatternsWhoseFourthDayIsBeforeTheWindow() {
        val early = listOf(
            day("2026-01-04", 9.09),
            day("2026-01-05", 10.00),
            day("2026-01-06", 11.00),
            day("2026-01-07", 9.90),
            day("2026-01-08", 10.20),
            day("2026-01-09", 9.80)
        )
        val flips = LimitFlipDetector.find(early + sample(), BigDecimal("0.10"), LocalDate.parse("2026-03-01"))
        assertEquals(LocalDate.parse("2026-03-14"), flips.single().fourthBar?.date)
    }

    @Test
    fun gapBelowTheLimitDownLowClearsAtTheOpen() {
        val bars = pattern(10.20) + day("2026-03-14", 9.40, open = 9.50, low = 9.20)
        val trade = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single().trade
        assertEquals("跌破清仓", trade.reason)
        assertEquals(9.50, trade.exitPrice, 0.0)
        assertEquals(0, trade.adds)
        assertEquals(-0.69, trade.accountPnlPercent, 0.0)
    }

    @Test
    fun touchingTheLimitDownLowClearsAtThatLow() {
        val bars = pattern(10.20) + day("2026-03-14", 10.00, open = 10.10, low = 9.90)
        val trade = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single().trade
        assertEquals("跌破清仓", trade.reason)
        assertEquals(9.90, trade.exitPrice, 0.0)
        assertEquals(-0.29, trade.accountPnlPercent, 0.0)
    }

    @Test
    fun anEightPercentBounceTakesProfit() {
        val bars = pattern(10.20) + day("2026-03-14", 11.10, open = 10.40, high = 11.20, low = 10.30)
        val trade = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single().trade
        assertEquals("止盈", trade.reason)
        assertEquals(11.02, trade.exitPrice, 0.0)
        assertEquals(0.80, trade.accountPnlPercent, 0.0)
    }

    @Test
    fun theSameDayStopWinsWhenBothBandsAreTouched() {
        val bars = pattern(10.20) + day("2026-03-14", 10.40, open = 10.10, high = 12.00, low = 9.80)
        val trade = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single().trade
        assertEquals("跌破清仓", trade.reason)
        assertEquals(9.90, trade.exitPrice, 0.0)
    }

    @Test
    fun theThirdSessionClosesTheTradeWhenNeitherBandHits() {
        val bars = pattern(10.20) + listOf(
            day("2026-03-14", 10.30, open = 10.20, low = 10.10, high = 10.40),
            day("2026-03-15", 10.40, open = 10.30, low = 10.20, high = 10.50),
            day("2026-03-16", 10.50, open = 10.40, low = 10.30, high = 10.60)
        )
        val trade = LimitFlipDetector.find(bars, BigDecimal("0.10"), LocalDate.parse("2026-03-01")).single().trade
        assertEquals("到期", trade.reason)
        assertEquals(LocalDate.parse("2026-03-16"), trade.exitDate)
        assertEquals(10.50, trade.exitPrice, 0.0)
        assertEquals(0.29, trade.accountPnlPercent, 0.0)
    }

    @Test
    fun unfinishedSessionIsNotACompletedBar() {
        val today = LocalDate.parse("2026-09-23")
        val bars = listOf(day("2026-09-22", 10.00), day(today.toString(), 11.00))
        val morning = ZonedDateTime.of(today, LocalTime.of(10, 0), ZoneId.of("Asia/Shanghai"))
        val afterClose = ZonedDateTime.of(today, LocalTime.of(15, 10), ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(LocalDate.parse("2026-09-22")), LimitFlipDetector.completedBars(bars, morning).map { it.date })
        assertEquals(2, LimitFlipDetector.completedBars(bars, afterClose).size)
    }

    private fun sample(fourth: Double = 9.80) = listOf(
        day("2026-03-09", 9.09),
        day("2026-03-10", 10.00),
        day("2026-03-11", 11.00),
        day("2026-03-12", 9.90),
        day("2026-03-13", 10.20),
        day("2026-03-14", fourth)
    )

    private fun pattern(third: Double) = listOf(
        day("2026-03-09", 9.09),
        day("2026-03-10", 10.00),
        day("2026-03-11", 11.00),
        day("2026-03-12", 9.90),
        day("2026-03-13", third)
    )

    private fun day(date: String, close: Double, open: Double = close, low: Double? = null, high: Double? = null) =
        DailyBar(LocalDate.parse(date), open, close, high ?: maxOf(open, close), low ?: minOf(open, close), 1.0)
}
