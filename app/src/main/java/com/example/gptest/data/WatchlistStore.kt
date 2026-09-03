package com.example.gptest.data

class WatchlistStore(private val dao: WatchlistDao) {
    fun load(): List<String> = dao.getAllCodes()

    fun add(code: String) {
        dao.insert(WatchlistStock(code = code, sortOrder = dao.maxSortOrder() + 1))
    }

    fun remove(code: String) {
        dao.deleteByCode(code)
    }

    fun reorder(codes: List<String>) {
        dao.replaceOrder(codes)
    }
}
