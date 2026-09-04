package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteParserTest {

    @Test
    fun parseCurrentPrice_fromTencentSample() {
        val raw =
            """v_sz002491="51~通鼎互联~002491~21.15~21.46~21.51~785166~362386~422441~";"""
        assertEquals("21.15", QuoteParser.parseCurrentPrice(raw))
    }

    @Test
    fun parseCurrentPrice_empty_returnsNull() {
        assertNull(QuoteParser.parseCurrentPrice(""))
    }

    @Test
    fun parseCurrentPrice_tooFewFields_returnsNull() {
        assertNull(QuoteParser.parseCurrentPrice("""v_sz002491="51~通鼎互联~002491";"""))
    }

    @Test
    fun normalizeStockCode_prefixedLowercase() {
        assertEquals("sz002491", QuoteParser.normalizeStockCode("sz002491"))
    }

    @Test
    fun normalizeStockCode_prefixedUppercase() {
        assertEquals("sz002491", QuoteParser.normalizeStockCode("SZ002491"))
    }

    @Test
    fun normalizeStockCode_sixDigitSz() {
        assertEquals("sz002491", QuoteParser.normalizeStockCode("002491"))
    }

    @Test
    fun normalizeStockCode_sixDigitSh() {
        assertEquals("sh600000", QuoteParser.normalizeStockCode("600000"))
    }

    @Test
    fun normalizeStockCode_sixDigitBj() {
        assertEquals("bj430047", QuoteParser.normalizeStockCode("430047"))
        assertEquals("bj830001", QuoteParser.normalizeStockCode("830001"))
    }

    @Test
    fun normalizeStockCode_beijingNewListing_usesBjNotSh() {
        assertEquals("bj920289", QuoteParser.normalizeStockCode("920289"))
        assertEquals("bj920289", QuoteParser.normalizeStockCode("SH920289"))
    }

    @Test
    fun normalizeStockCode_shanghaiBShare_staysSh() {
        assertEquals("sh900901", QuoteParser.normalizeStockCode("900901"))
        assertEquals("sh900901", QuoteParser.normalizeStockCode("sh900901"))
    }

    @Test
    fun normalizeStockCode_blank_returnsNull() {
        assertNull(QuoteParser.normalizeStockCode("  "))
    }

    @Test
    fun normalizeStockCode_sixDigitSzEtf() {
        assertEquals("sz159915", QuoteParser.normalizeStockCode("159915"))
    }

    @Test
    fun normalizeStockCode_sixDigitShEtf() {
        assertEquals("sh510300", QuoteParser.normalizeStockCode("510300"))
        assertEquals("sh588000", QuoteParser.normalizeStockCode("588000"))
    }

    @Test
    fun normalizeStockCode_sixDigitSzIndex() {
        assertEquals("sz399001", QuoteParser.normalizeStockCode("399001"))
        assertEquals("sz399006", QuoteParser.normalizeStockCode("399006"))
    }

    @Test
    fun normalizeStockCode_shanghaiIndex_requiresPrefix() {
        assertEquals("sz000001", QuoteParser.normalizeStockCode("000001"))
        assertEquals("sh000001", QuoteParser.normalizeStockCode("sh000001"))
        assertEquals("sh000300", QuoteParser.normalizeStockCode("SH000300"))
    }

    @Test
    fun normalizeStockCode_unknownSixDigitPrefix_returnsNull() {
        assertNull(QuoteParser.normalizeStockCode("200001"))
    }

    @Test
    fun resolveIntervalSeconds_blankOrInvalid_defaultsToFive() {
        assertEquals(5L, QuoteParser.resolveIntervalSeconds(""))
        assertEquals(5L, QuoteParser.resolveIntervalSeconds("abc"))
        assertEquals(5L, QuoteParser.resolveIntervalSeconds("0"))
    }

    @Test
    fun resolveIntervalSeconds_validValue() {
        assertEquals(8L, QuoteParser.resolveIntervalSeconds("8"))
    }

    @Test
    fun parseQuote_returnsAllFieldsAndPrice() {
        val raw = """v_sz002491="51~通鼎互联~002491~21.15~21.46~21.51~785166";"""
        val quote = QuoteParser.parseQuote(raw)!!
        assertEquals("21.15", quote.price)
        assertEquals("通鼎互联", quote.fields[1])
        assertEquals("002491", quote.fields[2])
        assertEquals(7, quote.fields.size)
    }

    @Test
    fun parseQuote_empty_returnsNull() {
        assertNull(QuoteParser.parseQuote(""))
    }

    @Test
    fun formatQuoteDetail_includesLabeledFields() {
        val quote = QuoteParser.parseQuote(
            """v_sz002491="51~通鼎互联~002491~21.15~21.46";"""
        )!!
        val text = QuoteParser.formatQuoteDetail(quote.fields)
        assertTrue(text.contains("名称"))
        assertTrue(text.contains("通鼎互联"))
        assertTrue(text.contains("当前价格"))
        assertTrue(text.contains("21.15"))
        assertTrue(text.contains("[ 3]"))
    }

    @Test
    fun parseQuotes_parsesMultipleStocks() {
        val raw = """
            v_sz002491="51~通鼎互联~002491~21.15~21.46";
            v_sh600000="1~浦发银行~600000~10.20~10.11";
        """.trimIndent()
        val quotes = QuoteParser.parseQuotes(raw)
        assertEquals(2, quotes.size)
        assertEquals("sz002491", quotes[0].requestCode)
        assertEquals("通鼎互联", quotes[0].name)
        assertEquals("21.15", quotes[0].price)
        assertEquals("sh600000", quotes[1].requestCode)
        assertEquals("浦发银行", quotes[1].name)
        assertEquals("10.20", quotes[1].price)
    }

    @Test
    fun parseQuotes_emptyOrInvalid_returnsEmptyList() {
        assertTrue(QuoteParser.parseQuotes("").isEmpty())
        assertTrue(QuoteParser.parseQuotes("no-quotes-here").isEmpty())
    }

    @Test
    fun parseQuote_fillsNameAndRequestCode() {
        val quote = QuoteParser.parseQuote(
            """v_sz002491="51~通鼎互联~002491~21.15~21.46";"""
        )!!
        assertEquals("sz002491", quote.requestCode)
        assertEquals("通鼎互联", quote.name)
    }

    @Test
    fun parseQuote_readsChangePercent() {
        val fields = MutableList(33) { "" }
        fields[1] = "通鼎互联"
        fields[2] = "002491"
        fields[3] = "21.15"
        fields[32] = "-1.44"
        val quote = QuoteParser.parseQuote("""v_sz002491="${fields.joinToString("~")}";""")!!
        assertEquals("-1.44", quote.changePercent)
    }

    @Test
    fun parseQuote_missingChangePercent_isEmpty() {
        val quote = QuoteParser.parseQuote(
            """v_sz002491="51~通鼎互联~002491~21.15~21.46";"""
        )!!
        assertEquals("", quote.changePercent)
    }

    @Test
    fun formatChangePercent_addsSignAndPercent() {
        assertEquals("+1.44%", QuoteParser.formatChangePercent("1.44"))
        assertEquals("-1.44%", QuoteParser.formatChangePercent("-1.44"))
        assertEquals("0%", QuoteParser.formatChangePercent("0"))
        assertEquals("--", QuoteParser.formatChangePercent(""))
        assertEquals("+2.1%", QuoteParser.formatChangePercent("+2.1%"))
    }

    @Test
    fun formatChangeAmount_addsSignWithoutPercent() {
        assertEquals("+0.31", QuoteParser.formatChangeAmount("0.31"))
        assertEquals("-0.31", QuoteParser.formatChangeAmount("-0.31"))
        assertEquals("0", QuoteParser.formatChangeAmount("0"))
        assertEquals("--", QuoteParser.formatChangeAmount(""))
        assertEquals("+1.2", QuoteParser.formatChangeAmount("+1.2"))
    }

    @Test
    fun changeAmount_readsField31() {
        val fields = MutableList(33) { "" }
        fields[1] = "通鼎互联"
        fields[2] = "002491"
        fields[3] = "21.15"
        fields[31] = "-0.31"
        fields[32] = "-1.44"
        val quote = QuoteParser.parseQuote("""v_sz002491="${fields.joinToString("~")}";""")!!
        assertEquals("-0.31", QuoteParser.changeAmount(quote))
    }

    @Test
    fun limitBoard_detectsUpDownAndNone() {
        assertEquals(LimitBoard.UP, QuoteParser.limitBoard(limitQuote(price = "10.00", limitUp = "10.00", limitDown = "8.00")))
        assertEquals(LimitBoard.DOWN, QuoteParser.limitBoard(limitQuote(price = "8.00", limitUp = "10.00", limitDown = "8.00")))
        assertEquals(LimitBoard.NONE, QuoteParser.limitBoard(limitQuote(price = "9.00", limitUp = "10.00", limitDown = "8.00")))
        assertEquals(LimitBoard.NONE, QuoteParser.limitBoard(limitQuote(price = "10.00")))
        assertFalse(QuoteParser.isAtLimitUp(null))
        assertFalse(QuoteParser.isAtLimitDown(null))
    }

    private fun limitQuote(
        price: String,
        limitUp: String = "",
        limitDown: String = ""
    ): QuoteSnapshot {
        val fields = MutableList(50) { "" }
        fields[3] = price
        fields[47] = limitUp
        fields[48] = limitDown
        return QuoteSnapshot("sz000001", "测试", price, "0", fields)
    }
}
