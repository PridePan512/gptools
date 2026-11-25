package com.example.gptest.data

import androidx.core.text.HtmlCompat
import com.example.gptest.business.CommunityAccount
import com.example.gptest.business.CommunityPost
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class CommunityPostRepository {
    fun fetch(): List<CommunityPost> {
        val connection = (URL(CommunityAccount.POSTS).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException(when (code) {
                401, 403 -> "同花顺暂时要求访问校验"
                429 -> "请求受到限频"
                else -> "接口暂不可用（HTTP $code）"
            })
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 2_000_000) throw IOException("动态响应过大")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parse(body: String): List<CommunityPost> {
            try {
                val root = JSONObject(body)
                if (root.getInt("status_code") != 0) throw IOException("动态接口返回异常状态")
                val rows = root.getJSONArray("data")
                if (rows.length() == 0) throw IOException("暂未取得动态，保留上次记录")
                return (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val id = row.getLong("pid")
                    val time = row.getLong("ctime")
                    if (id <= 0 || time <= 0 || row.getLong("uid") != CommunityAccount.UID) {
                        throw IOException("动态数据与目标账号不匹配")
                    }
                    val url = row.getString("jump_url")
                    if (!isAllowedUrl(url)) throw IOException("动态链接格式异常")
                    val text = HtmlCompat.fromHtml(row.getString("content"), HtmlCompat.FROM_HTML_MODE_LEGACY)
                        .toString().trim().ifBlank { "发布了一条图片动态" }
                    CommunityPost(id, time, text, url)
                }.distinctBy { it.id }.sortedByDescending { it.createdAtSeconds }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("动态数据格式异常或需要访问校验", error)
            }
        }

        fun isAllowedUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in setOf("c.10jqka.com.cn", "t.10jqka.com.cn") &&
                uri.userInfo == null && (uri.port == -1 || uri.port == 443)
        }.getOrDefault(false)
    }
}
