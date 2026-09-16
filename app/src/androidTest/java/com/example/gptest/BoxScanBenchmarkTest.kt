package com.example.gptest

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gptest.business.AStockUniverse
import com.example.gptest.business.BoxDetector
import com.example.gptest.business.BoxMarketCalendar
import com.example.gptest.business.ConcurrentScanRunner
import com.example.gptest.business.DailyBar
import com.example.gptest.business.TradingSession
import com.example.gptest.data.HistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import kotlin.system.measureTimeMillis

/** Explicit opt-in network benchmark; does not modify the user's scan records. */
@RunWith(AndroidJUnit4::class)
class BoxScanBenchmarkTest {
    @Test fun compareSequentialAndConcurrentOnSameTwelveStocks() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("boxBenchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val repository = HistoryRepository()
        val codes = AStockUniverse.parse(app.assets.open("stock_catalog.txt").bufferedReader().use { it.readText() })
            .take(12).map { it.code }
        val dates = BoxMarketCalendar.completedDates(repository.fetchBars("sh000001", 40).getOrThrow().bars,
            ZonedDateTime.now(TradingSession.SHANGHAI))
        fun fetch(code: String) = repository.fetchBars(code, 100).getOrThrow().bars.filter { it.date <= dates.last() }
        val sequential = mutableMapOf<String, List<DailyBar>>()
        val parallel = mutableMapOf<String, List<DailyBar>>()
        val serialMs = measureTimeMillis {
            for (code in codes) {
                sequential[code] = fetch(code)
                delay(500)
            }
        }
        val parallelMs = measureTimeMillis {
            val summary = ConcurrentScanRunner().run(codes, request = { fetch(it) }, onResult = { code, result ->
                parallel[code] = result.getOrThrow()
            })
            assertFalse(summary.pausedForFailures)
        }
        assertEquals(sequential, parallel)
        assertEquals(sequential.mapValues { BoxDetector.detect(it.value, dates) },
            parallel.mapValues { BoxDetector.detect(it.value, dates) })
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nBOX_BENCHMARK stocks=${codes.size} sequentialMs=$serialMs parallelMs=$parallelMs failures=0 resultsEqual=true\n")
        })
    }
}
