package com.example.gptest.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistStock(
    @PrimaryKey val code: String,
    val sortOrder: Int
)
