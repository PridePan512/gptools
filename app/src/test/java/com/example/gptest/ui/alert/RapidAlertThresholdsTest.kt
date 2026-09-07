package com.example.gptest.ui.alert

import org.junit.Assert.assertEquals
import org.junit.Test

class RapidAlertThresholdsTest {

    @Test
    fun defaults_matchBuiltInPercents() {
        val defaults = RapidAlertThresholds.DEFAULT
        assertEquals(200.0, defaults.volumeSurgePercent, 0.0)
        assertEquals(50.0, defaults.volumeShrinkPercent, 0.0)
        assertEquals(1.0, defaults.priceSurgePercent, 0.0)
        assertEquals(1.0, defaults.priceDropPercent, 0.0)
        assertEquals(2.0, defaults.volumeSurgeRatio, 0.0)
        assertEquals(0.5, defaults.volumeShrinkRatio, 0.0)
        assertEquals(0.01, defaults.priceSurgeRatio, 0.0)
        assertEquals(0.01, defaults.priceDropRatio, 0.0)
    }

    @Test
    fun parse_readsPercents() {
        val parsed = RapidAlertThresholds.parse("150", "40", "0.8", "2.5")
        assertEquals(150.0, parsed.volumeSurgePercent, 0.0)
        assertEquals(40.0, parsed.volumeShrinkPercent, 0.0)
        assertEquals(0.8, parsed.priceSurgePercent, 0.0)
        assertEquals(2.5, parsed.priceDropPercent, 0.0)
    }

    @Test
    fun parse_invalidOrNonPositive_fallsBackToDefault() {
        val parsed = RapidAlertThresholds.parse(" ", "abc", "0", "-1")
        assertEquals(RapidAlertThresholds.DEFAULT, parsed)
    }

    @Test
    fun parse_stripsPercentSuffix() {
        val parsed = RapidAlertThresholds.parse("180%", "60%", "1.2%", "3%")
        assertEquals(180.0, parsed.volumeSurgePercent, 0.0)
        assertEquals(60.0, parsed.volumeShrinkPercent, 0.0)
        assertEquals(1.2, parsed.priceSurgePercent, 0.0)
        assertEquals(3.0, parsed.priceDropPercent, 0.0)
    }

    @Test
    fun formatPercent_omitsTrailingZero() {
        assertEquals("200", RapidAlertThresholds.formatPercent(200.0))
        assertEquals("0.8", RapidAlertThresholds.formatPercent(0.8))
    }
}
