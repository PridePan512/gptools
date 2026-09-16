package com.example.gptest.business

data class BoxScanConfig(
    val windows: List<Int> = listOf(60, 40, 30, 20),
    val maxWidthPercent: Double = 15.0,
    val breakoutPercent: Double = 1.0,
    val slopeWidthPercent: Double = 35.0,
    val meanWidthPercent: Double = 35.0,
    val volumeDays: Int = 20,
    val volumeRatio: Double = 1.5
) {
    init {
        require(windows.isNotEmpty() && windows.distinct().size == windows.size && windows.all { it in 10..250 }) { "整理周期须为 10～250 的不重复整数" }
        require(maxWidthPercent.isFinite() && maxWidthPercent in 0.1..200.0) { "箱体宽度须为 0.1～200%" }
        require(breakoutPercent.isFinite() && breakoutPercent in 0.0..30.0) { "突破幅度须为 0～30%" }
        require(slopeWidthPercent.isFinite() && slopeWidthPercent in 0.0..200.0 &&
            meanWidthPercent.isFinite() && meanWidthPercent in 0.0..200.0) { "趋势偏移须为 0～200%" }
        require(volumeDays in 5..250) { "均量周期须为 5～250 日" }
        require(volumeRatio.isFinite() && volumeRatio in 0.1..20.0) { "放量倍数须为 0.1～20" }
    }
    val minimumBars get() = maxOf(windows.min(), volumeDays) + 5
    val historyCount get() = maxOf(100, maxOf(windows.max(), volumeDays) + 10)
}
