package com.example.gptest.data

import java.util.UUID

data class WatchlistTab(
    val id: String,
    val name: String,
    val locked: Boolean,
    val sortOrder: Int
)

object WatchlistTabs {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "自选股"
    const val MAX_TABS = 20

    fun defaultTab(): WatchlistTab = WatchlistTab(
        id = DEFAULT_ID,
        name = DEFAULT_NAME,
        locked = true,
        sortOrder = 0
    )

    fun normalizeName(name: String): String? = name.trim().takeIf { it.isNotEmpty() }

    fun newId(): String = UUID.randomUUID().toString()
}
