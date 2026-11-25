package com.example.gptest.ui.community

import android.content.Context
import androidx.core.content.edit
import com.example.gptest.business.CommunityCheckpoint
import com.example.gptest.business.CommunityPost
import com.example.gptest.business.CommunityAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class CommunityMonitorState(
    val running: Boolean = false,
    val interval: Int = 30,
    val message: String = "尚未开启；首次检查只记录已有动态",
    val checkedAt: Long = 0,
    val nextCheckAt: Long = 0,
    val posts: List<CommunityPost> = emptyList()
)

class CommunityMonitorStore(context: Context) {
    private val prefs = context.getSharedPreferences("community-monitor", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CommunityMonitorState(
        interval = prefs.getInt("interval", 30).takeIf { it in CommunityAccount.intervals } ?: 30,
        message = if (prefs.getBoolean("enabled", false)) "上次监控已中断，请重新开启" else prefs.getString("message", null)
            ?: "尚未开启；首次检查只记录已有动态",
        checkedAt = prefs.getLong("checkedAt", 0),
        posts = readPosts()
    ))
    val state = mutableState.asStateFlow()
    var checkpoint = CommunityCheckpoint(
        prefs.getBoolean("initialized", false),
        prefs.getLong("newestAt", 0),
        prefs.getString("seen", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
    )
        private set

    val resumeRequested: Boolean get() = prefs.getBoolean("enabled", false)

    fun setInterval(seconds: Int) {
        if (state.value.running || seconds !in CommunityAccount.intervals) return
        prefs.edit { putInt("interval", seconds) }
        mutableState.value = state.value.copy(interval = seconds)
    }

    fun started() {
        prefs.edit { putBoolean("enabled", true) }
        mutableState.value = state.value.copy(running = true, message = "正在检查最新动态…", nextCheckAt = 0)
    }

    fun stopped(message: String, resume: Boolean = false) {
        prefs.edit { putBoolean("enabled", resume); putString("message", message) }
        mutableState.value = state.value.copy(running = false, message = message, nextCheckAt = 0)
    }

    fun status(message: String, nextCheckAt: Long) {
        mutableState.value = state.value.copy(message = message, nextCheckAt = nextCheckAt)
    }

    suspend fun save(next: CommunityCheckpoint, posts: List<CommunityPost>, now: Long) {
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            posts.forEach { post -> array.put(JSONObject().apply {
                put("id", post.id)
                put("time", post.createdAtSeconds)
                put("text", post.text)
                put("url", post.url)
            }) }
            val saved = prefs.edit().putBoolean("initialized", next.initialized)
                .putLong("newestAt", next.newestAtSeconds).putString("seen", next.seenIds.joinToString(","))
                .putString("posts", array.toString()).putLong("checkedAt", now).commit()
            if (!saved) throw IOException("无法保存动态记录，将稍后重试")
        }
        checkpoint = next
        mutableState.value = state.value.copy(posts = posts, checkedAt = now)
    }

    private fun readPosts(): List<CommunityPost> = runCatching {
        val array = JSONArray(prefs.getString("posts", "[]"))
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            CommunityPost(row.getLong("id"), row.getLong("time"), row.getString("text"), row.getString("url"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: CommunityMonitorStore? = null
        fun get(context: Context): CommunityMonitorStore = instance ?: synchronized(this) {
            instance ?: CommunityMonitorStore(context.applicationContext).also { instance = it }
        }
    }
}
