package com.noneplayer.plugins.player.ui

import com.noneplayer.kernel.api.NpSubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A3：转码副本的扩展名。
 *
 * 副本必须沿用源格式——写死 `.srt` 时，引擎按末段判 mime 会把转码后的 ASS/VTT 当
 * SubRip 解，`[Script Info]` / `Dialogue:` / `WEBVTT` 都对不上时间轴，一条 Cue 都不出，
 * 而菜单仍显示「已挂载」（它读的是原始 uri）。
 */
class SubtitleCopyNameTest {

    @Test
    fun `copy extension follows the source format`() {
        assertEquals("ass", subtitleCopyExtension(NpSubtitleFormat.SSA))
        assertEquals("vtt", subtitleCopyExtension(NpSubtitleFormat.WEBVTT))
        assertEquals("ttml", subtitleCopyExtension(NpSubtitleFormat.TTML))
        assertEquals("srt", subtitleCopyExtension(NpSubtitleFormat.SUBRIP))
    }

    @Test
    fun `unknown format falls back to srt without inventing one from the file name`() {
        // 扩展名取自白名单，不是用户文件名上的任意串
        assertEquals("srt", subtitleCopyExtension(null))
    }
}
