package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.SubtitleEffectTag
import com.noneplayer.kernel.api.NpSubtitleFormat
import com.noneplayer.kernel.api.SubtitleAlignment
import com.noneplayer.kernel.ui.api.TextCueClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4 / A12 独立验收：字幕特效提示条件 + Cue 对齐映射（只写测试，不改生产）。
 *
 * 判据（task-20 第 3 条）：
 * 1. 有 `\pos` / 卡拉 OK（`\k`）才提示，纯文本字幕不提示；非 ASS 文件里的反斜杠只是正文，不提示；
 * 2. 提示文案与任务口径逐字一致，且字幕总开关关闭 / 用户已关闭提示时不出现；
 * 3. 文本 Cue 的对齐映射（Cue 自带比例 → 画面像素落点）有确定结果并钳进画面内。
 *
 * 全部是纯 JVM 用例（生产侧已把这些口径抽成 internal 纯函数），不依赖 Robolectric。
 */
class R4SubtitleEffectAcceptanceTest {

    /** 拼一份最小 ASS：`Dialogue:` 行是特效检测的唯一入口。 */
    private fun assText(vararg dialogues: String): String = buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine("[V4+ Styles]")
        appendLine("Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1")
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        dialogues.forEach { appendLine("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,$it") }
    }

    @Test
    fun posOverrideInAssTriggersEffectNotice() {
        val info = subtitleEffectInfoFor(
            format = NpSubtitleFormat.SSA,
            sourceUri = "content://subs/a.ass",
            text = assText("""{\pos(320,240)}你好，世界"""),
        )

        assertNotNull("带 \\pos 的 ASS 必须识别为特效字幕", info)
        assertTrue("\\pos 应归入「定位」类特效", SubtitleEffectTag.POSITION in info!!.tags)
        assertEquals("content://subs/a.ass", info.sourceUri)
        assertTrue(
            "有特效且未关闭提示时必须显示提示",
            shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = false),
        )
    }

    @Test
    fun karaokeOverrideTriggersEffectNotice() {
        val info = subtitleEffectInfoFor(
            format = NpSubtitleFormat.SSA,
            sourceUri = "content://subs/k.ass",
            text = assText("""{\k20}卡{\k30}拉{\k20}OK"""),
        )

        assertNotNull("带卡拉 OK 的 ASS 必须识别为特效字幕", info)
        assertTrue("\\k 应归入「卡拉OK」类特效", SubtitleEffectTag.KARAOKE in info!!.tags)
        assertTrue(shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = false))
    }

    @Test
    fun plainAssWithoutOverridesShowsNoNotice() {
        val text = assText("你好，世界", "第二句台词")
        val info = subtitleEffectInfoFor(NpSubtitleFormat.SSA, "content://subs/plain.ass", text)

        assertNull("无覆盖标签的 ASS 不得提示特效", info)
        assertFalse(
            "没有检测到特效时不出现提示",
            shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = false),
        )
    }

    @Test
    fun nonAssSubtitleNeverClaimsEffects() {
        // SRT 正文里的 {\pos(...)} 是字面文本，不是样式覆盖。
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n{\\pos(320,240)}你好\n"
        assertNull(
            "SRT 不做 ASS 语义解释，不得误报特效",
            subtitleEffectInfoFor(NpSubtitleFormat.SUBRIP, "content://subs/a.srt", srt),
        )
        assertNull(
            "文本缺失时不得因检测抛错或误报",
            subtitleEffectInfoFor(NpSubtitleFormat.SSA, "content://subs/broken.ass", null),
        )
    }

    @Test
    fun noticeRespectsSubtitleSwitchAndDismiss() {
        val info = subtitleEffectInfoFor(
            NpSubtitleFormat.SSA,
            "content://subs/a.ass",
            assText("""{\fad(200,200)}淡入淡出"""),
        )
        assertNotNull(info)

        assertFalse(
            "字幕总开关关闭时连提示也不出现",
            shouldShowSubtitleEffectNotice(enabled = false, info = info, dismissed = false),
        )
        assertFalse(
            "用户已关闭本次提示时不得再次出现",
            shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = true),
        )
        assertTrue(shouldShowSubtitleEffectNotice(enabled = true, info = info, dismissed = false))
    }

    @Test
    fun onlyDialogueLinesCountForEffectDetection() {
        val text = buildString {
            appendLine("[V4+ Styles]")
            // Style/Comment 行里同样出现 \pos，但不能算特效字幕。
            appendLine("Style: Tagged,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1")
            appendLine("Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(1,2)}注释")
            appendLine("[Events]")
            appendLine("Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,普通台词")
        }
        val report = com.noneplayer.core.media.SubtitleEffectDetector.detect(text)
        assertFalse("Style/Comment 行里的覆盖标签不是特效字幕证据", report.hasEffects)
        assertNull(subtitleEffectInfoFor(NpSubtitleFormat.SSA, "content://subs/c.ass", text))
    }

    @Test
    fun effectNoticeTextMatchesTaskWording() {
        assertEquals("此字幕含特效，当前只显示文本", SUBTITLE_EFFECT_NOTICE_TEXT)
    }

    // ------------------------------------------------------------------
    // Cue 对齐映射：Cue 自带比例（ASS \an/\pos）→ 画面像素落点
    // ------------------------------------------------------------------

    @Test
    fun cueHorizontalPlacementMapsAlignmentToStagePixels() {
        val stageWidth = 1000
        val textWidth = 200
        val padding = 24

        assertEquals(
            "居中：左边界 = (舞台宽 - 文本宽) / 2",
            400,
            textCueLeftX(stageWidth, textWidth, positionFraction = null, alignment = SubtitleAlignment.CENTER, horizontalPaddingPx = padding),
        )
        assertEquals(
            "左对齐：左边界 = 水平留白",
            padding,
            textCueLeftX(stageWidth, textWidth, positionFraction = null, alignment = SubtitleAlignment.LEFT, horizontalPaddingPx = padding),
        )
        assertEquals(
            "右对齐：左边界 = 舞台宽 - 文本宽 - 留白",
            776,
            textCueLeftX(stageWidth, textWidth, positionFraction = null, alignment = SubtitleAlignment.RIGHT, horizontalPaddingPx = padding),
        )
        assertEquals(
            "Cue 自带水平比例时按中心点摆放（0.25 → 250 - 100）",
            150,
            textCueLeftX(stageWidth, textWidth, positionFraction = 0.25f, alignment = SubtitleAlignment.LEFT, horizontalPaddingPx = padding),
        )
    }

    @Test
    fun cueHorizontalPlacementIsClampedInsideStage() {
        assertEquals(
            "比例把文本推到画面左外时必须钳到 0",
            0,
            textCueLeftX(1000, 200, positionFraction = 0.0f, alignment = SubtitleAlignment.CENTER, horizontalPaddingPx = 24),
        )
        assertEquals(
            "比例把文本推到画面右外时必须钳到舞台右边界",
            800,
            textCueLeftX(1000, 200, positionFraction = 1.0f, alignment = SubtitleAlignment.CENTER, horizontalPaddingPx = 24),
        )
    }

    @Test
    fun cueVerticalPlacementMapsLineFractionToStagePixels() {
        val stageHeight = 1000
        val textHeight = 100
        val bottomPadding = 60

        assertEquals(
            "lineFraction ≤ 0.5 视为顶边锚：0.1 → 100",
            100,
            textCueTopY(stageHeight, textHeight, lineFraction = 0.1f, verticalPosition = 0.5f, bottomPaddingPx = bottomPadding),
        )
        assertEquals(
            "lineFraction > 0.5 视为底边锚：0.6 → 600 - 100",
            500,
            textCueTopY(stageHeight, textHeight, lineFraction = 0.6f, verticalPosition = 0.5f, bottomPaddingPx = bottomPadding),
        )
        assertEquals(
            "无 Cue 位置时按用户设置插值：贴顶 → 0",
            0,
            textCueTopY(stageHeight, textHeight, lineFraction = null, verticalPosition = 0f, bottomPaddingPx = bottomPadding),
        )
        assertEquals(
            "无 Cue 位置时按用户设置插值：贴底 → 舞台高 - 文本高 - 底部留白",
            840,
            textCueTopY(stageHeight, textHeight, lineFraction = null, verticalPosition = 1f, bottomPaddingPx = bottomPadding),
        )
        assertEquals(
            "Cue 位置越界时钳进画面内",
            900,
            textCueTopY(stageHeight, textHeight, lineFraction = 1.0f, verticalPosition = 0.5f, bottomPaddingPx = bottomPadding),
        )
    }

    @Test
    fun textCuesYieldToClaimingOverlayOnlyForSameTrack() {
        val cue = com.noneplayer.kernel.api.SubtitleCue(text = "台词")
        val bitmapCue = com.noneplayer.kernel.api.SubtitleCue(bitmap = Any())

        val claimSame = TextCueClaim(mediaUri = "content://v/1", trackId = "track-1")
        assertEquals(
            "同一媒体同一条已选轨才让位",
            listOf(bitmapCue),
            visibleCuesForArbitration(listOf(cue, bitmapCue), shouldSuppressTextCues(claimSame, "content://v/1", "track-1")),
        )
        assertFalse(
            "换媒体后上一条片子的认领不得压住当前字幕",
            shouldSuppressTextCues(claimSame, "content://v/2", "track-1"),
        )
        assertFalse(
            "同 URI 分轨时认领另一条轨不得压住当前字幕",
            shouldSuppressTextCues(claimSame, "content://v/1", "track-2"),
        )
        assertFalse(
            "没有已选文本轨时不让位",
            shouldSuppressTextCues(claimSame, "content://v/1", null),
        )
    }
}
