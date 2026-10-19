package com.noneplayer.core.ui

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * 全部时长令牌（名称, 毫秒）。A16 的判据要逐条走三档，漏一个就漏掉一个可能被
     * 下限压平的令牌；新增令牌时这里同步加。
     */
    private val motionTokens: List<Pair<String, Int>> = listOf(
        "Hover" to Motion.Hover,
        "Press" to Motion.Press,
        "Field" to Motion.Field,
        "Panel" to Motion.Panel,
        "Switch" to Motion.Switch,
        "Drawer" to Motion.Drawer,
        "Tab" to Motion.Tab,
        "Overlay" to Motion.Overlay,
        "OverlayExit" to Motion.OverlayExit,
        "MiniProgress" to Motion.MiniProgress,
        "Scheme" to Motion.Scheme,
        "Sheet" to Motion.Sheet,
        "Tonearm" to Motion.Tonearm,
        "Spin" to Motion.Spin,
        "Shimmer" to Motion.Shimmer,
        "Toast" to Motion.Toast,
        "ToastHold" to Motion.ToastHold,
        "Stagger" to Motion.Stagger,
        "ReduceFade" to Motion.ReduceFade,
        "NavEnter" to Motion.NavEnter,
        "NavFade" to Motion.NavFade,
        "NavExit" to Motion.NavExit,
        "Bubble" to Motion.Bubble,
        "Shared" to Motion.Shared,
        "AccentShift" to Motion.AccentShift,
        "FormMorph" to Motion.FormMorph,
    )

    /** 与 [silkStiffness] 互逆：刚度 → 该弹簧的等效自然时长（ms）。 */
    private fun equivalentDurationMs(stiffness: Float): Double =
        8000.0 / sqrt(stiffness.toDouble())

    // ---------- silkStiffness 换算（§3.9 / A16） ----------

    @Test
    fun `silkStiffness 按 8000 除时长再平方，下限与轻量档时长地板对齐`() {
        // 公式：(8000 / max(ms, 45))²，45 就是 NpMotionQuality.lightDurationFloorMs。
        // A16：刚度地板与轻量档时长地板必须是同一个数，否则 scaled() 夹进 [45,120] 的
        // 结果全在旧 140ms 地板之下，短令牌被压成同一刚度。
        val floor = NpMotionQuality.lightDurationFloorMs
        assertEquals(45, floor)
        val floorStiffness = (8000f / floor) * (8000f / floor)
        assertEquals(floorStiffness, silkStiffness(45), 0.5f)  // ≈31605

        // 公式精确值：8000/100=80 → 6400；8000/120≈66.67 → 4444.4；8000/250=32 → 1024；
        // 8000/320=25 → 625；8000/160=50 → 2500。
        assertEquals(6400f, silkStiffness(100), 0.5f)
        assertEquals(4444.44f, silkStiffness(120), 0.5f)
        assertEquals(2500f, silkStiffness(160), 0.5f)
        assertEquals(1024f, silkStiffness(250), 0.5f)
        assertEquals(625f, silkStiffness(320), 0.5f)

        // 只有地板以下才夹：100ms 与 120ms 必须给出不同刚度——旧 140ms 地板把二者
        // （以及所有短令牌）钉成同一个值，这正是 A16 要拆掉的洞。
        assertNotEquals(silkStiffness(45).toDouble(), silkStiffness(100).toDouble(), 0.0)
        assertNotEquals(silkStiffness(100).toDouble(), silkStiffness(120).toDouble(), 0.0)

        // 下限仍要兜住 0 / 负数（公开 API，调用方可能给裸毫秒）
        assertEquals(floorStiffness, silkStiffness(0), 0.5f)
        assertEquals(floorStiffness, silkStiffness(-500), 0.5f)
        assertEquals(floorStiffness, silkStiffness(floor - 1), 0.5f)

        // 时长越长刚度越低——反过来就是"越按越弹"
        assertTrue(silkStiffness(Motion.Hover) > silkStiffness(Motion.Sheet))
        assertTrue(silkStiffness(Motion.Sheet) > silkStiffness(Motion.Tonearm))
    }

    @Test
    fun `每个令牌在三档下的弹簧等效时长等于该档实际时长，不再被 140 压平`() {
        // `silkStiffness` 与 equivalentDurationMs 互逆 ⇒ 等效时长必须精确等于 scaled()。
        // 任何一个令牌被地板吃掉（旧公式下 120→140）都会让这条断言偏离自己的实际时长。
        for (quality in listOf(NpMotionQuality.Lightweight, NpMotionQuality.Balanced, NpMotionQuality.High)) {
            for ((name, token) in motionTokens) {
                val actualMs = quality.scaled(token)
                val equivalent = equivalentDurationMs(silkStiffness(actualMs))
                assertEquals(
                    "${quality.id} 档 $name：刚度换算回的等效时长应等于实际时长 ${actualMs}ms",
                    actualMs.toDouble(),
                    equivalent,
                    0.5,
                )
            }
        }
    }

    @Test
    fun `轻量档里实际时长不同的令牌刚度互不相同，同值才同刚度`() {
        val light = NpMotionQuality.Lightweight

        // 关键分界档位逐个核（缩到 [45,120] 之后的真实值）。
        assertEquals(45, light.scaled(Motion.Hover))      // 100×0.2=20 → 地板
        assertEquals(52, light.scaled(Motion.Drawer))     // 260×0.2=52
        assertEquals(56, light.scaled(Motion.Overlay))    // 280×0.2=56
        assertEquals(64, light.scaled(Motion.Sheet))      // 320×0.2=64
        assertEquals(64, light.scaled(Motion.Scheme))     // 320×0.2=64，与 Sheet 同值
        assertEquals(100, light.scaled(Motion.Tonearm))   // 500×0.2=100
        assertEquals(120, light.scaled(Motion.Spin))      // 22000×0.2=4400 → 上限

        val durations = motionTokens.map { light.scaled(it.second) }.distinct().sorted()
        val stiffnesses = durations.map { silkStiffness(it) }
        // 时长不同 → 刚度不同（旧 140ms 地板下这里只剩 1 个刚度，必然红）
        assertEquals(
            "轻量档 ${durations.size} 个不同的实际时长应给出同样多的刚度",
            durations.size,
            stiffnesses.distinct().size,
        )
        // 45 / 52 / 56 / 64 / 100 / 120 六档两两不同（Sheet 与 Scheme 同为 64 仍共享刚度，
        // 那是时长折叠加不是压平）
        assertEquals(
            6,
            listOf(45, 52, 56, 64, 100, 120).map { silkStiffness(it) }.distinct().size,
        )
        // 越短越刚：轻量档里"更快"必须真的更刚
        for (i in 1 until durations.size) {
            assertTrue(
                "${durations[i - 1]}ms 的刚度应高于 ${durations[i]}ms",
                stiffnesses[i - 1] > stiffnesses[i],
            )
        }
    }

    @Test
    fun `平衡档短令牌不再共用 140 地板，Hover 与 Press 刚度不同`() {
        val balanced = NpMotionQuality.Balanced
        // 倍率为 1 的档位令牌原值直通，刚度因此逐条不同。
        assertEquals(Motion.Hover, balanced.scaled(Motion.Hover))
        assertEquals(6400f, silkStiffness(balanced.scaled(Motion.Hover)), 0.5f)
        assertNotEquals(
            silkStiffness(balanced.scaled(Motion.Hover)).toDouble(),
            silkStiffness(balanced.scaled(Motion.Press)).toDouble(),
            0.0,
        )
        // 100ms 与 120ms 都短于旧 140ms 地板——旧公式下这里是同一个刚度。
        assertNotEquals(
            silkStiffness(balanced.scaled(Motion.Hover)).toDouble(),
            silkStiffness(balanced.scaled(Motion.ReduceFade)).toDouble(),
            0.0,
        )
        // 整张令牌表：平衡档令牌值不同 → 刚度不同。
        val durations = motionTokens.map { balanced.scaled(it.second) }.distinct()
        assertEquals(
            "平衡档 ${durations.size} 个不同的令牌时长应给出同样多的刚度",
            durations.size,
            durations.map { silkStiffness(it) }.distinct().size,
        )
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
