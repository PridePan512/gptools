package com.example.gptest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gptest.data.AlertDataSource
import com.example.gptest.data.NoOpAlertDataSource
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource
import com.example.gptest.ui.alert.AlertNotifier
import com.example.gptest.ui.alert.NoOpAlertNotifier

class MainViewModelFactory(
    private val quoteDataSource: QuoteDataSource,
    private val watchlistDataSource: WatchlistDataSource,
    private val sortModeStore: SortModeStore,
    private val alertDataSource: AlertDataSource = NoOpAlertDataSource,
    private val alertNotifier: AlertNotifier = NoOpAlertNotifier
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        return MainViewModel(
            quoteDataSource,
            watchlistDataSource,
            sortModeStore,
            alertDataSource = alertDataSource,
            alertNotifier = alertNotifier
        ) as T
    }
}
