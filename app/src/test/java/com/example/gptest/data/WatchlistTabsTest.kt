package com.example.gptest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchlistTabsTest {

    @Test
    fun normalizeName_trimsAndRejectsBlank() {
        assertEquals("观察", WatchlistTabs.normalizeName("  观察  "))
        assertNull(WatchlistTabs.normalizeName("   "))
        assertNull(WatchlistTabs.normalizeName(""))
    }

    @Test
    fun defaultTab_isLockedSelfSelect() {
        val tab = WatchlistTabs.defaultTab()
        assertEquals(WatchlistTabs.DEFAULT_ID, tab.id)
        assertEquals(WatchlistTabs.DEFAULT_NAME, tab.name)
        assertEquals(true, tab.locked)
        assertEquals(0, tab.sortOrder)
    }
}
