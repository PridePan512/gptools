package com.example.gptest.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class QuotePagerAdapter(
    activity: FragmentActivity
) : FragmentStateAdapter(activity) {
    private var tabs: List<WatchlistTabUi> = emptyList()
    private val stableIds = mutableMapOf<String, Long>()
    private var nextStableId = 1L

    fun submit(tabs: List<WatchlistTabUi>): Boolean {
        val oldIds = this.tabs.map { it.id }
        val newIds = tabs.map { it.id }
        this.tabs = tabs
        val liveIds = newIds.toSet()
        stableIds.keys.retainAll(liveIds)
        tabs.forEach { tab ->
            stableIds.getOrPut(tab.id) { nextStableId++ }
        }
        val idsChanged = oldIds != newIds
        if (idsChanged) {
            notifyDataSetChanged()
        }
        return idsChanged
    }

    fun titleAt(position: Int): String = tabs.getOrNull(position)?.name.orEmpty()

    fun idAt(position: Int): String = tabs.getOrNull(position)?.id.orEmpty()

    fun indexOf(tabId: String): Int = tabs.indexOfFirst { it.id == tabId }

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return QuoteTabFragment.newInstance(tabs[position].id)
    }

    override fun getItemId(position: Int): Long {
        val id = tabs.getOrNull(position)?.id ?: return -1L
        return stableIds.getOrPut(id) { nextStableId++ }
    }

    override fun containsItem(itemId: Long): Boolean = stableIds.values.contains(itemId)
}
