package com.example.gptest.business

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.cos

class BoxDetectorTest {
    private val start = LocalDate.of(2026, 5, 1)
    private fun box(): List<DailyBar> = (0 until 60).map { index ->
        val close = 10.5 + 0.35 * cos(index * Math.PI / 3)
        DailyBar(start.plusDays(index.toLong()), close, close,
            if (index % 6 == 0) 11.0 else close + 0.05,
            if (index % 6 == 3) 10.0 else close - 0.05, 1000.0)
    }
    private fun tail(close: Double = 11.3) = (60..64).map { index ->
        DailyBar(start.plusDays(index.toLong()), close - 0.1, close, close + 0.1, close - 0.2, 1600.0)
    }
    private fun detect(rows: List<DailyBar>) = BoxDetector.detect(rows, (60..64).map { start.plusDays(it.toLong()) })

    @Test fun findsBoxWithFrozenBoundariesAndVolumeBaseline() {
        val result = detect(box() + tail())!!
        assertEquals(60, result.days)
        assertEquals(start.plusDays(60), result.breakout)
        assertEquals(11.0, result.upper, 0.001)
        assertEquals(10.0, result.lower, 0.001)
        assertEquals(1.6, result.volumeRatio!!, 0.001)
        assertTrue(result.stillAbove)
    }

    @Test fun aWickOrExactlyOnePercentDoesNotConfirmBreakout() {
        assertNull(detect(box() + tail(11.0).map { it.copy(high = 12.0) }))
        assertNull(detect(box() + tail(11.0 * 1.01)))
    }

    @Test fun retainsBreakoutAfterReturnInsideAndIgnoresFutureBars() {
        val rows = box() + tail().mapIndexed { index, bar ->
            if (index > 0) bar.copy(open = 10.8, close = 10.7, high = 10.9, low = 10.6) else bar
        }
        val result = detect(rows)!!
        assertFalse(result.stillAbove)
        val future = DailyBar(start.plusDays(65), 20.0, 30.0, 31.0, 19.0, 100000.0)
        assertEquals(result, detect(rows + future))
        assertEquals(result, detect(rows.reversed()))
    }

    @Test fun trendingNewHighIsNotABox() {
        val rows = (0..64).map { index ->
            val price = 10.0 + index * 0.04
            DailyBar(start.plusDays(index.toLong()), price, price, price + 0.05, price - 0.05, 1000.0)
        }
        assertNull(detect(rows))
    }

    @Test fun skipsOldAndShortHistoryAndHandlesZeroVolume() {
        assertNull(detect((box() + tail()).dropLast(1)))
        assertNull(detect((box() + tail()).takeLast(24)))
        assertNull(detect(box().map { it.copy(volume = 0.0) } + tail())!!.volumeRatio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedOhlc() {
        detect(box() + tail().map { it.copy(high = 1.0) })
    }

    @Test fun universeIncludesAStocksAndExcludesFundsIndicesAndBShares() {
        val codes = AStockUniverse.parse("sh600519|茅台\nsz000001|平安\nsz301001|股票\nbj920001|北交\nbj830001|北交\nsh000001|指数\nsh510300|ETF\nsz159001|ETF\nsh900901|B股\nsz200001|B股\nsh600519|重复")
        assertEquals(setOf("sh600519", "sz000001", "sz301001", "bj920001", "bj830001"), codes.map { it.code }.toSet())
    }

    @Test fun calendarUsesMarketSessionsRatherThanCalendarDaysOrStockHistory() {
        val dates = listOf("2026-09-04", "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-14")
        val bars = dates.map { DailyBar(LocalDate.parse(it), 10.0, 10.0, 10.0, 10.0, 100.0) }
        val morning = LocalDate.parse("2026-09-14").atTime(10, 0).atZone(TradingSession.SHANGHAI)
        assertEquals(LocalDate.parse("2026-09-11"), BoxMarketCalendar.completedDates(bars, morning).last())
        assertEquals(LocalDate.parse("2026-09-07"), BoxMarketCalendar.completedDates(bars, morning).first())
        assertEquals(LocalDate.parse("2026-09-14"), BoxMarketCalendar.completedDates(bars, morning.withHour(16)).last())
    }

    @Test fun configurableWidthAndBreakoutActuallyChangeSelection() {
        val dates = (60..64).map { start.plusDays(it.toLong()) }
        val rows = box() + tail()
        assertNull(BoxDetector.detect(rows, dates, BoxScanConfig(maxWidthPercent = 9.0)))
        assertNotNull(BoxDetector.detect(rows, dates, BoxScanConfig(maxWidthPercent = 11.0)))
        assertNull(BoxDetector.detect(rows, dates, BoxScanConfig(breakoutPercent = 4.0)))
        assertNotNull(BoxDetector.detect(rows, dates, BoxScanConfig(breakoutPercent = 2.0)))
    }

    @Test fun configurableWindowAffectsDetection() {
        val dates = (60..64).map { start.plusDays(it.toLong()) }
        val rows = box() + tail()
        val config = BoxScanConfig(windows = listOf(30))
        assertEquals(30, BoxDetector.detect(rows, dates, config)!!.days)
        assertEquals(260, BoxScanConfig(windows = listOf(250)).historyCount)
        assertEquals(255, BoxScanConfig(volumeDays = 250).minimumBars)
    }

    @Test fun singleHighAndLowAreEnoughWhenWidthAndTrendQualify() {
        val rows = (0 until 60).map { index ->
            DailyBar(start.plusDays(index.toLong()), 10.5, 10.5,
                if (index == 15) 11.0 else 10.6, if (index == 45) 10.0 else 10.4, 1000.0)
        } + tail()
        val hit = BoxDetector.detect(rows, (60..64).map { start.plusDays(it.toLong()) },
            BoxScanConfig(windows = listOf(60)))!!
        assertEquals(11.0, hit.upper, 0.001)
        assertEquals(10.0, hit.lower, 0.001)
        assertEquals(start.plusDays(60), hit.breakout)
    }

    @Test fun configurableVolumeBaselineAndInvalidInputs() {
        val dates = (60..64).map { start.plusDays(it.toLong()) }
        val rows = box().mapIndexed { i, bar -> bar.copy(volume = if (i >= 55) 2000.0 else 1000.0) } + tail()
        val hit = BoxDetector.detect(rows, dates, BoxScanConfig(volumeDays = 5))!!
        assertEquals(0.8, hit.volumeRatio!!, 0.001)
        assertThrows(IllegalArgumentException::class.java) { BoxScanConfig(maxWidthPercent = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BoxScanConfig(windows = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { BoxScanConfig(windows = listOf(300)) }
        assertThrows(IllegalArgumentException::class.java) { BoxScanConfig(volumeRatio = 0.0) }
    }
}
