package com.example.gptest

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gptest.business.CommunityCheckpoint
import com.example.gptest.business.CommunityPost
import com.example.gptest.business.CommunityPostDetector
import com.example.gptest.ui.community.CommunityMonitorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityMonitorStoreTest {
    @Test fun restartPreservesBaselineAndDoesNotPretendServiceIsRunning() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "community-test-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(unique, mode)
        }
        try {
            val post = CommunityPost(2427500719, 1795570303, "测试", "https://c.10jqka.com.cn/m/post/1")
            val original = CommunityMonitorStore(context)
            original.setInterval(15)
            original.started()
            original.setInterval(60)
            assertEquals(15, original.state.value.interval)
            val baseline = CommunityPostDetector.compare(CommunityCheckpoint(), listOf(post)).checkpoint
            original.save(baseline, listOf(post), 1234)

            val restored = CommunityMonitorStore(context)
            assertFalse(restored.state.value.running)
            assertTrue(restored.resumeRequested)
            assertEquals(15, restored.state.value.interval)
            assertEquals(1234L, restored.state.value.checkedAt)
            assertEquals(listOf(post), restored.state.value.posts)
            assertTrue(CommunityPostDetector.compare(restored.checkpoint, listOf(post)).newPosts.isEmpty())
            assertEquals(listOf(2427500720L), CommunityPostDetector.compare(restored.checkpoint,
                listOf(post.copy(id = 2427500720, createdAtSeconds = 1795570400), post)).newPosts.map { it.id })
            restored.stopped("已停止")
            assertFalse(CommunityMonitorStore(context).resumeRequested)
        } finally {
            app.deleteSharedPreferences(unique)
        }
    }
}
