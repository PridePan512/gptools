package com.example.gptest.ui

import androidx.lifecycle.ViewModel
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AlertDataSource
import com.example.gptest.data.NoOpAlertDataSource
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
import com.example.gptest.ui.alert.AlertNotifier
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.NoOpAlertNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock

class MainViewModel(
    private val monitor: QuoteMonitor,
    private val serviceGateway: MonitorServiceGateway = NoOpMonitorServiceGateway
) : ViewModel() {

    constructor(
        quoteDataSource: QuoteDataSource,
        watchlistDataSource: WatchlistDataSource,
        sortModeStore: SortModeStore,
        clock: Clock = Clock.system(TradingSession.SHANGHAI),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        alertDataSource: AlertDataSource = NoOpAlertDataSource,
        alertNotifier: AlertNotifier = NoOpAlertNotifier,
        serviceGateway: MonitorServiceGateway = NoOpMonitorServiceGateway
    ) : this(
        QuoteMonitor(
            quoteDataSource,
            watchlistDataSource,
            sortModeStore,
            clock,
            ioDispatcher,
            alertDataSource,
            alertNotifier
        ),
        serviceGateway
    )

    val uiState: StateFlow<MainUiState> = monitor.uiState
    val events: SharedFlow<UiEvent> = monitor.events

    fun addCode(raw: String) = monitor.addCode(raw)
    fun removeCode(code: String) = monitor.removeCode(code)
    fun undoRemove() = monitor.undoRemove()
    fun reorder(codes: List<String>) = monitor.reorder(codes)
    fun select(code: String) = monitor.select(code)
    fun setSortMode(mode: QuoteSortMode) = monitor.setSortMode(mode)
    fun toggleRaw() = monitor.toggleRaw()
    fun saveInterval(intervalText: String) = monitor.saveInterval(intervalText)
    fun saveRapidThresholds(
        volumeSurge: String,
        volumeShrink: String,
        priceSurge: String,
        priceDrop: String
    ) = monitor.saveRapidThresholds(volumeSurge, volumeShrink, priceSurge, priceDrop)

    fun addQuickAlert(code: String, operator: AlertOperator) = monitor.addQuickAlert(code, operator)

    fun startPolling(intervalText: String) {
        monitor.startPolling(intervalText)
        if (monitor.uiState.value.isRunning) {
            serviceGateway.start()
        }
    }

    fun stopPolling() {
        monitor.stopPolling()
        serviceGateway.stop()
    }
}
