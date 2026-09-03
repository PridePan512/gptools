package com.example.gptest.data

import android.content.Context
import com.example.gptest.business.QuoteSortMode

class SortPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: QuoteSortMode
        get() {
            val stored = prefs.getString(KEY_MODE, QuoteSortMode.CUSTOM.name)
            return QuoteSortMode.entries.find { it.name == stored } ?: QuoteSortMode.CUSTOM
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    companion object {
        private const val PREFS_NAME = "gptest_prefs"
        private const val KEY_MODE = "quote_sort_mode"
    }
}
