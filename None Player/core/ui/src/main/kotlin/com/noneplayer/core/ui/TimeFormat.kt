package com.noneplayer.core.ui

/**
 * 播放时间戳：h:mm:ss / mm:ss（与 iOS 时长展示习惯一致）。
 * 未知时长显示 0:00。
 *
 * 手写补零而不是 `"%d:%02d".format(...)`（E121）：本函数在**每次列表行绑定**与
 * **每次进度上报**上都会被叫到，而 `String.format` 每次都要新建一个 `Formatter`、
 * 解析一遍格式串、再装箱三个参数——它是这条链上唯一"每 tick 必然分配"的东西。
 * 行为与 `%02d` 逐字符等价（见 `TimeFormatTest`）。
 */
fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

private fun pad2(v: Long): String = if (v < 10) "0$v" else v.toString()

/**
 * 文件大小展示：GB / MB / KB 自适应；未知（≤0）为空串。
 * 供媒体卡副标题、播放页信息行等"元数据行"统一使用。
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

/**
 * 网格卡 meta 的日期（设计稿 `v.date`）：今天 / 昨天 / M月d日 / yyyy年M月d日。
 *
 * 用相对日期而不是完整时间戳：卡片 meta 是"扫一眼"的信息位，
 * 「2026/10/3 14:22」这类完整格式会把行撑爆且读不出"新旧"。
 */
fun formatRelativeDate(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return ""
    val zone = java.util.TimeZone.getDefault().toZoneId()
    val date = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}
