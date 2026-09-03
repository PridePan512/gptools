package com.example.gptest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.QuoteSorter
import com.example.gptest.business.TradingSession
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class MainViewModel(
    private val quoteDataSource: QuoteDataSource,
    private val watchlistDataSource: WatchlistDataSource,
    private val sortModeStore: SortModeStore,
    private val clock: Clock = Clock.system(TradingSession.SHANGHAI),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val watchlist = mutableListOf<String>()
    private var quotes: List<QuoteSnapshot> = emptyList()
    private var selectedCode: String? = null
    private var sortMode: QuoteSortMode = sortModeStore.mode
    private var rawExpanded = false
    private var isRunning = false
    private var watchlistLoaded = false
    private var status: QuoteStatus = QuoteStatus.Idle
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var pollJob: Job? = null

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val codes = withContext(ioDispatcher) { watchlistDataSource.load() }
            watchlist.clear()
            watchlist.addAll(codes)
            watchlistLoaded = true
            publish()
        }
    }

    fun addCode(raw: String) {
        val code = QuoteParser.normalizeStockCode(raw) ?: run {
            status = QuoteStatus.InvalidCode
            publish()
            return
        }
        if (watchlist.contains(code)) {
            status = QuoteStatus.DuplicateCode
            publish()
            return
        }
        watchlist.add(code)
        persist { watchlistDataSource.add(code) }
        _events.tryEmit(UiEvent.ClearCodeInput)
        if (isRunning) {
            publish()
            refreshQuotesNow()
        } else {
            status = QuoteStatus.Idle
            publish()
        }
    }

    fun removeCode(code: String) {
        watchlist.remove(code)
        persist { watchlistDataSource.remove(code) }
        quotes = quotes.filter { it.requestCode != code }
        if (selectedCode == code) selectedCode = null
        publish()
        if (watchlist.isEmpty()) {
            if (isRunning) stopPolling()
            return
        }
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

    fun startPolling(intervalText: String) {
        if (watchlist.isEmpty()) {
            status = QuoteStatus.EmptyWatchlist
            publish()
            return
        }
        intervalMs = QuoteParser.resolveIntervalSeconds(intervalText) * 1000L
        isRunning = true
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
        status = QuoteStatus.Stopped
        publish()
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private fun refreshQuotesNow() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch { fetchOnceThenSchedule() }
    }

    private suspend fun fetchOnceThenSchedule() {
        if (!isRunning) return
        val codes = watchlist.toList()
        if (codes.isEmpty()) {
            stopPolling()
            return
        }
        val result = withContext(ioDispatcher) { quoteDataSource.fetchQuotes(codes) }
        if (!isRunning) return
        val keepErrorStatus = result.isFailure
        result.fold(
            onSuccess = { latest ->
                quotes = latest
                publish()
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

    private fun persist(action: () -> Unit) {
        viewModelScope.launch { withContext(ioDispatcher) { action() } }
    }

    private fun publish() {
        _uiState.value = buildState()
    }

    private fun buildState(): MainUiState {
        val rows = displayRows()
        if (selectedCode != null && rows.none { it.requestCode == selectedCode }) {
            selectedCode = null
        }
        if (selectedCode == null && quotes.isNotEmpty()) {
            selectedCode = quotes.first().requestCode
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

    companion object {
        private const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
