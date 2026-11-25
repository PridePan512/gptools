package com.example.gptest.ui.community

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.gptest.business.CommunityPostDetector
import com.example.gptest.data.CommunityPostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class CommunityMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { CommunityMonitorStore.get(this) }
    private val notifications by lazy { CommunityNotifications(this) }
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopMonitoring("已停止；再次开启时检查停用期间的新动态")
            return START_NOT_STICKY
        }
        if (intent == null && !store.resumeRequested) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, CommunityNotifications.MONITOR_ID,
                notifications.ongoing("正在检查最新动态…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) {
            stopMonitoring("系统暂不允许后台监控，请回到页面重新开启")
            return START_NOT_STICKY
        }
        if (!notifications.allowed()) {
            stopMonitoring("通知权限未开启，请开启后再开始提醒")
            return START_NOT_STICKY
        }
        if (pollJob?.isActive == true) return START_STICKY
        notifications.clearPaused()
        store.started()
        pollJob = scope.launch { poll() }
        return START_STICKY
    }

    private suspend fun poll() {
        val repository = CommunityPostRepository()
        var failures = 0
        while (scope.isActive) {
            var waitSeconds = store.state.value.interval.toLong()
            try {
                if (!notifications.allowed()) {
                    stopMonitoring("通知权限已关闭，动态提醒已停止")
                    return
                }
                val posts = withContext(Dispatchers.IO) { repository.fetch() }
                // Recheck after the request: do not consume unseen posts when notifications are blocked.
                if (!notifications.allowed()) {
                    stopMonitoring("通知权限已关闭，动态提醒已停止")
                    return
                }
                val first = !store.checkpoint.initialized
                val changes = CommunityPostDetector.compare(store.checkpoint, posts)
                changes.newPosts.forEach(notifications::newPost)
                store.save(changes.checkpoint, posts, System.currentTimeMillis())
                failures = 0
                val message = when {
                    first -> "已记录 ${posts.size} 条已有动态，之后只提醒新动态"
                    changes.possibleGap -> "已提醒 ${changes.newPosts.size} 条；间隔过久，可能遗漏更早动态，请查看主页"
                    changes.newPosts.isNotEmpty() -> "已提醒 ${changes.newPosts.size} 条新动态"
                    else -> "监控正常，暂无新动态"
                }
                store.status(message, System.currentTimeMillis() + waitSeconds * 1000)
                notifications.update(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                stopMonitoring("通知权限不可用，请检查通知设置")
                return
            } catch (error: Exception) {
                failures = (failures + 1).coerceAtMost(10)
                waitSeconds = CommunityPostDetector.retrySeconds(store.state.value.interval, failures)
                val reason = if (error is IOException) error.message ?: "网络连接失败" else "检查失败"
                val message = "$reason；${waitSeconds} 秒后重试"
                store.status(message, System.currentTimeMillis() + waitSeconds * 1000)
                notifications.update(message)
            }
            delay(waitSeconds * 1000)
        }
    }

    // Android 15+ shares the background dataSync time budget with other monitors in this app.
    override fun onTimeout(startId: Int, fgsType: Int) {
        val message = "已达到系统后台运行时限，请打开动态提醒页面重新开启"
        stopMonitoring(message)
        runCatching { notifications.paused(message) }
    }

    private fun stopMonitoring(message: String) {
        pollJob?.cancel()
        pollJob = null
        store.stopped(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (store.state.value.running) store.stopped("监控已中断，等待系统恢复或手动重新开启", resume = true)
        super.onDestroy()
    }

    companion object {
        const val START = "com.example.gptest.community.START"
        const val STOP = "com.example.gptest.community.STOP"
    }
}
