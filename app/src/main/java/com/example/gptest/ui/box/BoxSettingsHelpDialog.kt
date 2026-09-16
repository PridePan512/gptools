package com.example.gptest.ui.box

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BoxSettingsHelpDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), 0, context.dp(20), context.dp(12))
        }
        content.addView(TextView(context).apply {
            text = "点击参数名称展开说明。示例使用默认值，不代表你当前填写的数值。\n\n箱体上沿取区间最高价，下沿取最低价；箱高＝上沿－下沿。先判断是否形成箱体，再检查最近五个已完成交易日是否收盘突破。"
            textSize = 14f
            setLineSpacing(context.dp(3).toFloat(), 1f)
        })
        val expanded = savedInstanceState?.getIntegerArrayList("expanded").orEmpty()
        HELP.forEachIndexed { index, (title, description) ->
            val body = TextView(context).apply {
                tag = index
                text = description
                textSize = 14f
                setTextIsSelectable(true)
                setLineSpacing(context.dp(4).toFloat(), 1f)
                setPadding(0, context.dp(4), 0, context.dp(16))
                visibility = if (index in expanded) View.VISIBLE else View.GONE
            }
            val button = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "${if (index in expanded) "−" else "+"} ${index + 1}. $title"
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isAllCaps = false
                setOnClickListener {
                    val show = body.visibility != View.VISIBLE
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    text = "${if (show) "−" else "+"} ${index + 1}. $title"
                }
            }
            content.addView(button, LinearLayout.LayoutParams(-1, -2))
            content.addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(TextView(context).apply {
            text = "生效方式\n保存设置后，开始新扫描或点击“重新扫描”才会应用。继续旧进度时使用当次保存的参数，详情与筛选也沿用它们。\n\n这些阈值是可调整的经验筛选条件。放宽条件不代表结果更准确；调整某一项后，仍须满足其他箱体与突破条件。"
            textSize = 14f
            setLineSpacing(context.dp(3).toFloat(), 1f)
            setPadding(0, context.dp(12), 0, 0)
        })
        val scroll = ScrollView(context).apply {
            id = 4201
            addView(content)
        }
        helpContent = content
        val container = LinearLayout(context).apply {
            addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.65).toInt()))
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle("参数详细说明")
            .setView(container)
            .setPositiveButton("知道了", null)
            .create()
    }

    private var helpContent: LinearLayout? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val expanded = arrayListOf<Int>()
        helpContent?.let { parent ->
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.tag is Int && child.visibility == View.VISIBLE) expanded += child.tag as Int
            }
        }
        outState.putIntegerArrayList("expanded", expanded)
    }

    override fun onDestroyView() {
        helpContent = null
        super.onDestroyView()
    }

    companion object {
        private val HELP = listOf(
            "整理周期（交易日）" to "含义：对每个候选突破日，向前取指定数量的日 K 线作为整理区间，不含突破当天。\n\n默认：60、40、30、20 日；允许 10～250 的不重复整数，用逗号分隔。程序先检查最近的候选日，同一天多个周期都符合时优先最长周期。\n\n例子：填写 20,40，会分别检查突破前 20 根和 40 根日线。\n\n调整影响：短周期能识别较短的整理；长周期涵盖更多历史，高低点范围也可能更宽。增加周期会增加候选形态，但每只股票仍只保留一条结果。数据不足的周期会跳过。",
            "最大箱体宽度（%）" to "计算：（上沿－下沿）÷ 下沿 × 100%。宽度小于或等于设置值才通过，最高最低价包含上下影线。\n\n默认：15%；范围：0.1～200%。\n\n例子：下沿 10 元、上沿 11.5 元，宽度为 15%，设置为 15 时可以通过这一项。\n\n调大：允许更宽的震荡区间，可能纳入波动较大的股票。调小：更偏向窄幅整理，可能漏掉较宽的箱体。\n\n注意：15% 是经验阈值，并非箱体成立的普遍标准；一根极端影线也可能明显放大宽度。",
            "最小突破幅度（%）" to "计算：（突破日收盘价÷箱体上沿－1）×100%。必须严格大于设置值。比较的是收盘价与箱体上沿，不是当天相对昨收的涨幅。\n\n默认：1%；范围：0～30%。\n\n例子：上沿 10 元、设置 1%，收盘价必须高于 10.10 元；正好 10.10 元不通过。盘中最高价冲到 10.50 元但收盘只有 10 元，也不通过。\n\n调大：要求收盘离开上沿更远，可能漏掉刚突破的形态。调小：更容易捕捉轻微越过上沿的形态，也更容易纳入短暂越界。",
            "拟合趋势总偏移 / 箱高（%）" to "含义：用区间内每日收盘价拟合一条直线，计算直线从区间开始到结束的价格变化绝对值，再除以箱高。该比例不得超过本项。上涨和下跌趋势同样受限。\n\n默认：35%；范围：0～200%。\n\n例子：箱高 2 元，设置 35%，允许的拟合直线总变化最多为 0.70 元；拟合上升 0.80 元或下降 0.80 元都不通过。\n\n调大：允许更明显的方向性趋势，斜向通道更可能被纳入。调小：更强调横盘；设置 0 时只有拟合总变化为零才通过。它衡量整个区间的趋势，不是仅比较首尾两天。",
            "前后段均价差 / 箱高（%）" to "计算：分别取区间前、后各三分之一日线的收盘均价，计算两者差的绝对值÷箱高×100%。每段日数向下取整，中间部分不参与这一项。\n\n默认：35%；范围：0～200%。\n\n例子：20 日区间取最前 6 日和最后 6 日。若箱高 1 元，两段均价分别为 10.20、10.60 元，差为箱高的 40%，设置 35% 时不通过。\n\n调大：允许前后价格中心有更大位移。调小：要求前后均价更接近。此项与拟合趋势限制必须同时满足，用于从不同角度约束横盘结构。",
            "成交量基准周期（交易日）" to "计算：取突破日之前指定数量日线的成交量平均值作为基准，不包含突破当天。放量比＝突破当天成交量÷该均量。\n\n默认：20 日；范围：5～250 日。\n\n例子：此前 20 日平均成交量为 100 万，突破日为 160 万，放量比为 1.60 倍。基准均量为零时，不生成可用的放量比。\n\n调大：基准包含更长时间，通常对近期变化反应较慢。调小：更贴近近期成交量。倍数变大还是变小取决于实际数据，并非周期越长就越容易放量。周期过长且历史不足时，该股可能无法完成检测。",
            "放量倍数" to "含义：放量比大于或等于此值时，标记为满足放量条件。用于列表的“放量”筛选和突破详情展示，不是基础箱体突破的必选条件。\n\n默认：1.5 倍；范围：0.1～20 倍。\n\n例子：基准均量 100 万，设置 1.5，突破日成交量达到 150 万就满足。若只有 120 万，只要其他条件满足，仍可出现在未开启放量筛选的突破列表中。\n\n调大：放量筛选更严格。调小：更多结果满足标记；小于 1 时，低于基准均量的交易日也可能满足该条件。"
        )
    }
}
