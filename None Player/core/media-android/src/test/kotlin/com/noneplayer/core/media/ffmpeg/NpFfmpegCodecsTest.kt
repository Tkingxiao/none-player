package com.noneplayer.core.media.ffmpeg

import com.noneplayer.core.media.MediaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单路由表的纯 JVM 回归（E204）。
 *
 * 表是播放链（NpFfmpeg 渲染器）与探测侧（FormatProbe）的唯一路由来源——
 * 表内容与 tag 细分规则在 [NpFfmpegCodecs.videoCodecInTable]/[NpFfmpegCodecs.audioCodecInTable]
 * （internal 纯函数，无可用性闸）上钉住；公开查询在 JVM 无 native 时必须全部返回
 * null（探测不得冒充可解）。
 */
class NpFfmpegCodecsTest {

    @Test
    fun `视频表覆盖白名单可达 mime 且不与 legacy 链重叠`() {
        // 可达集：AVI（本插件提取器认领）+ MKV（MatroskaExtractor 的 wvc1）
        assertEquals("wmv3", NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_WMV3))
        assertEquals("wmv1", NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_WMV1))
        assertEquals("vc1", NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_VC1))
        assertEquals("msmpeg4v2", NpFfmpegCodecs.videoCodecInTable("video/mp42"))
        assertEquals("msmpeg4", NpFfmpegCodecs.videoCodecInTable("video/mp43"))
        // WMV2 走 libnplegacy 专用链（既定裁定），不得被 npffmpeg 抢
        assertNull(NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_WMV2))
        // libnplegacy 的 ASP mime 同理
        assertNull(NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_MPEG4_ASP))
        // 标准集仍在 NextLib FfmpegRenderer，不归本表
        assertNull(NpFfmpegCodecs.videoCodecInTable("video/avc"))
    }

    @Test
    fun `音频表按 WAVE tag 细分 wma 族`() {
        // 无 extradata：按最常见的 wmav2 兜底
        assertEquals("wmav2", NpFfmpegCodecs.audioCodecInTable(MediaNames.MIME_WMA, emptyList()))
        // tag 0x0160/0x0161 来自 WAVEFORMATEX 前两字节（Media3Avi 与本插件的 csd 都是整段 strf）
        assertEquals(
            "wmav1",
            NpFfmpegCodecs.audioCodecInTable(MediaNames.MIME_WMA, listOf(byteArrayOf(0x60, 0x01))),
        )
        assertEquals(
            "wmav2",
            NpFfmpegCodecs.audioCodecInTable(MediaNames.MIME_WMA, listOf(byteArrayOf(0x61, 0x01))),
        )
        // wmapro/wmalossless 有专属 mime，extradata 不参与判别
        assertEquals("wmapro", NpFfmpegCodecs.audioCodecInTable(MediaNames.MIME_WMAPRO, emptyList()))
        assertEquals(
            "wmalossless",
            NpFfmpegCodecs.audioCodecInTable(MediaNames.MIME_WMALOSSLESS, emptyList()),
        )
        // 表外 mime 一律 null
        assertNull(NpFfmpegCodecs.audioCodecInTable("audio/mp3", emptyList()))
    }

    @Test
    fun `JVM 无 native 时公开查询不冒充可解，inTable 仍反映表本身`() {
        // Robolectric/JVM 下 System.loadLibrary("npffmpeg") 必然失败 → isAvailable=false
        assertFalse(NpFfmpegCodecs.isAvailable())
        assertNull(NpFfmpegCodecs.videoCodecName(MediaNames.MIME_WMV3))
        assertNull(NpFfmpegCodecs.audioCodecName(MediaNames.MIME_WMA, emptyList()))
        assertTrue(NpFfmpegCodecs.inTable(MediaNames.MIME_WMV3))
        assertTrue(NpFfmpegCodecs.inTable(MediaNames.MIME_WMA))
        assertFalse(NpFfmpegCodecs.inTable("video/avc"))
        assertFalse(NpFfmpegCodecs.inTable(null))
    }
}
