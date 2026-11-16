package com.example.gptest.ui.limit

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.gptest.business.AStockUniverse
import com.example.gptest.business.ConcurrentScanRunner
import com.example.gptest.business.LimitFlipDetector
import com.example.gptest.business.TradingSession
import com.example.gptest.data.HistoryAccess
import com.example.gptest.data.HistoryAccessBlockedException
import com.example.gptest.data.HistoryRepository
import com.example.gptest.data.LimitFlipHit
import com.example.gptest.data.LimitFlipState
import com.example.gptest.data.LimitFlipStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class LimitFlipController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = LimitFlipStore(app)
    private val repository = HistoryRepository()
    private val mutable = MutableStateFlow(LimitFlipState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch { mutable.value = withContext(Dispatchers.IO) { store.read() } }
    }

    fun start(fresh: Boolean = false, retryFailures: Boolean = false) {
        if (!state.value.loaded || job?.isActive == true) return
        if (!fresh && state.value.universe.isNotEmpty() && state.value.ruleVersion != LimitFlipDetector.VERSION) {
            mutable.value = state.value.copy(message = "统计规则已更新，请重新统计；旧结果仍可查看")
            return
        }
        HistoryAccess.gate.blocked()?.let {
            mutable.value = state.value.copy(message = it.message.orEmpty())
            return
        }
        mutable.value = state.value.copy(running = true, message = "正在准备统计…")
        try {
            ContextCompat.startForegroundService(app, Intent(app, LimitFlipService::class.java))
        } catch (error: Exception) {
            mutable.value = state.value.copy(running = false, message = "无法启动后台统计：${error.message}")
            return
        }
        job = scope.launch {
            try {
                if (fresh || state.value.universe.isEmpty()) {
                    val prepared = withContext(Dispatchers.IO) {
                        val now = ZonedDateTime.now(TradingSession.SHANGHAI)
                        val stocks = AStockUniverse.parse(app.assets.open("stock_catalog.txt").bufferedReader().use { it.readText() })
                            .filter { LimitFlipDetector.included(it.code) }
                        check(stocks.isNotEmpty()) { "内置股票名单为空" }
                        LimitFlipState(
                            loaded = true,
                            running = true,
                            universe = stocks,
                            windowStart = now.toLocalDate().minusMonths(6),
                            startedAt = System.currentTimeMillis(),
                            message = "统计中"
                        )
                    }
                    mutable.value = prepared
                    checkpoint()
                } else if (retryFailures) {
                    mutable.value = state.value.copy(outcomes = state.value.outcomes.filterValues { it != "failed" })
                }
                val pending = state.value.universe.filterNot { it.code in state.value.outcomes }
                val windowStart = state.value.windowStart ?: return@launch
                var lastError: String? = null
                val summary = ConcurrentScanRunner().run(
                    items = pending,
                    request = { stock ->
                        val history = repository.fetchBars(stock.code, LimitFlipDetector.HISTORY_BARS, forwardAdjust = false).getOrThrow()
                        check(!history.forwardAdjusted) { "涨跌停统计需要不复权日线" }
                        val bars = LimitFlipDetector.completedBars(history.bars, ZonedDateTime.now(TradingSession.SHANGHAI))
                        if (bars.size < 5) "short" to emptyList() else {
                            val flips = LimitFlipDetector.find(bars, LimitFlipDetector.LIMIT_RATIO, windowStart)
                            if (flips.isEmpty()) "none" to emptyList() else "hit" to flips.map { LimitFlipHit(stock, it) }
                        }
                    },
                    onDispatch = { stock, limit ->
                        mutable.value = state.value.copy(message = "并发统计（最多 $limit 路）${stock.name} · ${stock.code}")
                    },
                    shouldPause = { it is HistoryAccessBlockedException },
                    onResult = { stock, result ->
                        val error = result.exceptionOrNull()
                        if (error != null) {
                            lastError = error.message ?: error.javaClass.simpleName
                            android.util.Log.w("LimitFlip", "History failed for ${stock.code}", error)
                        }
                        if (error is HistoryAccessBlockedException) return@run
                        val (outcome, found) = result.getOrElse { "failed" to emptyList() }
                        mutable.value = state.value.copy(
                            outcomes = state.value.outcomes + (stock.code to outcome),
                            hits = state.value.hits.filterNot { it.stock.code == stock.code } + found
                        )
                        if (state.value.processed % 10 == 0) checkpoint()
                    }
                )
                if (summary.pausedForFailures) {
                    mutable.value = state.value.copy(message = HistoryAccess.gate.blocked()?.message
                        ?: "连续请求失败，已暂停：${lastError ?: "未知错误"}。请稍后重试失败项")
                }
                if (state.value.complete) mutable.value = state.value.copy(message = "统计完成")
            } catch (cancelled: CancellationException) {
                mutable.value = state.value.copy(message = "已暂停，进度已保存")
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("LimitFlip", "Scan preparation or save failed", error)
                mutable.value = state.value.copy(message = error.message ?: "统计准备或保存失败，请稍后重试")
            } finally {
                mutable.value = state.value.copy(running = false)
                withContext(NonCancellable) {
                    try {
                        checkpoint()
                    } catch (_: Exception) {
                        mutable.value = state.value.copy(message = "进度保存失败，本次结果暂留内存")
                    }
                }
            }
        }
    }

    fun pause() {
        if (job?.isActive == true) {
            mutable.value = state.value.copy(message = "正在暂停并保存进度…")
            job?.cancel()
        }
    }

    private suspend fun checkpoint() {
        val snapshot = state.value
        withContext(Dispatchers.IO) { store.save(snapshot) }
    }

    companion object {
        @Volatile private var instance: LimitFlipController? = null
        fun get(context: Context): LimitFlipController = instance ?: synchronized(this) {
            instance ?: LimitFlipController(context).also { instance = it }
        }
    }
}
