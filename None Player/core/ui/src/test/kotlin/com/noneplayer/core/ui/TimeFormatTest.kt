package com.noneplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `formatTimestamp` 手写补零必须与它取代的 `"%d:%02d(:%02d)"` **逐字符一致**（E121）。
 *
 * 换实现的唯一理由是分配：`String.format` 每次新建 Formatter + 解析格式串 + 装箱，
 * 而本函数在每次列表行绑定与每次进度上报上都会被叫到。分配省了，字符串不能变。
 * 所以这里把旧实现原样留在测试里当对照，而不是把新实现的输出写死成期望值。
 */
class TimeFormatTest {

    /** 被替换掉的旧实现——对照基准，故意保留 `String.format`。 */
    private fun legacy(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    @Test
    fun `边界样本与旧实现逐字符一致`() {
        val samples = longArrayOf(
            Long.MIN_VALUE, -1000, 0, 1, 999, 1_000, 9_999, 10_000, 59_000, 59_999,
            60_000, 61_000, 599_000, 600_000, 601_000, 3_599_000, 3_600_000,
            3_609_000, 3_660_000, 10 * 3_600_000 + 5 * 60_000 + 9_000,
            86_399_000, 86_400_000, 86_409_000,
        )
        for (ms in samples) {
            assertEquals("ms=$ms", legacy(ms), formatTimestamp(ms))
        }
    }

    @Test
    fun `逐秒扫描两小时都不差一个字符`() {
        for (sec in 0..7_200) {
            val ms = sec * 1_000L
            assertEquals("sec=$sec", legacy(ms), formatTimestamp(ms))
        }
    }

    @Test
    fun `秒位始终两位、分位不补零`() {
        // 这是 mm:ss 的读法本身：0:05 而不是 0:5，也不是 00:05
        assertEquals("0:00", formatTimestamp(0))
        assertEquals("0:05", formatTimestamp(5_000))
        assertEquals("12:03", formatTimestamp(12 * 60_000L + 3_000))
        assertEquals("1:02:03", formatTimestamp(3_600_000L + 2 * 60_000L + 3_000))
    }
}
