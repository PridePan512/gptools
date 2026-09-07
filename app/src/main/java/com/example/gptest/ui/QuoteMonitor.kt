package com.example.gptest.ui

import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.QuoteSorter
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AlertDataSource
import com.example.gptest.data.NoOpAlertDataSource
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
import com.example.gptest.ui.alert.AlertEvaluator
import com.example.gptest.ui.alert.AlertNotifier
import com.example.gptest.ui.alert.NoOpAlertNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

class QuoteMonitor(
    private val quoteDataSource: QuoteDataSource,
    private val watchlistDataSource: WatchlistDataSource,
    private val sortModeStore: SortModeStore,
    private val clock: Clock = Clock.system(TradingSession.SHANGHAI),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val alertDataSource: AlertDataSource = NoOpAlertDataSource,
    private val alertNotifier: AlertNotifier = NoOpAlertNotifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchlist = mutableListOf<String>()
    private var quotes: List<QuoteSnapshot> = emptyList()
    private var previousQuotes: List<QuoteSnapshot> = emptyList()
    private var selectedCode: String? = null
    private var sortMode: QuoteSortMode = sortModeStore.mode
    private var rawExpanded = false
    private var isRunning = false
    private var watchlistLoaded = false
    private var status: QuoteStatus = QuoteStatus.Idle
    private var intervalSeconds = sortModeStore.intervalSeconds.let { if (it < 1L) 5L else it }
    private var intervalMs = intervalSeconds * 1000L
    private var pollJob: Job? = null
    private var pendingUndo: PendingUndo? = null
    private var lastUpdatedMs: Long? = null
    private var shanghaiIndex: QuoteSnapshot? = null

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            val codes = withContext(ioDispatcher) { watchlistDataSource.load() }
            watchlist.clear()
            watchlist.addAll(codes)
            watchlistLoaded = true
            publish()
        }
    }

    fun addCode(raw: String) {
        if (raw.trim().isEmpty()) {
            _events.tryEmit(UiEvent.AddEmptyCode)
            return
        }
        val code = QuoteParser.normalizeStockCode(raw) ?: run {
            status = QuoteStatus.InvalidCode
            _events.tryEmit(UiEvent.AddInvalidCode)
            publish()
            return
        }
        if (watchlist.contains(code)) {
            status = QuoteStatus.DuplicateCode
            _events.tryEmit(UiEvent.AddDuplicateCode)
            publish()
            return
        }
        watchlist.add(code)
        persist { watchlistDataSource.add(code) }
        val label = code.removePrefix("sz").removePrefix("sh").removePrefix("bj")
        _events.tryEmit(UiEvent.AddSucceeded(label))
        if (isRunning) {
            publish()
            refreshQuotesNow()
        } else {
            publish()
        }
    }

    fun removeCode(code: String) {
        val index = watchlist.indexOf(code)
        if (index < 0) return
        val quote = quotes.find { it.requestCode == code }
        pendingUndo = PendingUndo(
            index = index,
            code = code,
            quote = quote,
            selected = selectedCode == code
        )
        watchlist.removeAt(index)
        persist { watchlistDataSource.remove(code) }
        quotes = quotes.filter { it.requestCode != code }
        if (selectedCode == code) selectedCode = null
        _events.tryEmit(UiEvent.OfferUndoDelete(undoLabel(quote, code)))
        publish()
        if (watchlist.isEmpty()) {
            if (isRunning) stopPolling()
            return
        }
        if (isRunning) refreshQuotesNow()
    }

    fun undoRemove() {
        val deleted = pendingUndo ?: return
        pendingUndo = null
        if (watchlist.contains(deleted.code)) return
        val index = deleted.index.coerceIn(0, watchlist.size)
        watchlist.add(index, deleted.code)
        if (deleted.quote != null) {
            quotes = quotes + deleted.quote
        }
        if (deleted.selected) selectedCode = deleted.code
        persist {
            watchlistDataSource.add(deleted.code)
            watchlistDataSource.reorder(watchlist.toList())
        }
        publish()
        if (isRunning) refreshQuotesNow()
    }

    fun reorder(codes: List<String>) {
        if (sortMode != QuoteSortMode.CUSTOM) return
        watchlist.clear()
        watchlist.addAll(codes)
        persist { watchlistDataSource.reorder(codes) }
        publish()
    }

    fun select(code: String) {
        selectedCode = code
        publish()
    }

    fun setSortMode(mode: QuoteSortMode) {
        sortMode = mode
        sortModeStore.mode = mode
        publish()
    }

    fun toggleRaw() {
        rawExpanded = !rawExpanded
        publish()
    }

    fun saveInterval(intervalText: String) {
        intervalSeconds = QuoteParser.resolveIntervalSeconds(intervalText)
        intervalMs = intervalSeconds * 1000L
        sortModeStore.intervalSeconds = intervalSeconds
        publish()
    }

    fun startPolling(intervalText: String) {
        saveInterval(intervalText)
        if (watchlist.isEmpty()) {
            status = QuoteStatus.EmptyWatchlist
            sortModeStore.monitorRunning = false
            publish()
            return
        }
        isRunning = true
        sortModeStore.monitorRunning = true
        if (TradingSession.isOpen(clock)) {
            status = QuoteStatus.Running
        }
        publish()
        refreshQuotesNow()
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        isRunning = false
        sortModeStore.monitorRunning = false
        status = QuoteStatus.Stopped
        publish()
    }

    private fun refreshQuotesNow() {
        pollJob?.cancel()
        pollJob = scope.launch { fetchOnceThenSchedule() }
    }

    private suspend fun fetchOnceThenSchedule() {
        if (!isRunning) return
        val codes = fetchCodes()
        if (codes.isEmpty()) {
            stopPolling()
            return
        }
        val result = withContext(ioDispatcher) { quoteDataSource.fetchQuotes(codes) }
        if (!isRunning) return
        val keepErrorStatus = result.isFailure
        result.fold(
            onSuccess = { latest ->
                val older = previousQuotes
                val previous = quotes
                val fetchedAtMs = clock.millis()
                val stamped = latest.map { it.copy(fetchedAtMs = fetchedAtMs) }
                shanghaiIndex = stamped.find { it.requestCode == QuoteParser.SHANGHAI_INDEX_CODE } ?: shanghaiIndex
                quotes = stamped.filter {
                    it.requestCode != QuoteParser.SHANGHAI_INDEX_CODE ||
                        watchlist.contains(QuoteParser.SHANGHAI_INDEX_CODE)
                }
                previousQuotes = previous
                lastUpdatedMs = fetchedAtMs
                publish()
                evaluateAlerts(previous, quotes, older)
            },
            onFailure = { error ->
                status = if (error is IllegalStateException) {
                    QuoteStatus.InvalidResponse
                } else {
                    QuoteStatus.NetworkError(error.message.orEmpty())
                }
                publish()
            }
        )
        scheduleNextOrFinish(keepErrorStatus)
    }

    private suspend fun scheduleNextOrFinish(keepErrorStatus: Boolean) {
        if (TradingSession.isOpen(clock)) {
            if (!keepErrorStatus) {
                status = QuoteStatus.Running
                publish()
            }
            delay(intervalMs)
            if (isRunning) {
                fetchOnceThenSchedule()
            }
            return
        }
        isRunning = false
        pollJob = null
        sortModeStore.monitorRunning = false
        if (!keepErrorStatus) {
            val phase = TradingSession.phase(clock)
            status = if (phase == TradingSession.Phase.OPEN) {
                QuoteStatus.Stopped
            } else {
                QuoteStatus.SessionOnce(phase)
            }
        }
        publish()
    }

    private suspend fun evaluateAlerts(
        previous: List<QuoteSnapshot>,
        current: List<QuoteSnapshot>,
        older: List<QuoteSnapshot>
    ) {
        val fires = withContext(ioDispatcher) {
            val rules = alertDataSource.loadRules()
            if (rules.isEmpty()) return@withContext emptyList()
            val result = AlertEvaluator.evaluate(
                rules,
                current,
                previous,
                clock.millis(),
                older,
                intervalSeconds
            )
            alertDataSource.replaceRules(result.updatedRules)
            result.fires
        }
        if (fires.isNotEmpty()) {
            alertNotifier.notifyFired(fires)
        }
    }

    private fun fetchCodes(): List<String> {
        val codes = watchlist.toList()
        if (codes.isEmpty()) return emptyList()
        return if (codes.contains(QuoteParser.SHANGHAI_INDEX_CODE)) {
            codes
        } else {
            codes + QuoteParser.SHANGHAI_INDEX_CODE
        }
    }

    private fun persist(action: () -> Unit) {
        scope.launch { withContext(ioDispatcher) { action() } }
    }

    private fun publish() {
        _uiState.value = buildState()
    }

    private fun buildState(): MainUiState {
        val rows = displayRows()
        if (selectedCode != null && rows.none { it.requestCode == selectedCode }) {
            selectedCode = null
        }
        val selected = selectedCode
        val quote = quotes.find { it.requestCode == selected }
        return MainUiState(
            rows = rows,
            selectedCode = selected,
            selectedCard = quote?.let { QuoteCardMapper.from(it) },
            sortMode = sortMode,
            dragEnabled = sortMode == QuoteSortMode.CUSTOM,
            rawExpanded = rawExpanded,
            isRunning = isRunning,
            watchlistLoaded = watchlistLoaded,
            intervalSeconds = intervalSeconds,
            lastUpdatedMs = lastUpdatedMs,
            shanghaiIndex = shanghaiIndex,
            status = status
        )
    }

    private fun displayRows(): List<QuoteSnapshot> {
        val byCode = quotes.associateBy { it.requestCode }
        val rows = watchlist.map { code ->
            byCode[code] ?: QuoteSnapshot(
                requestCode = code,
                name = "--",
                price = "--",
                changePercent = "",
                fields = emptyList()
            )
        }
        return QuoteSorter.sort(rows, sortMode, watchlist.toList())
    }

    private fun undoLabel(quote: QuoteSnapshot?, code: String): String {
        val name = quote?.name?.trim().orEmpty()
        if (name.isNotEmpty() && name != "--") return name
        return QuoteListAdapter.displayCode(
            quote ?: QuoteSnapshot(code, "", "", "", emptyList())
        )
    }

    private data class PendingUndo(
        val index: Int,
        val code: String,
        val quote: QuoteSnapshot?,
        val selected: Boolean
    )
}
