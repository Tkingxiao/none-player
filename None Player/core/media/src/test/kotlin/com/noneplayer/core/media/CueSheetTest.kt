package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批次 8：CUE 分轨表解析单测——INDEX 01 起点制、1/75 秒→微秒、坏表 fail-open。
 */
class CueSheetTest {

    @Test
    fun `标准 cue 解析出 N 轨与绝对微秒起点`() {
        val cue = """
            TITLE "测试专辑"
            PERFORMER "测试艺人"
            FILE "测试专辑.wav" WAVE
              TRACK 01 AUDIO
                TITLE "第一轨"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "第二轨"
                INDEX 01 02:30:37
              TRACK 03 AUDIO
                TITLE "第三轨"
                INDEX 01 05:01:00
        """.trimIndent()
        val sheet = CueSheet.parse(cue)!!
        assertEquals("测试专辑.wav", sheet.fileRef)
        assertEquals(3, sheet.tracks.size)
        assertEquals("第一轨", sheet.tracks[0].title)
        assertEquals(0L, sheet.tracks[0].startUs)
        // 02:30.494：(2*60+30)*75 + 37 帧 → 9037 帧 * 13333.33us ≈ 120_493_333us
        val expected = (2 * 60 + 30) * 75L * 1_000_000L / 75L + 37L * 1_000_000L / 75L
        assertEquals(expected, sheet.tracks[1].startUs)
    }

    @Test
    fun `INDEX 00 pregap 不产生轨`() {
        val cue = """
            FILE "a.wav" WAVE
            TRACK 01 AUDIO
              INDEX 00 00:00:00
              INDEX 01 00:02:00
            TRACK 02 AUDIO
              INDEX 00 01:00:00
              INDEX 01 01:00:05
        """.trimIndent()
        val sheet = CueSheet.parse(cue)!!
        assertEquals(2, sheet.tracks.size)
        // 轨 1 起点 = INDEX 01 的 00:02:00 → 2_000_000us（INDEX 00 的 00:00:00 被忽略）
        assertEquals(2_000_000L, sheet.tracks[0].startUs)
        // 轨 2 起点 01:00.05 → 60s + 5 帧（1/75 秒制）= 60_066_666us
        assertEquals(60_066_666L, sheet.tracks[1].startUs)
    }

    @Test
    fun `无 TRACK 或无 INDEX 01 返回 null`() {
        assertNull(CueSheet.parse("TITLE \"只有标题\""))
        assertNull(CueSheet.parse("FILE \"a.wav\" WAVE\nTRACK 01 AUDIO\nTITLE \"没有 INDEX\""))
    }

    @Test
    fun `REM 行与未知行被跳过 数据轨不产生轨`() {
        val cue = """
            REM COMMENT "由某工具生成"
            REM DATE 2020
            FILE "a.wav" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            TRACK 02 MODE1/2352
              INDEX 01 01:00:00
        """.trimIndent()
        val sheet = CueSheet.parse(cue)!!
        assertEquals(1, sheet.tracks.size)
    }

    @Test
    fun `timeToUs 拒绝越界与畸形输入`() {
        assertNull(CueSheet.timeToUs("01:60:00"))
        assertNull(CueSheet.timeToUs("01:00:75"))
        assertNull(CueSheet.timeToUs("01:00"))
        assertEquals(60_000_000L, CueSheet.timeToUs("01:00:00"))
    }
}
