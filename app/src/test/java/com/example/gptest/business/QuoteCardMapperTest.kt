package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCardMapperTest {

    @Test
    fun from_mapsReadableFieldsAndFiveLevels() {
        val fields = MutableList(50) { "" }
        fields[1] = "通鼎互联"
        fields[2] = "002491"
        fields[3] = "21.15"
        fields[4] = "21.46"
        fields[5] = "21.51"
        fields[6] = "785166"
        fields[9] = "21.13"
        fields[10] = "1"
        fields[19] = "21.17"
        fields[20] = "34"
        fields[31] = "-0.31"
        fields[32] = "-1.44"
        fields[33] = "21.71"
        fields[34] = "20.65"
        fields[37] = "165688"
        fields[38] = "6.67"
        fields[39] = "120.58"
        fields[43] = "4.94"
        fields[46] = "9.74"
        val quote = QuoteSnapshot(
            requestCode = "sz002491",
            name = "通鼎互联",
            price = "21.15",
            changePercent = "-1.44",
            fields = fields
        )

        val card = QuoteCardMapper.from(quote)

        assertEquals("通鼎互联", card.name)
        assertEquals("002491", card.code)
        assertEquals("21.15", card.price)
        assertEquals("-0.31", card.change)
        assertEquals("-1.44%", card.changePercent)
        assertEquals("21.51", card.open)
        assertEquals("21.46", card.prevClose)
        assertEquals("21.71", card.high)
        assertEquals("20.65", card.low)
        assertEquals("4.94%", card.amplitude)
        assertEquals("785166", card.volume)
        assertEquals("165688", card.amount)
        assertEquals("6.67%", card.turnover)
        assertEquals("120.58", card.pe)
        assertEquals("9.74", card.pb)
        assertEquals("21.13", card.bids[0].price)
        assertEquals("1", card.bids[0].volume)
        assertEquals("21.17", card.asks[0].price)
        assertEquals("34", card.asks[0].volume)
        assertTrue(card.rawDetail.contains("通鼎互联"))
    }

    @Test
    fun from_emptyFields_usePlaceholder() {
        val quote = QuoteSnapshot(
            requestCode = "sz002491",
            name = "",
            price = "21.15",
            changePercent = "",
            fields = listOf("51", "", "002491", "21.15")
        )
        val card = QuoteCardMapper.from(quote)
        assertEquals("--", card.name)
        assertEquals("002491", card.code)
        assertEquals("--", card.open)
        assertEquals("--", card.changePercent)
        assertEquals("--", card.bids[0].price)
    }
}
