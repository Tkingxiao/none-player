package com.noneplayer.plugins.player.exo

import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.SubtitleAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A12 第一期：media3 `Cue` → kernel `SubtitleCue` 的映射纯函数（纯 JVM）。
 *
 * 这些算式原本内联在 `ExoVideoPlayer.toKernelCue()` 里，抽出来是为了能离线钉死
 * 「对齐 / 主色 / 位图归一化摆放」这三件第一批验收行为，且不依赖 Android。
 * 行为必须与抽取前逐位一致（缺省底部居中、非法 DIMEN 回落、0..1 钳位）。
 */
class SubtitleCueMappingTest {

    @Test
    fun alignmentPrefersStartThenEndThenCenter() {
        assertEquals(SubtitleAlignment.LEFT, npCueAlignment(isStartAligned = true, isEndAligned = false))
        assertEquals(SubtitleAlignment.RIGHT, npCueAlignment(isStartAligned = false, isEndAligned = true))
        assertEquals(SubtitleAlignment.CENTER, npCueAlignment(isStartAligned = false, isEndAligned = false))
        // 理论上 media3 不会同时给两个对齐标志；真出现时按原先 inline when 的顺序（start 先命中）
        assertEquals(SubtitleAlignment.LEFT, npCueAlignment(isStartAligned = true, isEndAligned = true))
    }

    @Test
    fun fractionIsClampedToUnitRangeAndNullStaysNull() {
        assertNull(npCueFraction(null))
        assertEquals(0.25f, npCueFraction(0.25f)!!, 1e-6f)
        assertEquals(0f, npCueFraction(-0.5f)!!, 1e-6f)
        assertEquals(1f, npCueFraction(1.5f)!!, 1e-6f)
    }

    @Test
    fun colorArgbKeepsAlphaAndUnsignedValue() {
        assertNull(npCueColorArgb(null))
        // 不透明主色：Cue 的 Int 是带符号的，落到契约里必须是无符号 ARGB
        assertEquals(0xFF00FF00L, npCueColorArgb(0xFF00FF00.toInt())!!)
        // 半透明主色：alpha 不能被 toLong() 的符号扩展吃掉
        assertEquals(0x80FF0000L, npCueColorArgb(0x80FF0000.toInt())!!)
        assertEquals(0x00112233L, npCueColorArgb(0x00112233)!!)
    }

    @Test
    fun bitmapPlacementFallsBackToFullWidthBottomCenter() {
        // 宽高比 640:180，未给 heightFraction/anchor/baseline → 铺满宽度、底部对齐缺省值
        val placement = npCuePlacement(
            bitmapWidthPx = 640,
            bitmapHeightPx = 180,
            heightFraction = null,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(0f, placement.left, 1e-6f)
        assertEquals(0f, placement.top, 1e-6f)
        assertEquals(1f, placement.width, 1e-6f)
        assertEquals(1f, placement.height, 1e-6f)
    }

    @Test
    fun bitmapPlacementHonorsBaselineAndWidthByAspectRatio() {
        // 400x100（宽高比 4），高度占 0.1 → 宽度 0.4，中心 0.5 → left 0.3；baseline 0.05 顶部锚
        val placement = npCuePlacement(
            bitmapWidthPx = 400,
            bitmapHeightPx = 100,
            heightFraction = 0.1f,
            anchorX = 0.5f,
            baselineY = 0.05f,
        )
        assertEquals(0.3f, placement.left, 1e-6f)
        assertEquals(0f, placement.top, 1e-6f)
        assertEquals(0.4f, placement.width, 1e-6f)
        assertEquals(0.1f, placement.height, 1e-6f)
    }

    @Test
    fun bitmapPlacementClampsWidthFractionToOne() {
        // 400x100 + heightFraction 0.5 → 宽度 2.0，必须钳到 1.0，left 回到 0
        val placement = npCuePlacement(
            bitmapWidthPx = 400,
            bitmapHeightPx = 100,
            heightFraction = 0.5f,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(1f, placement.width, 1e-6f)
        assertEquals(0f, placement.left, 1e-6f)
        assertEquals(0.5f, placement.height, 1e-6f)
        assertEquals(0.5f, placement.top, 1e-6f)
    }

    @Test
    fun bitmapPlacementHandlesZeroHeightBitmap() {
        // 解码器给了 0 高度（或异常值）时宽高比按 1 处理，不能除零
        val placement = npCuePlacement(
            bitmapWidthPx = 100,
            bitmapHeightPx = 0,
            heightFraction = null,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(1f, placement.width, 1e-6f)
        assertEquals(1f, placement.height, 1e-6f)
        assertEquals(0f, placement.left, 1e-6f)
        assertEquals(0f, placement.top, 1e-6f)
    }

    @Test
    fun invalidHeightFractionFallsBackToOne() {
        // Cue 契约里 heightFraction 只允许 (0,1]；异常值一律回落整屏高
        val placement = npCuePlacement(
            bitmapWidthPx = 400,
            bitmapHeightPx = 100,
            heightFraction = 2f,
            anchorX = null,
            baselineY = null,
        )
        assertEquals(1f, placement.height, 1e-6f)
        assertEquals(1f, placement.width, 1e-6f)
    }

    @Test
    fun placementIsClampedIntoUnitBox() {
        // anchorX 1.5 → left 1.3 钳到 1.0；baselineY 1.5 → top 1.4 钳到 1.0（与抽取前 inline 行为一致）
        val placement = npCuePlacement(
            bitmapWidthPx = 400,
            bitmapHeightPx = 100,
            heightFraction = 0.1f,
            anchorX = 1.5f,
            baselineY = 1.5f,
        )
        assertEquals(1f, placement.left, 1e-6f)
        assertEquals(1f, placement.top, 1e-6f)
    }

    @Test
    fun placementUsesGivenHeightFractionForAspect() {
        // 320x240（4:3），高度 0.5 → 宽度 0.6667，居中 left = 0.5 - 0.3333
        val placement: CuePlacement = npCuePlacement(
            bitmapWidthPx = 320,
            bitmapHeightPx = 240,
            heightFraction = 0.5f,
            anchorX = 0.5f,
            baselineY = 1f,
        )
        assertEquals(0.6667f, placement.width, 1e-3f)
        assertEquals(0.1667f, placement.left, 1e-3f)
        assertEquals(0.5f, placement.top, 1e-6f)
    }
}
