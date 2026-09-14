package com.example.gptest.ui.box

import android.content.Context
import com.example.gptest.business.AStockUniverse
import com.example.gptest.business.BoxDetector
import com.example.gptest.business.BoxMarketCalendar
import com.example.gptest.business.TradingSession
import com.example.gptest.data.BoxHit
import com.example.gptest.data.BoxScanState
import com.example.gptest.data.BoxScanStore
import com.example.gptest.data.HistoryRepository
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
        mutable.value = state.value.copy(running = true, message = "正在准备扫描…")
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
                            startedAt = System.currentTimeMillis(), message = "扫描中")
                    }
                    mutable.value = prepared
                    checkpoint()
                } else if (retryFailures) {
                    mutable.value = state.value.copy(outcomes = state.value.outcomes.filterValues { it != "failed" })
                }
                var consecutiveFailures = 0
                for (stock in state.value.universe) {
                    ensureActive()
                    if (stock.code in state.value.outcomes) continue
                    mutable.value = state.value.copy(message = "正在检测 ${stock.name} · ${stock.code}")
                    val dates = state.value.dates
                    val result = withContext(Dispatchers.IO) {
                        repository.fetchBars(stock.code, 100).mapCatching { history ->
                            val completed = history.bars.filter { it.date <= dates.last() }
                            when {
                                completed.size < 25 -> "short" to null
                                completed.last().date != dates.last() -> "stale" to null
                                else -> {
                                    val box = BoxDetector.detect(completed, dates)
                                    if (box == null) "none" to null else "hit" to BoxHit(stock, box,
                                        completed.filter { it.date >= box.start }, history.forwardAdjusted)
                                }
                            }
                        }
                    }
                    ensureActive()
                    val (outcome, hit) = result.getOrElse { "failed" to null }
                    consecutiveFailures = if (outcome == "failed") consecutiveFailures + 1 else 0
                    mutable.value = state.value.copy(outcomes = state.value.outcomes + (stock.code to outcome),
                        hits = if (hit == null) state.value.hits else state.value.hits.filterNot { it.stock.code == stock.code } + hit)
                    if (state.value.processed % 10 == 0) checkpoint()
                    if (consecutiveFailures >= 5) {
                        mutable.value = state.value.copy(message = "连续请求失败，已暂停。请稍后继续或重试失败项")
                        break
                    }
                    // A separate, deliberately modest queue; never occupies the quote polling job.
                    delay(if (outcome == "failed") 3_000L else 500L)
                }
                if (state.value.complete) mutable.value = state.value.copy(message = "扫描完成")
            } catch (cancelled: CancellationException) {
                mutable.value = state.value.copy(message = "已暂停，进度已保存")
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(message = "扫描准备或保存失败，请检查网络与存储后重试")
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
