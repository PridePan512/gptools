package com.example.gptest.business

data class QuoteSnapshot(
    val requestCode: String,
    val name: String,
    val price: String,
    val changePercent: String,
    val fields: List<String>
)

object QuoteParser {

    private val prefixedCode = Regex("^(sz|sh|bj)\\d{6}$")
    private val sixDigitCode = Regex("^\\d{6}$")

    internal val fieldLabels = listOf(
        "未知",
        "名称",
        "代码",
        "当前价格",
        "昨收",
        "今开",
        "成交量(手)",
        "外盘",
        "内盘",
        "买一价",
        "买一量",
        "买二价",
        "买二量",
        "买三价",
        "买三量",
        "买四价",
        "买四量",
        "买五价",
        "买五量",
        "卖一价",
        "卖一量",
        "卖二价",
        "卖二量",
        "卖三价",
        "卖三量",
        "卖四价",
        "卖四量",
        "卖五价",
        "卖五量",
        "最近逐笔",
        "时间",
        "涨跌",
        "涨跌幅",
        "最高",
        "最低",
        "价格/成交量/成交额",
        "成交量(手)",
        "成交额(万)",
        "换手率",
        "市盈率",
        "无名",
        "最高",
        "最低",
        "振幅",
        "流通市值",
        "总市值",
        "市净率",
        "涨停价",
        "跌停价",
        "量比",
        "委差",
        "均价",
        "动态市盈率",
        "静态市盈率"
    )

    fun parseCurrentPrice(raw: String): String? = parseQuote(raw)?.price

    fun parseQuote(raw: String): QuoteSnapshot? = parseQuotes(raw).firstOrNull()

    fun parseQuotes(raw: String): List<QuoteSnapshot> {
        return QUOTE_BLOCK.findAll(raw).mapNotNull { match ->
            val requestCode = match.groupValues[1].lowercase()
            val fields = match.groupValues[2].split('~')
            if (fields.size <= 3) return@mapNotNull null
            val price = fields[3].trim().ifEmpty { return@mapNotNull null }
            val name = fields.getOrNull(1)?.trim().orEmpty()
            val changePercent = fields.getOrNull(CHANGE_PERCENT_INDEX)?.trim().orEmpty()
            QuoteSnapshot(
                requestCode = requestCode,
                name = name,
                price = price,
                changePercent = changePercent,
                fields = fields
            )
        }.toList()
    }

    fun formatQuoteDetail(fields: List<String>): String {
        return fields.mapIndexed { index, value ->
            val label = fieldLabels.getOrNull(index) ?: "字段$index"
            "[${index.toString().padStart(2)}] $label：$value"
        }.joinToString("\n")
    }

    fun normalizeStockCode(code: String): String? {
        val trimmed = code.trim().lowercase()
        if (trimmed.isEmpty()) return null
        if (prefixedCode.matches(trimmed)) return trimmed
        if (!sixDigitCode.matches(trimmed)) return null
        val prefix = when (trimmed.first()) {
            '6', '9' -> "sh"
            '0', '3' -> "sz"
            '4', '8' -> "bj"
            else -> return null
        }
        return prefix + trimmed
    }

    fun formatChangePercent(raw: String): String {
        val trimmed = raw.trim().removeSuffix("%")
        if (trimmed.isEmpty()) return "--"
        val number = trimmed.toDoubleOrNull() ?: return "$trimmed%"
        val unsigned = trimmed.removePrefix("+")
        return if (number > 0) "+$unsigned%" else "$unsigned%"
    }

    fun changePercentValue(raw: String): Double? {
        return raw.trim().removeSuffix("%").toDoubleOrNull()
    }

    fun resolveIntervalSeconds(input: String): Long {
        val value = input.trim().toLongOrNull()
        return if (value == null || value < 1L) 5L else value
    }

    private val QUOTE_BLOCK = Regex("""v_([a-zA-Z]{2}\d+)="([^"]*)"""")
    private const val CHANGE_PERCENT_INDEX = 32
}
