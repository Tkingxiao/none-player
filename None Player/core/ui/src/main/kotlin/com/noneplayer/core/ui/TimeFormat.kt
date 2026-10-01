package com.noneplayer.core.ui

/**
 * 播放时间戳：h:mm:ss / mm:ss（与 iOS 时长展示习惯一致）。
 * 未知时长显示 0:00。
 */
fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
