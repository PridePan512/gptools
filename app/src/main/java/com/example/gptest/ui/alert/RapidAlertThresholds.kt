package com.example.gptest.ui.alert

data class RapidAlertThresholds(
    val volumeSurgePercent: Double = DEFAULT_VOLUME_SURGE_PERCENT,
    val volumeShrinkPercent: Double = DEFAULT_VOLUME_SHRINK_PERCENT,
    val priceSurgePercent: Double = DEFAULT_PRICE_SURGE_PERCENT,
    val priceDropPercent: Double = DEFAULT_PRICE_DROP_PERCENT
) {
    val volumeSurgeRatio: Double get() = volumeSurgePercent / 100.0
    val volumeShrinkRatio: Double get() = volumeShrinkPercent / 100.0
    val priceSurgeRatio: Double get() = priceSurgePercent / 100.0
    val priceDropRatio: Double get() = priceDropPercent / 100.0

    companion object {
        const val DEFAULT_VOLUME_SURGE_PERCENT = 200.0
        const val DEFAULT_VOLUME_SHRINK_PERCENT = 50.0
        const val DEFAULT_PRICE_SURGE_PERCENT = 1.0
        const val DEFAULT_PRICE_DROP_PERCENT = 1.0

        val DEFAULT = RapidAlertThresholds()

        fun parse(
            volumeSurge: String,
            volumeShrink: String,
            priceSurge: String,
            priceDrop: String
        ): RapidAlertThresholds {
            return RapidAlertThresholds(
                volumeSurgePercent = parsePercent(volumeSurge, DEFAULT_VOLUME_SURGE_PERCENT),
                volumeShrinkPercent = parsePercent(volumeShrink, DEFAULT_VOLUME_SHRINK_PERCENT),
                priceSurgePercent = parsePercent(priceSurge, DEFAULT_PRICE_SURGE_PERCENT),
                priceDropPercent = parsePercent(priceDrop, DEFAULT_PRICE_DROP_PERCENT)
            )
        }

        fun formatPercent(value: Double): String {
            return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        }

        private fun parsePercent(raw: String, fallback: Double): Double {
            val value = raw.trim().removeSuffix("%").toDoubleOrNull() ?: return fallback
            return if (value > 0.0) value else fallback
        }
    }
}
