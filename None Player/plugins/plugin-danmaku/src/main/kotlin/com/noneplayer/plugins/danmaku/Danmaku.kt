package com.noneplayer.plugins.danmaku

/**
 * 弹幕在屏幕上的行为。批次 6 规格（审核文档 §13 批次 6 表）只渲染三模式：
 * 滚动 / 顶部 / 底部。B 站的 mode 6（逆向滚动）、7（高级）、8（代码）、9（BAS）
 * 无法用这三类表达，解析时**丢弃**而不是降级——高级弹幕降级成普通滚动会
 * 出现完全不对位的假内容，比没有更糟。
 */
enum class DanmakuMode {
    Scroll,
    Top,
    Bottom,
}

/** 一条弹幕——三种来源格式（B 站 XML / 转换器 JSON / ASS）统一后的模型 */
data class Danmaku(
    /** 相对播放起点的时间（毫秒），来源格式均按秒给出，这里统一换算 */
    val timeMs: Long,
    val mode: DanmakuMode,
    /**
     * 相对字号：1.0 = 标准字号（B 站 fontSize 25）。
     * 渲染层用它 × 用户的「字号」设置档，来源里的大小差保留。
     */
    val fontScale: Float,
    /** 文字颜色（ARGB；alpha 恒 FF，透明度由用户设置统一控制） */
    val colorArgb: Int,
    val text: String,
)

/** 弹幕文件格式——决定用哪个解析器 */
enum class DanmakuFormat(val extension: String) {
    BilibiliXml("xml"),
    ConverterJson("json"),
    Ass("ass"),
    ;

    companion object {
        /** 按扩展名取格式；未知扩展名返回 null（不猜内容，见 [parse] 的 sniff 兜底） */
        fun fromFileName(name: String): DanmakuFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}

/**
 * 本地屏蔽词匹配（批次 6 设置项）：子串命中即屏蔽，忽略大小写。
 * 空词表恒不屏蔽；词的取舍完全交给用户（单字词也照办——那是用户的显式选择）。
 */
object DanmakuBlocklist {
    fun isBlocked(text: String, words: List<String>): Boolean =
        words.any { text.contains(it, ignoreCase = true) }
}

/** 统一入口：按格式解析 + 屏蔽词过滤 + 时间升序 */
fun parseDanmakuFile(content: String, format: DanmakuFormat, blocklist: List<String>): List<Danmaku> =
    DanmakuParsers.parse(content, format)
        .filter { !DanmakuBlocklist.isBlocked(it.text, blocklist) }

/**
 * E223：弹幕文件的读取上限（16MB）。弹幕文本文件正常在 KB–MB 量级；被误标成
 * 弹幕扩展名的大文件（视频/整季合集）按旧的全量 `readBytes()` 会把整个文件
 * 读进内存再解析——OOM 面。超限不解析（返回 [Oversize] 哨兵），调用方跳过
 * 该候选并给出提示。
 */
const val MAX_DANMAKU_BYTES = 16L * 1024 * 1024

/** 读取结局：[Content] = 字节（≤上限，编码由调用方 decodeText 判定），[Oversize] = 超限 */
sealed interface BoundedRead {
    data class Content(val bytes: ByteArray) : BoundedRead
    object Oversize : BoundedRead
}

/**
 * 有界读取：最多读 [MAX_DANMAKU_BYTES] 字节——多读 1 字节用于区分
 * 「恰好等于上限」与「超限」，不整段 buffer 进内存。编码判定（UTF-8/GBK）
 * 留给调用方的 decodeText，这里只管字节数。
 */
fun readBoundedBytes(stream: java.io.InputStream, cap: Long = MAX_DANMAKU_BYTES): BoundedRead {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > cap) return BoundedRead.Oversize
        out.write(buf, 0, n)
    }
    return BoundedRead.Content(out.toByteArray())
}

/**
 * E223：内嵌弹幕 spawned 列表的重建规则（倒退 seek 重建 + 时间升序不变量）。
 *
 * 内嵌通路「新 cue → 立即 spawn 一条滚动弹幕」：**倒退 seek** 后旧 spawned
 * 的显示位置全部失效（它们的 timeMs 在未来），且 cue 会重新出现造成重复
 * spawn——检测到显著倒退就整表清空，等当前 cue 重新 spawn。
 *
 * @param backwardSeek 检测器判定本次 position 变化是倒退（新 position 比
 *   上次小超过阈值——抖动/同位置刷新不算）
 */
fun nextSpawnedOnCue(
    current: List<Danmaku>,
    spawned: Danmaku?,
    backwardSeek: Boolean,
): List<Danmaku> {
    val base = if (backwardSeek) emptyList() else current
    if (spawned == null) return base
    // 时间升序不变量：新 spawn 的 timeMs（= 显示位置）不早于列表尾
    return if (base.isEmpty() || (base.last().timeMs <= spawned.timeMs)) {
        (base + spawned).takeLast(500)
    } else {
        (base + spawned).sortedBy { it.timeMs }.takeLast(500)
    }
}

/** E223：倒退 seek 判定——比上次 position 小超过阈值（毫秒）才算（普通抖动/同位刷新不算） */
fun isBackwardSeek(newPositionMs: Long, lastPositionMs: Long, thresholdMs: Long = 1_500L): Boolean =
    newPositionMs < lastPositionMs - thresholdMs

/** E224：内嵌 cue 的 spawn 决策（屏蔽词与文件通路同口径） */
sealed interface EmbeddedCueDecision {
    /** 生成弹幕 */
    object Spawn : EmbeddedCueDecision

    /** 与上一条同文本（不重复生成，也不把空文本记成 lastText） */
    object SkipDuplicate : EmbeddedCueDecision

    /** 命中屏蔽词（记 lastText：同一条屏蔽文本不重复过筛） */
    object SkipBlocked : EmbeddedCueDecision
}

/**
 * E224：内嵌 cue 的 spawn 决策。屏蔽词此前只过滤**文件**通路
 * （[parseDanmakuFile]），内嵌 cue 直接 spawn 绕过了它——同一份屏蔽词对
 * 两条通路必须同口径。空文本视为「无 cue」：跳过且不更新 lastText。
 */
fun embeddedCueDecision(text: String, lastText: String?, blocklist: List<String>): EmbeddedCueDecision =
    when {
        text.isEmpty() || text == lastText -> EmbeddedCueDecision.SkipDuplicate
        DanmakuBlocklist.isBlocked(text, blocklist) -> EmbeddedCueDecision.SkipBlocked
        else -> EmbeddedCueDecision.Spawn
    }
