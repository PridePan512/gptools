package com.example.gptest.data

import com.example.gptest.business.QuoteSnapshot

interface QuoteDataSource {
    fun fetchQuotes(codes: List<String>): Result<List<QuoteSnapshot>>
}
