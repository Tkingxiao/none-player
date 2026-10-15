package com.noneplayer.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A3：外挂字幕的格式判定。
 *
 * 判格式必须用**真实文件名**：MediaStore 与 SAF 的 uri 末段是 id，从那里取不到扩展名，
 * 一律退回 SubRip 就会让自动匹配的 `.ass` 一条 Cue 都不出。
 */
class SubtitleFormatTest {

    @Test
    fun `display name decides the format`() {
        assertEquals(NpSubtitleFormat.SSA, npSubtitleFormatOf("进击的巨人 01.ass"))
        assertEquals(NpSubtitleFormat.SSA, npSubtitleFormatOf("movie.SSA"))
        assertEquals(NpSubtitleFormat.WEBVTT, npSubtitleFormatOf("movie.vtt"))
        assertEquals(NpSubtitleFormat.SUBRIP, npSubtitleFormatOf("movie.srt"))
        assertEquals(NpSubtitleFormat.TTML, npSubtitleFormatOf("movie.ttml"))
    }

    @Test
    fun `mediastore id uri carries no extension and is not guessed`() {
        assertNull(npSubtitleFormatOf("content://media/external/file/1234"))
    }

    @Test
    fun `saf document uri still ends with the real extension`() {
        assertEquals(
            NpSubtitleFormat.SSA,
            npSubtitleFormatOf(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fmovie.ass",
            ),
        )
    }

    @Test
    fun `unknown and multi dot suffixes do not invent a format`() {
        assertNull(npSubtitleFormatOf("movie.sub.bak"))
        assertNull(npSubtitleFormatOf("no-extension-here"))
        // `.sub` 是 SubRip 家族里常见的另一种写法
        assertEquals(NpSubtitleFormat.SUBRIP, npSubtitleFormatOf("movie.sub"))
    }
}
