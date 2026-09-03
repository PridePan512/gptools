package com.example.gptest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gptest.data.QuoteDataSource
import com.example.gptest.data.SortModeStore
import com.example.gptest.data.WatchlistDataSource

class MainViewModelFactory(
    private val quoteDataSource: QuoteDataSource,
    private val watchlistDataSource: WatchlistDataSource,
    private val sortModeStore: SortModeStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        return MainViewModel(quoteDataSource, watchlistDataSource, sortModeStore) as T
    }
}
