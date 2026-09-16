package com.example.gptest

import android.app.Application
import com.example.gptest.data.HistoryAccess

class GPTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("history-access", MODE_PRIVATE)
        HistoryAccess.gate.restore(preferences.getLong("retryAt", 0L)) { until ->
            preferences.edit().putLong("retryAt", until).commit()
        }
    }
}
