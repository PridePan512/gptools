package com.example.gptest.ui.limit

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.data.HistoryAccess
import com.example.gptest.data.LimitFlipHit
import com.example.gptest.data.LimitFlipState
import com.example.gptest.ui.box.boxScreen
import com.example.gptest.ui.box.dp
import com.example.gptest.ui.box.number
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class LimitFlipActivity : AppCompatActivity() {
    private val controller by lazy { LimitFlipController.get(this) }
    private var pendingStart: Pair<Boolean, Boolean>? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        pendingStart?.let { (fresh, retry) -> controller.start(fresh, retry) }
        pendingStart = null
    }
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var start: MaterialButton
    private lateinit var restart: MaterialButton
    private lateinit var retry: MaterialButton
    private lateinit var empty: TextView
    private val adapter = HitAdapter { hit ->
        startActivity(Intent(this, LimitFlipDetailActivity::class.java)
            .putExtra("code", hit.stock.code)
            .putExtra("up", hit.flip.limitUpDate.toString())
            .putExtra("down", hit.flip.limitDownDate.toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("pendingScan") == true) {
            pendingStart = savedInstanceState.getBoolean("pendingFresh") to savedInstanceState.getBoolean("pendingRetry")
        }
        val root = boxScreen("先涨停后跌停")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "统计规则"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@LimitFlipActivity).setTitle("统计规则")
                    .setMessage(RULES)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        })
        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(status)
        progress = LinearProgressIndicator(this)
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val actions = LinearLayout(this)
        start = MaterialButton(this).apply {
            setOnClickListener { if (controller.state.value.running) controller.pause() else startScan() }
        }
        restart = MaterialButton(this).apply {
            text = "重新统计"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@LimitFlipActivity).setTitle("重新统计？")
                    .setMessage("将替换上次进度和结果，按最近半年重新统计沪深主板。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重新统计") { _, _ -> startScan(fresh = true) }
                    .show()
            }
        }
        actions.addView(start, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(restart, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        retry = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "重试失败并继续"
            setOnClickListener { startScan(retryFailures = true) }
        }
        content.addView(retry)
        empty = TextView(this).apply { textSize = 14f; setPadding(0, dp(16), 0, dp(16)) }
        content.addView(empty)
        content.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LimitFlipActivity)
            adapter = this@LimitFlipActivity.adapter
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { controller.state.collect(::render) }
        }
    }

    private fun startScan(fresh: Boolean = false, retryFailures: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = fresh to retryFailures
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else controller.start(fresh, retryFailures)
    }

    override fun onStop() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStart?.let { (fresh, retry) ->
            outState.putBoolean("pendingScan", true)
            outState.putBoolean("pendingFresh", fresh)
            outState.putBoolean("pendingRetry", retry)
        }
        super.onSaveInstanceState(outState)
    }

    private fun render(state: LimitFlipState) {
        if (state.running) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!::empty.isInitialized) return
        val blocked = HistoryAccess.gate.blocked()
        val oldRules = state.universe.isNotEmpty() && state.ruleVersion != com.example.gptest.business.LimitFlipDetector.VERSION
        val shown = state.hits.sortedWith(compareByDescending<LimitFlipHit> { it.flip.trade.entryDate }.thenBy { it.stock.code })
        status.text = if (!state.loaded) "正在读取上次记录…" else buildString {
            append(blocked?.message ?: state.message)
            if (oldRules) append("\n统计规则已更新，请重新统计；以下保留旧结果")
            state.windowStart?.let { append("\n$it 起的最近半年 · 未收盘的当日不计入") }
            append("\n已处理 ${state.processed}/${state.universe.size} · ${state.stockCount} 只 · ${state.hits.size} 次")
            append("\n失败 ${state.failures} · 历史不足 ${state.insufficient}")
            if (state.hits.isNotEmpty() && !oldRules) {
                val sum = state.hits.sumOf { it.flip.trade.accountPnlPercent }
                append("\n${state.hits.size} 笔各用 1 万元单独入一成，盈亏相加 ${signed(sum)}%（${signed(sum / 100.0 * 10000.0)} 元）")
            }
        }
        progress.max = state.universe.size.coerceAtLeast(1)
        progress.progress = state.processed
        start.text = when {
            state.running -> "暂停统计"
            state.universe.isEmpty() -> "开始统计"
            state.complete -> "统计已完成"
            else -> "继续统计"
        }
        start.isEnabled = state.loaded && (state.running || (!state.complete && blocked == null && !oldRules))
        restart.isEnabled = state.loaded && !state.running && state.universe.isNotEmpty() && blocked == null
        retry.visibility = if (state.failures > 0) View.VISIBLE else View.GONE
        retry.isEnabled = state.loaded && !state.running && blocked == null && !oldRules
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.running -> "尚未发现符合条件的股票，结果会陆续显示"
            state.complete && state.failures > 0 -> "已完成部分数据统计，暂无命中；失败项尚未判定"
            state.complete -> "最近半年的可用数据中，没有同时满足这三条的股票"
            state.universe.isEmpty() -> "点击开始统计。扫描沪深主板最近半年。离开页面会继续，可在通知栏暂停。"
            else -> "暂无命中，可继续完成统计"
        }
        adapter.submit(shown)
    }

    private class HitAdapter(val click: (LimitFlipHit) -> Unit) : RecyclerView.Adapter<HitAdapter.Holder>() {
        private var rows = emptyList<LimitFlipHit>()
        fun submit(next: List<LimitFlipHit>) {
            if (rows != next) {
                rows = next
                notifyDataSetChanged()
            }
        }
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
            val flip = hit.flip
            val thirdChange = (flip.thirdBar.close / flip.limitDownBar.close - 1) * 100
            val thirdLine = if (flip.confirmedOnFourth) {
                "${flip.thirdBar.date} 收盘 ${number(flip.thirdBar.close)}（${signed(thirdChange)}%）"
            } else {
                "${flip.thirdBar.date} 收盘转红 ${number(flip.thirdBar.close)}（${signed(thirdChange)}%）"
            }
            val yuan = flip.trade.accountPnlPercent / 100.0 * 10000.0
            holder.text.text = "${hit.stock.name}  ${hit.stock.code.uppercase()}\n" +
                "${flip.limitUpDate} ${flip.limitUpBoards}连板收盘 ${number(flip.limitUpClose)}\n" +
                "${flip.limitDownDate} 收盘跌停 ${number(flip.limitDownBar.close)}\n" +
                "$thirdLine\n" +
                "${flip.trade.entryDate} 收盘 ${number(flip.trade.entryPrice)} 入场一成\n" +
                "盈亏 ${signed(flip.trade.accountPnlPercent)}%（${signed(yuan)} 元）· ${flip.trade.reason}"
            holder.itemView.setOnClickListener { click(hit) }
        }
        class Holder(view: View, val text: TextView) : RecyclerView.ViewHolder(view)
    }

    companion object {
        private val RULES = """
            最近半年内，同时满足下面三条才入选：

            1. 第一天收盘必须是连板，至少二连板。
            2. 第二天收盘跌停。开盘价不限制。
            3. 第三天收盘价高于第二天收盘价；或者第四天收盘价比第二天跌停价至少高 3%。

            入选后按每笔单独 1 万元本金复盘，各笔互不影响：
            · 第三天收盘转红时，第三天收盘买入一成（1000 元）；否则第四天收盘买入。买入后不加仓。
            · 止损是跌停当天的最低价。之后开盘价跌破它，按开盘价清仓；盘中最低价跌破它，按这个最低价清仓。
            · 涨到买入价的 8% 就止盈。开盘价已经越过止盈价，按开盘价卖出；否则按止盈价卖出。同一天止损和止盈都碰到，按止损算。
            · 买入后第 3 个交易日收盘仍未触发，按收盘价平仓。数据不够 3 天的，按最后收盘价估算。

            只统计沪深主板，涨跌幅按 10% 计算，ST 同样是 10%。科创板、创业板和北交所不参与。涨跌停价按前一日不复权收盘价四舍五入到分。当天 15:10 前不把当日当作已收盘。
        """.trimIndent()
    }
}

internal fun signed(value: Double) = String.format(java.util.Locale.CHINA, "%+.2f", value)
