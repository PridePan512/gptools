package com.example.gptest.ui.limit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gptest.business.DailyBar
import com.example.gptest.business.LimitFlipDetector
import com.example.gptest.data.HistoryRepository
import com.example.gptest.data.LimitFlipHit
import com.example.gptest.ui.box.boxScreen
import com.example.gptest.ui.box.dp
import com.example.gptest.ui.box.number
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

class LimitFlipDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = boxScreen("涨停与跌停")
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        val loading = TextView(this).apply { text = "正在读取记录…" }
        content.addView(loading)
        lifecycleScope.launch {
            val state = LimitFlipController.get(this@LimitFlipDetailActivity).state.first { it.loaded }
            val code = intent.getStringExtra("code")
            val up = intent.getStringExtra("up")?.let(LocalDate::parse)
            val down = intent.getStringExtra("down")?.let(LocalDate::parse)
            val hit = state.hits.find { it.stock.code == code && it.flip.limitUpDate == up && it.flip.limitDownDate == down }
            if (hit == null) {
                loading.text = "该记录已失效，请返回列表重新选择"
                return@launch
            }
            val flip = hit.flip
            var candles = listOfNotNull(flip.limitUpBar, flip.limitDownBar, flip.thirdBar, flip.fourthBar)
            if (candles.takeLast(2).all { it.volume == 0.0 }) {
                loading.text = "正在读取这四天的日线…"
                val fetched = withContext(Dispatchers.IO) {
                    HistoryRepository().fetchBars(hit.stock.code, LimitFlipDetector.HISTORY_BARS, forwardAdjust = false)
                }
                fetched.onSuccess { history ->
                    val rows = history.bars.sortedBy { it.date }
                    val start = rows.indexOfFirst { it.date == flip.limitUpDate }
                    if (start >= 0 && start + 3 < rows.size) candles = rows.subList(start, start + 4)
                }
            }
            content.removeAllViews()
            fun text(value: String, size: Float = 15f) {
                content.addView(TextView(this@LimitFlipDetailActivity).apply {
                    this.text = value
                    textSize = size
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            val limitDown = BigDecimal.valueOf(flip.limitUpClose)
                .multiply(BigDecimal.ONE.subtract(LimitFlipDetector.LIMIT_RATIO))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            val upBar = candles[0]
            val downBar = candles[1]
            val thirdBar = candles[2]
            val fourthBar = candles.getOrNull(3)
            val yuan = flip.trade.accountPnlPercent / 100.0 * 10000.0
            text("${hit.stock.name} · ${hit.stock.code.uppercase()}", 22f)
            text("${upBar.date} ${flip.limitUpBoards}连板收盘，${downBar.date} 收盘跌停\n${thirdBar.date} 收盘 ${number(thirdBar.close)}" +
                (fourthBar?.let { "，${it.date} 收盘 ${number(it.close)}" } ?: ""))
            text("入场 ${flip.trade.entryDate} 收盘 ${number(flip.trade.entryPrice)} 元，买入一成。\n" +
                "${flip.trade.reason}" + (flip.trade.exitDate?.let { " · $it ${number(flip.trade.exitPrice)} 元" } ?: "") +
                "\n" +
                "这笔按 1 万元本金计算，盈亏 ${signed(flip.trade.accountPnlPercent)}%（${signed(yuan)} 元）")
            content.addView(
                LimitFlipChartView(this@LimitFlipDetailActivity, hit, candles, flip.limitUpClose, limitDown),
                LinearLayout.LayoutParams(-1, dp(330))
            )
            text("依次为涨停日、跌停日、第三天、第四天。横线是第一天的涨停价和第二天的跌停价。红涨绿跌，价格来自不复权日线。", 12f)
            text("涨停日", 18f)
            text(ohlc(upBar) + "\n涨停价 ${number(flip.limitUpClose)} 元")
            text("跌停日", 18f)
            text(ohlc(downBar) + "\n跌停价 ${number(limitDown)} 元")
            text("第三天", 18f)
            text(ohlc(thirdBar))
            if (fourthBar != null) {
                text(if (flip.confirmedOnFourth) "第四天 · 收盘入场" else "第四天", 18f)
                text(ohlc(fourthBar))
            }
        }
    }

    private fun ohlc(bar: DailyBar) =
        "${bar.date}\n开盘 ${number(bar.open)}　收盘 ${number(bar.close)}\n最高 ${number(bar.high)}　最低 ${number(bar.low)}"
}

private class LimitFlipChartView(
    context: Context,
    hit: LimitFlipHit,
    private val bars: List<DailyBar>,
    private val limitUp: Double,
    private val limitDown: Double
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = listOf("涨停", "跌停", "第三天", "第四天")

    init {
        contentDescription = "${hit.stock.name} 连续四天日K线，${bars.first().date} 涨停，${bars.getOrNull(1)?.date ?: ""} 跌停"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val left = context.dp(8).toFloat()
        val right = width - context.dp(64).toFloat()
        val top = context.dp(28).toFloat()
        val bottom = height - context.dp(94).toFloat()
        var maximum = maxOf(bars.maxOf { it.high }, limitUp, limitDown) * 1.02
        var minimum = minOf(bars.minOf { it.low }, limitUp, limitDown) * 0.98
        if (maximum <= minimum) {
            maximum *= 1.02
            minimum *= 0.98
        }
        val step = (right - left) / bars.size
        fun x(index: Int) = left + step * (index + 0.5f)
        fun y(value: Double) = bottom - ((value - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1).toFloat()
        paint.color = foreground
        paint.alpha = 120
        listOf(limitUp to "涨停价", limitDown to "跌停价").forEach { (price, label) ->
            canvas.drawLine(left, y(price), right, y(price), paint)
            paint.style = Paint.Style.FILL
            paint.textSize = context.dp(11).toFloat()
            canvas.drawText("$label ${number(price)}", right + context.dp(4), y(price), paint)
            paint.style = Paint.Style.STROKE
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        val volTop = bottom + context.dp(20)
        val volBottom = height - context.dp(28).toFloat()
        val maxVolume = bars.maxOf { it.volume }.coerceAtLeast(1.0)
        bars.forEachIndexed { index, bar ->
            paint.color = if (bar.close >= bar.open) Color.rgb(231, 71, 65) else Color.rgb(73, 166, 95)
            canvas.drawLine(x(index), y(bar.high), x(index), y(bar.low), paint)
            val half = max(context.dp(6).toFloat(), step * 0.22f)
            canvas.drawRect(
                x(index) - half, min(y(bar.open), y(bar.close)), x(index) + half,
                max(max(y(bar.open), y(bar.close)), min(y(bar.open), y(bar.close)) + 1f), paint
            )
            val volY = volBottom - ((volBottom - volTop) * bar.volume / maxVolume).toFloat()
            canvas.drawRect(x(index) - half, volY, x(index) + half, volBottom, paint)
            paint.color = foreground
            paint.textSize = context.dp(12).toFloat()
            val title = "${labels.getOrElse(index) { "" }} ${bar.date.monthValue}/${bar.date.dayOfMonth}"
            canvas.drawText(title, x(index) - paint.measureText(title) / 2, top - context.dp(8), paint)
        }
    }
}
