package com.example.gptest.ui.box

import com.example.gptest.business.BoxScanConfig

internal fun boxRules(c: BoxScanConfig): String =
    "扫描范围：项目内置沪深京 A 股名单，非实时全市场名单。剔除基金、指数及 B 股。\n\n" +
    "最近五日：上证指数最近五个已完成交易日，15:10 前不使用当天日线；本次截止日期固定。\n\n" +
    "箱体：突破前 ${c.windows.sortedDescending().joinToString(" / ")} 根日线，上下沿为最高 / 最低价，宽度不超过下沿的 ${number(c.maxWidthPercent)}%。\n\n" +
    "横盘：拟合趋势总变化不超过箱高的 ${number(c.slopeWidthPercent)}%；前后各三分之一平均收盘价差不超过箱高的 ${number(c.meanWidthPercent)}%。\n\n" +
    "突破：收盘价超过箱体上沿 ${number(c.breakoutPercent)}%（严格大于）。突破日不参与箱体计算。优先最近日期，同日优先最长窗口，每股保留一条。\n\n" +
    "放量：成交量 ÷ 此前 ${c.volumeDays} 日均量 ≥ ${number(c.volumeRatio)} 倍，用于筛选与标记。仍在上方：截止日收盘高于原上沿。\n\n" +
    "历史不足 ${c.minimumBars} 根或截止日无数据会单独统计。采用前复权，无复权字段时使用原始日线并标注。\n\n" +
    "离开页面继续扫描，可在页面或通知栏暂停。每 10 只及结束时保存。继续扫描沿用本次参数和截止日期，新设置用于新扫描。"
