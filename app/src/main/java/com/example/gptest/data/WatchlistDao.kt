package com.example.gptest.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist_tabs ORDER BY sortOrder ASC")
    fun getTabs(): List<WatchlistTabEntity>

    @Query("SELECT * FROM watchlist_tabs WHERE id = :id")
    fun getTab(id: String): WatchlistTabEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTab(tab: WatchlistTabEntity)

    @Query("UPDATE watchlist_tabs SET name = :name WHERE id = :id")
    fun updateTabName(id: String, name: String)

    @Query("DELETE FROM watchlist_tabs WHERE id = :id AND locked = 0")
    fun deleteUnlockedTab(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM watchlist_tabs")
    fun maxTabSortOrder(): Int

    @Query("SELECT code FROM watchlist WHERE tabId = :tabId ORDER BY sortOrder ASC")
    fun getCodes(tabId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(stock: WatchlistStock)

    @Query("DELETE FROM watchlist WHERE tabId = :tabId AND code = :code")
    fun deleteByCode(tabId: String, code: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM watchlist WHERE tabId = :tabId")
    fun maxSortOrder(tabId: String): Int

    @Transaction
    fun replaceOrder(tabId: String, codes: List<String>) {
        codes.forEachIndexed { index, code ->
            insert(WatchlistStock(tabId = tabId, code = code, sortOrder = index))
        }
    }
}
