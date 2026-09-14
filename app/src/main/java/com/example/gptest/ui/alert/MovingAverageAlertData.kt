package com.example.gptest.ui.alert

import com.example.gptest.business.DailyClose
import com.example.gptest.business.TradingSession
import com.example.gptest.data.DailyHistory
import com.example.gptest.data.HistoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class MovingAverageReference(val date: LocalDate, val levels: Map<Int, Double>) {
    companion object {
        fun from(rows: List<DailyClose>, date: LocalDate): MovingAverageReference {
            // Require a current trading-day bar so old adjustment data cannot trigger an alert.
            if (rows.maxOfOrNull { it.date } != date) return MovingAverageReference(date, emptyMap())
            val closed = rows.filter { it.date < date }.sortedBy { it.date }
            val levels = AlertOperator.entries.mapNotNull { it.maPeriod }.associateWith { period ->
                if (closed.size < period - 1) null else closed.takeLast(period - 1).map { it.close }.average()
            }.mapNotNull { (period, value) -> value?.takeIf { it.isFinite() && it > 0 }?.let { period to it } }.toMap()
            // P < (sum(previous N-1 closes) + P) / N iff P < mean(previous N-1 closes).
            return MovingAverageReference(date, levels)
        }
    }
}

class MovingAverageAlertData(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val fetch: (String) -> Result<DailyHistory> = HistoryRepository()::fetchDaily
) {
    private data class Entry(val date: LocalDate, val attemptedAt: Long, val reference: MovingAverageReference?)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(3)

    fun references(codes: Set<String>, nowMs: Long): Map<String, MovingAverageReference> {
        val today = Instant.ofEpochMilli(nowMs).atZone(TradingSession.SHANGHAI).toLocalDate()
        entries.keys.retainAll(codes)
        codes.forEach { code ->
            val entry = entries[code]
            if ((entry == null || entry.date != today ||
                    (entry.reference == null && nowMs - entry.attemptedAt >= 60_000L)) && loading.add(code)) {
                scope.launch(dispatcher) {
                    try {
                        val reference = permits.withPermit {
                            fetch(code).getOrNull()?.let { MovingAverageReference.from(it.closes, today) }
                                ?.takeIf { it.levels.isNotEmpty() }
                        }
                        entries[code] = Entry(today, nowMs, reference)
                    } finally {
                        loading.remove(code)
                    }
                }
            }
        }
        return codes.mapNotNull { code ->
            entries[code]?.takeIf { it.date == today }?.reference?.let { code to it }
        }.toMap()
    }
}
