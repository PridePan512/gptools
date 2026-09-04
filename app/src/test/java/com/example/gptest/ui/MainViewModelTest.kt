package com.example.gptest.ui

import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AlertDataSource
import com.example.gptest.data.NoOpAlertDataSource
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
import com.example.gptest.ui.alert.AlertCondition
import com.example.gptest.ui.alert.AlertFire
import com.example.gptest.ui.alert.AlertMatchMode
import com.example.gptest.ui.alert.AlertMetric
import com.example.gptest.ui.alert.AlertNotifier
import com.example.gptest.ui.alert.AlertNotifyMode
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertRule
import com.example.gptest.ui.alert.AlertRuleStatus
import com.example.gptest.ui.alert.AlertStockOption
import com.example.gptest.ui.alert.NoOpAlertNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidCode_setsStatus_doesNotAddRow() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()
        vm.addCode("abc")
        advanceUntilIdle()
        job.cancel()
        assertEquals(QuoteStatus.InvalidCode, vm.uiState.value.status)
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(listOf(UiEvent.AddInvalidCode), events)
    }

    @Test
    fun emptyCode_emitsEmptyEvent_doesNotAddRow() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()
        vm.addCode("  ")
        advanceUntilIdle()
        job.cancel()
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(listOf(UiEvent.AddEmptyCode), events)
    }

    @Test
    fun duplicateCode_setsStatus_doesNotAddSecondRow() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addCode("000001")
        advanceUntilIdle()
        vm.addCode("sz000001")
        advanceUntilIdle()
        assertEquals(QuoteStatus.DuplicateCode, vm.uiState.value.status)
        assertEquals(1, vm.uiState.value.rows.size)
    }

    @Test
    fun addCode_addsPlaceholder_andEmitsClearInput() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()
        vm.addCode("000001")
        advanceUntilIdle()
        job.cancel()
        val row = vm.uiState.value.rows.single()
        assertEquals("sz000001", row.requestCode)
        assertEquals("--", row.name)
        assertEquals("--", row.price)
        assertEquals(listOf(UiEvent.AddSucceeded("000001")), events)
        assertEquals(QuoteStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun setSortMode_updatesDragEnabled_andRowOrder() = runTest(dispatcher) {
        val quotes = FakeQuotes(
            Result.success(
                listOf(
                    snapshot("sz000001", price = "10.00", change = "1.00"),
                    snapshot("sz000002", price = "20.00", change = "2.00")
                )
            )
        )
        val vm = viewModel(
            quotes = quotes,
            watchlist = FakeWatchlist(mutableListOf("sz000001", "sz000002")),
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals(listOf("sz000001", "sz000002"), vm.uiState.value.rows.map { it.requestCode })
        assertTrue(vm.uiState.value.dragEnabled)
        vm.setSortMode(QuoteSortMode.PRICE)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.dragEnabled)
        assertEquals(listOf("sz000002", "sz000001"), vm.uiState.value.rows.map { it.requestCode })
    }

    @Test
    fun startPolling_emptyWatchlist_setsEmptyStatus() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals(QuoteStatus.EmptyWatchlist, vm.uiState.value.status)
        assertFalse(vm.uiState.value.isRunning)
    }

    @Test
    fun fetchSuccess_updatesRows() = runTest(dispatcher) {
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "11.00", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals("11.00", vm.uiState.value.rows.single().price)
        assertFalse(vm.uiState.value.isRunning)
        assertEquals(QuoteStatus.SessionOnce(TradingSession.Phase.CLOSED), vm.uiState.value.status)
        assertEquals(closedClock().millis(), vm.uiState.value.lastUpdatedMs)
    }

    @Test
    fun fetchIllegalState_setsInvalidResponse() = runTest(dispatcher) {
        val vm = viewModel(
            quotes = FakeQuotes(Result.failure(IllegalStateException("invalid response"))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals(QuoteStatus.InvalidResponse, vm.uiState.value.status)
        assertFalse(vm.uiState.value.isRunning)
        assertEquals(null, vm.uiState.value.lastUpdatedMs)
    }

    @Test
    fun savedInterval_isExposedInUiState() = runTest(dispatcher) {
        val vm = viewModel(sortStore = FakeSortStore(intervalSeconds = 12))
        advanceUntilIdle()
        assertEquals(12L, vm.uiState.value.intervalSeconds)
    }

    @Test
    fun startPolling_persistsResolvedInterval() = runTest(dispatcher) {
        val store = FakeSortStore()
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "11.00", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            sortStore = store,
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("8")
        advanceUntilIdle()
        assertEquals(8L, store.intervalSeconds)
        assertEquals(8L, vm.uiState.value.intervalSeconds)
    }

    @Test
    fun startPolling_invalidInterval_persistsDefaultFive() = runTest(dispatcher) {
        val store = FakeSortStore()
        val vm = viewModel(sortStore = store)
        advanceUntilIdle()
        vm.startPolling("0")
        advanceUntilIdle()
        assertEquals(5L, store.intervalSeconds)
        assertEquals(5L, vm.uiState.value.intervalSeconds)
    }

    @Test
    fun saveInterval_persistsWithoutStarting() = runTest(dispatcher) {
        val store = FakeSortStore()
        val vm = viewModel(sortStore = store)
        advanceUntilIdle()
        vm.saveInterval("15")
        advanceUntilIdle()
        assertEquals(15L, store.intervalSeconds)
        assertEquals(15L, vm.uiState.value.intervalSeconds)
        assertFalse(vm.uiState.value.isRunning)
    }

    @Test
    fun removeCode_emitsUndoEvent_andDropsRow() = runTest(dispatcher) {
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "11.00", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001", "sz000002")),
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()
        vm.removeCode("sz000001")
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf("sz000002"), vm.uiState.value.rows.map { it.requestCode })
        assertTrue(events.any { it is UiEvent.OfferUndoDelete })
    }

    @Test
    fun fetchSuccess_evaluatesAlerts_andNotifies() = runTest(dispatcher) {
        val rule = AlertRule(
            id = "r1",
            name = "破21",
            enabled = true,
            matchMode = AlertMatchMode.ALL,
            notifyMode = AlertNotifyMode.ALWAYS,
            conditions = listOf(
                AlertCondition(
                    id = "c1",
                    stock = AlertStockOption("sz000001", "测试"),
                    metric = AlertMetric.PRICE,
                    operator = AlertOperator.GTE,
                    numberValue = "21"
                )
            ),
            status = AlertRuleStatus.WAITING
        )
        val alerts = FakeAlerts(mutableListOf(rule))
        val notifier = RecordingNotifier()
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "21.50", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            clock = closedClock(),
            alerts = alerts,
            notifier = notifier
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals(1, notifier.fires.size)
        assertEquals("破21", notifier.fires.single().rule.name)
        val saved = alerts.loadRules().single()
        assertEquals(AlertRuleStatus.FIRED, saved.status)
        assertTrue(saved.lastTriggeredMs != null)
    }

    @Test
    fun undoRemove_restoresCodeAtOriginalIndex() = runTest(dispatcher) {
        val store = FakeWatchlist(mutableListOf("sz000001", "sz000002", "sz000003"))
        val vm = viewModel(
            quotes = FakeQuotes(
                Result.success(
                    listOf(
                        snapshot("sz000001", "11.00", "1.50"),
                        snapshot("sz000002", "12.00", "2.00"),
                        snapshot("sz000003", "13.00", "3.00")
                    )
                )
            ),
            watchlist = store,
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        vm.removeCode("sz000002")
        advanceUntilIdle()
        vm.undoRemove()
        advanceUntilIdle()
        assertEquals(
            listOf("sz000001", "sz000002", "sz000003"),
            vm.uiState.value.rows.map { it.requestCode }
        )
        assertEquals(listOf("sz000001", "sz000002", "sz000003"), store.load())
        assertEquals("12.00", vm.uiState.value.rows[1].price)
    }

    @Test
    fun startPolling_withWatchlist_startsMonitorService() = runTest(dispatcher) {
        val gateway = RecordingGateway()
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "11.00", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            clock = closedClock(),
            serviceGateway = gateway
        )
        advanceUntilIdle()
        vm.startPolling("5")
        assertEquals(1, gateway.starts)
        assertTrue(vm.uiState.value.isRunning)
        vm.stopPolling()
        assertEquals(1, gateway.stops)
        assertFalse(vm.uiState.value.isRunning)
    }

    @Test
    fun startPolling_emptyWatchlist_doesNotStartMonitorService() = runTest(dispatcher) {
        val gateway = RecordingGateway()
        val vm = viewModel(serviceGateway = gateway)
        advanceUntilIdle()
        vm.startPolling("5")
        advanceUntilIdle()
        assertEquals(0, gateway.starts)
        assertEquals(0, gateway.stops)
    }

    @Test
    fun closedSession_clearsMonitorRunningFlag() = runTest(dispatcher) {
        val store = FakeSortStore()
        val vm = viewModel(
            quotes = FakeQuotes(Result.success(listOf(snapshot("sz000001", "11.00", "1.50")))),
            watchlist = FakeWatchlist(mutableListOf("sz000001")),
            sortStore = store,
            clock = closedClock()
        )
        advanceUntilIdle()
        vm.startPolling("5")
        assertTrue(store.monitorRunning)
        advanceUntilIdle()
        assertFalse(store.monitorRunning)
        assertFalse(vm.uiState.value.isRunning)
    }

    private fun viewModel(
        quotes: QuoteDataSource = FakeQuotes(Result.success(emptyList())),
        watchlist: WatchlistDataSource = FakeWatchlist(),
        sortStore: SortModeStore = FakeSortStore(),
        clock: Clock = closedClock(),
        alerts: AlertDataSource = NoOpAlertDataSource,
        notifier: AlertNotifier = NoOpAlertNotifier,
        serviceGateway: MonitorServiceGateway = NoOpMonitorServiceGateway
    ): MainViewModel {
        return MainViewModel(
            quotes,
            watchlist,
            sortStore,
            clock,
            dispatcher,
            alerts,
            notifier,
            serviceGateway
        )
    }

    private fun closedClock(): Clock {
        val instant = ZonedDateTime.of(
            LocalDate.of(2026, 9, 6),
            LocalTime.of(10, 0),
            TradingSession.SHANGHAI
        ).toInstant()
        return Clock.fixed(instant, TradingSession.SHANGHAI)
    }

    private fun snapshot(code: String, price: String, change: String): QuoteSnapshot {
        val fields = MutableList(40) { "" }
        fields[2] = code.takeLast(6)
        fields[3] = price
        fields[32] = change
        return QuoteSnapshot(code, "测试", price, change, fields)
    }

    private class FakeQuotes(private val result: Result<List<QuoteSnapshot>>) : QuoteDataSource {
        override fun fetchQuotes(codes: List<String>) = result
    }

    private class FakeWatchlist(
        private val codes: MutableList<String> = mutableListOf()
    ) : WatchlistDataSource {
        override fun load(): List<String> = codes.toList()
        override fun add(code: String) { codes.add(code) }
        override fun remove(code: String) { codes.remove(code) }
        override fun reorder(codes: List<String>) {
            this.codes.clear()
            this.codes.addAll(codes)
        }
    }

    private class FakeSortStore(
        override var mode: QuoteSortMode = QuoteSortMode.CUSTOM,
        override var intervalSeconds: Long = 5L,
        override var monitorRunning: Boolean = false
    ) : SortModeStore

    private class RecordingGateway : MonitorServiceGateway {
        var starts = 0
        var stops = 0
        override fun start() {
            starts += 1
        }
        override fun stop() {
            stops += 1
        }
    }

    private class FakeAlerts(
        private val rules: MutableList<AlertRule>
    ) : AlertDataSource {
        override fun loadRules(): List<AlertRule> = rules.toList()
        override fun get(id: String): AlertRule? = rules.find { it.id == id }
        override fun saveRule(rule: AlertRule) {
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index >= 0) rules[index] = rule else rules.add(rule)
        }
        override fun deleteRule(id: String) {
            rules.removeAll { it.id == id }
        }
        override fun replaceRules(rules: List<AlertRule>) {
            rules.forEach { saveRule(it) }
        }
        override fun setEnabled(id: String, enabled: Boolean) {
            val rule = get(id) ?: return
            saveRule(
                rule.copy(
                    enabled = enabled,
                    status = if (enabled) AlertRuleStatus.WAITING else AlertRuleStatus.OFF,
                    onceConsumed = if (enabled) false else rule.onceConsumed
                )
            )
        }
    }

    private class RecordingNotifier : AlertNotifier {
        val fires = mutableListOf<AlertFire>()
        override fun notifyFired(fires: List<AlertFire>) {
            this.fires += fires
        }
    }
}
