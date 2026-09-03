package com.example.gptest

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteSorterTest {

    private val customOrder = listOf("sz000002", "sz000001", "sh600000")
    private val rows = listOf(
        snapshot("sz000001", price = "10.00", change = "1.50", amount = "100"),
        snapshot("sz000002", price = "20.00", change = "-2.00", amount = "300"),
        snapshot("sh600000", price = "15.00", change = "3.00", amount = "200")
    )

    @Test
    fun custom_keepsWatchlistOrder() {
        val sorted = QuoteSorter.sort(rows, QuoteSortMode.CUSTOM, customOrder)
        assertEquals(listOf("sz000002", "sz000001", "sh600000"), sorted.map { it.requestCode })
    }

    @Test
    fun changePercent_sortsDescending() {
        val sorted = QuoteSorter.sort(rows, QuoteSortMode.CHANGE_PERCENT, customOrder)
        assertEquals(listOf("sh600000", "sz000001", "sz000002"), sorted.map { it.requestCode })
    }

    @Test
    fun amount_sortsDescending() {
        val sorted = QuoteSorter.sort(rows, QuoteSortMode.AMOUNT, customOrder)
        assertEquals(listOf("sz000002", "sh600000", "sz000001"), sorted.map { it.requestCode })
    }

    @Test
    fun price_sortsDescending() {
        val sorted = QuoteSorter.sort(rows, QuoteSortMode.PRICE, customOrder)
        assertEquals(listOf("sz000002", "sh600000", "sz000001"), sorted.map { it.requestCode })
    }

    @Test
    fun missingValues_sortLast() {
        val withMissing = rows + snapshot("sz000003", price = "--", change = "", amount = "")
        val byPrice = QuoteSorter.sort(withMissing, QuoteSortMode.PRICE, customOrder + "sz000003")
        assertEquals("sz000003", byPrice.last().requestCode)
    }

    private fun snapshot(
        code: String,
        price: String,
        change: String,
        amount: String
    ): QuoteSnapshot {
        val fields = MutableList(40) { "" }
        fields[2] = code.takeLast(6)
        fields[3] = price
        fields[32] = change
        fields[37] = amount
        return QuoteSnapshot(
            requestCode = code,
            name = code,
            price = price,
            changePercent = change,
            fields = fields
        )
    }
}
