package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StockCatalogTest {

    private val catalog = StockCatalog.parse(
        """
        # comment
        sz000001|平安银行
        sh601318|中国平安
        sz002491|通鼎互联
        sh600000|浦发银行
        sz000002|万科A
        sz002165|红 宝 丽
        """.trimIndent()
    )

    @Test
    fun parse_skipsCommentsAndBlankLines() {
        assertEquals("sz000001", catalog.resolveUnique("平安银行"))
        assertEquals("sz000002", catalog.resolveUnique("万科A"))
        assertNull(StockCatalog.parse("# only comment\n\n").resolveUnique("平安银行"))
    }

    @Test
    fun search_blank_returnsEmpty() {
        assertEquals(emptyList<StockCatalogEntry>(), StockCatalog.parse("sz000001|平安银行").search("  "))
    }

    @Test
    fun search_byNameContains() {
        val results = catalog.search("通鼎")
        assertEquals(listOf("sz002491"), results.map { it.code })
        assertEquals("通鼎互联  002491", results.single().displayLabel)
    }

    @Test
    fun search_ignoresSpacesInNameAndQuery() {
        assertEquals(listOf("sz002165"), catalog.search("红宝").map { it.code })
        assertEquals(listOf("sz002165"), catalog.search("红 宝").map { it.code })
        assertEquals("sz002165", catalog.resolveUnique("红宝丽"))
        assertEquals("sz002165", catalog.resolveAddQuery("红宝"))
    }

    @Test
    fun search_ranksStocksBeforeEtfsWithSameMatch() {
        val catalog = StockCatalog.parse(
            """
            sh510050|测试ETF华夏
            sz159919|测试ETF易方达
            sz000001|测试银行
            sh601318|测试集团
            """.trimIndent()
        )
        assertEquals(
            listOf("sh601318", "sz000001", "sh510050", "sz159919"),
            catalog.search("测试").map { it.code }
        )
    }

    @Test
    fun search_ranksCodeBasedEtfAfterStocks() {
        val catalog = StockCatalog.parse(
            """
            sh510010|180治理交银
            sz000001|180股份
            """.trimIndent()
        )
        assertEquals(listOf("sz000001", "sh510010"), catalog.search("180").map { it.code })
    }

    @Test
    fun search_ranksIndicesThenStocksThenEtfs() {
        val catalog = StockCatalog.parse(
            """
            sh510300|沪深300ETF华泰柏瑞
            sh000300|沪深300
            sz000001|沪深银行
            """.trimIndent()
        )
        assertEquals(
            listOf("sh000300", "sz000001", "sh510300"),
            catalog.search("沪深").map { it.code }
        )
    }

    @Test
    fun search_ranksExactNameBeforeContains() {
        val results = catalog.search("平安")
        assertEquals(listOf("sz000001", "sh601318"), results.map { it.code })
        assertEquals(listOf("sz000001"), catalog.search("平安银行").map { it.code })
    }

    @Test
    fun search_byCodeDigits() {
        assertEquals(listOf("sz002491"), catalog.search("00249").map { it.code })
        assertEquals(listOf("sz002491"), catalog.search("sz002491").map { it.code })
    }

    @Test
    fun search_respectsLimit() {
        val many = StockCatalog(
            (1..20).map { StockCatalogEntry("sz${it.toString().padStart(6, '0')}", "测试$it") }
        )
        assertEquals(8, many.search("测试").size)
        assertEquals(3, many.search("测试", limit = 3).size)
    }

    @Test
    fun resolveUnique_singleNameHit() {
        assertEquals("sz002491", catalog.resolveUnique("通鼎"))
        assertEquals("sz002491", catalog.resolveUnique("通鼎互联"))
    }

    @Test
    fun resolveUnique_multipleHits_returnsNull() {
        assertNull(catalog.resolveUnique("平安"))
        assertNull(catalog.resolveUnique("银行"))
    }

    @Test
    fun resolveUnique_blank_returnsNull() {
        assertNull(catalog.resolveUnique("  "))
    }

    @Test
    fun resolveAddQuery_prefersNormalizedCode() {
        assertEquals("sz000001", catalog.resolveAddQuery("000001"))
        assertEquals("sh601318", catalog.resolveAddQuery("SH601318"))
    }

    @Test
    fun resolveAddQuery_uniqueName() {
        assertEquals("sz002491", catalog.resolveAddQuery("通鼎"))
    }

    @Test
    fun resolveAddQuery_ambiguousOrUnknown_returnsNull() {
        assertNull(catalog.resolveAddQuery("平安"))
        assertNull(catalog.resolveAddQuery("没有这只股票"))
        assertNull(catalog.resolveAddQuery("  "))
    }

    @Test
    fun parse_normalizesPrefixedCode() {
        val parsed = StockCatalog.parse("SZ002491|通鼎互联")
        assertEquals("sz002491", parsed.resolveUnique("通鼎互联"))
    }

    @Test
    fun bundledCatalog_resolvesKnownStocks() {
        val catalog = StockCatalog.parse(bundledCatalogText())
        assertEquals("sz002491", catalog.resolveUnique("通鼎互联"))
        assertEquals("sz000001", catalog.resolveAddQuery("平安银行"))
        assertTrue(catalog.search("沪深300ETF").isNotEmpty())
        assertEquals("sz002165", catalog.resolveUnique("红宝丽"))
        assertTrue(catalog.search("红宝").any { it.code == "sz002165" })
        assertEquals("sh000001", catalog.resolveUnique("上证指数"))
        assertEquals("sz399006", catalog.resolveUnique("创业板指"))
        assertEquals("sh000300", catalog.resolveAddQuery("沪深300"))
        val hs300 = catalog.search("沪深300").map { it.code }
        assertTrue(hs300.first() == "sh000300")
        assertTrue(hs300.any { it.startsWith("sh51") || it.startsWith("sz15") })
    }

    @Test
    fun isIndex_usesShanghaiAndShenzhenIndexCodes() {
        assertTrue(StockCatalog.isIndex("sh000001"))
        assertTrue(StockCatalog.isIndex("sz399006"))
        assertTrue(StockCatalog.isIndex("bj899050"))
        assertTrue(!StockCatalog.isIndex("sz000001"))
        assertTrue(!StockCatalog.isIndex("sh510300"))
    }

    @Test
    fun parse_ignoresMalformedLines() {
        val parsed = StockCatalog.parse(
            """
            not-a-row
            |无代码
            sz002491|
            sz002491|通鼎互联
            """.trimIndent()
        )
        assertEquals(listOf("sz002491"), parsed.search("通鼎").map { it.code })
        assertTrue(parsed.search("无代码").isEmpty())
    }

    private fun bundledCatalogText(): String {
        val candidates = listOf(
            File("src/main/assets/stock_catalog.txt"),
            File("app/src/main/assets/stock_catalog.txt")
        )
        val file = candidates.first { it.exists() }
        return file.readText(Charsets.UTF_8)
    }
}
