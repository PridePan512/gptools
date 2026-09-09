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
import com.example.gptest.data.WatchlistTab
import com.example.gptest.data.WatchlistTabs
import com.example.gptest.ui.alert.AlertEvaluator
import com.example.gptest.ui.alert.AlertNotifier
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertQuickAdd
import com.example.gptest.ui.alert.AlertStockOption
import com.example.gptest.ui.alert.NoOpAlertNotifier
import com.example.gptest.ui.alert.RapidAlertThresholds
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
    private val tabs = mutableListOf<TabState>()
    private var selectedTabId: String = WatchlistTabs.DEFAULT_ID
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
    private var rapidThresholds = sortModeStore.rapidAlertThresholds
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
            val loaded = withContext(ioDispatcher) {
                watchlistDataSource.loadTabs().map { tab ->
                    tab to watchlistDataSource.load(tab.id)
                }
            }
            tabs.clear()
            loaded.forEach { (tab, codes) ->
                tabs.add(TabState.from(tab, codes))
            }
            if (tabs.none { it.id == WatchlistTabs.DEFAULT_ID }) {
                tabs.add(0, TabState.from(WatchlistTabs.defaultTab(), emptyList()))
            }
            selectedTabId = WatchlistTabs.DEFAULT_ID
            watchlistLoaded = true
            publish()
        }
    }

    fun selectTab(tabId: String) {
        if (tabs.none { it.id == tabId }) return
        selectedTabId = tabId
        publish()
    }

    fun addTab(rawName: String) {
        val name = WatchlistTabs.normalizeName(rawName) ?: run {
            _events.tryEmit(UiEvent.TabNameInvalid)
            return
        }
        if (tabs.size >= WatchlistTabs.MAX_TABS) {
            _events.tryEmit(UiEvent.TabLimitReached)
            return
        }
        val tab = WatchlistTab(
            id = WatchlistTabs.newId(),
            name = name,
            locked = false,
            sortOrder = tabs.size
        )
        tabs.add(TabState.from(tab, emptyList()))
        selectedTabId = tab.id
        persist { watchlistDataSource.addTab(tab) }
        _events.tryEmit(UiEvent.TabAdded(tab.id, name))
        publish()
    }

    fun renameTab(tabId: String, rawName: String) {
        val name = WatchlistTabs.normalizeName(rawName) ?: run {
            _events.tryEmit(UiEvent.TabNameInvalid)
            return
        }
        val tab = tabs.find { it.id == tabId } ?: return
        tab.name = name
        persist { watchlistDataSource.renameTab(tabId, name) }
        publish()
    }

    fun deleteTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs[index]
        if (tab.locked) {
            _events.tryEmit(UiEvent.TabDeleteDenied)
            return
        }
        tabs.removeAt(index)
        if (selectedTabId == tabId) {
            selectedTabId = WatchlistTabs.DEFAULT_ID
        }
        persist { watchlistDataSource.deleteTab(tabId) }
        _events.tryEmit(UiEvent.TabRemoved(tab.name))
        if (allCodes().isEmpty() && isRunning) {
            stopPolling()
            return
        }
        publish()
        if (isRunning && TradingSession.isOpen(clock)) refreshQuotesNow()
    }

    fun addCode(raw: String, tabId: String = selectedTabId) {
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
        val tab = tabById(tabId) ?: return
        if (tab.codes.contains(code)) {
            status = QuoteStatus.DuplicateCode
            _events.tryEmit(UiEvent.AddDuplicateCode)
            publish()
            return
        }
        tab.codes.add(code)
        persist { watchlistDataSource.add(tab.id, code) }
        val label = code.removePrefix("sz").removePrefix("sh").removePrefix("bj")
        _events.tryEmit(UiEvent.AddSucceeded(label))
        publish()
        if (isRunning && TradingSession.isOpen(clock)) {
            refreshQuotesNow()
        }
    }

    fun removeCode(code: String, tabId: String = selectedTabId) {
        val tab = tabById(tabId) ?: return
        val index = tab.codes.indexOf(code)
        if (index < 0) return
        val quote = quotes.find { it.requestCode == code }
        pendingUndo = PendingUndo(
            tabId = tab.id,
            index = index,
            code = code,
            quote = quote,
            selected = selectedCode == code
        )
        tab.codes.removeAt(index)
        persist { watchlistDataSource.remove(tab.id, code) }
        if (!allCodes().contains(code)) {
            quotes = quotes.filter { it.requestCode != code }
            if (selectedCode == code) selectedCode = null
        }
        _events.tryEmit(UiEvent.OfferUndoDelete(undoLabel(quote, code)))
        publish()
        if (allCodes().isEmpty()) {
            if (isRunning) stopPolling()
            return
        }
        if (isRunning && TradingSession.isOpen(clock)) refreshQuotesNow()
    }

    fun undoRemove() {
        val deleted = pendingUndo ?: return
        pendingUndo = null
        val tab = tabById(deleted.tabId) ?: return
        if (tab.codes.contains(deleted.code)) return
        val index = deleted.index.coerceIn(0, tab.codes.size)
        tab.codes.add(index, deleted.code)
        if (deleted.quote != null && quotes.none { it.requestCode == deleted.code }) {
            quotes = quotes + deleted.quote
        }
        if (deleted.selected) selectedCode = deleted.code
        persist {
            watchlistDataSource.add(tab.id, deleted.code)
            watchlistDataSource.reorder(tab.id, tab.codes.toList())
        }
        publish()
        if (isRunning && TradingSession.isOpen(clock)) refreshQuotesNow()
    }

    fun reorder(codes: List<String>, tabId: String = selectedTabId) {
        if (sortMode != QuoteSortMode.CUSTOM) return
        val tab = tabById(tabId) ?: return
        tab.codes.clear()
        tab.codes.addAll(codes)
        persist { watchlistDataSource.reorder(tab.id, codes) }
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

    fun saveRapidThresholds(
        volumeSurge: String,
        volumeShrink: String,
        priceSurge: String,
        priceDrop: String
    ) {
        rapidThresholds = RapidAlertThresholds.parse(volumeSurge, volumeShrink, priceSurge, priceDrop)
        sortModeStore.rapidAlertThresholds = rapidThresholds
        publish()
    }

    fun addQuickAlert(code: String, operator: AlertOperator) {
        if (operator.needsValue) return
        if (operator !in AlertQuickAdd.availableOperators(code)) return
        scope.launch {
            val event = withContext(ioDispatcher) { addQuickAlertBlocking(code, operator) }
            _events.tryEmit(event)
        }
    }

    private fun addQuickAlertBlocking(code: String, operator: AlertOperator): UiEvent {
        val stock = stockOption(code)
        val label = "${stock.name}  ${operator.label}"
        val ids = AlertQuickAdd.matchingRuleIds(alertDataSource.loadRules(), code, operator)
        if (ids.isNotEmpty()) {
            ids.forEach { alertDataSource.deleteRule(it) }
            return UiEvent.QuickAlertRemoved(label, operator)
        }
        alertDataSource.saveRule(AlertQuickAdd.createRule(stock, operator))
        return UiEvent.QuickAlertAdded(label, operator)
    }

    private fun stockOption(code: String): AlertStockOption {
        val quote = quotes.find { it.requestCode == code }
        return if (quote != null) AlertStockOption.fromQuote(quote) else AlertStockOption.fromCode(code)
    }

    fun quickAlertOperators(code: String): Set<AlertOperator> {
        return AlertQuickAdd.operatorsFor(alertDataSource.loadRules(), code)
    }

    fun startPolling(intervalText: String) {
        saveInterval(intervalText)
        if (allCodes().isEmpty()) {
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
                        allCodes().contains(QuoteParser.SHANGHAI_INDEX_CODE)
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
        if (!isRunning) return
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
        if (TradingSession.shouldHoldUntilOpen(clock)) {
            val phase = TradingSession.phase(clock)
            if (!keepErrorStatus) {
                status = QuoteStatus.SessionOnce(phase)
                publish()
            }
            val waitMs = TradingSession.millisUntilOpen(clock)?.coerceAtLeast(1L) ?: intervalMs
            delay(waitMs)
            if (!isRunning) return
            if (TradingSession.isOpen(clock)) {
                fetchOnceThenSchedule()
                return
            }
            if (TradingSession.shouldHoldUntilOpen(clock)) {
                scheduleNextOrFinish(keepErrorStatus)
                return
            }
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
                intervalSeconds,
                rapidThresholds
            )
            alertDataSource.replaceRules(result.updatedRules)
            result.fires
        }
        if (fires.isNotEmpty()) {
            alertNotifier.notifyFired(fires)
        }
    }

    private fun fetchCodes(): List<String> {
        val codes = allCodes()
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
        val tabUis = tabs.map { tab ->
            WatchlistTabUi(
                id = tab.id,
                name = tab.name,
                locked = tab.locked,
                rows = displayRows(tab.codes)
            )
        }
        val current = tabUis.find { it.id == selectedTabId } ?: tabUis.firstOrNull()
        val rows = current?.rows.orEmpty()
        if (selectedCode != null && tabUis.none { ui -> ui.rows.any { it.requestCode == selectedCode } }) {
            selectedCode = null
        }
        val selected = selectedCode
        val quote = quotes.find { it.requestCode == selected }
        return MainUiState(
            rows = rows,
            tabs = tabUis,
            selectedTabId = current?.id ?: WatchlistTabs.DEFAULT_ID,
            selectedCode = selected,
            selectedCard = quote?.let { QuoteCardMapper.from(it) },
            sortMode = sortMode,
            dragEnabled = sortMode == QuoteSortMode.CUSTOM,
            rawExpanded = rawExpanded,
            isRunning = isRunning,
            watchlistLoaded = watchlistLoaded,
            intervalSeconds = intervalSeconds,
            rapidAlertThresholds = rapidThresholds,
            lastUpdatedMs = lastUpdatedMs,
            shanghaiIndex = shanghaiIndex,
            status = status
        )
    }

    private fun displayRows(codes: List<String>): List<QuoteSnapshot> {
        val byCode = quotes.associateBy { it.requestCode }
        val rows = codes.map { code ->
            byCode[code] ?: QuoteSnapshot(
                requestCode = code,
                name = "--",
                price = "--",
                changePercent = "",
                fields = emptyList()
            )
        }
        return QuoteSorter.sort(rows, sortMode, codes)
    }

    private fun allCodes(): List<String> {
        val seen = LinkedHashSet<String>()
        tabs.forEach { tab -> tab.codes.forEach { seen.add(it) } }
        return seen.toList()
    }

    private fun tabById(tabId: String): TabState? = tabs.find { it.id == tabId }

    private fun undoLabel(quote: QuoteSnapshot?, code: String): String {
        val name = quote?.name?.trim().orEmpty()
        if (name.isNotEmpty() && name != "--") return name
        return QuoteListAdapter.displayCode(
            quote ?: QuoteSnapshot(code, "", "", "", emptyList())
        )
    }

    private data class PendingUndo(
        val tabId: String,
        val index: Int,
        val code: String,
        val quote: QuoteSnapshot?,
        val selected: Boolean
    )

    private class TabState(
        val id: String,
        var name: String,
        val locked: Boolean,
        val codes: MutableList<String>
    ) {
        companion object {
            fun from(tab: WatchlistTab, codes: List<String>): TabState {
                return TabState(tab.id, tab.name, tab.locked, codes.toMutableList())
            }
        }
    }
}
