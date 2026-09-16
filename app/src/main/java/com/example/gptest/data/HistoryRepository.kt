package com.example.gptest.data

import com.example.gptest.business.DailyClose
import com.example.gptest.business.DailyBar
import com.example.gptest.business.MovingAverageCalculator
import com.example.gptest.business.MovingAverages
import com.example.gptest.business.QuoteParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class HistoryAverages(val averages: MovingAverages, val forwardAdjusted: Boolean)
data class DailyHistory(val closes: List<DailyClose>, val forwardAdjusted: Boolean)
data class BarHistory(val bars: List<DailyBar>, val forwardAdjusted: Boolean)

class HistoryRepository {
    fun fetchAverages(code: String): Result<HistoryAverages> = fetchDaily(code).mapCatching {
        HistoryAverages(MovingAverageCalculator.calculate(it.closes), it.forwardAdjusted)
    }

    fun fetchDaily(code: String): Result<DailyHistory> = fetchBars(code).mapCatching { history ->
        val closes = history.bars.map { DailyClose(it.date, it.close) }
        MovingAverageCalculator.calculate(closes)
        DailyHistory(closes, history.forwardAdjusted)
    }

    fun fetchBars(code: String, count: Int = 320): Result<BarHistory> = runCatching {
        require(count in 1..320)
        val normalized = requireNotNull(QuoteParser.normalizeStockCode(code))
        HistoryAccess.gate.blocked()?.let { throw it }
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$normalized,day,,,$count,qfq"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            val status = connection.responseCode
            val stream = if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HistoryAccess.gate.checkResponse(status, body, connection.getHeaderField("Retry-After"))
            check(status == HttpURLConnection.HTTP_OK) { "历史行情请求失败（HTTP $status）" }
            val root = JSONObject(body)
            check(root.getInt("code") == 0) { "History service error" }
            val stock = root.getJSONObject("data").getJSONObject(normalized)
            val adjusted = stock.optJSONArray("qfqday")
            val rows = adjusted ?: stock.getJSONArray("day")
            val bars = (0 until rows.length()).map { index ->
                val row = rows.getJSONArray(index)
                DailyBar(LocalDate.parse(row.getString(0)), row.getString(1).toDouble(),
                    row.getString(2).toDouble(), row.getString(3).toDouble(),
                    row.getString(4).toDouble(), row.getString(5).toDouble())
            }
            check(bars.isNotEmpty()) { "Empty history" }
            BarHistory(bars.sortedBy { it.date }, adjusted != null)
        } finally {
            connection.disconnect()
        }
    }
}
