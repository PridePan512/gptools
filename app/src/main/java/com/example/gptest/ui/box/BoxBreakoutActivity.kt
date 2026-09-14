package com.example.gptest.ui.box

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gptest.data.BoxHit
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class BoxBreakoutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = boxScreen("箱体突破详情")
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        val loading = TextView(this).apply { text = "正在读取突破记录…" }
        content.addView(loading)
        lifecycleScope.launch {
            val state = BoxScanController.get(this@BoxBreakoutActivity).state.first { it.loaded }
            val hit = state.hits.find { it.stock.code == intent.getStringExtra("code") }
            if (hit == null) {
                loading.text = "该记录已失效，请返回检测列表重新选择"
                return@launch
            }
            content.removeAllViews()
            fun text(value: String, size: Float = 15f) {
                content.addView(TextView(this@BoxBreakoutActivity).apply {
                    text = value
                    textSize = size
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            val box = hit.box
            text("${hit.stock.name} · ${hit.stock.code.uppercase()}", 22f)
            text("${box.breakout} 收盘突破 · ${if (box.stillAbove) "仍在箱体上方" else "已回落箱体"}")
            content.addView(BoxChartView(this@BoxBreakoutActivity, hit), LinearLayout.LayoutParams(-1, dp(330)))
            text("紫色区域为原箱体，竖线标记突破日；下方为成交量。红涨绿跌，价格均来自同批日线。", 12f)
            text("箱体区间", 18f)
            text("${box.start} — ${box.end} · ${box.days} 个交易日\n" +
                "上沿 ${number(box.upper)} 元　下沿 ${number(box.lower)} 元\n" +
                "箱体宽度 ${number(box.widthPercent)}%\n上沿测试 ${box.upperTouches} 次 · 下沿测试 ${box.lowerTouches} 次")
            text("突破依据", 18f)
            text("突破收盘价 ${number(box.breakoutClose)} 元\n" +
                "超过上沿 ${number(box.breakoutPercent)}%（阈值 > 1%）\n" +
                "成交量 / 前 20 日均量：${box.volumeRatio?.let { number(it) + " 倍" } ?: "数据不足"}\n" +
                "${if ((box.volumeRatio ?: 0.0) >= 1.5) "满足放量条件（≥ 1.5 倍）" else "未满足放量条件或无可用基准"}")
            text("截止日状态", 18f)
            text("${box.latestDate} 收盘 ${number(box.latestClose)} 元\n" +
                "距原上沿 ${number((box.latestClose / box.upper - 1) * 100)}%\n" +
                "数据口径：${if (hit.adjusted) "前复权" else "原始日线（接口未返回复权字段）"} · 腾讯日线\n" +
                "本页展示本次扫描快照，不随实时行情变化。")
            text("检测规则", 18f)
            text(BOX_RULES, 13f)
        }
    }
}

internal class BoxChartView(context: Context, private val hit: BoxHit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = "${hit.stock.name} 日K线与成交量图，箱体 ${number(hit.box.lower)} 至 ${number(hit.box.upper)} 元，${hit.box.breakout} 突破"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hit.bars.isEmpty()) return
        val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val left = context.dp(4).toFloat()
        val right = width - context.dp(60).toFloat()
        val top = context.dp(28).toFloat()
        val bottom = height - context.dp(94).toFloat()
        val maximum = hit.bars.maxOf { it.high } * 1.02
        val minimum = hit.bars.minOf { it.low } * 0.98
        val step = (right - left) / hit.bars.size
        fun x(index: Int) = left + step * (index + 0.5f)
        fun y(value: Double) = bottom - ((value - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
        val end = hit.bars.indexOfLast { it.date <= hit.box.end }
        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = 35
        canvas.drawRect(left, y(hit.box.upper), x(end) + step / 2, y(hit.box.lower), paint)
        paint.alpha = 255
        paint.strokeWidth = context.dp(1).toFloat()
        paint.textSize = context.dp(11).toFloat()
        listOf(hit.box.upper, hit.box.lower).forEach {
            canvas.drawLine(left, y(it), right, y(it), paint)
            canvas.drawText(number(it), right + context.dp(4), y(it), paint)
        }
        val volTop = bottom + context.dp(20)
        val volBottom = height - context.dp(24).toFloat()
        val maxVolume = hit.bars.maxOf { it.volume }.coerceAtLeast(1.0)
        hit.bars.forEachIndexed { index, bar ->
            paint.color = if (bar.close >= bar.open) Color.rgb(231, 71, 65) else Color.rgb(73, 166, 95)
            canvas.drawLine(x(index), y(bar.high), x(index), y(bar.low), paint)
            val half = max(1f, step * 0.3f)
            canvas.drawRect(x(index) - half, min(y(bar.open), y(bar.close)), x(index) + half,
                max(max(y(bar.open), y(bar.close)), min(y(bar.open), y(bar.close)) + 1f), paint)
            val volY = volBottom - ((volBottom - volTop) * bar.volume / maxVolume).toFloat()
            canvas.drawRect(x(index) - half, volY, x(index) + half, volBottom, paint)
        }
        val breakout = hit.bars.indexOfFirst { it.date == hit.box.breakout }
        paint.color = foreground
        paint.textSize = context.dp(11).toFloat()
        if (breakout >= 0) {
            canvas.drawLine(x(breakout), top, x(breakout), bottom, paint)
            val label = "突破 ${hit.box.breakout}"
            canvas.drawText(label, (x(breakout) - paint.measureText(label)).coerceAtLeast(left), top - context.dp(8), paint)
        }
        canvas.drawText(hit.bars.first().date.toString(), left, height - context.dp(4).toFloat(), paint)
        val last = hit.bars.last().date.toString()
        canvas.drawText(last, right - paint.measureText(last), height - context.dp(4).toFloat(), paint)
    }
}
