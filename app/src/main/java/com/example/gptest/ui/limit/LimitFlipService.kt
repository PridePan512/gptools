package com.example.gptest.ui.limit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.gptest.R
import com.example.gptest.data.LimitFlipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class LimitFlipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { LimitFlipController.get(this) }
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "先涨停后跌停", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(controller.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (intent?.action == "pause") controller.pause()
        if (observer == null) observer = scope.launch {
            controller.state.distinctUntilChangedBy { Triple(it.running, it.processed / 10, it.hits.size) }.collect {
                if (!it.running) {
                    ServiceCompat.stopForeground(this@LimitFlipService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else manager.notify(NOTIFICATION_ID, notification(it))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: LimitFlipState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_alert)
        .setContentTitle("先涨停后跌停正在统计")
        .setContentText("已处理 ${state.processed}/${state.universe.size} · ${state.stockCount} 只")
        .setProgress(state.universe.size.coerceAtLeast(1), state.processed, state.universe.isEmpty())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, LimitFlipActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "暂停统计", PendingIntent.getService(this, NOTIFICATION_ID + 1,
            Intent(this, LimitFlipService::class.java).setAction("pause"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.pause()
        stopSelf()
    }

    override fun onDestroy() {
        controller.pause()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "limit-flip"
        private const val NOTIFICATION_ID = 2302
    }
}
