package com.example.gptest.business

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentScanRunnerTest {
    @Test fun completionAtDispatchDeadlineIsNotLost() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val results = mutableListOf<Int>()
        val summary = runner.run((0..39).toList(), request = { item ->
            delay(if (item % 2 == 0) 250 else 500)
            item
        }, onResult = { item, response ->
            assertEquals(item, response.getOrThrow())
            results += item
        })
        assertEquals(40, summary.completed)
        assertEquals((0..39).toList(), results.sorted())
    }

    @Test fun limitsConcurrencyAndGloballySpacesRequestStarts() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        var active = 0
        var peak = 0
        val starts = mutableListOf<Long>()
        val completed = mutableListOf<Int>()
        val summary = runner.run((0..11).toList(), request = { item ->
            starts += testScheduler.currentTime
            active++
            peak = maxOf(peak, active)
            delay(4300)
            active--
            item
        }, onResult = { item, response -> assertEquals(item, response.getOrThrow()); completed += item })
        assertEquals(4, peak)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 1000 })
        assertEquals((0..11).toList(), completed.sorted())
        assertEquals(12, summary.completed)
        assertFalse(summary.pausedForFailures)
        assertTrue(testScheduler.currentTime < 12 * 4300)
    }

    @Test fun outOfOrderResultsAreDeliveredExactlyOnceAndCallbacksDoNotOverlap() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val results = mutableListOf<Int>()
        var callbacks = 0
        runner.run((0..9).toList(), request = { item ->
            delay(if (item == 0) 1800 else 100)
            item * 2
        }, onResult = { item, response ->
            callbacks++
            assertEquals(1, callbacks)
            delay(30) // Simulate checkpoint IO.
            assertEquals(item * 2, response.getOrThrow())
            results += item
            callbacks--
        })
        assertNotEquals(0, results.first())
        assertEquals((0..9).toList(), results.sorted())
    }

    @Test fun persistentFailuresBackOffThenStopDispatching() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val launches = mutableListOf<Pair<Long, Int>>()
        val summary = runner.run((0..99).toList(), request = { _: Int ->
            delay(10)
            throw IllegalStateException("limited")
        }, onDispatch = { _, limit -> launches += testScheduler.currentTime to limit }, onResult = { _, result ->
            assertTrue(result.isFailure)
        })
        assertEquals(5, summary.completed)
        assertTrue(summary.pausedForFailures)
        assertEquals(listOf(4, 4, 2, 1, 1), launches.map { it.second })
        assertTrue(launches[2].first - launches[1].first >= 3000)
    }

    @Test fun successStreakGraduallyRestoresFourWorkers() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val limits = mutableListOf<Int>()
        val summary = runner.run((0..24).toList(), request = { item ->
            delay(10)
            if (item < 3) error("offline")
            item
        }, onDispatch = { _, limit -> limits += limit }, onResult = { _, _ -> })
        assertFalse(summary.pausedForFailures)
        assertTrue(1 in limits && 2 in limits)
        assertEquals(4, limits.last())
    }

    @Test fun pauseLeavesUncommittedStocksForResumeAndNoLateCallbacks() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val outcomes = mutableSetOf<Int>()
        val scan = launch {
            runner.run((0..9).toList(), request = { item -> delay(1000); item },
                onResult = { item, _ -> outcomes += item })
        }
        advanceTimeBy(1350)
        runCurrent()
        scan.cancelAndJoin()
        val checkpoint = outcomes.toSet()
        advanceTimeBy(3000)
        assertEquals(checkpoint, outcomes)
        assertTrue(checkpoint.isNotEmpty() && checkpoint.size < 10)
        runner.run((0..9).filterNot { it in outcomes }, request = { it }, onResult = { item, _ ->
            assertTrue(outcomes.add(item))
        })
        assertEquals((0..9).toSet(), outcomes)
    }

    @Test fun workerCancellationCancelsQueueInsteadOfHanging() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val scan = launch {
            runner.run(listOf(1, 2), request = { throw CancellationException("cancelled") }, onResult = { _, _ -> fail() })
        }
        runCurrent()
        assertTrue(scan.isCancelled)
    }

    @Test fun providerBlockStopsImmediatelyAndDrainsAlreadyRunningRequests() = runTest {
        val runner = ConcurrentScanRunner(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        val received = mutableListOf<Int>()
        val summary = runner.run((0..20).toList(), request = { item ->
            if (item == 0) { delay(1500); throw IllegalAccessException("blocked") }
            delay(2000)
            item
        }, shouldPause = { it is IllegalAccessException }, onResult = { item, _ -> received += item })
        assertTrue(summary.pausedForFailures)
        assertEquals(listOf(0, 1), received)
        assertEquals(2, summary.completed)
    }
}
