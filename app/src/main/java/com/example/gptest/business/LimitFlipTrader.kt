package com.example.gptest.business

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.min

data class LimitFlipTrade(
    val entryDate: LocalDate,
    val entryPrice: Double,
    val exitDate: LocalDate?,
    val exitPrice: Double,
    val reason: String,
    val accountPnlPercent: Double,
    val adds: Int,
    val halves: Int
)

object LimitFlipTrader {
    private val CAPITAL = BigDecimal("10000")
    private val STAKE = BigDecimal("1000")
    private const val HOLD_DAYS = 3

    @Suppress("UNUSED_PARAMETER")
    fun simulate(bars: List<DailyBar>, entryIndex: Int, stopLow: Double, ratio: BigDecimal): LimitFlipTrade {
        val entryBar = bars[entryIndex]
        val entry = LimitFlipDetector.cents(entryBar.close)
        val stop = LimitFlipDetector.cents(stopLow)
        val target = entry.multiply(BigDecimal("1.08")).setScale(2, RoundingMode.HALF_UP)
        val shares = STAKE.divide(entry, 8, RoundingMode.HALF_UP)
        val book = Book(shares, CAPITAL.subtract(shares.multiply(entry)))
        val lastIndex = min(bars.lastIndex, entryIndex + HOLD_DAYS)
        for (index in (entryIndex + 1)..lastIndex) {
            val bar = bars[index]
            val open = LimitFlipDetector.cents(bar.open)
            val high = LimitFlipDetector.cents(bar.high)
            val low = LimitFlipDetector.cents(bar.low)
            val close = LimitFlipDetector.cents(bar.close)
            if (open <= stop) return finish(book, entryBar.date, entry, bar.date, open, "跌破清仓")
            if (open >= target) return finish(book, entryBar.date, entry, bar.date, open, "止盈")
            if (low <= stop) return finish(book, entryBar.date, entry, bar.date, stop, "跌破清仓")
            if (high >= target) return finish(book, entryBar.date, entry, bar.date, target, "止盈")
            if (index == entryIndex + HOLD_DAYS) return finish(book, entryBar.date, entry, bar.date, close, "到期")
        }
        val mark = bars.last()
        return markToMarket(book, entryBar.date, entry, mark.date, LimitFlipDetector.cents(mark.close), "持仓中")
    }

    private fun finish(
        book: Book,
        entryDate: LocalDate,
        entry: BigDecimal,
        date: LocalDate,
        price: BigDecimal,
        reason: String
    ): LimitFlipTrade {
        book.cash = book.cash.add(book.shares.multiply(price))
        book.shares = BigDecimal.ZERO
        return markToMarket(book, entryDate, entry, date, price, reason)
    }

    private fun markToMarket(
        book: Book,
        entryDate: LocalDate,
        entry: BigDecimal,
        date: LocalDate,
        price: BigDecimal,
        reason: String
    ): LimitFlipTrade {
        val equity = book.cash.add(book.shares.multiply(price))
        val pnl = equity.subtract(CAPITAL).multiply(BigDecimal(100)).divide(CAPITAL, 2, RoundingMode.HALF_UP)
        return LimitFlipTrade(entryDate, entry.toDouble(), date, price.toDouble(), reason, pnl.toDouble(), 0, 0)
    }

    private class Book(var shares: BigDecimal, var cash: BigDecimal)
}
