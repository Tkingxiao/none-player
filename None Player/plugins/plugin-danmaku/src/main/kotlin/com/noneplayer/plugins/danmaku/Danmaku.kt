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
