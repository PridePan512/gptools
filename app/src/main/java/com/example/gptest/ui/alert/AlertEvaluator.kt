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
    private const val VOLUME_INDEX = 6
    private const val AMOUNT_INDEX = 37
    private const val TURNOVER_INDEX = 38
    private const val AMPLITUDE_INDEX = 43
    private const val PRICE_MOVE_EPS = 1e-8
    private const val WINDOW_MIN_RATIO = 0.5
    private const val WINDOW_MAX_RATIO = 2.0

    fun evaluate(
        rules: List<AlertRule>,
        current: List<QuoteSnapshot>,
        previous: List<QuoteSnapshot>,
        nowMs: Long,
        older: List<QuoteSnapshot> = emptyList(),
        intervalSeconds: Long = 5L,
        thresholds: RapidAlertThresholds = RapidAlertThresholds.DEFAULT
    ): AlertTickResult {
        val currentByCode = current.associateBy { it.requestCode }
        val previousByCode = previous.associateBy { it.requestCode }
        val olderByCode = older.associateBy { it.requestCode }
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
                    isSatisfied(it, currentByCode, previousByCode, olderByCode, intervalSeconds, thresholds)
                }
                AlertMatchMode.ANY -> rule.conditions.any {
                    isSatisfied(it, currentByCode, previousByCode, olderByCode, intervalSeconds, thresholds)
                }
            }
            val status = if (matched) AlertRuleStatus.FIRED else AlertRuleStatus.WAITING
            if (matched && shouldNotify(rule, nowMs)) {
                val detail = rule.conditions.joinToString("；") {
                    conditionDetail(it, currentByCode, previousByCode, olderByCode)
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
        olderByCode: Map<String, QuoteSnapshot> = emptyMap(),
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
                    quote,
                    previousByCode[condition.stock.code],
                    olderByCode[condition.stock.code],
                    intervalSeconds,
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
        olderByCode: Map<String, QuoteSnapshot>
    ): String {
        val quote = currentByCode[condition.stock.code]
        if (condition.operator == AlertOperator.VOLUME_SURGE ||
            condition.operator == AlertOperator.VOLUME_SHRINK
        ) {
            val deltas = volumeDeltas(
                quote,
                previousByCode[condition.stock.code],
                olderByCode[condition.stock.code]
            )
            if (deltas != null) {
                return "${condition.sentence()}（本口 ${formatHands(deltas.current)} 手 / 上一口 ${formatHands(deltas.previous)} 手）"
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
        current: QuoteSnapshot?,
        previous: QuoteSnapshot?,
        older: QuoteSnapshot?,
        intervalSeconds: Long,
        thresholds: RapidAlertThresholds
    ): Boolean {
        return when (operator) {
            AlertOperator.PRICE_SURGE, AlertOperator.PRICE_DROP -> {
                val currentPrice = numeric(current?.price) ?: return false
                val previousPrice = numeric(previous?.price) ?: return false
                if (previousPrice <= 0.0) return false
                val elapsedMs = elapsedMs(current, previous) ?: return false
                if (!windowMatchesInterval(elapsedMs, intervalSeconds)) return false
                val expectedMs = intervalSeconds * 1000.0
                val moveRatio = if (operator == AlertOperator.PRICE_SURGE) {
                    thresholds.priceSurgeRatio
                } else {
                    thresholds.priceDropRatio
                }
                val threshold = moveRatio * (elapsedMs / expectedMs)
                val change = (currentPrice - previousPrice) / previousPrice
                if (operator == AlertOperator.PRICE_SURGE) {
                    change + PRICE_MOVE_EPS >= threshold
                } else {
                    change - PRICE_MOVE_EPS <= -threshold
                }
            }
            AlertOperator.VOLUME_SURGE, AlertOperator.VOLUME_SHRINK -> {
                val deltas = volumeDeltas(current, previous, older) ?: return false
                val prevWindowMs = elapsedMs(previous, older) ?: return false
                val currWindowMs = elapsedMs(current, previous) ?: return false
                if (!windowMatchesInterval(prevWindowMs, intervalSeconds)) return false
                if (!windowMatchesInterval(currWindowMs, intervalSeconds)) return false
                if (!windowsComparable(prevWindowMs, currWindowMs)) return false
                val prevRate = deltas.previous / prevWindowMs
                val currRate = deltas.current / currWindowMs
                if (operator == AlertOperator.VOLUME_SURGE) {
                    currRate >= prevRate * thresholds.volumeSurgeRatio
                } else {
                    currRate <= prevRate * thresholds.volumeShrinkRatio
                }
            }
            else -> false
        }
    }

    private fun elapsedMs(later: QuoteSnapshot?, earlier: QuoteSnapshot?): Long? {
        val laterMs = later?.fetchedAtMs ?: return null
        val earlierMs = earlier?.fetchedAtMs ?: return null
        if (laterMs <= 0L || earlierMs <= 0L) return null
        val elapsed = laterMs - earlierMs
        return elapsed.takeIf { it > 0L }
    }

    private fun windowMatchesInterval(elapsedMs: Long, intervalSeconds: Long): Boolean {
        val expectedMs = intervalSeconds * 1000.0
        if (expectedMs <= 0.0) return false
        val ratio = elapsedMs / expectedMs
        return ratio >= WINDOW_MIN_RATIO && ratio <= WINDOW_MAX_RATIO
    }

    private fun windowsComparable(previousMs: Long, currentMs: Long): Boolean {
        if (previousMs <= 0L || currentMs <= 0L) return false
        val ratio = currentMs.toDouble() / previousMs
        return ratio >= WINDOW_MIN_RATIO && ratio <= WINDOW_MAX_RATIO
    }

    private fun volumeDeltas(
        current: QuoteSnapshot?,
        previous: QuoteSnapshot?,
        older: QuoteSnapshot?
    ): VolumeDeltas? {
        val currentVol = numeric(current?.fields?.getOrNull(VOLUME_INDEX)) ?: return null
        val previousVol = numeric(previous?.fields?.getOrNull(VOLUME_INDEX)) ?: return null
        val olderVol = numeric(older?.fields?.getOrNull(VOLUME_INDEX)) ?: return null
        val prevDelta = previousVol - olderVol
        val currDelta = currentVol - previousVol
        if (prevDelta <= 0.0 || currDelta < 0.0) return null
        return VolumeDeltas(currDelta, prevDelta)
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

private data class VolumeDeltas(
    val current: Double,
    val previous: Double
)
