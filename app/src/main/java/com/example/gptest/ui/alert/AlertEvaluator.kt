package com.example.gptest.ui.alert

import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.TradingSession
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
    private const val AMOUNT_INDEX = 37
    private const val TURNOVER_INDEX = 38
    private const val AMPLITUDE_INDEX = 43

    fun evaluate(
        rules: List<AlertRule>,
        current: List<QuoteSnapshot>,
        previous: List<QuoteSnapshot>,
        nowMs: Long
    ): AlertTickResult {
        val currentByCode = current.associateBy { it.requestCode }
        val previousByCode = previous.associateBy { it.requestCode }
        val fires = mutableListOf<AlertFire>()
        val updated = rules.map { rule ->
            if (!rule.enabled) {
                return@map rule.copy(status = AlertRuleStatus.OFF)
            }
            if (rule.conditions.isEmpty()) {
                return@map rule.copy(status = AlertRuleStatus.WAITING)
            }
            val matched = when (rule.matchMode) {
                AlertMatchMode.ALL -> rule.conditions.all { isSatisfied(it, currentByCode, previousByCode) }
                AlertMatchMode.ANY -> rule.conditions.any { isSatisfied(it, currentByCode, previousByCode) }
            }
            val status = if (matched) AlertRuleStatus.FIRED else AlertRuleStatus.WAITING
            if (matched && shouldNotify(rule, nowMs)) {
                val detail = rule.conditions.joinToString("；") { conditionDetail(it, currentByCode) }
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
        previousByCode: Map<String, QuoteSnapshot>
    ): Boolean {
        val current = metricValue(currentByCode[condition.stock.code], condition.metric) ?: return false
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
        quotes: Map<String, QuoteSnapshot>
    ): String {
        val current = metricValue(quotes[condition.stock.code], condition.metric)
        val shown = current?.let { formatMetric(it, condition.metric) } ?: "--"
        return "${condition.sentence()}（当前 $shown）"
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
