package com.noneplayer.core.media

/**
 * A12 第一期「特效字幕诚实提示」：字幕文件里**当前渲染层做不到**的 ASS/SSA 覆盖标签。
 *
 * 为什么要自己扫：media3 的 `SsaParser` 在产出 Cue 之前就调用
 * `SsaStyle.Overrides.stripStyleOverrides`，把 `{\pos(...)}`、`{\k20}`、`{\frz15}`
 * 这类覆盖块整段剥掉（media3-extractor 1.11.1 `SsaParser.java:358-364`），Cue 里
 * 事后看不出任何特效痕迹。因此只能在**原始字幕文件文本**上检测。
 *
 * 标签只表示「这个文件用到了这类特效」，不代表每条对白都用；提示语按任务口径统一
 * 是「此字幕含特效，当前只显示文本」，不做逐条判断。
 */
enum class SubtitleEffectTag(val label: String) {
    /** `\pos`：绝对定位（第一期已能把静态位置带出去，但 ASS 排版整体仍不支持） */
    POSITION("定位"),

    /** `\move`：移动轨迹 */
    MOVE("移动"),

    /** `\org`：旋转原点 */
    ORG("原点"),

    /** `\fr` / `\frx` / `\fry` / `\frz`：旋转 */
    ROTATION("旋转"),

    /** `\fscx` / `\fscy` / `\fax` / `\fay`：缩放与形变 */
    SCALE("缩放"),

    /** `\k` / `\kf` / `\ko`：卡拉OK逐字填色 */
    KARAOKE("卡拉OK"),

    /** `\t`：动画过渡 */
    TRANSFORM("动画"),

    /** `\fad` / `\fade`：淡入淡出 */
    FADE("淡入淡出"),

    /** `\clip` / `\iclip`：裁剪区域 */
    CLIP("裁剪"),

    /** `\blur` / `\be`：模糊与边缘虚化 */
    BLUR("模糊"),

    /** `\p` / `\pbo`：矢量绘图 */
    DRAWING("矢量绘图"),
}

/**
 * 一次检测的结果。
 *
 * [tags] 按 [SubtitleEffectTag] 声明顺序去重排列（同一类特效只出现一次），便于日志
 * 与提示稳定可断言；[dialogueLines] 是扫描到的 `Dialogue:` 行数（排查用，不参与提示）。
 */
data class SubtitleEffectReport(
    val tags: List<SubtitleEffectTag>,
    val dialogueLines: Int,
) {
    val hasEffects: Boolean get() = tags.isNotEmpty()

    companion object {
        val NONE = SubtitleEffectReport(emptyList(), 0)
    }
}

/**
 * 原始字幕文本里的特效标签检测（纯 JVM，无 Android 依赖）。
 *
 * 口径（与第一批验收边界一致）：
 * - 只认 `Dialogue:` 行——`Style:` / `Format:` / `Comment:` 行里出现同样字样不算；
 * - 只在 `{...}` 覆盖块内识别标签：标签名取反斜杠之后的连续字母
 *   （`\pos(`→`pos`、`\p1`→`p`、`\k20`→`k`）；
 * - 覆盖块外的 `\N`/`\n`/`\h` 是换行/空格转义，不是特效；
 * - 容错优先：空文本、未闭合花括号、CRLF、乱码一律不抛异常，能认多少认多少
 *   （提示是加分项，绝不能因为字幕解析异常把播放页搞崩）。
 */
object SubtitleEffectDetector {

    private const val DIALOGUE_PREFIX = "dialogue:"

    fun detect(rawText: String?): SubtitleEffectReport {
        if (rawText.isNullOrEmpty()) return SubtitleEffectReport.NONE
        val found = LinkedHashSet<SubtitleEffectTag>()
        var dialogueLines = 0
        rawText.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (!trimmed.regionMatches(
                    thisOffset = 0,
                    other = DIALOGUE_PREFIX,
                    otherOffset = 0,
                    length = DIALOGUE_PREFIX.length,
                    ignoreCase = true,
                )
            ) {
                return@forEach
            }
            dialogueLines++
            scanOverrideBlocks(trimmed, found)
        }
        if (found.isEmpty()) return SubtitleEffectReport(emptyList(), dialogueLines)
        return SubtitleEffectReport(found.sortedBy { it.ordinal }, dialogueLines)
    }

    /** 扫一行里所有 `{...}` 覆盖块；未闭合的块扫到行尾为止（不抛错）。 */
    private fun scanOverrideBlocks(line: String, out: MutableSet<SubtitleEffectTag>) {
        var cursor = DIALOGUE_PREFIX.length
        while (cursor < line.length) {
            val open = line.indexOf('{', cursor)
            if (open < 0) return
            val close = line.indexOf('}', open + 1)
            val end = if (close < 0) line.length else close
            scanOverrideBlock(line, open + 1, end, out)
            if (close < 0) return
            cursor = close + 1
        }
    }

    private fun scanOverrideBlock(
        line: String,
        from: Int,
        to: Int,
        out: MutableSet<SubtitleEffectTag>,
    ) {
        var i = from
        while (i < to) {
            if (line[i] != '\\') {
                i++
                continue
            }
            var nameStart = i + 1
            // ASS 颜色覆盖 `\1c&H00FF00&` 的 `1`/`2`/`3`/`4` 是索引不是标签名，跳过数字前缀
            while (nameStart < to && line[nameStart].isDigit()) nameStart++
            var nameEnd = nameStart
            while (nameEnd < to && line[nameEnd].isLetter()) nameEnd++
            if (nameEnd > nameStart) {
                tagOf(line.substring(nameStart, nameEnd).lowercase())?.let(out::add)
                i = nameEnd
            } else {
                i = nameStart
            }
        }
    }

    private fun tagOf(name: String): SubtitleEffectTag? = when (name) {
        "pos" -> SubtitleEffectTag.POSITION
        "move" -> SubtitleEffectTag.MOVE
        "org" -> SubtitleEffectTag.ORG
        "fr", "frx", "fry", "frz" -> SubtitleEffectTag.ROTATION
        "fscx", "fscy", "fax", "fay" -> SubtitleEffectTag.SCALE
        "k", "kf", "ko" -> SubtitleEffectTag.KARAOKE
        "t" -> SubtitleEffectTag.TRANSFORM
        "fad", "fade" -> SubtitleEffectTag.FADE
        "clip", "iclip" -> SubtitleEffectTag.CLIP
        "blur", "be" -> SubtitleEffectTag.BLUR
        "p", "pbo" -> SubtitleEffectTag.DRAWING
        else -> null
    }
}
