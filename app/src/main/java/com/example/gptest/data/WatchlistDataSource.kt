package com.example.gptest.data

interface WatchlistDataSource {
    fun load(): List<String>
    fun add(code: String)
    fun remove(code: String)
    fun reorder(codes: List<String>)
}
