package com.example.gptest.ui.box

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.gptest.data.BoxScanPreferences
import com.example.gptest.business.AStockUniverse
import com.example.gptest.business.BoxDetector
import com.example.gptest.business.BoxMarketCalendar
import com.example.gptest.business.TradingSession
import com.example.gptest.data.BoxHit
import com.example.gptest.data.BoxScanState
import com.example.gptest.data.BoxScanStore
import com.example.gptest.data.HistoryRepository
import com.example.gptest.data.HistoryAccess
import com.example.gptest.data.HistoryAccessBlockedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

class BoxScanController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = BoxScanStore(app)
    private val repository = HistoryRepository()
    private val mutable = MutableStateFlow(BoxScanState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch { mutable.value = withContext(Dispatchers.IO) { store.read() } }
    }

    fun start(fresh: Boolean = false, retryFailures: Boolean = false) {
        if (!state.value.loaded || job?.isActive == true) return
        if (!fresh && state.value.universe.isNotEmpty() && state.value.ruleVersion != BoxDetector.VERSION) {
            mutable.value = state.value.copy(message = "检测规则已更新，请重新扫描以应用当前规则；旧结果仍可查看")
            return
        }
        HistoryAccess.gate.blocked()?.let {
            mutable.value = state.value.copy(message = it.message.orEmpty())
            return
        }
        val scanConfig = if (fresh || state.value.universe.isEmpty()) BoxScanPreferences(app).read() else state.value.config
        mutable.value = state.value.copy(running = true, message = "正在准备扫描…")
        try {
            ContextCompat.startForegroundService(app, Intent(app, BoxScanService::class.java))
        } catch (error: Exception) {
            mutable.value = state.value.copy(running = false, message = "无法启动后台扫描：${error.message}")
            return
        }
        job = scope.launch {
            try {
                if (fresh || state.value.universe.isEmpty()) {
                    val prepared = withContext(Dispatchers.IO) {
                        val now = ZonedDateTime.now(TradingSession.SHANGHAI)
                        val bars = repository.fetchBars("sh000001", 40).getOrThrow().bars
                        val dates = BoxMarketCalendar.completedDates(bars, now)
                        check(dates.size == 5 && dates.last() >= now.toLocalDate().minusDays(20)) { "无法获得近期市场交易日期，请稍后重试" }
                        val stocks = AStockUniverse.parse(app.assets.open("stock_catalog.txt").bufferedReader().use { it.readText() })
                        check(stocks.isNotEmpty()) { "内置股票名单为空" }
                        BoxScanState(loaded = true, running = true, universe = stocks, dates = dates,
                            config = scanConfig,
                            startedAt = System.currentTimeMillis(), message = "扫描中")
                    }
                    mutable.value = prepared
                    checkpoint()
                } else if (retryFailures) {
                    mutable.value = state.value.copy(outcomes = state.value.outcomes.filterValues { it != "failed" })
                }
                val pending = state.value.universe.filterNot { it.code in state.value.outcomes }
                val dates = state.value.dates
                val config = state.value.config
                var lastError: String? = null
                val summary = com.example.gptest.business.ConcurrentScanRunner().run(
                    items = pending,
                    request = { stock ->
                        val history = repository.fetchBars(stock.code, config.historyCount).getOrThrow()
                        val completed = history.bars.filter { it.date <= dates.last() }
                        when {
                            completed.size < config.minimumBars -> "short" to null
                            completed.last().date != dates.last() -> "stale" to null
                            else -> {
                                val box = BoxDetector.detect(completed, dates, config)
                                if (box == null) "none" to null else "hit" to BoxHit(stock, box,
                                    completed.filter { it.date >= box.start }, history.forwardAdjusted)
                            }
                        }
                    },
                    onDispatch = { stock, limit ->
                        mutable.value = state.value.copy(message = "并发检测（最多 $limit 路）${stock.name} · ${stock.code}")
                    },
                    shouldPause = { it is HistoryAccessBlockedException },
                    onResult = { stock, result ->
                        val error = result.exceptionOrNull()
                        if (error != null) {
                            lastError = error.message ?: error.javaClass.simpleName
                            android.util.Log.w("BoxScan", "History failed for ${stock.code}", error)
                        }
                        // A provider-wide block is not a judgement about this stock. Leave it pending.
                        if (error is HistoryAccessBlockedException) return@run
                        val (outcome, hit) = result.getOrElse { "failed" to null }
                        mutable.value = state.value.copy(outcomes = state.value.outcomes + (stock.code to outcome),
                            hits = if (hit == null) state.value.hits else state.value.hits.filterNot { it.stock.code == stock.code } + hit)
                        if (state.value.processed % 10 == 0) checkpoint()
                    }
                )
                if (summary.pausedForFailures) {
                    mutable.value = state.value.copy(message = HistoryAccess.gate.blocked()?.message
                        ?: "连续请求失败，已暂停：${lastError ?: "未知错误"}。请稍后重试失败项")
                }
                if (state.value.complete) mutable.value = state.value.copy(message = "扫描完成")
            } catch (cancelled: CancellationException) {
                mutable.value = state.value.copy(message = "已暂停，进度已保存")
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BoxScan", "Scan preparation or save failed", error)
                mutable.value = state.value.copy(message = error.message ?: "扫描准备或保存失败，请稍后重试")
            } finally {
                mutable.value = state.value.copy(running = false)
                withContext(NonCancellable) {
                    try { checkpoint() } catch (_: Exception) {
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
        @Volatile private var instance: BoxScanController? = null
        fun get(context: Context): BoxScanController = instance ?: synchronized(this) {
            instance ?: BoxScanController(context).also { instance = it }
        }
    }
}
