package com.example.gptest.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "watchlist",
    primaryKeys = ["tabId", "code"],
    foreignKeys = [
        ForeignKey(
            entity = WatchlistTabEntity::class,
            parentColumns = ["id"],
            childColumns = ["tabId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tabId")]
)
data class WatchlistStock(
    val tabId: String,
    val code: String,
    val sortOrder: Int
)
