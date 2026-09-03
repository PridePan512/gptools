package com.example.gptest.data

import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class QuoteRepository : QuoteDataSource {

    override fun fetchQuotes(codes: List<String>): Result<List<QuoteSnapshot>> {
        if (codes.isEmpty()) {
            return Result.failure(IllegalStateException("invalid response"))
        }
        val connection = (URL("$QUOTE_URL${codes.joinToString(",")}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            val bytes = connection.inputStream.use { it.readBytes() }
            val text = String(bytes, gbkOrDefault())
            val quotes = QuoteParser.parseQuotes(text)
            if (quotes.isEmpty()) {
                Result.failure(IllegalStateException("invalid response"))
            } else {
                Result.success(quotes)
            }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun gbkOrDefault(): Charset {
        return try {
            Charset.forName("GBK")
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    companion object {
        private const val QUOTE_URL = "https://qt.gtimg.cn/q="
        private const val TIMEOUT_MS = 8_000
    }
}
