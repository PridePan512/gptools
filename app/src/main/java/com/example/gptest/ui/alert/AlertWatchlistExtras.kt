package com.example.gptest.ui.alert

import android.content.Intent
import com.example.gptest.business.QuoteSnapshot

object AlertWatchlistExtras {
    const val EXTRA_CODES = "alert_watchlist_codes"
    const val EXTRA_NAMES = "alert_watchlist_names"

    fun fromQuotes(rows: List<QuoteSnapshot>): List<AlertStockOption> {
        return rows
            .distinctBy { it.requestCode }
            .map { AlertStockOption.fromQuote(it) }
    }

    fun put(intent: Intent, stocks: List<AlertStockOption>): Intent {
        intent.putStringArrayListExtra(EXTRA_CODES, ArrayList(stocks.map { it.code }))
        intent.putStringArrayListExtra(EXTRA_NAMES, ArrayList(stocks.map { it.name }))
        return intent
    }

    fun get(intent: Intent): List<AlertStockOption> {
        val codes = intent.getStringArrayListExtra(EXTRA_CODES).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        return codes.mapIndexed { index, code ->
            AlertStockOption.fromCode(code, names.getOrNull(index))
        }
    }

    fun merge(codes: List<String>, named: List<AlertStockOption>): List<AlertStockOption> {
        val byCode = named.associateBy { it.code }
        return codes.map { code ->
            byCode[code] ?: AlertStockOption.fromCode(code)
        }
    }
}
