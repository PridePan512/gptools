package com.example.gptest.business

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout

data class ScanRunSummary(val completed: Int, val pausedForFailures: Boolean)

/** Owns dispatch and completion bookkeeping on the caller's coroutine. Workers only return results. */
class ConcurrentScanRunner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T, R> run(
        items: List<T>,
        request: suspend (T) -> R,
        onDispatch: (T, Int) -> Unit = { _, _ -> },
        shouldPause: (Throwable) -> Boolean = { false },
        onResult: suspend (T, Result<R>) -> Unit
    ): ScanRunSummary = coroutineScope {
        val results = Channel<Pair<T, Result<R>>>(Channel.UNLIMITED)
        var next = 0
        var active = 0
        var completed = 0
        var limit = 4
        var nextStart = nowMs()
        var failures = 0
        var successes = 0
        var paused = false
        try {
            while ((!paused && next < items.size) || active > 0) {
                ensureActive()
                val mayDispatch = !paused && next < items.size && active < limit
                val wait = nextStart - nowMs()
                // Process already available completions before launching more work, including failure feedback.
                var result = results.tryReceive().getOrNull()
                if (result == null && mayDispatch && wait <= 0) {
                    val item = items[next++]
                    active++
                    onDispatch(item, limit)
                    nextStart = nowMs() + when (limit) { 1 -> 3_000L; 2 -> 1_500L; else -> 1_000L }
                    launch {
                        val response = try {
                            Result.success(withContext(dispatcher) { request(item) })
                        } catch (cancelled: CancellationException) {
                            this@coroutineScope.cancel(cancelled)
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                        results.send(item to response)
                    }
                    continue
                }
                if (result == null) {
                    // Atomic selection avoids consuming a completion and losing it to a timeout race.
                    result = if (mayDispatch) select<Pair<T, Result<R>>?> {
                        results.onReceive { it }
                        onTimeout(wait.coerceAtLeast(1L)) { null }
                    } else results.receive()
                }
                if (result == null) continue
                active--
                completed++
                if (result.second.isFailure) {
                    if (shouldPause(result.second.exceptionOrNull()!!)) paused = true
                    successes = 0
                    failures++
                    if (failures >= 2) {
                        limit = if (failures >= 3) 1 else 2
                        nextStart = maxOf(nextStart, nowMs() + 3_000L)
                    }
                    if (failures >= 5) paused = true
                } else {
                    failures = 0
                    successes++
                    if (successes >= 8 && limit < 4) {
                        limit = if (limit == 1) 2 else 4
                        successes = 0
                    }
                }
                onResult(result.first, result.second)
            }
            ScanRunSummary(completed, paused)
        } finally {
            results.close()
        }
    }
}
