package com.example.gptest

data class OrderLevel(
    val price: String,
    val volume: String
)

data class QuoteCard(
    val name: String,
    val code: String,
    val price: String,
    val change: String,
    val changePercent: String,
    val open: String,
    val prevClose: String,
    val high: String,
    val low: String,
    val amplitude: String,
    val volume: String,
    val amount: String,
    val turnover: String,
    val pe: String,
    val pb: String,
    val bids: List<OrderLevel>,
    val asks: List<OrderLevel>,
    val rawDetail: String
)

object QuoteCardMapper {

    fun from(quote: QuoteSnapshot): QuoteCard {
        val fields = quote.fields
        return QuoteCard(
            name = display(quote.name.ifBlank { fields.value(1) }),
            code = display(fields.value(2).ifBlank { quote.requestCode }),
            price = display(quote.price),
            change = display(fields.value(31)),
            changePercent = QuoteParser.formatChangePercent(quote.changePercent.ifBlank { fields.value(32) }),
            open = display(fields.value(5)),
            prevClose = display(fields.value(4)),
            high = display(fields.value(33)),
            low = display(fields.value(34)),
            amplitude = withPercent(fields.value(43)),
            volume = display(fields.value(6).ifBlank { fields.value(36) }),
            amount = display(fields.value(37)),
            turnover = withPercent(fields.value(38)),
            pe = display(fields.value(39)),
            pb = display(fields.value(46)),
            bids = (0 until 5).map { index ->
                OrderLevel(
                    price = display(fields.value(9 + index * 2)),
                    volume = display(fields.value(10 + index * 2))
                )
            },
            asks = (0 until 5).map { index ->
                OrderLevel(
                    price = display(fields.value(19 + index * 2)),
                    volume = display(fields.value(20 + index * 2))
                )
            },
            rawDetail = QuoteParser.formatQuoteDetail(fields)
        )
    }

    private fun List<String>.value(index: Int): String = getOrNull(index)?.trim().orEmpty()

    private fun display(raw: String): String = raw.trim().ifEmpty { "--" }

    private fun withPercent(raw: String): String {
        val value = display(raw)
        if (value == "--" || value.endsWith("%")) return value
        return "$value%"
    }
}
