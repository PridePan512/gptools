package com.example.gptest

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gptest.business.BoxBreakout
import com.example.gptest.business.DailyBar
import com.example.gptest.business.StockCatalogEntry
import com.example.gptest.data.BoxHit
import com.example.gptest.data.BoxScanState
import com.example.gptest.data.BoxScanStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BoxScanStoreTest {
    @Test fun roundTripKeepsCheckpointResultsAndChartWithoutAutoStarting() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "box-store-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        try {
            val store = BoxScanStore(context)
            val date = LocalDate.of(2026, 9, 11)
            val stock = StockCatalogEntry("sh600000", "测试股票")
            val box = BoxBreakout(date.minusDays(30), date.minusDays(1), date, 20,
                10.0, 11.0, 3, 2, 11.3, null, 11.4, date)
            val hit = BoxHit(stock, box, listOf(DailyBar(date, 11.0, 11.3, 11.5, 10.9, 1500.0)), true)
            val state = BoxScanState(loaded = true, running = true, universe = listOf(stock),
                dates = (4 downTo 0).map { date.minusDays(it.toLong()) }, outcomes = mapOf(stock.code to "hit"),
                hits = listOf(hit), startedAt = 1234L)
            store.save(state)
            val restored = BoxScanStore(context).read()
            assertFalse(restored.running)
            assertEquals(state.universe, restored.universe)
            assertEquals(state.dates, restored.dates)
            assertEquals(state.outcomes, restored.outcomes)
            assertEquals(state.hits, restored.hits)
            assertEquals(1234L, restored.startedAt)
            assertTrue(restored.complete)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
