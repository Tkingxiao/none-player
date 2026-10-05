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
        /** 用户字号倍率 */
        val fontScale: Float,
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
     * [measure] 由调用方提供文字宽度测量（滚动模式用于出生点右移与越界判定）；
     * [laneHeightPx] = 单条弹幕占用的行高（字号 × 1.4）。
     */
    fun plan(
        danmaku: List<Danmaku>,
        displayPosMs: Long,
        canvasWidth: Float,
        canvasHeight: Float,
        config: Config,
        laneHeightPx: Float,
        measure: (Danmaku, Float) -> Float,
    ): List<Placed> {
        if (danmaku.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
        val layerHeight = canvasHeight * config.areaFraction

        // 窗口起点：滚动弹幕最长的存活时间决定回看距离
        val scrollSpan = (BASE_SCROLL_SPAN_MS / config.speed).toLong()
        val windowStart = displayPosMs - scrollSpan
        val window = danmaku.asSequence()
            .filter { it.timeMs in windowStart..displayPosMs }
            .filter {
                val span = when (it.mode) {
                    DanmakuMode.Scroll -> scrollSpan
                    DanmakuMode.Top, DanmakuMode.Bottom -> HOLD_SPAN_MS
                }
                displayPosMs < it.timeMs + span
            }
            .toList()
            .let { if (config.maxOnScreen > 0) it.takeLast(config.maxOnScreen) else it }
        if (window.isEmpty()) return emptyList()

        val baseTextPx = (laneHeightPx / 1.4f) * config.fontScale
        val lanesByMode = mapOf(
            DanmakuMode.Scroll to maxOf(1, floorDiv(layerHeight, laneHeightPx)),
            DanmakuMode.Top to maxOf(1, floorDiv(layerHeight, laneHeightPx)),
            DanmakuMode.Bottom to maxOf(1, floorDiv(layerHeight, laneHeightPx)),
        )
        // 各模式的序号计数器：同模式内顺序编号 → 取模散轨
        val counters = mutableMapOf<DanmakuMode, Int>()

        return buildList {
            window.forEach { d ->
                val lanes = lanesByMode.getValue(d.mode)
                val i = counters.merge(d.mode, 1, Int::plus)!! - 1
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
}
