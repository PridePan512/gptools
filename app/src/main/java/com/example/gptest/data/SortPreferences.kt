package com.example.gptest.data

import android.content.Context
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.ui.alert.RapidAlertThresholds

class SortPreferences(context: Context) : SortModeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var mode: QuoteSortMode
        get() {
            val stored = prefs.getString(KEY_MODE, QuoteSortMode.CUSTOM.name)
            return QuoteSortMode.entries.find { it.name == stored } ?: QuoteSortMode.CUSTOM
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    override var intervalSeconds: Long
        get() {
            val stored = prefs.getLong(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
            return if (stored < 1L) DEFAULT_INTERVAL_SECONDS else stored
        }
        set(value) {
            val seconds = if (value < 1L) DEFAULT_INTERVAL_SECONDS else value
            prefs.edit().putLong(KEY_INTERVAL_SECONDS, seconds).apply()
        }

    override var monitorRunning: Boolean
        get() = prefs.getBoolean(KEY_MONITOR_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MONITOR_RUNNING, value).commit()
        }

    override var rapidAlertThresholds: RapidAlertThresholds
        get() = RapidAlertThresholds(
            volumeSurgePercent = storedPercent(KEY_VOLUME_SURGE_PERCENT, RapidAlertThresholds.DEFAULT_VOLUME_SURGE_PERCENT),
            volumeShrinkPercent = storedPercent(KEY_VOLUME_SHRINK_PERCENT, RapidAlertThresholds.DEFAULT_VOLUME_SHRINK_PERCENT),
            priceSurgePercent = storedPercent(KEY_PRICE_SURGE_PERCENT, RapidAlertThresholds.DEFAULT_PRICE_SURGE_PERCENT),
            priceDropPercent = storedPercent(KEY_PRICE_DROP_PERCENT, RapidAlertThresholds.DEFAULT_PRICE_DROP_PERCENT)
        )
        set(value) {
            prefs.edit()
                .putString(KEY_VOLUME_SURGE_PERCENT, value.volumeSurgePercent.toString())
                .putString(KEY_VOLUME_SHRINK_PERCENT, value.volumeShrinkPercent.toString())
                .putString(KEY_PRICE_SURGE_PERCENT, value.priceSurgePercent.toString())
                .putString(KEY_PRICE_DROP_PERCENT, value.priceDropPercent.toString())
                .apply()
        }

    private fun storedPercent(key: String, fallback: Double): Double {
        val raw = prefs.getString(key, null) ?: return fallback
        val value = raw.toDoubleOrNull() ?: return fallback
        return if (value > 0.0) value else fallback
    }

    companion object {
        private const val PREFS_NAME = "gptest_prefs"
        private const val KEY_MODE = "quote_sort_mode"
        private const val KEY_INTERVAL_SECONDS = "quote_interval_seconds"
        private const val KEY_MONITOR_RUNNING = "quote_monitor_running"
        private const val KEY_VOLUME_SURGE_PERCENT = "rapid_volume_surge_percent"
        private const val KEY_VOLUME_SHRINK_PERCENT = "rapid_volume_shrink_percent"
        private const val KEY_PRICE_SURGE_PERCENT = "rapid_price_surge_percent"
        private const val KEY_PRICE_DROP_PERCENT = "rapid_price_drop_percent"
        private const val DEFAULT_INTERVAL_SECONDS = 5L
    }
}
