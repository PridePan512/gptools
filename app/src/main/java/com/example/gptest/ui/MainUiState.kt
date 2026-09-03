package com.example.gptest.ui

import com.example.gptest.business.QuoteCard
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.TradingSession

sealed interface QuoteStatus {
    data object Idle : QuoteStatus
    data object Running : QuoteStatus
    data object Stopped : QuoteStatus
    data object InvalidCode : QuoteStatus
    data object DuplicateCode : QuoteStatus
    data object EmptyWatchlist : QuoteStatus
    data object InvalidResponse : QuoteStatus
    data class NetworkError(val message: String) : QuoteStatus
    data class SessionOnce(val phase: TradingSession.Phase) : QuoteStatus
}

sealed interface UiEvent {
    data object ClearCodeInput : UiEvent
}

data class MainUiState(
    val rows: List<QuoteSnapshot> = emptyList(),
    val selectedCode: String? = null,
    val selectedCard: QuoteCard? = null,
    val sortMode: QuoteSortMode = QuoteSortMode.CUSTOM,
    val dragEnabled: Boolean = true,
    val rawExpanded: Boolean = false,
    val isRunning: Boolean = false,
    val watchlistLoaded: Boolean = false,
    val status: QuoteStatus = QuoteStatus.Idle
)
