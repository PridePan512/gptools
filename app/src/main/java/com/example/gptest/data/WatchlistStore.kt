package com.example.gptest.data

class WatchlistStore(private val dao: WatchlistDao) : WatchlistDataSource {

    override fun loadTabs(): List<WatchlistTab> {
        ensureDefaultTab()
        return dao.getTabs().map { it.toTab() }
    }

    override fun addTab(tab: WatchlistTab) {
        ensureDefaultTab()
        if (dao.getTab(tab.id) != null) return
        if (dao.getTabs().size >= WatchlistTabs.MAX_TABS) return
        val name = WatchlistTabs.normalizeName(tab.name) ?: return
        val sortOrder = if (tab.id == WatchlistTabs.DEFAULT_ID) {
            tab.sortOrder
        } else {
            dao.maxTabSortOrder() + 1
        }
        dao.insertTab(
            WatchlistTabEntity(
                id = tab.id,
                name = name,
                sortOrder = sortOrder,
                locked = tab.locked
            )
        )
    }

    override fun renameTab(id: String, name: String) {
        val trimmed = WatchlistTabs.normalizeName(name) ?: return
        if (dao.getTab(id) == null) return
        dao.updateTabName(id, trimmed)
    }

    override fun deleteTab(id: String) {
        dao.deleteUnlockedTab(id)
    }

    override fun load(tabId: String): List<String> {
        ensureDefaultTab()
        return dao.getCodes(tabId)
    }

    override fun add(tabId: String, code: String) {
        ensureDefaultTab()
        dao.insert(
            WatchlistStock(
                tabId = tabId,
                code = code,
                sortOrder = dao.maxSortOrder(tabId) + 1
            )
        )
    }

    override fun remove(tabId: String, code: String) {
        dao.deleteByCode(tabId, code)
    }

    override fun reorder(tabId: String, codes: List<String>) {
        dao.replaceOrder(tabId, codes)
    }

    override fun allCodes(): List<String> {
        val seen = LinkedHashSet<String>()
        loadTabs().forEach { tab ->
            load(tab.id).forEach { seen.add(it) }
        }
        return seen.toList()
    }

    private fun ensureDefaultTab() {
        if (dao.getTab(WatchlistTabs.DEFAULT_ID) != null) return
        val tab = WatchlistTabs.defaultTab()
        dao.insertTab(
            WatchlistTabEntity(
                id = tab.id,
                name = tab.name,
                sortOrder = tab.sortOrder,
                locked = tab.locked
            )
        )
    }

    private fun WatchlistTabEntity.toTab(): WatchlistTab {
        return WatchlistTab(id = id, name = name, locked = locked, sortOrder = sortOrder)
    }
}
