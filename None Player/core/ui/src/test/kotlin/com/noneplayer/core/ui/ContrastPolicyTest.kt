package com.noneplayer.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §14 #9 的对比度红线（2026-10-08 落地为门禁；《设计文档.md》§14 第 9 条）。
 *
 * 这是**纯计算**的验收：`fg/bg ≥ 7`、`muted/bg ≥ 4.5`、控制层压白字处 ≥ 4.5。
 * 比截图取色的好处是**永久**——调色板或封面 scrim 的钳制一被改坏，构建当场就红；
 * 渲染像素与令牌的一致性另由设备抽查背书（档案 §19.20）。
 */
class ContrastPolicyTest {

    @Test
    fun audioBackdropIsReadableOnEveryCoverBrightness() {
        for (ink in listOf(NpInkLight, NpInkDark)) {
            val alpha = npReadableBackdropAlpha(ink)
            for (level in 0..255) {
                val cover = level / 255f
                val background = Color(
                    ink.bg.red * alpha + cover * (1f - alpha),
                    ink.bg.green * alpha + cover * (1f - alpha),
                    ink.bg.blue * alpha + cover * (1f - alpha),
                )
                assertRatio("audio primary", ink.fg, background, 7.0)
                assertRatio("audio secondary", ink.muted, background, 4.5)
            }
        }
    }

    private fun ratio(fg: Color, bg: Color): Double = npContrastRatio(fg, bg)

    private fun assertRatio(what: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("%s 对比度 %.2f < %.1f".format(what, r, min), r >= min)
    }

    @Test
    fun `暗色主题主字与次字都达标`() {
        val ink = NpInkDark
        assertRatio("暗 fg/bg", ink.fg, ink.bg, 7.0)
        assertRatio("暗 muted/bg", ink.muted, ink.bg, 4.5)
        // 面板底色与页面底几乎同色（#010101 vs #000000），次字在 surface 上同样要达标
        assertRatio("暗 muted/surface", ink.muted, ink.surface, 4.5)
    }

    @Test
    fun `亮色主题主字与次字都达标`() {
        val ink = NpInkLight
        assertRatio("亮 fg/bg", ink.fg, ink.bg, 7.0)
        assertRatio("亮 muted/bg", ink.muted, ink.bg, 4.5)
        // 封面未加载时的占位底（surface2 #F7F7F7）上也要达标
        assertRatio("亮 muted/surface2", ink.muted, ink.surface2, 4.5)
    }

    @Test
    fun `封面 scrim 整域扫描下白字都达标`() {
        // §4.7 的钳制（S ≤ 0.55、V ∈ [0.28,0.42]）就是这条红线的兑现方式：
        // 两个上界顶满时白字对比度最差，若整域扫描都 ≥4.5，scrim 就不需要"回退纯黑"这条分支。
        var worst = Double.MAX_VALUE
        var worstAt = ""
        for (hue in 0..359 step 5) {
            for (sat in 0..55 step 5) {
                for (value in 28..42 step 2) {
                    val scrim = NpCoverAccent.scrimFor(hue.toFloat(), sat / 100f, value / 100f)
                    val contrast = NpCoverAccent.whiteTextContrast(scrim)
                    if (contrast < worst) {
                        worst = contrast
                        worstAt = "h=$hue s=${sat / 100f} v=${value / 100f}"
                    }
                }
            }
        }
        assertTrue("白字最低对比度 %.2f @ %s（应 ≥4.5）".format(worst, worstAt), worst >= 4.5)
    }
}
