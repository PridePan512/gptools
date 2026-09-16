package com.example.gptest.data

import android.content.Context
import com.example.gptest.business.BoxScanConfig
import org.json.JSONArray
import org.json.JSONObject

internal fun BoxScanConfig.toJson(): JSONObject = JSONObject()
    .put("windows", JSONArray(windows)).put("width", maxWidthPercent).put("breakout", breakoutPercent)
    .put("slope", slopeWidthPercent).put("mean", meanWidthPercent)
    .put("volumeDays", volumeDays).put("volumeRatio", volumeRatio)

internal fun decodeBoxConfig(json: JSONObject): BoxScanConfig = BoxScanConfig(
    windows = json.getJSONArray("windows").let { a -> (0 until a.length()).map { a.getInt(it) } },
    maxWidthPercent = json.getDouble("width"), breakoutPercent = json.getDouble("breakout"),
    slopeWidthPercent = json.getDouble("slope"), meanWidthPercent = json.getDouble("mean"),
    volumeDays = json.getInt("volumeDays"), volumeRatio = json.getDouble("volumeRatio"))

class BoxScanPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("box-rules", Context.MODE_PRIVATE)
    fun read(): BoxScanConfig = runCatching {
        prefs.getString("config", null)?.let { decodeBoxConfig(JSONObject(it)) } ?: BoxScanConfig()
    }.getOrDefault(BoxScanConfig())
    fun save(config: BoxScanConfig) {
        check(prefs.edit().putString("config", config.toJson().toString()).commit()) { "设置保存失败，请重试" }
    }
}
