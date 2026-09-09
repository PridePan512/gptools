package com.example.gptest.data

class InMemoryWatchlistStore(
    initialCodes: MutableList<String> = mutableListOf()
) : WatchlistDataSource {
    private val tabs = mutableListOf(WatchlistTabs.defaultTab())
    private val codesByTab = mutableMapOf(WatchlistTabs.DEFAULT_ID to initialCodes)

    override fun loadTabs(): List<WatchlistTab> = tabs.sortedBy { it.sortOrder }

    override fun addTab(tab: WatchlistTab) {
        if (tabs.any { it.id == tab.id }) return
        if (tabs.size >= WatchlistTabs.MAX_TABS) return
        tabs.add(tab)
        codesByTab.putIfAbsent(tab.id, mutableListOf())
    }

    override fun renameTab(id: String, name: String) {
        val trimmed = WatchlistTabs.normalizeName(name) ?: return
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index] = tabs[index].copy(name = trimmed)
    }

    override fun deleteTab(id: String) {
        val tab = tabs.find { it.id == id } ?: return
        if (tab.locked) return
        tabs.removeAll { it.id == id }
        codesByTab.remove(id)
    }

    override fun load(tabId: String): List<String> = codesByTab[tabId]?.toList().orEmpty()

    override fun add(tabId: String, code: String) {
        val list = codesByTab.getOrPut(tabId) { mutableListOf() }
        if (!list.contains(code)) list.add(code)
    }

    override fun remove(tabId: String, code: String) {
        codesByTab[tabId]?.remove(code)
    }

    override fun reorder(tabId: String, codes: List<String>) {
        codesByTab[tabId] = codes.toMutableList()
    }

    override fun allCodes(): List<String> {
        val seen = LinkedHashSet<String>()
        loadTabs().forEach { tab ->
            load(tab.id).forEach { seen.add(it) }
        }
        return seen.toList()
    }
}
