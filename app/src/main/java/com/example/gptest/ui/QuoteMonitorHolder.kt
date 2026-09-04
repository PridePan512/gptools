package com.example.gptest.ui

import android.content.Context
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AlertStore
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.QuoteRepository
import com.example.gptest.data.SortPreferences
import com.example.gptest.data.WatchlistStore
import com.example.gptest.ui.alert.AndroidAlertNotifier
import kotlinx.coroutines.Dispatchers
import java.time.Clock

object QuoteMonitorHolder {
    @Volatile
    private var instance: QuoteMonitor? = null

    fun get(context: Context): QuoteMonitor {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun peek(): QuoteMonitor? = instance

    internal fun reset() {
        instance = null
    }

    private fun create(app: Context): QuoteMonitor {
        val database = AppDatabase.get(app)
        return QuoteMonitor(
            quoteDataSource = QuoteRepository(),
            watchlistDataSource = WatchlistStore(database.watchlistDao()),
            sortModeStore = SortPreferences(app),
            clock = Clock.system(TradingSession.SHANGHAI),
            ioDispatcher = Dispatchers.IO,
            alertDataSource = AlertStore(database.alertDao()),
            alertNotifier = AndroidAlertNotifier(app)
        )
    }
}
