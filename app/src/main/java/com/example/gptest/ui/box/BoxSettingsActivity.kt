package com.example.gptest.ui.box

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gptest.business.BoxScanConfig
import com.example.gptest.data.BoxScanPreferences
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class BoxSettingsActivity : AppCompatActivity() {
    private val fields = mutableListOf<TextInputEditText>()
    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = boxScreen("箱体检测设置")
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "参数详细说明"
            setOnClickListener {
                if (supportFragmentManager.findFragmentByTag("box-settings-help") == null) {
                    BoxSettingsHelpDialog().show(supportFragmentManager, "box-settings-help")
                }
            }
        })
        content.addView(TextView(this).apply {
            text = "保存后用于下一次新扫描。继续旧进度沿用该次参数；如需应用新规则，请返回后选择重新扫描。\n这些数值是形态筛选条件，可按需调整。"
            setPadding(0, 0, 0, dp(16))
        })
        fun section(title: String) { content.addView(TextView(this).apply {
            text = title; textSize = 18f; setPadding(0, dp(12), 0, dp(8))
        }) }
        fun field(label: String, helper: String, integer: Boolean = false, list: Boolean = false) {
            val layout = TextInputLayout(this).apply {
                hint = label; helperText = helper
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val edit = TextInputEditText(layout.context).apply {
                id = 4100 + fields.size
                inputType = if (list) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER or
                    (if (integer) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setSingleLine()
            }
            fields += edit
            layout.addView(edit)
            content.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        section("区间与突破")
        field("整理周期（交易日）", "10～250，可填多个，以逗号分隔；优先最长周期", list = true)
        field("最大箱体宽度（%）", "0.1～200；（上沿－下沿）÷ 下沿")
        field("最小突破幅度（%）", "0～30；收盘涨幅须严格超过此值")
        section("横盘与成交量")
        field("拟合趋势总偏移 / 箱高（%）", "0～200；越小越严格")
        field("前后段均价差 / 箱高（%）", "0～200；前后各三分之一收盘均价")
        field("成交量基准周期（交易日）", "5～250；取突破前的日均成交量", integer = true)
        field("放量倍数", "0.1～20；用于放量筛选和详情标记")
        val prefs = BoxScanPreferences(this)
        fun fill(c: BoxScanConfig) {
            val values = listOf(c.windows.joinToString(","), c.maxWidthPercent, c.breakoutPercent,
                c.slopeWidthPercent, c.meanWidthPercent, c.volumeDays, c.volumeRatio)
            fields.zip(values).forEach { (edit, value) -> edit.setText(value.toString()) }
        }
        fill(prefs.read())
        content.addView(MaterialButton(this).apply {
            text = "保存设置"
            setOnClickListener {
                try {
                    val v = fields.map { it.text.toString().trim() }
                    val config = BoxScanConfig(v[0].split(',', '，').map { it.trim().toInt() }.sortedDescending(),
                        v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toInt(), v[6].toDouble())
                    prefs.save(config)
                    Toast.makeText(this@BoxSettingsActivity, "已保存，下次新扫描生效", Toast.LENGTH_LONG).show()
                    finish()
                } catch (error: Exception) {
                    Toast.makeText(this@BoxSettingsActivity,
                        if (error is NumberFormatException) "请完整填写有效数值" else error.message ?: "保存失败", Toast.LENGTH_LONG).show()
                }
            }
        })
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "填入默认值"
            setOnClickListener { fill(BoxScanConfig()) }
        })
    }
}
