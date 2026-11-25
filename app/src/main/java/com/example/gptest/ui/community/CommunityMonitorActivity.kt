package com.example.gptest.ui.community

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gptest.business.CommunityAccount
import com.example.gptest.business.CommunityPost
import com.example.gptest.data.CommunityPostRepository
import com.example.gptest.ui.box.boxScreen
import com.example.gptest.ui.box.dp
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CommunityMonitorActivity : AppCompatActivity() {
    private val store by lazy { CommunityMonitorStore.get(this) }
    private val notifications by lazy { CommunityNotifications(this) }
    private lateinit var status: TextView
    private lateinit var toggle: MaterialButton
    private lateinit var interval: Spinner
    private lateinit var postsContainer: LinearLayout
    private var displayed: List<CommunityPost>? = null
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMonitor() else showMessage("需要通知权限才能提醒新动态")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = boxScreen("动态提醒")
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(content)
        content.addView(label(CommunityAccount.NAME, 22f))
        content.addView(label("开启后检查最新动态，首次只记录已有内容。停止后再开启，会检查停用期间的新动态。", 14f))
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "查看同花顺主页"
            setOnClickListener { openUrl(CommunityAccount.HOME) }
        })
        content.addView(label("检查间隔（停止后可调整）", 14f))
        interval = Spinner(this).apply {
            contentDescription = "动态检查间隔"
            adapter = ArrayAdapter(this@CommunityMonitorActivity, android.R.layout.simple_spinner_dropdown_item,
                CommunityAccount.intervals.map { "$it 秒" })
            setSelection(CommunityAccount.intervals.indexOf(store.state.value.interval))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    store.setInterval(CommunityAccount.intervals[position])
                }
            }
        }
        content.addView(interval, LinearLayout.LayoutParams(-1, dp(48)))
        status = label("", 14f)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(status)
        toggle = MaterialButton(this).apply {
            setOnClickListener {
                if (store.state.value.running) {
                    startService(Intent(this@CommunityMonitorActivity, CommunityMonitorService::class.java)
                        .setAction(CommunityMonitorService.STOP))
                } else requestStart()
            }
        }
        content.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "通知设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        })
        content.addView(label("监控与行情监控独立，非交易时间也会检查。锁屏省电、断网或系统后台时限可能导致延迟或停止，无法保证秒到。当前仅检查最近 15 条，长时间离线可能漏掉更早内容。", 12f))
        content.addView(label("最近动态 · 点击查看原文", 18f))
        postsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(postsContainer)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { store.state.collect(::render) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) render(store.state.value)
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startMonitor()
    }

    private fun startMonitor() {
        if (!notifications.allowed()) {
            showMessage("请在通知设置中允许通知，并开启“关注账号的新动态”")
            return
        }
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, CommunityMonitorService::class.java).setAction(CommunityMonitorService.START))
        } catch (_: Exception) {
            store.stopped("启动失败，请保持应用在前台后重试")
        }
    }

    private fun render(state: CommunityMonitorState) {
        toggle.text = if (state.running) "停止提醒" else "开启提醒"
        interval.isEnabled = !state.running
        status.text = buildString {
            append(state.message)
            if (state.checkedAt > 0) append("\n上次成功检查：${time.format(Instant.ofEpochMilli(state.checkedAt))}")
            if (state.running && state.nextCheckAt > 0) append("\n预计下次检查：${time.format(Instant.ofEpochMilli(state.nextCheckAt))}")
            if (!notifications.allowed()) append("\n通知未开启，请检查通知设置")
        }
        if (displayed == state.posts) return
        displayed = state.posts
        postsContainer.removeAllViews()
        if (state.posts.isEmpty()) postsContainer.addView(label("开启提醒后显示最近动态", 14f))
        state.posts.forEach { post ->
            val card = MaterialCardView(this).apply {
                radius = dp(12).toFloat()
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                isClickable = true
                isFocusable = true
                setOnClickListener { openUrl(post.url) }
            }
            card.addView(label("${time.format(Instant.ofEpochSecond(post.createdAtSeconds))}\n${post.text}", 15f).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                maxLines = 7
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            postsContainer.addView(card)
        }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun openUrl(url: String) {
        if (!CommunityPostRepository.isAllowedUrl(url)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            showMessage("没有可打开链接的浏览器")
        }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
