package com.example.gptest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryWatchlistStoreTest {

    @Test
    fun loadTabs_startsWithLockedDefault() {
        val store = InMemoryWatchlistStore(mutableListOf("sz000001"))
        val tab = store.loadTabs().single()
        assertEquals(WatchlistTabs.DEFAULT_ID, tab.id)
        assertTrue(tab.locked)
        assertEquals(listOf("sz000001"), store.load(WatchlistTabs.DEFAULT_ID))
    }

    @Test
    fun addAndRemove_areScopedToTab() {
        val store = InMemoryWatchlistStore(mutableListOf("sz000001"))
        val other = WatchlistTab("g1", "观察", locked = false, sortOrder = 1)
        store.addTab(other)
        store.add("g1", "sz000001")
        store.add("g1", "sz000002")
        store.remove(WatchlistTabs.DEFAULT_ID, "sz000001")
        assertEquals(emptyList<String>(), store.load(WatchlistTabs.DEFAULT_ID))
        assertEquals(listOf("sz000001", "sz000002"), store.load("g1"))
        assertEquals(listOf("sz000001", "sz000002"), store.allCodes())
    }

    @Test
    fun allCodes_dedupesAcrossTabs_defaultFirst() {
        val store = InMemoryWatchlistStore(mutableListOf("sz000001", "sz000002"))
        store.addTab(WatchlistTab("g1", "观察", locked = false, sortOrder = 1))
        store.add("g1", "sz000002")
        store.add("g1", "sz000003")
        assertEquals(listOf("sz000001", "sz000002", "sz000003"), store.allCodes())
    }

    @Test
    fun deleteTab_skipsLocked_andDropsUnlockedStocks() {
        val store = InMemoryWatchlistStore(mutableListOf("sz000001"))
        store.addTab(WatchlistTab("g1", "观察", locked = false, sortOrder = 1))
        store.add("g1", "sz000002")
        store.deleteTab(WatchlistTabs.DEFAULT_ID)
        store.deleteTab("g1")
        assertEquals(listOf(WatchlistTabs.DEFAULT_ID), store.loadTabs().map { it.id })
        assertEquals(listOf("sz000001"), store.allCodes())
        assertEquals(emptyList<String>(), store.load("g1"))
    }

    @Test
    fun renameTab_updatesName() {
        val store = InMemoryWatchlistStore()
        store.renameTab(WatchlistTabs.DEFAULT_ID, "  我的自选  ")
        assertEquals("我的自选", store.loadTabs().single().name)
    }
}
