package com.example.gptest.ui.box

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
import com.example.gptest.data.BoxScanState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy

class BoxScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { BoxScanController.get(this) }
    private var observer: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "箱体扫描", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 2202, notification(controller.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (intent?.action == "pause") controller.pause()
        if (observer == null) observer = scope.launch {
            controller.state.distinctUntilChangedBy { Triple(it.running, it.processed / 10, it.hits.size) }.collect {
                if (!it.running) {
                    ServiceCompat.stopForeground(this@BoxScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else manager.notify(2202, notification(it))
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: BoxScanState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_alert).setContentTitle("箱体检测正在运行")
        .setContentText("已处理 ${state.processed}/${state.universe.size} · 命中 ${state.hits.size}")
        .setProgress(state.universe.size.coerceAtLeast(1), state.processed, state.universe.isEmpty())
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 2202, Intent(this, BoxScanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "暂停扫描", PendingIntent.getService(this, 2203,
            Intent(this, BoxScanService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
    companion object { private const val CHANNEL = "box-scan" }
}
