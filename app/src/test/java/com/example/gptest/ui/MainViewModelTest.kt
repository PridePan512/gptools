package com.example.gptest.ui

import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.TradingSession
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
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
        vm.addCode("abc")
        advanceUntilIdle()
        assertEquals(QuoteStatus.InvalidCode, vm.uiState.value.status)
        assertTrue(vm.uiState.value.rows.isEmpty())
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
        assertEquals(listOf(UiEvent.ClearCodeInput), events)
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
    }

    private fun viewModel(
        quotes: QuoteDataSource = FakeQuotes(Result.success(emptyList())),
        watchlist: WatchlistDataSource = FakeWatchlist(),
        sortStore: SortModeStore = FakeSortStore(),
        clock: Clock = closedClock()
    ): MainViewModel {
        return MainViewModel(quotes, watchlist, sortStore, clock, dispatcher)
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

    private class FakeSortStore : SortModeStore {
        override var mode: QuoteSortMode = QuoteSortMode.CUSTOM
    }
}
