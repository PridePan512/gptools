package com.example.gptest.data

interface WatchlistDataSource {
    fun loadTabs(): List<WatchlistTab>
    fun addTab(tab: WatchlistTab)
    fun renameTab(id: String, name: String)
    fun deleteTab(id: String)
    fun load(tabId: String): List<String>
    fun add(tabId: String, code: String)
    fun remove(tabId: String, code: String)
    fun reorder(tabId: String, codes: List<String>)
    fun allCodes(): List<String>
}
