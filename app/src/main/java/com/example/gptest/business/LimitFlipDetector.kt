package com.example.gptest.business

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class LimitFlip(
    val limitUpDate: LocalDate,
    val limitDownDate: LocalDate,
    val limitUpClose: Double,
    val nextLow: Double,
    val nextClose: Double,
    val closedLimitDown: Boolean,
    val limitUpBar: DailyBar,
    val limitDownBar: DailyBar,
    val thirdBar: DailyBar,
    val fourthBar: DailyBar?,
    val limitUpBoards: Int,
    val confirmedOnFourth: Boolean,
    val trade: LimitFlipTrade
)

object LimitFlipDetector {
    const val VERSION = 9
    const val HISTORY_BARS = 320
    val LIMIT_RATIO: BigDecimal = BigDecimal("0.10")

    fun included(code: String): Boolean {
        val normalized = code.lowercase()
        return !normalized.startsWith("bj") &&
            !normalized.startsWith("sh688") &&
            !normalized.startsWith("sz300") &&
            !normalized.startsWith("sz301")
    }

    fun completedBars(bars: List<DailyBar>, now: ZonedDateTime): List<DailyBar> {
        val local = now.withZoneSameInstant(TradingSession.SHANGHAI)
        return bars.filter {
            it.date < local.toLocalDate() ||
                (it.date == local.toLocalDate() && !local.toLocalTime().isBefore(LocalTime.of(15, 10)))
        }.sortedBy { it.date }
    }

    fun find(bars: List<DailyBar>, ratio: BigDecimal, windowStart: LocalDate): List<LimitFlip> {
        val rows = bars.sortedBy { it.date }
        return rows.indices.drop(2).mapNotNull { index ->
            if (index + 2 >= rows.size) return@mapNotNull null
            val limitUpDay = rows[index]
            val limitDownDay = rows[index + 1]
            val third = rows[index + 2]
            val fourth = rows.getOrNull(index + 3)
            val boards = consecutiveLimitUps(rows, index, ratio)
            if (boards < 2) return@mapNotNull null
            val down = limitPrice(limitUpDay.close, ratio, up = false)
            if (cents(limitDownDay.close).compareTo(down) != 0) return@mapNotNull null
            val thirdRed = cents(third.close).compareTo(cents(limitDownDay.close)) > 0
            val rebound = cents(limitDownDay.close).multiply(BigDecimal("1.03")).setScale(2, RoundingMode.HALF_UP)
            val fourthRecovered = fourth != null && cents(fourth.close).compareTo(rebound) >= 0
            if (!thirdRed && !fourthRecovered) return@mapNotNull null
            val entryIndex = if (thirdRed) index + 2 else index + 3
            if (rows[entryIndex].date < windowStart) return@mapNotNull null
            if (cents(rows[entryIndex].close).compareTo(BigDecimal.ZERO) <= 0) return@mapNotNull null
            LimitFlip(
                limitUpDay.date,
                limitDownDay.date,
                limitUpDay.close,
                limitDownDay.low,
                limitDownDay.close,
                true,
                limitUpDay,
                limitDownDay,
                third,
                fourth,
                boards,
                confirmedOnFourth = !thirdRed,
                trade = LimitFlipTrader.simulate(rows, entryIndex, limitDownDay.low, ratio)
            )
        }
    }

    private fun consecutiveLimitUps(rows: List<DailyBar>, end: Int, ratio: BigDecimal): Int {
        var count = 0
        var cursor = end
        while (cursor >= 1) {
            val up = limitPrice(rows[cursor - 1].close, ratio, up = true)
            if (cents(rows[cursor].close).compareTo(up) != 0) break
            count++
            cursor--
        }
        return count
    }

    internal fun limitPrice(previousClose: Double, ratio: BigDecimal, up: Boolean): BigDecimal {
        val factor = if (up) BigDecimal.ONE.add(ratio) else BigDecimal.ONE.subtract(ratio)
        return BigDecimal.valueOf(previousClose).multiply(factor).setScale(2, RoundingMode.HALF_UP)
    }

    internal fun cents(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
}
