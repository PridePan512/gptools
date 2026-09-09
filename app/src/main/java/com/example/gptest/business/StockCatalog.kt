package com.example.gptest.business

data class StockCatalogEntry(
    val code: String,
    val name: String
) {
    val displayLabel: String
        get() = "$name  ${code.drop(2)}"

    val isIndex: Boolean
        get() = StockCatalog.isIndex(code)

    val isEtf: Boolean
        get() = StockCatalog.isEtf(code, name)
}

class StockCatalog(private val entries: List<StockCatalogEntry>) {

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<StockCatalogEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val needleLower = needle.lowercase()
        val needleCompact = compact(needleLower)
        val needleDigits = stripMarketPrefix(needleLower)
        return entries.mapNotNull { entry ->
            val rank = rank(entry, needleLower, needleCompact, needleDigits) ?: return@mapNotNull null
            rank to entry
        }.sortedWith(
            compareBy(
                { it.first },
                { kindRank(it.second) },
                { it.second.code }
            )
        )
            .take(limit)
            .map { it.second }
    }

    fun resolveUnique(query: String): String? {
        val hits = search(query, limit = DEFAULT_LIMIT)
        val needle = compact(query.trim().lowercase())
        val exact = hits.filter { compact(it.name.lowercase()) == needle }
        if (exact.size == 1) return exact.single().code
        return hits.singleOrNull()?.code
    }

    fun resolveAddQuery(raw: String): String? {
        val query = raw.trim()
        if (query.isEmpty()) return null
        return QuoteParser.normalizeStockCode(query) ?: resolveUnique(query)
    }

    private fun rank(
        entry: StockCatalogEntry,
        needleLower: String,
        needleCompact: String,
        needleDigits: String
    ): Int? {
        val nameCompact = compact(entry.name.lowercase())
        val codeDigits = entry.code.drop(2)
        return when {
            nameCompact == needleCompact -> RANK_EXACT_NAME
            nameCompact.startsWith(needleCompact) -> RANK_NAME_PREFIX
            nameCompact.contains(needleCompact) -> RANK_NAME_CONTAINS
            entry.code.startsWith(needleLower) || codeDigits.startsWith(needleDigits) -> RANK_CODE_PREFIX
            entry.code.contains(needleLower) || codeDigits.contains(needleDigits) -> RANK_CODE_CONTAINS
            else -> null
        }
    }

    private fun kindRank(entry: StockCatalogEntry): Int = when {
        entry.isIndex -> 0
        entry.isEtf -> 2
        else -> 1
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        private const val RANK_EXACT_NAME = 0
        private const val RANK_NAME_PREFIX = 1
        private const val RANK_NAME_CONTAINS = 2
        private const val RANK_CODE_PREFIX = 3
        private const val RANK_CODE_CONTAINS = 4

        fun parse(text: String): StockCatalog {
            val parsed = text.lineSequence().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val sep = trimmed.indexOf('|')
                if (sep <= 0) return@mapNotNull null
                val code = QuoteParser.normalizeStockCode(trimmed.substring(0, sep)) ?: return@mapNotNull null
                val name = trimmed.substring(sep + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                StockCatalogEntry(code, name)
            }.toList()
            return StockCatalog(parsed)
        }

        fun isIndex(code: String): Boolean {
            val normalized = code.lowercase()
            return normalized.startsWith("sh000") ||
                normalized.startsWith("sz399") ||
                normalized.startsWith("bj899")
        }

        fun isEtf(code: String, name: String): Boolean {
            if (name.contains("ETF", ignoreCase = true)) return true
            val digits = code.drop(2)
            return when {
                code.startsWith("sh") &&
                    (digits.startsWith("51") || digits.startsWith("56") || digits.startsWith("58")) -> true
                code.startsWith("sz") && digits.startsWith("15") -> true
                else -> false
            }
        }

        private fun stripMarketPrefix(value: String): String {
            return when {
                value.startsWith("sz") || value.startsWith("sh") || value.startsWith("bj") -> value.drop(2)
                else -> value
            }
        }

        private fun compact(value: String): String = value.filterNot { it.isWhitespace() }
    }
}
