package com.noneplayer.core.media

/**
 * 自然序比较器（§12.4 / E42）：标题里的人类编号按"数值"排，而不是按字符码排。
 *
 * 后果对比（E42 的验收恰好就是这一条）：字符序下 `E10` 排在 `E2` 前面——
 * 剧集列表、连播队列、"下一集"全都错位。这里处理四类常见形态：
 * - **数字段按数值**：`E2 < E10`、`1 < 2 < 10`；
 * - **前导零**：数值相等时位数少的在前（`1 < 01`），零字节序稳定；
 * - **`S01E02`**：等价于两段独立数字（S 段与 E 段各自按数值比）；
 * - **中文数字**：`第一集 < 第二集 < 第十集`（简写〇零一二三四五六七八九十百千两）。
 *
 * 媒体库排序与连播队列**必须用同一个比较器**（同一份用户可见顺序），
 * 所以它在 `core:media` 里，两侧都只是引用。
 *
 * 比较规则（从主到次）：
 * 1. 数字段/中文数字段按数值；
 * 2. 其余字符按小写序（大小写不敏感的主序）；
 * 3. 完全同前缀时短的在前。
 */
object NaturalOrder : Comparator<String> {

    private val CHINESE_NUMERALS = "〇零一二三四五六七八九十百千两"

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            // 数字段优先按数值比。阿拉伯与中文数字**可混排**（"第2集" vs "第十集"）——
            // 此前只在"两侧同为数制"时才进数值分支，混排会掉回字符序（'1' < '十'），
            // 同一部剧的集序就错位了。只有两侧都真读出数值才走这里，否则回退字符序。
            val runA = readNumericRun(a, i)
            val runB = readNumericRun(b, j)
            if (runA != null && runB != null) {
                val byValue = runA.value.compareTo(runB.value)
                if (byValue != 0) return byValue
                // 数值相等（1 vs 01）→ 位数少的在前，让 "1.mp4" 排在 "01.mp4" 前。
                // "前导零"只对阿拉伯数字成立，中文数字（十/十二）不比长度。
                if (runA.ascii && runB.ascii) {
                    val byWidth = runA.width.compareTo(runB.width)
                    if (byWidth != 0) return byWidth
                }
                i = runA.end
                j = runB.end
                continue
            }
            val la = ca.lowercaseChar()
            val lb = cb.lowercaseChar()
            if (la != lb) return la.compareTo(lb)
            i++
            j++
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun isChineseNumeral(c: Char): Boolean = c in CHINESE_NUMERALS

    private fun readAsciiNumber(s: String, start: Int): Pair<Long, Int> {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        val run = s.substring(start, end)
        // 超长数字串（时间戳一类）按"前 18 位"截断比较，避免 Long 溢出；
        // 位数相同才可能走到这里，数值级排序语义不受影响。
        val value = if (run.length <= 18) run.toLong() else run.take(18).toLong()
        return value to end
    }

    /** 从 [start] 起读出的一段数字：数值 + 结束下标 + 原始宽度 + 是否阿拉伯数字 */
    private class NumericRun(val value: Long, val end: Int, val width: Int, val ascii: Boolean)

    /**
     * 从 [start] 起读一段数字，读不出返回 null（调用方回退字符序，不猜）。
     * 阿拉伯数字直接取整串；中文数字交给 [parseChineseNumber]（简写解析不了就返回 null）。
     */
    private fun readNumericRun(s: String, start: Int): NumericRun? {
        val c = s[start]
        if (c.isDigit()) {
            val (value, end) = readAsciiNumber(s, start)
            return NumericRun(value, end, end - start, ascii = true)
        }
        if (!isChineseNumeral(c)) return null
        var end = start
        while (end < s.length && isChineseNumeral(s[end])) end++
        val value = parseChineseNumber(s.substring(start, end)) ?: return null
        return NumericRun(value, end, end - start, ascii = false)
    }

    /**
     * 中文数字解析（简版：支持「十/百/千」以内，形如 十二 / 二十 / 二十五 /
     * 一百零五 / 一千零一十）。解析不了返回 null（调用方回退字符序，不猜）。
     */
    fun parseChineseNumber(run: String): Long? {
        if (run.isEmpty()) return null
        var section = 0L
        var current = 0L
        var sawAny = false
        for (ch in run) {
            when (ch) {
                '〇', '零' -> current = 0L
                '一' -> current = 1L
                '二', '两' -> current = 2L
                '三' -> current = 3L
                '四' -> current = 4L
                '五' -> current = 5L
                '六' -> current = 6L
                '七' -> current = 7L
                '八' -> current = 8L
                '九' -> current = 9L
                '十' -> {
                    section += (if (current == 0L) 1L else current) * 10L
                    current = 0L
                }
                '百' -> {
                    section += (if (current == 0L) 1L else current) * 100L
                    current = 0L
                }
                '千' -> {
                    section += (if (current == 0L) 1L else current) * 1000L
                    current = 0L
                }
                else -> return null
            }
            sawAny = true
        }
        return if (sawAny) section + current else null
    }
}

/**
 * 媒体项的自然序（E42 三处同口径：媒体库排序 / 连播队列 / 详情页同文件夹列表）。
 *
 * 先比**展示标题**（用户看到什么就按什么排），并列时退化到**原文件名**的自然序——
 * 短标题相同是常态（"剧集 第2集"与"剧集 第10集"的展示标题都是"剧集"），
 * 这一步不能退化成字符序，否则 E10 又跑到 E2 前面（模拟器实测踩到过）。
 */
val MediaNaturalOrder: Comparator<MediaItem> = Comparator { a, b ->
    val byTitle = NaturalOrder.compare(a.cardTitle, b.cardTitle)
    if (byTitle != 0) byTitle else NaturalOrder.compare(a.title, b.title)
}
