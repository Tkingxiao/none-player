package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.SubtitleAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R4 / A12 独立验收（播放侧）：media3 Cue → kernel SubtitleCue 的映射算式。
 *
 * 只钉验收口径（任务卡第 3 条「字幕 Cue 对齐映射纯逻辑」），不依赖实现方的测试：
 * 1. Cue 自带对齐（start/end）映射为 LEFT/RIGHT/CENTER，缺省居中；
 * 2. Cue 的比例值必须钳进 0..1，缺省交给渲染层兜底；
 * 3. Cue 的带符号颜色必须清符号扩展成无符号 ARGB；
 * 4. 位图 Cue 的归一化摆放：高度比例、按宽高比推宽度、锚点/基线换算，并钳进画面。
 */
class R4CueMappingAcceptanceTest {

    @Test
    fun cueAlignmentMapsStartAndEndFlags() {
        assertEquals("start 对齐 → LEFT", SubtitleAlignment.LEFT, npCueAlignment(isStartAligned = true, isEndAligned = false))
        assertEquals("end 对齐 → RIGHT", SubtitleAlignment.RIGHT, npCueAlignment(isStartAligned = false, isEndAligned = true))
        assertEquals("两者都不给 → 居中", SubtitleAlignment.CENTER, npCueAlignment(isStartAligned = false, isEndAligned = false))
        assertEquals(
            "两者都给时 start 优先（与抽取前 inline 行为一致）",
            SubtitleAlignment.LEFT,
            npCueAlignment(isStartAligned = true, isEndAligned = true),
        )
    }

    @Test
    fun cueFractionsAreClampedIntoUnitRange() {
        assertEquals(0f, npCueFraction(-0.2f))
        assertEquals(1f, npCueFraction(1.5f))
        assertEquals(0.4f, npCueFraction(0.4f))
        assertNull("Cue 没给比例时必须保留 null，交给渲染层按用户设置兜底", npCueFraction(null))
    }

    @Test
    fun cueColorDropsSignExtension() {
        // 0xFF112233 作为 Int 是负数，映射到 Long 后必须是 0x00000000FF112233。
        assertEquals(0xFF112233L, npCueColorArgb(0xFF112233.toInt()))
        assertEquals(0x00FFFFFFL, npCueColorArgb(0x00FFFFFF))
        assertNull("Cue 没给颜色时不硬编码白色", npCueColorArgb(null))
    }

    @Test
    fun bitmapPlacementUsesHeightFractionAndAspectRatio() {
        // 1:2 竖图，占屏幕高度一半 → 宽 0.25，水平居中偏左 0.375，底边贴底 → top = 1 - 0.5。
        val tall = npCuePlacement(
            bitmapWidthPx = 100,
            bitmapHeightPx = 200,
            heightFraction = 0.5f,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(0.375f, tall.left, 1e-4f)
        assertEquals(0.5f, tall.top, 1e-4f)
        assertEquals(0.25f, tall.width, 1e-4f)
        assertEquals(0.5f, tall.height, 1e-4f)

        // 2:1 横图、半屏高 → 推算宽度 1.0 封顶，水平居中即 left=0。
        val wide = npCuePlacement(
            bitmapWidthPx = 200,
            bitmapHeightPx = 100,
            heightFraction = 0.5f,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(0f, wide.left, 1e-4f)
        assertEquals(1f, wide.width, 1e-4f)
        assertEquals(0.5f, wide.top, 1e-4f)
    }

    @Test
    fun bitmapPlacementClampsAnchorsInsideStage() {
        // 锚点在最左 + 底边在画面顶：left/top 都必须钳到 0，不能露出画面外。
        val edge = npCuePlacement(
            bitmapWidthPx = 100,
            bitmapHeightPx = 100,
            heightFraction = 0.5f,
            anchorX = 0f,
            baselineY = 0f,
        )
        assertEquals("锚点越界时 left 钳到 0", 0f, edge.left, 1e-4f)
        assertEquals("基线越界时 top 钳到 0", 0f, edge.top, 1e-4f)
        assertEquals(0.5f, edge.width, 1e-4f)

        // 自定义锚点/基线：0.25 中心、0.75 底边。
        val custom = npCuePlacement(
            bitmapWidthPx = 100,
            bitmapHeightPx = 100,
            heightFraction = 0.5f,
            anchorX = 0.25f,
            baselineY = 0.75f,
        )
        assertEquals(0.0f, custom.left, 1e-4f)
        assertEquals(0.25f, custom.top, 1e-4f)
    }

    @Test
    fun invalidHeightFractionFallsBackToFullStage() {
        val zero = npCuePlacement(100, 200, heightFraction = 0f, anchorX = null, baselineY = null)
        assertEquals("0 高度比例是非法值，必须回落整屏高", 1f, zero.height, 1e-4f)
        assertEquals(0f, zero.top, 1e-4f)

        val negative = npCuePlacement(100, 200, heightFraction = -0.5f, anchorX = null, baselineY = null)
        assertEquals("负高度比例同样回落整屏高", 1f, negative.height, 1e-4f)

        val tooLarge = npCuePlacement(100, 200, heightFraction = 1.5f, anchorX = null, baselineY = null)
        assertEquals("超过 1 的高度比例回落整屏高", 1f, tooLarge.height, 1e-4f)
    }

    @Test
    fun zeroBitmapHeightDoesNotDivideByZero() {
        val placement = npCuePlacement(
            bitmapWidthPx = 100,
            bitmapHeightPx = 0,
            heightFraction = 0.5f,
            anchorX = null,
            baselineY = null,
        )
        assertEquals("解码器给 0 高度时按 1:1 兜底宽度", 0.5f, placement.width, 1e-4f)
        assertEquals(0.5f, placement.height, 1e-4f)
        assertEquals(0.25f, placement.left, 1e-4f)
        assertEquals(0.5f, placement.top, 1e-4f)
    }
}
