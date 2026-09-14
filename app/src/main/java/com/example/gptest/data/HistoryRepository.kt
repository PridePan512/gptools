package com.example.gptest.data

import com.example.gptest.business.DailyClose
import com.example.gptest.business.MovingAverageCalculator
import com.example.gptest.business.MovingAverages
import com.example.gptest.business.QuoteParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class HistoryAverages(val averages: MovingAverages, val forwardAdjusted: Boolean)

class HistoryRepository {
    fun fetchAverages(code: String): Result<HistoryAverages> = runCatching {
        val normalized = requireNotNull(QuoteParser.normalizeStockCode(code))
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$normalized,day,,,320,qfq"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "History request failed" }
            val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(root.getInt("code") == 0) { "History service error" }
            val stock = root.getJSONObject("data").getJSONObject(normalized)
            val adjusted = stock.optJSONArray("qfqday")
            val rows = adjusted ?: stock.getJSONArray("day")
            val closes = (0 until rows.length()).map { index ->
                val row = rows.getJSONArray(index)
                DailyClose(LocalDate.parse(row.getString(0)), row.getString(2).toDouble())
            }
            HistoryAverages(MovingAverageCalculator.calculate(closes), adjusted != null)
        } finally {
            connection.disconnect()
        }
    }
}
