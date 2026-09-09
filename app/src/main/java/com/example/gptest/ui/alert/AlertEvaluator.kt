package com.example.gptest.ui.alert

import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.TradingSession
import com.example.gptest.business.QuoteParser
import java.time.Instant
import java.time.ZonedDateTime

data class AlertFire(
    val rule: AlertRule,
    val detail: String
)

data class AlertTickResult(
    val updatedRules: List<AlertRule>,
    val fires: List<AlertFire>
)

object AlertEvaluator {

    const val COOLDOWN_MS = 10 * 60 * 1000L
    const val PRICE_LOOKBACK_MS = 30_000L
    const val VOLUME_SHORT_MS = 15_000L
    const val VOLUME_BASELINE_MS = 60_000L
    private const val VOLUME_INDEX = 6
    private const val AMOUNT_INDEX = 37
    private const val TURNOVER_INDEX = 38
    private const val AMPLITUDE_INDEX = 43
    private const val PRICE_MOVE_EPS = 1e-8
    private const val PRICE_MIN_LOOKBACK_MS = 15_000L
    private const val PRICE_MAX_LOOKBACK_MS = 90_000L
    private const val VOLUME_SHORT_MIN_MS = 8_000L
    private const val VOLUME_SHORT_MAX_MS = 30_000L
    private const val VOLUME_BASELINE_MIN_MS = 40_000L
    private const val VOLUME_BASELINE_MAX_MS = 90_000L
    private const val VOLUME_MIN_HANDS = 200.0

    fun evaluate(
        rules: List<AlertRule>,
        current: List<QuoteSnapshot>,
        previous: List<QuoteSnapshot>,
        nowMs: Long,
        older: List<QuoteSnapshot> = emptyList(),
        intervalSeconds: Long = 5L,
        thresholds: RapidAlertThresholds = RapidAlertThresholds.DEFAULT,
        history: List<QuoteSnapshot> = older
    ): AlertTickResult {
        val currentByCode = current.associateBy { it.requestCode }
        val previousByCode = previous.associateBy { it.requestCode }
        val historyByCode = history.groupBy { it.requestCode }
        val fires = mutableListOf<AlertFire>()
        val updated = rules.map { rule ->
            if (!rule.enabled) {
                return@map rule.copy(status = AlertRuleStatus.OFF)
            }
            if (rule.conditions.isEmpty()) {
                return@map rule.copy(status = AlertRuleStatus.WAITING)
            }
            val matched = when (rule.matchMode) {
                AlertMatchMode.ALL -> rule.conditions.all {
                    isSatisfied(it, currentByCode, previousByCode, historyByCode, intervalSeconds, thresholds)
                }
                AlertMatchMode.ANY -> rule.conditions.any {
                    isSatisfied(it, currentByCode, previousByCode, historyByCode, intervalSeconds, thresholds)
                }
            }
            val status = if (matched) AlertRuleStatus.FIRED else AlertRuleStatus.WAITING
            if (matched && shouldNotify(rule, nowMs)) {
                val detail = rule.conditions.joinToString("；") {
                    conditionDetail(it, currentByCode, previousByCode, historyByCode)
                }
                val next = rule.copy(
                    status = status,
                    lastTriggeredMs = nowMs,
                    onceConsumed = rule.notifyMode == AlertNotifyMode.ONCE || rule.onceConsumed
                )
                fires += AlertFire(next, detail)
                next
            } else {
                rule.copy(status = status)
            }
        }
        return AlertTickResult(updated, fires)
    }

    fun shouldNotify(rule: AlertRule, nowMs: Long): Boolean {
        if (!rule.enabled || rule.conditions.isEmpty()) return false
        return when (rule.notifyMode) {
            AlertNotifyMode.ONCE -> !rule.onceConsumed
            AlertNotifyMode.ALWAYS -> true
            AlertNotifyMode.COOLDOWN -> {
                val last = rule.lastTriggeredMs ?: return true
                nowMs - last >= COOLDOWN_MS
            }
        }
    }

    fun isSatisfied(
        condition: AlertCondition,
        currentByCode: Map<String, QuoteSnapshot>,
        previousByCode: Map<String, QuoteSnapshot>,
        historyByCode: Map<String, List<QuoteSnapshot>> = emptyMap(),
        intervalSeconds: Long = 5L,
        thresholds: RapidAlertThresholds = RapidAlertThresholds.DEFAULT
    ): Boolean {
        val quote = currentByCode[condition.stock.code]
        if (!condition.operator.needsValue) {
            return when (condition.operator) {
                AlertOperator.VOLUME_SURGE,
                AlertOperator.VOLUME_SHRINK,
                AlertOperator.PRICE_SURGE,
                AlertOperator.PRICE_DROP -> rapidSatisfied(
                    condition.operator,
                    seriesFor(condition.stock.code, quote, previousByCode[condition.stock.code], historyByCode),
                    thresholds
                )
                else -> boardSatisfied(condition.operator, quote, previousByCode[condition.stock.code])
            }
        }
        val current = metricValue(quote, condition.metric) ?: return false
        val target = targetValue(condition, currentByCode) ?: return false
        return when (condition.operator) {
            AlertOperator.GTE -> current >= target
            AlertOperator.LTE -> current <= target
            AlertOperator.CROSS_UP -> {
                val previous = metricValue(previousByCode[condition.stock.code], condition.metric) ?: return false
                val previousTarget = targetValue(condition, previousByCode) ?: target
                previous < previousTarget && current >= target
            }
            AlertOperator.CROSS_DOWN -> {
                val previous = metricValue(previousByCode[condition.stock.code], condition.metric) ?: return false
                val previousTarget = targetValue(condition, previousByCode) ?: target
                previous > previousTarget && current <= target
            }
            AlertOperator.LIMIT_UP,
            AlertOperator.LIMIT_DOWN,
            AlertOperator.OPEN_LIMIT_UP,
            AlertOperator.OPEN_LIMIT_DOWN,
            AlertOperator.VOLUME_SURGE,
            AlertOperator.VOLUME_SHRINK,
            AlertOperator.PRICE_SURGE,
            AlertOperator.PRICE_DROP -> false
        }
    }

    fun metricValue(quote: QuoteSnapshot?, metric: AlertMetric): Double? {
        if (quote == null) return null
        val raw = when (metric) {
            AlertMetric.PRICE -> quote.price
            AlertMetric.CHANGE_PERCENT -> quote.changePercent
            AlertMetric.AMOUNT -> quote.fields.getOrNull(AMOUNT_INDEX)
            AlertMetric.TURNOVER -> quote.fields.getOrNull(TURNOVER_INDEX)
            AlertMetric.AMPLITUDE -> quote.fields.getOrNull(AMPLITUDE_INDEX)
        }
        return numeric(raw)
    }

    fun formatTriggered(ms: Long?, now: ZonedDateTime = ZonedDateTime.now(TradingSession.SHANGHAI)): String? {
        if (ms == null) return null
        val triggered = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), TradingSession.SHANGHAI)
        val time = "%02d:%02d".format(triggered.hour, triggered.minute)
        val today = now.toLocalDate()
        val date = triggered.toLocalDate()
        return when (date) {
            today -> "今天 $time"
            today.minusDays(1) -> "昨天 $time"
            else -> "${date.monthValue}/${date.dayOfMonth} $time"
        }
    }

    private fun targetValue(
        condition: AlertCondition,
        quotes: Map<String, QuoteSnapshot>
    ): Double? {
        val compare = condition.compareStock
        if (compare != null) {
            return metricValue(quotes[compare.code], condition.metric)
        }
        return numeric(condition.numberValue)
    }

    private fun conditionDetail(
        condition: AlertCondition,
        currentByCode: Map<String, QuoteSnapshot>,
        previousByCode: Map<String, QuoteSnapshot>,
        historyByCode: Map<String, List<QuoteSnapshot>>
    ): String {
        val quote = currentByCode[condition.stock.code]
        if (condition.operator == AlertOperator.VOLUME_SURGE ||
            condition.operator == AlertOperator.VOLUME_SHRINK
        ) {
            val windows = volumeWindows(
                seriesFor(condition.stock.code, quote, previousByCode[condition.stock.code], historyByCode)
            )
            if (windows != null) {
                val recentLabel = "${windows.recentMs / 1000}秒"
                val baselineLabel = "${windows.baselineMs / 1000}秒"
                return "${condition.sentence()}（近$recentLabel ${formatHands(windows.recent)} 手 / 此前$baselineLabel ${formatHands(windows.baseline)} 手）"
            }
        }
        if (!condition.operator.needsValue) {
            val price = metricValue(quote, AlertMetric.PRICE)?.let { formatMetric(it, AlertMetric.PRICE) } ?: "--"
            return "${condition.sentence()}（当前 $price）"
        }
        val current = metricValue(quote, condition.metric)
        val shown = current?.let { formatMetric(it, condition.metric) } ?: "--"
        return "${condition.sentence()}（当前 $shown）"
    }

    private fun rapidSatisfied(
        operator: AlertOperator,
        series: List<QuoteSnapshot>,
        thresholds: RapidAlertThresholds
    ): Boolean {
        return when (operator) {
            AlertOperator.PRICE_SURGE, AlertOperator.PRICE_DROP -> {
                val current = series.lastOrNull() ?: return false
                val currentPrice = numeric(current.price) ?: return false
                val anchor = nearestBefore(
                    series,
                    current.fetchedAtMs,
                    PRICE_LOOKBACK_MS,
                    PRICE_MIN_LOOKBACK_MS,
                    PRICE_MAX_LOOKBACK_MS
                ) ?: return false
                val previousPrice = numeric(anchor.price) ?: return false
                if (previousPrice <= 0.0) return false
                val elapsedMs = current.fetchedAtMs - anchor.fetchedAtMs
                val moveRatio = if (operator == AlertOperator.PRICE_SURGE) {
                    thresholds.priceSurgeRatio
                } else {
                    thresholds.priceDropRatio
                }
                val scale = (elapsedMs.toDouble() / PRICE_LOOKBACK_MS).coerceAtLeast(1.0)
                val threshold = moveRatio * scale
                val change = (currentPrice - previousPrice) / previousPrice
                if (operator == AlertOperator.PRICE_SURGE) {
                    change + PRICE_MOVE_EPS >= threshold
                } else {
                    change - PRICE_MOVE_EPS <= -threshold
                }
            }
            AlertOperator.VOLUME_SURGE, AlertOperator.VOLUME_SHRINK -> {
                val windows = volumeWindows(series) ?: return false
                if (operator == AlertOperator.VOLUME_SURGE) {
                    if (windows.recent + PRICE_MOVE_EPS < VOLUME_MIN_HANDS) return false
                    if (windows.baseline <= 0.0) return true
                    windows.recentRate + PRICE_MOVE_EPS >= windows.baselineRate * thresholds.volumeSurgeRatio
                } else {
                    if (windows.baseline + PRICE_MOVE_EPS < VOLUME_MIN_HANDS) return false
                    if (windows.baselineRate <= 0.0) return false
                    windows.recentRate - PRICE_MOVE_EPS <= windows.baselineRate * thresholds.volumeShrinkRatio
                }
            }
            else -> false
        }
    }

    private fun seriesFor(
        code: String,
        current: QuoteSnapshot?,
        previous: QuoteSnapshot?,
        historyByCode: Map<String, List<QuoteSnapshot>>
    ): List<QuoteSnapshot> {
        val ticks = ArrayList<QuoteSnapshot>()
        historyByCode[code]?.let { ticks.addAll(it) }
        if (previous != null) ticks.add(previous)
        if (current != null) ticks.add(current)
        return ticks
            .filter { it.fetchedAtMs >= 0L }
            .distinctBy { it.fetchedAtMs }
            .sortedBy { it.fetchedAtMs }
    }

    private fun volumeWindows(series: List<QuoteSnapshot>): VolumeWindows? {
        val current = series.lastOrNull() ?: return null
        val now = current.fetchedAtMs
        val shortAnchor = nearestBefore(
            series,
            now,
            VOLUME_SHORT_MS,
            VOLUME_SHORT_MIN_MS,
            VOLUME_SHORT_MAX_MS
        ) ?: return null
        val baselineAnchor = nearestBefore(
            series,
            now,
            VOLUME_BASELINE_MS,
            VOLUME_BASELINE_MIN_MS,
            VOLUME_BASELINE_MAX_MS
        ) ?: return null
        if (baselineAnchor.fetchedAtMs >= shortAnchor.fetchedAtMs) return null
        val currentVol = numeric(current.fields.getOrNull(VOLUME_INDEX)) ?: return null
        val shortVol = numeric(shortAnchor.fields.getOrNull(VOLUME_INDEX)) ?: return null
        val baselineVol = numeric(baselineAnchor.fields.getOrNull(VOLUME_INDEX)) ?: return null
        val recent = currentVol - shortVol
        val baseline = shortVol - baselineVol
        if (recent < 0.0 || baseline < 0.0) return null
        val recentMs = now - shortAnchor.fetchedAtMs
        val baselineMs = shortAnchor.fetchedAtMs - baselineAnchor.fetchedAtMs
        if (recentMs <= 0L || baselineMs <= 0L) return null
        return VolumeWindows(recent, baseline, recentMs, baselineMs)
    }

    private fun nearestBefore(
        series: List<QuoteSnapshot>,
        nowMs: Long,
        targetLookbackMs: Long,
        minLookbackMs: Long,
        maxLookbackMs: Long
    ): QuoteSnapshot? {
        if (nowMs < 0L) return null
        val minMs = nowMs - maxLookbackMs
        val maxMs = nowMs - minLookbackMs
        val targetMs = nowMs - targetLookbackMs
        return series
            .filter { it.fetchedAtMs in minMs..maxMs }
            .minByOrNull { kotlin.math.abs(it.fetchedAtMs - targetMs) }
    }

    private fun formatHands(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }

    private fun boardSatisfied(
        operator: AlertOperator,
        current: QuoteSnapshot?,
        previous: QuoteSnapshot?
    ): Boolean {
        return when (operator) {
            AlertOperator.LIMIT_UP -> QuoteParser.isAtLimitUp(current)
            AlertOperator.LIMIT_DOWN -> QuoteParser.isAtLimitDown(current)
            AlertOperator.OPEN_LIMIT_UP -> current != null && QuoteParser.isAtLimitUp(previous) && !QuoteParser.isAtLimitUp(current)
            AlertOperator.OPEN_LIMIT_DOWN -> current != null && QuoteParser.isAtLimitDown(previous) && !QuoteParser.isAtLimitDown(current)
            else -> false
        }
    }

    private fun formatMetric(value: Double, metric: AlertMetric): String {
        val text = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
        return text + metric.suffix
    }

    private fun numeric(raw: String?): Double? {
        val value = raw?.trim()?.removeSuffix("%")?.removePrefix("+")
        if (value.isNullOrEmpty() || value == "--") return null
        return value.toDoubleOrNull()
    }
}

private data class VolumeWindows(
    val recent: Double,
    val baseline: Double,
    val recentMs: Long,
    val baselineMs: Long
) {
    val recentRate: Double get() = recent / recentMs
    val baselineRate: Double get() = baseline / baselineMs
}
