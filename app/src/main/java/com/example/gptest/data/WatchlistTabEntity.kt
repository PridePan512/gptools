package com.example.gptest.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist_tabs")
data class WatchlistTabEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val locked: Boolean
)
