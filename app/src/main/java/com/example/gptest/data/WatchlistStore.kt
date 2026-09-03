package com.example.gptest.data

class WatchlistStore(private val dao: WatchlistDao) : WatchlistDataSource {
    override fun load(): List<String> = dao.getAllCodes()

    override fun add(code: String) {
        dao.insert(WatchlistStock(code = code, sortOrder = dao.maxSortOrder() + 1))
    }

    override fun remove(code: String) {
        dao.deleteByCode(code)
    }

    override fun reorder(codes: List<String>) {
        dao.replaceOrder(codes)
    }
}
