package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A12「特效字幕诚实提示」——检测原始字幕文件里的特效标签（纯 JVM）。
 *
 * 为什么扫原始文本而不是 Cue：media3 的 SsaParser 在产出 Cue 之前就调
 * `SsaStyle.Overrides.stripStyleOverrides` 把 `{\pos(...)}`、`{\k20}`、`{\frz15}`
 * 等覆盖块整体剥掉（media3-extractor 1.11.1 `SsaParser.java:358-364`），Cue.text
 * 里不会留下任何痕迹，事后无法反推。
 *
 * 口径：只认 `Dialogue:` 行（`Style:` / `Format:` / `Comment:` 行里出现同样字样不
 * 算），只在 `{...}` 覆盖块内识别标签，标签名取反斜杠之后的连续字母
 * （`\pos(`→pos、`\p1`→p、`\k20`→k）。
 */
class SubtitleEffectDetectorTest {

    @Test
    fun positionTagIsDetected() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(320,240)}顶部文本",
        )
        assertTrue(report.hasEffects)
        assertEquals(listOf(SubtitleEffectTag.POSITION), report.tags)
        assertEquals(1, report.dialogueLines)
    }

    @Test
    fun karaokeTagsAreDetected() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\k20}卡{\\kf30}拉{\\ko10}OK",
        )
        assertEquals(listOf(SubtitleEffectTag.KARAOKE), report.tags)
    }

    @Test
    fun rotationScaleAndTransformTagsAreDetectedInEnumOrder() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\frz15\\t(0,500,\\fscx120)}旋转",
        )
        assertEquals(
            listOf(SubtitleEffectTag.ROTATION, SubtitleEffectTag.SCALE, SubtitleEffectTag.TRANSFORM),
            report.tags,
        )
    }

    @Test
    fun multipleTagsAreDeduplicatedInEnumOrder() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,," +
                "{\\fad(100,200)\\pos(320,240)\\k20}文本{\\pos(1,2)}",
        )
        assertEquals(
            listOf(SubtitleEffectTag.POSITION, SubtitleEffectTag.KARAOKE, SubtitleEffectTag.FADE),
            report.tags,
        )
        assertEquals(1, report.dialogueLines)
    }

    @Test
    fun moveOrgClipAndBlurTagsAreDetected() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,," +
                "{\\move(10,20,30,40)\\org(5,5)\\clip(0,0,100,100)\\blur2\\be1}动",
        )
        assertEquals(
            listOf(
                SubtitleEffectTag.MOVE,
                SubtitleEffectTag.ORG,
                SubtitleEffectTag.CLIP,
                SubtitleEffectTag.BLUR,
            ),
            report.tags,
        )
    }

    @Test
    fun drawingTagsAreDetected() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0{\\p0}",
        )
        assertEquals(listOf(SubtitleEffectTag.DRAWING), report.tags)
    }

    @Test
    fun plainDialogueHasNoEffects() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,一句普通对白\n",
        )
        assertFalse(report.hasEffects)
        assertEquals(0, report.tags.size)
        assertEquals(1, report.dialogueLines)
    }

    @Test
    fun nonDialogueLinesAreIgnored() {
        val report = SubtitleEffectDetector.detect(
            """
            [Script Info]
            ScriptType: v4.00+
            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,{\pos(1,2)}
            Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\frz90}注释行
            """.trimIndent(),
        )
        assertFalse(report.hasEffects)
        assertEquals(0, report.dialogueLines)
    }

    @Test
    fun unclosedOverrideBlockDoesNotCrashAndKeepsDetectedTag() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(320,240}缺右括号",
        )
        assertEquals(listOf(SubtitleEffectTag.POSITION), report.tags)
    }

    @Test
    fun blankOrNullTextHasNoEffects() {
        assertFalse(SubtitleEffectDetector.detect(null).hasEffects)
        assertFalse(SubtitleEffectDetector.detect("").hasEffects)
        assertFalse(SubtitleEffectDetector.detect("   \r\n  ").hasEffects)
        assertEquals(0, SubtitleEffectDetector.detect(null).dialogueLines)
    }

    @Test
    fun crlfInputIsHandledAndLineCountReported() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\k20}一\r\n" +
                "Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\\fscx120}二\r\n",
        )
        assertEquals(listOf(SubtitleEffectTag.SCALE, SubtitleEffectTag.KARAOKE), report.tags)
        assertEquals(2, report.dialogueLines)
    }

    @Test
    fun literalNewlineEscapeOutsideOverrideBlocksIsNotAnEffect() {
        val report = SubtitleEffectDetector.detect(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,第一行\\N第二行",
        )
        assertFalse(report.hasEffects)
    }

    @Test
    fun noticePublishAndClearDriveTheSharedFlow() {
        assertNull(SubtitleEffectNotice.info.value)
        val info = SubtitleEffectInfo(
            sourceUri = "content://subs/demo.ass",
            tags = listOf(SubtitleEffectTag.POSITION),
        )
        assertEquals(listOf("定位"), info.tagLabels)
        SubtitleEffectNotice.publish(info)
        assertEquals(info, SubtitleEffectNotice.info.value)
        SubtitleEffectNotice.clear()
        assertNull(SubtitleEffectNotice.info.value)
    }
}
