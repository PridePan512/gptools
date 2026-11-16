package com.example.gptest.data

import android.content.Context
import android.util.AtomicFile
import com.example.gptest.business.DailyBar
import com.example.gptest.business.LimitFlip
import com.example.gptest.business.LimitFlipDetector
import com.example.gptest.business.LimitFlipTrade
import com.example.gptest.business.StockCatalogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class LimitFlipHit(val stock: StockCatalogEntry, val flip: LimitFlip)

data class LimitFlipState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val universe: List<StockCatalogEntry> = emptyList(),
    val windowStart: LocalDate? = null,
    val outcomes: Map<String, String> = emptyMap(),
    val hits: List<LimitFlipHit> = emptyList(),
    val message: String = "尚未统计",
    val startedAt: Long = 0L,
    val ruleVersion: Int = LimitFlipDetector.VERSION
) {
    val processed get() = outcomes.size
    val failures get() = outcomes.count { it.value == "failed" }
    val insufficient get() = outcomes.count { it.value == "short" }
    val complete get() = universe.isNotEmpty() && processed == universe.size
    val stockCount get() = hits.map { it.stock.code }.distinct().size
}

class LimitFlipStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "limit-flip-v1.json"))

    fun read(): LimitFlipState {
        if (!file.baseFile.exists()) return LimitFlipState(loaded = true)
        return try {
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            check(json.getInt("version") == 1)
            val outcomes = json.getJSONObject("outcomes")
            LimitFlipState(
                loaded = true,
                ruleVersion = json.optInt("ruleVersion", 1),
                universe = json.getJSONArray("universe").objects().map { StockCatalogEntry(it.getString("code"), it.getString("name")) },
                windowStart = json.optString("windowStart").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                outcomes = outcomes.keys().asSequence().associateWith { outcomes.getString(it) },
                hits = json.getJSONArray("hits").objects().map(::decodeHit),
                message = "已恢复上次统计，可继续或重新统计",
                startedAt = json.getLong("started")
            )
        } catch (_: Exception) {
            LimitFlipState(loaded = true, message = "上次记录无法读取，请重新统计")
        }
    }

    fun save(state: LimitFlipState) {
        val json = JSONObject()
            .put("version", 1)
            .put("started", state.startedAt)
            .put("ruleVersion", state.ruleVersion)
            .put("windowStart", state.windowStart?.toString().orEmpty())
            .put("universe", JSONArray(state.universe.map { JSONObject().put("code", it.code).put("name", it.name) }))
            .put("outcomes", JSONObject(state.outcomes))
            .put("hits", JSONArray(state.hits.map(::encodeHit)))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun encodeHit(hit: LimitFlipHit) = JSONObject()
        .put("code", hit.stock.code)
        .put("name", hit.stock.name)
        .put("up", hit.flip.limitUpDate.toString())
        .put("down", hit.flip.limitDownDate.toString())
        .put("upClose", hit.flip.limitUpClose)
        .put("low", hit.flip.nextLow)
        .put("close", hit.flip.nextClose)
        .put("sealed", hit.flip.closedLimitDown)
        .put("upBar", encodeBar(hit.flip.limitUpBar))
        .put("downBar", encodeBar(hit.flip.limitDownBar))
        .put("thirdBar", encodeBar(hit.flip.thirdBar))
        .put("boards", hit.flip.limitUpBoards)
        .put("confirmedOnFourth", hit.flip.confirmedOnFourth)
        .put("trade", encodeTrade(hit.flip.trade))
        .apply { hit.flip.fourthBar?.let { put("fourthBar", encodeBar(it)) } }

    private fun decodeHit(json: JSONObject): LimitFlipHit {
        val upDate = LocalDate.parse(json.getString("up"))
        val downDate = LocalDate.parse(json.getString("down"))
        val upClose = json.getDouble("upClose")
        val low = json.getDouble("low")
        val close = json.getDouble("close")
        val third = json.optJSONArray("thirdBar")?.let(::decodeBar) ?: DailyBar(downDate, close, close, close, close, 0.0)
        return LimitFlipHit(
            StockCatalogEntry(json.getString("code"), json.getString("name")),
            LimitFlip(
                upDate,
                downDate,
                upClose,
                low,
                close,
                json.getBoolean("sealed"),
                json.optJSONArray("upBar")?.let(::decodeBar) ?: DailyBar(upDate, upClose, upClose, upClose, upClose, 0.0),
                json.optJSONArray("downBar")?.let(::decodeBar) ?: DailyBar(downDate, close, close, close, low, 0.0),
                third,
                json.optJSONArray("fourthBar")?.let(::decodeBar),
                json.optInt("boards", 2),
                json.optBoolean("confirmedOnFourth", false),
                json.optJSONObject("trade")?.let(::decodeTrade) ?: LimitFlipTrade(third.date, third.close, null, third.close, "待重新统计", 0.0, 0, 0)
            )
        )
    }

    private fun encodeTrade(trade: LimitFlipTrade) = JSONObject()
        .put("entry", trade.entryDate.toString())
        .put("entryPrice", trade.entryPrice)
        .put("exit", trade.exitDate?.toString().orEmpty())
        .put("exitPrice", trade.exitPrice)
        .put("reason", trade.reason)
        .put("pnl", trade.accountPnlPercent)
        .put("adds", trade.adds)
        .put("halves", trade.halves)

    private fun decodeTrade(json: JSONObject) = LimitFlipTrade(
        LocalDate.parse(json.getString("entry")),
        json.getDouble("entryPrice"),
        json.optString("exit").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        json.getDouble("exitPrice"),
        json.getString("reason"),
        json.getDouble("pnl"),
        json.optInt("adds"),
        json.optInt("halves")
    )

    private fun encodeBar(bar: DailyBar) = JSONArray(listOf(bar.date.toString(), bar.open, bar.close, bar.high, bar.low, bar.volume))

    private fun decodeBar(array: JSONArray) = DailyBar(
        LocalDate.parse(array.getString(0)),
        array.getDouble(1),
        array.getDouble(2),
        array.getDouble(3),
        array.getDouble(4),
        array.getDouble(5)
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
