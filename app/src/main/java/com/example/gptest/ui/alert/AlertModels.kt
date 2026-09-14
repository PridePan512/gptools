package com.example.gptest.ui.alert

import com.example.gptest.business.QuoteSnapshot
import java.util.UUID

enum class AlertMetric(val label: String, val suffix: String) {
    PRICE("现价", ""),
    CHANGE_PERCENT("涨跌幅", "%"),
    AMOUNT("成交额", "万"),
    TURNOVER("换手率", "%"),
    AMPLITUDE("振幅", "%")
}

enum class AlertOperator(val label: String, val needsValue: Boolean = true, val maPeriod: Int? = null) {
    GTE("高于"),
    LTE("低于"),
    CROSS_UP("涨到"),
    CROSS_DOWN("跌破"),
    LIMIT_UP("涨停", needsValue = false),
    LIMIT_DOWN("跌停", needsValue = false),
    OPEN_LIMIT_UP("撬开涨停", needsValue = false),
    OPEN_LIMIT_DOWN("撬开跌停", needsValue = false),
    VOLUME_SURGE("快速放量", needsValue = false),
    VOLUME_SHRINK("快速缩量", needsValue = false),
    PRICE_SURGE("快速拉升", needsValue = false),
    PRICE_DROP("快速下跌", needsValue = false),
    BREAK_MA5("破五日线", false, 5),
    BREAK_MA10("破十日线", false, 10),
    BREAK_MA20("破月线", false, 20),
    BREAK_MA60("破季线", false, 60),
    BREAK_MA120("破半年线", false, 120),
    BREAK_MA250("破年线", false, 250)
}

enum class AlertMatchMode(val label: String, val joiner: String) {
    ALL("全部满足", "且"),
    ANY("任一满足", "或")
}

enum class AlertNotifyMode(val label: String) {
    ONCE("仅通知一次"),
    ALWAYS("每次满足都通知"),
    COOLDOWN("冷却 10 分钟")
}

enum class AlertRuleStatus {
    WAITING,
    FIRED,
    OFF
}

data class AlertStockOption(
    val code: String,
    val name: String
) {
    val menuLabel: String
        get() = "$name  ${shortCode(code)}"

    companion object {
        fun shortCode(code: String): String {
            return code.removePrefix("sz").removePrefix("sh").removePrefix("bj")
        }

        fun fromQuote(quote: QuoteSnapshot): AlertStockOption {
            val name = quote.name.trim()
            val displayName = when {
                name.isNotEmpty() && name != "--" -> name
                else -> quote.fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: shortCode(quote.requestCode)
            }
            return AlertStockOption(quote.requestCode, displayName)
        }

        fun fromCode(code: String, name: String? = null): AlertStockOption {
            val displayName = name?.trim()?.takeIf { it.isNotEmpty() && it != "--" } ?: shortCode(code)
            return AlertStockOption(code, displayName)
        }
    }
}

data class AlertCondition(
    val id: String,
    val stock: AlertStockOption,
    val metric: AlertMetric,
    val operator: AlertOperator,
    val numberValue: String = "",
    val compareStock: AlertStockOption? = null
) {
    fun sentence(): String {
        if (!operator.needsValue) {
            return "${stock.name}  ${operator.label}"
        }
        val target = compareStock?.name ?: (numberValue + metric.suffix)
        return "${stock.name}  ${metric.label}  ${operator.label}  $target"
    }
}

data class AlertRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val matchMode: AlertMatchMode,
    val notifyMode: AlertNotifyMode,
    val conditions: List<AlertCondition>,
    val status: AlertRuleStatus,
    val lastTriggeredMs: Long? = null,
    val onceConsumed: Boolean = false
) {
    fun displayName(): String = name.ifBlank { "未命名规则" }

    fun summary(): String {
        if (conditions.isEmpty()) return "尚未添加条件"
        return conditions.joinToString("  ${matchMode.joiner}  ") { it.sentence() }
    }
}

object AlertIds {
    fun newId(): String = UUID.randomUUID().toString()
}
