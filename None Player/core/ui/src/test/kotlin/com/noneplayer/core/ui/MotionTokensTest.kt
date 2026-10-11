package com.noneplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌层的三条可计算判据（§14 第 22 条点名的四件里除波形外的三件）：
 * `silkStiffness` 换算、§7.4 越界橡皮筋、§9.6 性能档位判据。
 *
 * 都是纯函数，所以不接 Robolectric——一旦谁改公式，这里先红，而不是在真机上
 * "看起来差不多"就放过。
 */
class MotionTokensTest {

    // ---------- silkStiffness 换算（§3.9） ----------

    @Test
    fun `silkStiffness 按 8000 除时长再平方，并对 140ms 取下限`() {
        // 公式：(8000 / max(ms,140))²
        assertEquals(1024f, silkStiffness(250), 0.5f)     // 8000/250 = 32 → 32²
        assertEquals(625f, silkStiffness(320), 1f)        // Sheet/Scheme 档
        assertEquals(2500f, silkStiffness(160), 2f)       // Press 档
        // 短于 140ms 的令牌全部夹到下限：100 与 0 必须给出同一个刚度
        val floor = (8000f / 140f) * (8000f / 140f)
        assertEquals(floor, silkStiffness(100), 1f)
        assertEquals(floor, silkStiffness(0), 1f)
        assertEquals(floor, silkStiffness(-500), 1f)
        // 时长越长刚度越低——反过来就是"越按越弹"
        assertTrue(silkStiffness(Motion.Hover) > silkStiffness(Motion.Sheet))
        assertTrue(silkStiffness(Motion.Sheet) > silkStiffness(Motion.Tonearm))
    }

    // ---------- 性能档位判据（§9.6） ----------

    @Test
    fun `自动判档三条分支各自命中`() {
        // ① 低内存 或 Android 10 以下 → 轻量（优先于其它一切判据）
        assertEquals(NpMotionQuality.Lightweight, autoMotionQualityFor(lowRam = true, sdkInt = 34, isDesktopLike = true, decodingVideo = false))
        assertEquals(NpMotionQuality.Lightweight, autoMotionQualityFor(lowRam = false, sdkInt = 28, isDesktopLike = true, decodingVideo = false))
        // ② Android 12+ 且非低内存且（无解码 或 桌面形态）→ 全效
        assertEquals(NpMotionQuality.High, autoMotionQualityFor(lowRam = false, sdkInt = 31, isDesktopLike = false, decodingVideo = false))
        assertEquals(NpMotionQuality.High, autoMotionQualityFor(lowRam = false, sdkInt = 34, isDesktopLike = true, decodingVideo = true))
        // ③ 其余 → 平衡（12 以下但够新、以及 12+ 正在解视频的手机上）
        assertEquals(NpMotionQuality.Balanced, autoMotionQualityFor(lowRam = false, sdkInt = 30, isDesktopLike = false, decodingVideo = false))
        assertEquals(NpMotionQuality.Balanced, autoMotionQualityFor(lowRam = false, sdkInt = 31, isDesktopLike = false, decodingVideo = true))
    }

    @Test
    fun `用户档是上限：取低者只能往下压`() {
        // 弱机用户选"全效"也上不去（§9.6 施工期澄清落的那条规则）
        assertEquals(
            NpMotionQuality.Lightweight,
            NpMotionQuality.lowerOf(NpMotionQuality.High, autoMotionQualityFor(lowRam = true, sdkInt = 29, isDesktopLike = false, decodingVideo = false)),
        )
        // 强机用户没选（默认平衡）就停在平衡——自动判定不擅自加戏
        assertEquals(
            NpMotionQuality.Balanced,
            NpMotionQuality.lowerOf(NpMotionQuality.Balanced, NpMotionQuality.High),
        )
        assertEquals(NpMotionQuality.High, NpMotionQuality.lowerOf(NpMotionQuality.High, NpMotionQuality.High))
        // 越界档序夹回默认档，而不是抛异常
        assertEquals(NpMotionQuality.Balanced, NpMotionQuality.fromLevel(1))
        assertEquals(NpMotionQuality.Lightweight, NpMotionQuality.fromLevel(-3))
        assertEquals(NpMotionQuality.High, NpMotionQuality.fromLevel(9))
    }

    @Test
    fun `轻量档倍率夹区间与播放降档只动装饰`() {
        // ×0.20 后再夹 [45,120]：下限保证"还看得见"，上限保证"不再拖沓"
        assertEquals(100, NpMotionQuality.Lightweight.scaled(Motion.Tonearm))   // 500×0.2 = 100
        assertEquals(45, NpMotionQuality.Lightweight.scaled(Motion.Hover))      // 100×0.2 = 20 → 夹 45
        assertEquals(120, NpMotionQuality.Lightweight.scaled(2000))             // 400 → 夹 120
        // 平衡/全效倍率为 1，令牌原值直通
        assertEquals(Motion.Overlay, NpMotionQuality.Balanced.scaled(Motion.Overlay))
        assertEquals(Motion.Sheet, NpMotionQuality.High.scaled(Motion.Sheet))

        // M2 共享封面的档位门控（§9.6）：轻量档关，平衡/全效开——卡片侧的起飞条件
        // （plugin-library）与播放页的接手都靠这一位，掉档就不该有飞行帧。
        assertFalse(NpMotionQuality.Lightweight.sharedElement)
        assertTrue(NpMotionQuality.Balanced.sharedElement)
        assertTrue(NpMotionQuality.High.sharedElement)

        // §9.4 播放中降一档：只关装饰列，倍率/位移/触觉不动
        val dropped = NpMotionQuality.High.forPlayback()
        assertFalse(dropped.stagger)
        assertFalse(dropped.shimmer)
        assertFalse(dropped.centerBubble)
        assertEquals(NpMotionQuality.Lightweight.waveformBars, dropped.waveformBars)
        assertEquals(NpMotionQuality.High.durationFactor, dropped.durationFactor, 0f)
        assertFalse(dropped.displacementAsFade)
        assertTrue(dropped.haptics)
        // 已经是最轻的档：降档不会把波形条数弄成 0，也不会关掉触觉兜底
        val lightDropped = NpMotionQuality.Lightweight.forPlayback()
        assertEquals(28, lightDropped.waveformBars)
        assertTrue(lightDropped.haptics)
    }
}
