package com.example.gptest.ui.alert

import com.example.gptest.business.QuoteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertWatchlistExtrasTest {

    @Test
    fun fromQuotes_usesNameAndDropsDuplicates() {
        val rows = listOf(
            QuoteSnapshot("sz002491", "通鼎互联", "21", "", emptyList()),
            QuoteSnapshot("sz002491", "通鼎互联", "21", "", emptyList()),
            QuoteSnapshot("sh600000", "--", "10", "", emptyList())
        )
        val stocks = AlertWatchlistExtras.fromQuotes(rows)
        assertEquals(listOf("sz002491", "sh600000"), stocks.map { it.code })
        assertEquals("通鼎互联", stocks[0].name)
        assertEquals("600000", stocks[1].name)
    }

    @Test
    fun merge_keepsWatchlistOrderAndNames() {
        val named = listOf(AlertStockOption.fromCode("sz002491", "通鼎互联"))
        val merged = AlertWatchlistExtras.merge(
            listOf("sh600000", "sz002491"),
            named
        )
        assertEquals("sh600000", merged[0].code)
        assertEquals("600000", merged[0].name)
        assertEquals("通鼎互联", merged[1].name)
    }

    @Test
    fun fromQuotes_unionsRowsFromAllTabs() {
        val rows = listOf(
            QuoteSnapshot("sz000001", "平安银行", "11", "", emptyList()),
            QuoteSnapshot("sz000001", "平安银行", "11", "", emptyList()),
            QuoteSnapshot("sz000002", "万科A", "12", "", emptyList())
        )
        val stocks = AlertWatchlistExtras.fromQuotes(rows)
        assertEquals(listOf("sz000001", "sz000002"), stocks.map { it.code })
    }
}
