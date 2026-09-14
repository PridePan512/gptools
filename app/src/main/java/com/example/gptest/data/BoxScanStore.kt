package com.example.gptest.data

import android.content.Context
import android.util.AtomicFile
import com.example.gptest.business.BoxBreakout
import com.example.gptest.business.DailyBar
import com.example.gptest.business.StockCatalogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class BoxHit(val stock: StockCatalogEntry, val box: BoxBreakout,
                  val bars: List<DailyBar>, val adjusted: Boolean)

data class BoxScanState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val universe: List<StockCatalogEntry> = emptyList(),
    val dates: List<LocalDate> = emptyList(),
    val outcomes: Map<String, String> = emptyMap(),
    val hits: List<BoxHit> = emptyList(),
    val message: String = "尚未扫描",
    val startedAt: Long = 0L
) {
    val processed get() = outcomes.size
    val failures get() = outcomes.count { it.value == "failed" }
    val insufficient get() = outcomes.count { it.value == "short" }
    val stale get() = outcomes.count { it.value == "stale" }
    val complete get() = universe.isNotEmpty() && processed == universe.size
}

class BoxScanStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "box-scan-v1.json"))

    fun read(): BoxScanState {
        if (!file.baseFile.exists()) return BoxScanState(loaded = true)
        return try {
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            check(json.getInt("version") == 1)
            val outcomes = json.getJSONObject("outcomes")
            BoxScanState(loaded = true,
                universe = json.getJSONArray("universe").objects().map { StockCatalogEntry(it.getString("code"), it.getString("name")) },
                dates = json.getJSONArray("dates").let { array -> (0 until array.length()).map { LocalDate.parse(array.getString(it)) } },
                outcomes = outcomes.keys().asSequence().associateWith { outcomes.getString(it) },
                hits = json.getJSONArray("hits").objects().map(::decodeHit),
                message = "已恢复上次扫描记录，可继续或重新扫描", startedAt = json.getLong("started"))
        } catch (_: Exception) {
            BoxScanState(loaded = true, message = "上次记录无法读取，请重新扫描")
        }
    }

    fun save(state: BoxScanState) {
        val json = JSONObject().put("version", 1).put("started", state.startedAt)
            .put("universe", JSONArray(state.universe.map { JSONObject().put("code", it.code).put("name", it.name) }))
            .put("dates", JSONArray(state.dates.map { it.toString() }))
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

    private fun encodeHit(hit: BoxHit): JSONObject {
        val box = hit.box
        return JSONObject().put("code", hit.stock.code).put("name", hit.stock.name)
            .put("adjusted", hit.adjusted).put("start", box.start.toString()).put("end", box.end.toString())
            .put("breakout", box.breakout.toString()).put("days", box.days).put("lower", box.lower)
            .put("upper", box.upper).put("upperTouches", box.upperTouches).put("lowerTouches", box.lowerTouches)
            .put("close", box.breakoutClose).put("ratio", box.volumeRatio ?: JSONObject.NULL)
            .put("latest", box.latestClose).put("latestDate", box.latestDate.toString())
            .put("bars", JSONArray(hit.bars.map { bar -> JSONArray(listOf(bar.date.toString(), bar.open,
                bar.close, bar.high, bar.low, bar.volume)) }))
    }

    private fun decodeHit(json: JSONObject): BoxHit {
        fun date(key: String) = LocalDate.parse(json.getString(key))
        val bars = json.getJSONArray("bars")
        return BoxHit(StockCatalogEntry(json.getString("code"), json.getString("name")),
            BoxBreakout(date("start"), date("end"), date("breakout"), json.getInt("days"),
                json.getDouble("lower"), json.getDouble("upper"), json.getInt("upperTouches"),
                json.getInt("lowerTouches"), json.getDouble("close"),
                if (json.isNull("ratio")) null else json.getDouble("ratio"),
                json.getDouble("latest"), date("latestDate")),
            (0 until bars.length()).map { index -> bars.getJSONArray(index).let {
                DailyBar(LocalDate.parse(it.getString(0)), it.getDouble(1), it.getDouble(2),
                    it.getDouble(3), it.getDouble(4), it.getDouble(5))
            } }, json.getBoolean("adjusted"))
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
