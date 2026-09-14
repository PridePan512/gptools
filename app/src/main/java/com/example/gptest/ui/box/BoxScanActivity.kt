package com.example.gptest.ui.box

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.data.BoxHit
import com.example.gptest.data.BoxScanState
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.util.Locale

internal const val BOX_RULES = "扫描范围：项目内置沪深京 A 股名单，非实时全市场名单。剔除基金、指数及 B 股。\n\n" +
    "最近五日：依据上证指数日线确定的五个已完成交易日。15:10 前不使用当天日线；扫描期间固定截止日期。\n\n" +
    "箱体：突破前 20 / 30 / 40 / 60 根日线，上下沿取区间最高 / 最低价，宽度不超过下沿的 15%。上下沿各至少两次测试，接近边界的误差不超过箱高的 20% 或上沿的 1%（取较小值）；两次测试间至少隔 3 根未触及边界的日线。\n\n" +
    "横盘：拟合趋势的总变化、前后各三分之一平均收盘价差均不超过箱高的 35%。突破日不参与箱体计算。\n\n" +
    "突破：收盘价严格超过箱体上沿 1%。优先最近的突破日，同日多个窗口优先最长窗口，每只股票只展示一次。\n\n" +
    "放量：突破日成交量 ÷ 此前 20 日平均成交量，达到 1.5 倍标记放量。仍在上方：截止日收盘价高于原箱体上沿。\n\n" +
    "历史不足 25 根或截止日无日线会单独统计。价格采用接口前复权数据；无复权字段时采用原始日线并在详情标注。\n\n" +
    "离开检测页面自动暂停，每 10 只及暂停时保存进度。继续扫描沿用原截止日期；获取最新结果请重新扫描。形态规则版本：1。"

internal fun AppCompatActivity.boxScreen(title: String): LinearLayout {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
    }
    val toolbar = MaterialToolbar(this).apply {
        this.title = title
        setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        setNavigationOnClickListener { finish() }
    }
    root.addView(toolbar)
    setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    return root
}

internal fun android.content.Context.dp(value: Int) = (resources.displayMetrics.density * value).toInt()
internal fun number(value: Double) = String.format(Locale.CHINA, "%.2f", value)

class BoxScanActivity : AppCompatActivity() {
    private val controller by lazy { BoxScanController.get(this) }
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var start: MaterialButton
    private lateinit var restart: MaterialButton
    private lateinit var retry: MaterialButton
    private lateinit var empty: TextView
    private lateinit var above: MaterialCheckBox
    private lateinit var volume: MaterialCheckBox
    private lateinit var hideSt: MaterialCheckBox
    private val adapter = HitAdapter { hit ->
        startActivity(Intent(this, BoxBreakoutActivity::class.java).putExtra("code", hit.stock.code))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = boxScreen("箱体检测")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val rule = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "内置 A 股名单 · 规则说明"
            setOnClickListener { MaterialAlertDialogBuilder(this@BoxScanActivity).setTitle("检测规则")
                .setMessage(BOX_RULES).setPositiveButton("知道了", null).show() }
        }
        content.addView(rule)
        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(status)
        progress = LinearProgressIndicator(this)
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val actions = LinearLayout(this)
        start = MaterialButton(this).apply { setOnClickListener {
            if (controller.state.value.running) controller.pause() else controller.start()
        } }
        restart = MaterialButton(this).apply { text = "重新扫描"; setOnClickListener {
            MaterialAlertDialogBuilder(this@BoxScanActivity).setTitle("重新扫描？")
                .setMessage("将替换上次进度和结果，按最新已完成交易日重新检测内置 A 股名单。")
                .setNegativeButton("取消", null).setPositiveButton("重新扫描") { _, _ -> controller.start(fresh = true) }.show()
        } }
        actions.addView(start, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(restart, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        retry = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "重试失败并继续"; setOnClickListener { controller.start(retryFailures = true) }
        }
        content.addView(retry)
        val filters = com.google.android.material.chip.ChipGroup(this)
        fun filter(label: String) = MaterialCheckBox(this).apply {
            text = label; textSize = 12f
            setOnCheckedChangeListener { _, _ -> render(controller.state.value) }
            filters.addView(this)
        }
        above = filter("仍在上方")
        volume = filter("放量≥1.5倍")
        hideSt = filter("隐藏 ST")
        above.isChecked = savedInstanceState?.getBoolean("above") ?: false
        volume.isChecked = savedInstanceState?.getBoolean("volume") ?: false
        hideSt.isChecked = savedInstanceState?.getBoolean("st") ?: false
        content.addView(filters)
        empty = TextView(this).apply { textSize = 14f; setPadding(0, dp(16), 0, dp(16)) }
        content.addView(empty)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BoxScanActivity)
            adapter = this@BoxScanActivity.adapter
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { controller.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("above", above.isChecked)
        outState.putBoolean("volume", volume.isChecked)
        outState.putBoolean("st", hideSt.isChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isChangingConfigurations) controller.pause()
        super.onStop()
    }

    private fun render(state: BoxScanState) {
        if (state.running) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!::empty.isInitialized) return
        val shown = state.hits.filter {
            (!above.isChecked || it.box.stillAbove) &&
                (!volume.isChecked || (it.box.volumeRatio ?: 0.0) >= 1.5) &&
                (!hideSt.isChecked || !it.stock.name.contains("ST", true))
        }.sortedWith(compareByDescending<BoxHit> { it.box.breakout }.thenByDescending { it.box.breakoutPercent })
        status.text = if (!state.loaded) "正在读取上次记录…" else buildString {
            append(state.message)
            if (state.dates.isNotEmpty()) append("\n${state.dates.first()} 至 ${state.dates.last()} · 收盘确认")
            append("\n已处理 ${state.processed}/${state.universe.size} · 命中 ${state.hits.size} · 显示 ${shown.size}")
            append("\n未命中 ${state.outcomes.count { it.value == "none" }}")
            append("\n失败 ${state.failures} · 历史不足 ${state.insufficient} · 数据过旧 ${state.stale}")
        }
        progress.max = state.universe.size.coerceAtLeast(1)
        progress.progress = state.processed
        start.text = when { state.running -> "暂停扫描"; state.universe.isEmpty() -> "开始扫描"; state.complete -> "扫描已完成"; else -> "继续扫描" }
        start.isEnabled = state.loaded && (!state.complete || state.running)
        restart.isEnabled = state.loaded && !state.running && state.universe.isNotEmpty()
        retry.visibility = if (state.failures > 0) View.VISIBLE else View.GONE
        retry.isEnabled = !state.running
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.hits.isNotEmpty() -> "当前筛选条件下没有结果"
            state.running -> "尚未发现符合规则的突破，结果会陆续显示"
            state.complete && state.failures > 0 -> "已完成部分数据检测，暂无命中；失败项尚未判定"
            state.complete -> "本次可用数据中未发现符合规则的突破"
            state.universe.isEmpty() -> "点击开始扫描，检测近五个已完成交易日的向上突破。离开页面自动暂停。"
            else -> "暂无命中，可继续完成扫描"
        }
        adapter.submit(shown)
    }

    private class HitAdapter(val click: (BoxHit) -> Unit) : RecyclerView.Adapter<HitAdapter.Holder>() {
        private var rows = emptyList<BoxHit>()
        fun submit(next: List<BoxHit>) { if (rows != next) { rows = next; notifyDataSetChanged() } }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = MaterialCardView(parent.context).apply {
                radius = context.dp(12).toFloat()
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) }
            }
            val text = TextView(parent.context).apply {
                textSize = 15f
                setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
                setLineSpacing(context.dp(4).toFloat(), 1f)
            }
            card.addView(text)
            return Holder(card, text)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val hit = rows[position]
            val box = hit.box
            holder.text.text = "${hit.stock.name}  ${hit.stock.code.uppercase()}\n" +
                "${box.breakout} 突破 · 整理 ${box.days} 日 · +${number(box.breakoutPercent)}%\n" +
                "${if (box.stillAbove) "仍在箱体上方" else "已回落箱体"} · 成交量 ${box.volumeRatio?.let { number(it) + "倍" } ?: "暂无比较"}"
            holder.itemView.setOnClickListener { click(hit) }
        }
        class Holder(view: View, val text: TextView) : RecyclerView.ViewHolder(view)
    }
}
