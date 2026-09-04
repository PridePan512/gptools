package com.example.gptest.ui.alert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.gptest.R

fun interface AlertNotifier {
    fun notifyFired(fires: List<AlertFire>)
}

object NoOpAlertNotifier : AlertNotifier {
    override fun notifyFired(fires: List<AlertFire>) = Unit
}

class AndroidAlertNotifier(context: Context) : AlertNotifier {

    private val app = context.applicationContext

    override fun notifyFired(fires: List<AlertFire>) {
        if (fires.isEmpty()) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(app)
        fires.forEach { fire ->
            val intent = Intent(app, AlertRulesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                app,
                fire.rule.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(fire.rule.displayName())
                .setContentText(fire.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fire.detail))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                manager.notify(fire.rule.id.hashCode(), notification)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = app.getString(R.string.alert_channel_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "alert_rules"
    }
}

object AlertNotificationPermission {
    fun requestIfNeeded(activity: FragmentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE
        )
    }

    private const val REQUEST_CODE = 4101
}
