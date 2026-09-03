package com.example.gptest

enum class QuoteSortMode {
    CUSTOM,
    CHANGE_PERCENT,
    AMOUNT,
    PRICE
}

object QuoteSorter {

    private const val AMOUNT_INDEX = 37

    fun sort(
        rows: List<QuoteSnapshot>,
        mode: QuoteSortMode,
        customOrder: List<String>
    ): List<QuoteSnapshot> {
        return when (mode) {
            QuoteSortMode.CUSTOM -> {
                val byCode = rows.associateBy { it.requestCode }
                customOrder.mapNotNull { byCode[it] }
            }
            QuoteSortMode.CHANGE_PERCENT -> rows.sortedByDescending { numericOrMin(it.changePercent) }
            QuoteSortMode.PRICE -> rows.sortedByDescending { numericOrMin(it.price) }
            QuoteSortMode.AMOUNT -> rows.sortedByDescending { numericOrMin(it.fields.getOrNull(AMOUNT_INDEX)) }
        }
    }

    private fun numericOrMin(raw: String?): Double {
        val value = raw?.trim()?.removeSuffix("%")?.removePrefix("+")
        return value?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
    }
}
