package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.SubtitleEffectInfo
import com.noneplayer.core.media.SubtitleEffectTag
import com.noneplayer.kernel.api.NpSubtitleFormat
import com.noneplayer.kernel.api.SubtitleAlignment
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.ui.api.TextCueClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A12 字幕浮层的纯逻辑（纯 JVM，不驱动 Compose）：
 *
 * - 文本 Cue 让位仲裁（弹幕层认领同一轨时不重复画文本）；
 * - 特效提示的显隐口径与文案；
 * - 文本 Cue 水平/垂直摆放算式（从 `TextCue` 的布局 lambda 里原样抽出，
 *   用于钉死「Cue 自带对齐/位置优先，缺省底部居中」第一期行为）；
 * - 外挂 ASS 文件的特效检测接线（只在 SSA/ASS 格式上检测）。
 */
class SubtitleOverlayTest {

    // ---------------------------------------------------------------- 让位仲裁

    @Test
    fun suppressionRequiresSameMediaAndSameSelectedTrack() {
        val claim = TextCueClaim(mediaUri = "content://v/1", trackId = "track-2")
        assertTrue(shouldSuppressTextCues(claim, "content://v/1", "track-2"))
        // 换了媒体：上一条片子的认领不能压住这一条的字幕
        assertFalse(shouldSuppressTextCues(claim, "content://v/2", "track-2"))
        // 同一媒体但换了一条文本轨（同 URI 分轨）：不应让位
        assertFalse(shouldSuppressTextCues(claim, "content://v/1", "track-3"))
        // 没有认领者 / 当前没有选中的文本轨：字幕层照常画
        assertFalse(shouldSuppressTextCues(null, "content://v/1", "track-2"))
        assertFalse(shouldSuppressTextCues(claim, "content://v/1", null))
    }

    @Test
    fun visibleCuesKeepOnlyBitmapsWhenSuppressed() {
        val textCue = SubtitleCue(text = "字幕文本")
        val bitmapCue = SubtitleCue(bitmap = Any())
        val cues = listOf(textCue, bitmapCue)

        assertEquals(cues, visibleCuesForArbitration(cues, suppressText = false))
        assertEquals(listOf(bitmapCue), visibleCuesForArbitration(cues, suppressText = true))
    }

    // ---------------------------------------------------------------- 特效提示

    @Test
    fun effectNoticeAppearsOnlyWhenEnabledDetectedAndNotDismissed() {
        val info = SubtitleEffectInfo("content://subs/a.ass", listOf(SubtitleEffectTag.POSITION))
        assertTrue(shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = false))
        // 用户关了字幕总开关 → 连提示也不该出现
        assertFalse(shouldShowSubtitleEffectNotice(enabled = false, info = info, dismissed = false))
        // 没有检测到特效 → 不出提示
        assertFalse(shouldShowSubtitleEffectNotice(enabled = true, info = null, dismissed = false))
        // 用户点过「×」→ 同一条字幕不再提醒
        assertFalse(shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = true))
    }

    @Test
    fun effectNoticeTextMatchesTaskWording() {
        assertEquals("此字幕含特效，当前只显示文本", SUBTITLE_EFFECT_NOTICE_TEXT)
    }

    // ---------------------------------------------------------------- 外挂字幕特效检测接线

    @Test
    fun assSubtitleWithEffectsProducesInfo() {
        val info = subtitleEffectInfoFor(
            format = NpSubtitleFormat.SSA,
            sourceUri = "content://subs/a.ass",
            text = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(320,240)}文本",
        )
        assertEquals("content://subs/a.ass", info?.sourceUri)
        assertEquals(listOf(SubtitleEffectTag.POSITION), info?.tags)
    }

    @Test
    fun assSubtitleWithoutEffectsProducesNoInfo() {
        assertNull(
            subtitleEffectInfoFor(
                format = NpSubtitleFormat.SSA,
                sourceUri = "content://subs/a.ass",
                text = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,一句普通对白",
            ),
        )
        // 空文本/探测失败的兜底
        assertNull(subtitleEffectInfoFor(NpSubtitleFormat.SSA, "content://subs/a.ass", null))
    }

    @Test
    fun nonAssFormatsAreNotScanned() {
        // SRT 文本里出现 `\pos` 只是正文，不是 ASS 覆盖标签，不该提示
        assertNull(
            subtitleEffectInfoFor(
                format = NpSubtitleFormat.SUBRIP,
                sourceUri = "content://subs/a.srt",
                text = "1\n00:00:01,000 --> 00:00:02,000\n{\\pos(320,240)}一句普通对白\n",
            ),
        )
        assertNull(subtitleEffectInfoFor(null, "content://subs/unknown", "{\\pos(1,2)}x"))
    }

    // ---------------------------------------------------------------- 文本 Cue 垂直摆放

    @Test
    fun textCueSitsAboveBottomPaddingByDefault() {
        // 用户垂直位置 = 1（底端）→ 画面高 - 文字高 - 控制层留白
        assertEquals(
            844,
            textCueTopY(
                stageHeight = 1000,
                textHeight = 100,
                lineFraction = null,
                verticalPosition = 1f,
                bottomPaddingPx = 56,
            ),
        )
    }

    @Test
    fun textCueUsesUserVerticalPositionWhenCueHasNoLine() {
        assertEquals(
            422,
            textCueTopY(
                stageHeight = 1000,
                textHeight = 100,
                lineFraction = null,
                verticalPosition = 0.5f,
                bottomPaddingPx = 56,
            ),
        )
        assertEquals(
            0,
            textCueTopY(
                stageHeight = 1000,
                textHeight = 100,
                lineFraction = null,
                verticalPosition = 0f,
                bottomPaddingPx = 56,
            ),
        )
    }

    @Test
    fun cueLineFractionOverridesUserVerticalPosition() {
        // ≤0.5：lineFraction 是顶边锚（ASS \an7-9 的顶部对齐）
        assertEquals(
            200,
            textCueTopY(
                stageHeight = 1000,
                textHeight = 100,
                lineFraction = 0.2f,
                verticalPosition = 1f,
                bottomPaddingPx = 56,
            ),
        )
        // >0.5：lineFraction 是底边锚
        assertEquals(
            800,
            textCueTopY(
                stageHeight = 1000,
                textHeight = 100,
                lineFraction = 0.9f,
                verticalPosition = 0f,
                bottomPaddingPx = 56,
            ),
        )
    }

    @Test
    fun textCueTopIsClampedIntoStage() {
        assertEquals(
            0,
            textCueTopY(1000, 1500, null, 0f, 56),
        )
        assertEquals(
            900,
            textCueTopY(1000, 100, 1.2f, 0f, 56),
        )
    }

    // ---------------------------------------------------------------- 文本 Cue 水平摆放

    @Test
    fun textCueCenterAndAlignmentPlacements() {
        // 左右各留 4% 画面宽（1920 → 77px）
        assertEquals(77, textCueLeftX(1920, 300, null, SubtitleAlignment.LEFT, 77))
        assertEquals(1543, textCueLeftX(1920, 300, null, SubtitleAlignment.RIGHT, 77))
        assertEquals(810, textCueLeftX(1920, 300, null, SubtitleAlignment.CENTER, 77))
    }

    @Test
    fun cuePositionFractionCentersOnGivenPoint() {
        assertEquals(330, textCueLeftX(1920, 300, 0.25f, SubtitleAlignment.CENTER, 77))
        assertEquals(810, textCueLeftX(1920, 300, 0.5f, SubtitleAlignment.LEFT, 77))
    }

    @Test
    fun textCueLeftIsClampedIntoStage() {
        assertEquals(0, textCueLeftX(1920, 300, 0f, SubtitleAlignment.CENTER, 77))
        assertEquals(1620, textCueLeftX(1920, 300, 1f, SubtitleAlignment.CENTER, 77))
    }
}
