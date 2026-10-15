package com.noneplayer.plugins.player.exo

import androidx.media3.common.MimeTypes
import com.noneplayer.kernel.api.NpSubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A3：字幕 mime 的取用顺序。
 *
 * 显式格式优先，`null` 才退回按 uri 末段猜（旧口径）。这条顺序是修复的全部要点——
 * 转码副本与 MediaStore 自动匹配回来的字幕，都靠挂载方把格式带进来才对上解容器。
 */
class SubtitleMimeTest {

    @Test
    fun `explicit format wins over the uri suffix`() {
        // 副本落在缓存目录、uri 末段是 id 的媒体库字幕，都必须靠显式格式
        assertEquals(
            MimeTypes.TEXT_SSA,
            npSubtitleMime("content://media/external/file/1234", NpSubtitleFormat.SSA),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            npSubtitleMime("/data/user/0/app/cache/subs/movie-abc.vtt", NpSubtitleFormat.WEBVTT),
        )
    }

    @Test
    fun `copy keeps the source format so the suffix still matches`() {
        // 修前：ASS 转码副本被写成 .srt → 这里读回 SubRip → 零 Cue
        assertEquals(
            MimeTypes.TEXT_SSA,
            npSubtitleMime("/cache/subs/进击的巨人-9f2c1a.ass", NpSubtitleFormat.SSA),
        )
    }

    @Test
    fun `without a format the old uri guess still applies`() {
        assertEquals(MimeTypes.TEXT_SSA, npSubtitleMime("movie.ass", null))
        assertEquals(MimeTypes.TEXT_VTT, npSubtitleMime("movie.vtt", null))
        assertEquals(MimeTypes.APPLICATION_TTML, npSubtitleMime("movie.ttml", null))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, npSubtitleMime("movie.srt", null))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, npSubtitleMime("content://media/external/file/1234", null))
    }

    @Test
    fun `mime reads back into the same format it was written from`() {
        NpSubtitleFormat.entries.forEach { format ->
            assertEquals(format, npSubtitleFormatOfMime(npSubtitleMime("x.${format.extension}", format)))
        }
        assertEquals(null, npSubtitleFormatOfMime(null))
        assertEquals(null, npSubtitleFormatOfMime("video/mp4"))
    }
}
