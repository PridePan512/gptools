package com.example.gptest.business

import java.time.LocalDate

data class DailyClose(val date: LocalDate, val close: Double)

data class MovingAverages(val date: LocalDate, val values: Map<Int, Double?>)

object MovingAverageCalculator {
    val periods = listOf(5, 10, 20, 60, 120, 250)

    fun calculate(rows: List<DailyClose>): MovingAverages {
        require(rows.isNotEmpty()) { "No daily prices" }
        require(rows.all { it.close.isFinite() && it.close > 0 }) { "Invalid close" }
        require(rows.map { it.date }.distinct().size == rows.size) { "Duplicate dates" }
        val sorted = rows.sortedBy { it.date }
        return MovingAverages(sorted.last().date, periods.associateWith { period ->
            if (sorted.size < period) null else sorted.takeLast(period).map { it.close }.average()
        })
    }
}
