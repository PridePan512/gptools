package com.example.gptest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.gptest.data.CommunityPostRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CommunityPostRepositoryTest {
    private fun row(id: Long = 2427500719, time: Long = 1795570303) = JSONObject().apply {
        put("pid", id)
        put("uid", 819295397)
        put("ctime", time)
        put("mtime", 1795570900)
        put("content", "<span>测试 &amp; 动态</span><br />第二行")
        put("jump_url", "https://c.10jqka.com.cn/m/post/discussDetail/?contentId=test")
    }

    private fun body(vararg rows: JSONObject) = JSONObject().put("status_code", 0)
        .put("data", JSONArray(rows.toList())).toString()

    @Test fun parsesPlainText64BitIdAndCreationTime() {
        val post = CommunityPostRepository.parse(body(row())).single()
        assertEquals(2427500719L, post.id)
        assertEquals(1795570303L, post.createdAtSeconds)
        assertTrue(post.text.contains("测试 & 动态"))
        assertTrue(post.text.contains("\n第二行"))
        assertFalse(post.text.contains("<span>"))
    }

    @Test fun sortsAndDeduplicatesTheResponse() {
        val rows = CommunityPostRepository.parse(body(row(2, 10), row(3, 20), row(3, 20)))
        assertEquals(listOf(3L, 2L), rows.map { it.id })
    }

    @Test fun rejectsInvalidResponsesInsteadOfTreatingThemAsBaseline() {
        for (response in listOf("<html>访问校验</html>", "{}", body(),
                body(row().put("uid", 1)), body(row().put("pid", 0)),
                body(row().put("jump_url", "https://example.com/")),
                body(row()).replace("\"status_code\":0", "\"status_code\":1"))) {
            assertThrows(IOException::class.java) { CommunityPostRepository.parse(response) }
        }
    }

    @Test fun acceptsOnlyHttpsLinksOnTheExpectedHosts() {
        assertTrue(CommunityPostRepository.isAllowedUrl("https://c.10jqka.com.cn/m/post/1"))
        assertFalse(CommunityPostRepository.isAllowedUrl("http://c.10jqka.com.cn/m/post/1"))
        assertFalse(CommunityPostRepository.isAllowedUrl("https://c.10jqka.com.cn.example.com/"))
        assertFalse(CommunityPostRepository.isAllowedUrl("https://name@c.10jqka.com.cn/"))
        assertFalse(CommunityPostRepository.isAllowedUrl("intent://test"))
    }
}
