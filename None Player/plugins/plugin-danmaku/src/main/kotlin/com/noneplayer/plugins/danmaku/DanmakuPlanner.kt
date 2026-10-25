package com.noneplayer.plugins.danmaku

import kotlin.math.ceil

/**
 * 弹幕轨道分配（批次 6 渲染任务①）——**纯函数**，无 Android/Compose 依赖。
 *
 * 输入"当前应可见"的弹幕窗口与画布参数，输出每条的摆放（轨道号 + 像素坐标）。
 * 设计取的是**确定性分配**：轨道号 = 弹幕序号 mod 轨道数，不维护跨帧的
 * "这条占着哪条道"状态——状态式分配要为暂停/seek/回放做一整套失效处理，
 * 而序号取模在时间相邻的弹幕上天然散开，配合密度上限后同轨碰撞概率
 * 与状态式方案肉眼不可分（ECHO 桌面端同款取舍）。
 *
 * 所有时间单位 ms，坐标单位 px（与 [DanmakuOverlay] 的 Canvas 同尺度）。
 */
object DanmakuPlanner {

    /** 滚动弹幕横穿屏幕的基础时长（1.0× 速度）；B 站手感约 8s */
    const val BASE_SCROLL_SPAN_MS = 8_000L

    /** 顶/底弹幕驻留时长 */
    const val HOLD_SPAN_MS = 4_000L

    /** 布局参数（由 Overlay 从用户设置换算而来，纯数据便于测试） */
    data class Config(
        /** 弹幕层高度 = 画布高 × 此系数（显示区域设置） */
        val areaFraction: Float,
        /** 滚动速度倍率 */
        val speed: Float,
        /** 同屏密度上限，0 = 不限 */
        val maxOnScreen: Int,
    )

    /** 一条已定摆位的弹幕 */
    data class Placed(
        val danmaku: Danmaku,
        val textPx: Float,
        /** 轨道号（从 0 起，滚动/顶/底各自独立编号） */
        val lane: Int,
        /** 滚动模式：文字左缘的 x；顶/底模式：水平居中由绘制方处理，此值为 -1 */
        val x: Float,
        /** 文字基线 y（绘制方按 paint 度量修正为居中） */
        val y: Float,
    )

    /**
     * 对排序后的 [danmaku]（时间升序）做窗口截取与摆位。
     *
     * 窗口截取走**二分**（依赖 timeMs 升序契约）：本函数是逐帧热路径，
     * 全表线性扫描在数万条的弹幕文件上会按刷新率放大成毫秒级积压。
     *
     * [measure] 由调用方提供文字宽度测量（滚动模式用于出生点右移与越界判定）；
     * [laneHeightPx] = 单条弹幕占用的行高（字号 × 1.4）。
     *
     * [laneOrdinals]（E225）：与 [danmaku] 平行的**文件内稳定轨道序号**
     * （按模式分别计数，见 [stableLaneOrdinals]）——车道号从它取模，弹幕
     * 在整个存活期车道不变。缺省 null 时回退到窗口内到达序号取模：窗口
     * 每滑一帧、前面任何一条出窗，后面所有弹幕的序号整体前移，**弹幕会
     * 在飞行途中集体换道**（y 瞬移）；有文件序号的文件源恒传，内嵌
     * spawned（无稳定文件序）回退。
     */
    fun plan(
        danmaku: List<Danmaku>,
        displayPosMs: Long,
        canvasWidth: Float,
        canvasHeight: Float,
        config: Config,
        laneHeightPx: Float,
        measure: (Danmaku, Float) -> Float,
        laneOrdinals: List<Int>? = null,
    ): List<Placed> {
        if (danmaku.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
        val layerHeight = canvasHeight * config.areaFraction

        // 窗口起点：滚动弹幕最长的存活时间决定回看距离。
        // 二分定位 [windowStart, displayPosMs] 两端，逐帧不再全表扫描；
        // 各模式的驻留时长差异（顶/底 4s < 滚动 8s）仍逐条收紧，语义不变
        val scrollSpan = (BASE_SCROLL_SPAN_MS / config.speed).toLong()
        val fromIdx = lowerBound(danmaku, displayPosMs - scrollSpan)
        val toIdx = upperBound(danmaku, displayPosMs)
        if (fromIdx >= toIdx) return emptyList()
        val window = buildList {
            for (i in fromIdx until toIdx) {
                val d = danmaku[i]
                val span = when (d.mode) {
                    DanmakuMode.Scroll -> scrollSpan
                    DanmakuMode.Top, DanmakuMode.Bottom -> HOLD_SPAN_MS
                }
                // E225：窗口元素携带**全表下标**——车道号要从文件内稳定序号取
                if (displayPosMs < d.timeMs + span) add(i to d)
            }
        }.let { if (config.maxOnScreen > 0) it.takeLast(config.maxOnScreen) else it }
        if (window.isEmpty()) return emptyList()

        // 基准字号从行高反推：行高由 Overlay 按 `画面高 × 0.04 × 倍率 × 1.4` 给出，
        // 已含用户字号倍率。此前这里再乘一次 config.fontScale，字号被平方放大
        // 而轨道只乘一次——1.5× 档字高就超过行高了，静默允许时相邻轨道会重叠。
        val baseTextPx = laneHeightPx / 1.4f
        // 三种模式轨道数一致（同占层内等高轨道）；各模式独立顺序编号 → 取模散轨
        val lanes = maxOf(1, floorDiv(layerHeight, laneHeightPx))
        var scrollNo = 0
        var topNo = 0
        var bottomNo = 0

        return buildList {
            window.forEach { (globalIndex, d) ->
                // E225：车道号优先取文件内稳定序号（同模式计数）；缺省回退窗口内
                // 到达序号（内嵌 spawned 无稳定文件序，行为与旧版一致）
                val i = laneOrdinals?.getOrNull(globalIndex)
                    ?: when (d.mode) {
                        DanmakuMode.Scroll -> scrollNo++
                        DanmakuMode.Top -> topNo++
                        DanmakuMode.Bottom -> bottomNo++
                    }
                val lane = i % lanes
                val textPx = baseTextPx * d.fontScale
                val y = when (d.mode) {
                    // 轨道中心线（基线由绘制方按 fontMetrics 修正）
                    DanmakuMode.Scroll, DanmakuMode.Top -> lane * laneHeightPx + laneHeightPx / 2f
                    // 底部轨道自下而上堆叠
                    DanmakuMode.Bottom -> layerHeight - (lane * laneHeightPx + laneHeightPx / 2f)
                }
                val x = when (d.mode) {
                    DanmakuMode.Scroll -> {
                        val textW = measure(d, textPx)
                        val elapsed = (displayPosMs - d.timeMs).toFloat()
                        // 出生：文字右缘贴屏幕右缘 → 左缘 = W - textW；随时间左移，
                        // 移动总距离 = W + textW（完全出屏为止）
                        canvasWidth - textW - elapsed / scrollSpan * (canvasWidth + textW)
                    }
                    else -> -1f // 顶/底居中，由绘制方处理
                }
                add(Placed(d, textPx, lane, x, y))
            }
        }
    }

    private fun floorDiv(a: Float, b: Float): Int = ceil(a / b).toInt().coerceAtLeast(1)

    /** 升序列表中第一个 timeMs >= [target] 的下标（全部小于时返回 size） */
    private fun lowerBound(danmaku: List<Danmaku>, target: Long): Int {
        var lo = 0
        var hi = danmaku.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (danmaku[mid].timeMs < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 升序列表中第一个 timeMs > [target] 的下标（全部不大于时返回 size） */
    private fun upperBound(danmaku: List<Danmaku>, target: Long): Int {
        var lo = 0
        var hi = danmaku.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (danmaku[mid].timeMs <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
