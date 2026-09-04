package com.example.gptest.ui

import android.content.Context
import androidx.core.content.ContextCompat

interface MonitorServiceGateway {
    fun start()
    fun stop()
}

object NoOpMonitorServiceGateway : MonitorServiceGateway {
    override fun start() = Unit
    override fun stop() = Unit
}

class AndroidMonitorServiceGateway(context: Context) : MonitorServiceGateway {
    private val app = context.applicationContext

    override fun start() {
        ContextCompat.startForegroundService(app, QuoteMonitorService.startIntent(app))
    }

    override fun stop() {
        app.startService(QuoteMonitorService.stopIntent(app))
    }
}
