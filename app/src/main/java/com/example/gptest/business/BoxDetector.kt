package com.example.gptest.business

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

data class DailyBar(val date: LocalDate, val open: Double, val close: Double,
                    val high: Double, val low: Double, val volume: Double)

data class BoxBreakout(
    val start: LocalDate, val end: LocalDate, val breakout: LocalDate,
    val days: Int, val lower: Double, val upper: Double,
    val breakoutClose: Double,
    val volumeRatio: Double?, val latestClose: Double, val latestDate: LocalDate
) {
    val widthPercent get() = (upper / lower - 1) * 100
    val breakoutPercent get() = (breakoutClose / upper - 1) * 100
    val stillAbove get() = latestClose > upper
}

object BoxDetector {
    const val VERSION = 2

    fun detect(bars: List<DailyBar>, marketDates: List<LocalDate>, config: BoxScanConfig = BoxScanConfig()): BoxBreakout? {
        require(marketDates.size == 5 && marketDates.distinct().size == 5)
        val dates = marketDates.sorted()
        val rows = bars.filter { it.date <= dates.last() }.sortedBy { it.date }
        require(rows.map { it.date }.distinct().size == rows.size)
        require(rows.all { bar ->
            listOf(bar.open, bar.close, bar.high, bar.low, bar.volume).all { it.isFinite() } &&
                bar.low > 0 && bar.volume >= 0 && bar.high >= bar.low &&
                bar.open in bar.low..bar.high && bar.close in bar.low..bar.high
        })
        if (rows.size < config.minimumBars || rows.last().date != dates.last()) return null
        for (index in rows.indices.reversed()) {
            val candle = rows[index]
            if (candle.date !in dates) continue
            for (length in config.windows.sortedDescending()) {
                if (index < maxOf(length, config.volumeDays)) continue
                val box = rows.subList(index - length, index)
                val upper = box.maxOf { it.high }
                val lower = box.minOf { it.low }
                val width = upper - lower
                if (width <= 0 || width / lower > config.maxWidthPercent / 100) continue
                if (candle.close <= upper * (1 + config.breakoutPercent / 100)) continue
                // Reject a steadily rising/falling channel masquerading as a rectangle.
                val meanX = (length - 1) / 2.0
                val meanY = box.map { it.close }.average()
                val slope = box.indices.sumOf { (it - meanX) * (box[it].close - meanY) } /
                    box.indices.sumOf { (it - meanX) * (it - meanX) }
                if (abs(slope * (length - 1)) > width * config.slopeWidthPercent / 100) continue
                val third = length / 3
                if (abs(box.take(third).map { it.close }.average() -
                            box.takeLast(third).map { it.close }.average()) > width * config.meanWidthPercent / 100) continue
                val averageVolume = rows.subList(index - config.volumeDays, index).map { it.volume }.average()
                return BoxBreakout(box.first().date, box.last().date, candle.date, length, lower, upper,
                    candle.close, if (averageVolume > 0) candle.volume / averageVolume else null,
                    rows.last().close, rows.last().date)
            }
        }
        return null
    }

}

object AStockUniverse {
    fun parse(catalog: String): List<StockCatalogEntry> = catalog.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (parts.size != 2) return@mapNotNull null
        val code = parts[0]
        if (!Regex("(?:sh(?:600|601|603|605|688)\\d{3}|sz(?:000|001|002|003|300|301)\\d{3}|bj(?:4|8|92)\\d{4,5})").matches(code) || code.length != 8) return@mapNotNull null
        StockCatalogEntry(code, parts[1])
    }.distinctBy { it.code }.sortedWith(compareBy<StockCatalogEntry> {
        when { it.code.startsWith("sh") -> 0; it.code.startsWith("sz") -> 1; else -> 2 }
    }.thenBy { it.code }).toList()
}

object BoxMarketCalendar {
    fun completedDates(bars: List<DailyBar>, now: ZonedDateTime): List<LocalDate> {
        val local = now.withZoneSameInstant(TradingSession.SHANGHAI)
        return bars.map { it.date }.distinct().filter {
            it < local.toLocalDate() || (it == local.toLocalDate() && local.toLocalTime() >= LocalTime.of(15, 10))
        }.sorted().takeLast(5)
    }
}
