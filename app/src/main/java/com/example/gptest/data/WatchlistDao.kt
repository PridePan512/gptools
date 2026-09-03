package com.example.gptest.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WatchlistDao {
    @Query("SELECT code FROM watchlist ORDER BY sortOrder ASC")
    fun getAllCodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(stock: WatchlistStock)

    @Query("DELETE FROM watchlist WHERE code = :code")
    fun deleteByCode(code: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM watchlist")
    fun maxSortOrder(): Int

    @Transaction
    fun replaceOrder(codes: List<String>) {
        codes.forEachIndexed { index, code ->
            insert(WatchlistStock(code = code, sortOrder = index))
        }
    }
}
