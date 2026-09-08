package com.example.gptest.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.gptest.R
import com.example.gptest.business.TradingSession
import com.example.gptest.data.SortPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QuoteMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monitor = QuoteMonitorHolder.get(this)
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(monitor.uiState.value),
            foregroundType()
        )
        if (intent?.action == ACTION_STOP) {
            monitor.stopPolling()
            stopNow()
            return START_NOT_STICKY
        }
        if (monitor.uiState.value.isRunning) {
            observe(monitor)
            return START_STICKY
        }
        val shouldResume = SortPreferences(this).monitorRunning
        if (!shouldResume) {
            stopNow()
            return START_NOT_STICKY
        }
        scope.launch {
            val loaded = monitor.uiState.first { it.watchlistLoaded }
            if (!loaded.isRunning) {
                monitor.startPolling(loaded.intervalSeconds.toString())
            }
            if (monitor.uiState.value.isRunning) {
                observe(monitor)
            } else {
                stopNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun observe(monitor: QuoteMonitor) {
        observeJob?.cancel()
        observeJob = scope.launch {
            monitor.uiState.collect { state ->
                if (state.isRunning) {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(state))
                } else {
                    stopNow()
                }
            }
        }
    }

    private fun stopNow() {
        observeJob?.cancel()
        observeJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: MainUiState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(notificationText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationText(state: MainUiState): String {
        val paused = state.status as? QuoteStatus.SessionOnce
        return when (paused?.phase) {
            TradingSession.Phase.PRE_OPEN -> getString(R.string.monitor_notification_paused_preopen)
            TradingSession.Phase.LUNCH -> getString(R.string.monitor_notification_paused_lunch)
            else -> getString(R.string.monitor_notification_text, state.intervalSeconds)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.monitor_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun foregroundType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    companion object {
        const val ACTION_START = "com.example.gptest.action.START_MONITOR"
        const val ACTION_STOP = "com.example.gptest.action.STOP_MONITOR"
        const val CHANNEL_ID = "quote_monitor"
        const val NOTIFICATION_ID = 4102

        fun startIntent(context: Context): Intent {
            return Intent(context, QuoteMonitorService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, QuoteMonitorService::class.java).setAction(ACTION_STOP)
        }
    }
}
