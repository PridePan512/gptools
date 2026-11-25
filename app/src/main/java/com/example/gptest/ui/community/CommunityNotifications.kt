package com.example.gptest.ui.community

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.example.gptest.R
import com.example.gptest.business.CommunityAccount
import com.example.gptest.business.CommunityPost

class CommunityNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(
            MONITOR_CHANNEL, "动态监控状态", NotificationManager.IMPORTANCE_LOW
        ))
        manager.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL, "关注账号的新动态", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "关注账号发布新动态时提醒" })
    }

    fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(ALERT_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE

    fun ongoing(message: String): Notification {
        val stop = PendingIntent.getService(context, 0,
            Intent(context, CommunityMonitorService::class.java).setAction(CommunityMonitorService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("正在关注 · ${CommunityAccount.NAME}")
            .setContentText(message)
            .setContentIntent(openMonitor())
            .setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止提醒", stop).build()
    }

    fun update(message: String) = manager.notify(MONITOR_ID, ongoing(message))

    fun newPost(post: CommunityPost) {
        val intent = Intent(Intent.ACTION_VIEW, post.url.toUri())
        val open = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("${CommunityAccount.NAME} 发布了新动态")
            .setContentText(post.text.take(4000))
            .setStyle(NotificationCompat.BigTextStyle().bigText(post.text.take(4000)))
            .setWhen(post.createdAtSeconds * 1000).setShowWhen(true)
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        // String tag preserves 64-bit post IDs and makes crash retries replace the same card.
        manager.notify("community-post:${post.id}", 1, notification)
    }

    fun paused(message: String) {
        manager.notify(PAUSED_ID, NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert).setContentTitle("动态提醒已停止")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openMonitor()).setAutoCancel(true).build())
    }

    fun clearPaused() = manager.cancel(PAUSED_ID)

    private fun openMonitor() = PendingIntent.getActivity(context, 0,
        Intent(context, CommunityMonitorActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ALERT_CHANNEL = "community_posts"
        const val MONITOR_CHANNEL = "community_monitor"
        const val MONITOR_ID = 4301
        private const val PAUSED_ID = 4302
    }
}
