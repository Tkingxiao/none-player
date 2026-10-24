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
        // E207 avformat 批：一个解码器一个 mime（vp6/rv 各变体是独立解码器 ID）。
        // flv 的流编码名是 "flv1"（描述符名），解码器注册名是 "flv"——表值取后者
        assertEquals("flv", NpFfmpegCodecs.videoCodecInTable("video/x-flv1"))
        assertEquals("vp6", NpFfmpegCodecs.videoCodecInTable("video/x-vp6"))
        assertEquals("vp6f", NpFfmpegCodecs.videoCodecInTable("video/x-vp6f"))
        assertEquals("vp6a", NpFfmpegCodecs.videoCodecInTable("video/x-vp6a"))
        assertEquals("rv10", NpFfmpegCodecs.videoCodecInTable("video/x-pn-realvideo"))
        assertEquals("rv20", NpFfmpegCodecs.videoCodecInTable("video/x-pn-realvideo2"))
        assertEquals("rv30", NpFfmpegCodecs.videoCodecInTable("video/x-pn-realvideo3"))
        assertEquals("rv40", NpFfmpegCodecs.videoCodecInTable("video/x-pn-realvideo4"))
        assertEquals("ffv1", NpFfmpegCodecs.videoCodecInTable("video/x-ffv1"))
        assertEquals("theora", NpFfmpegCodecs.videoCodecInTable("video/theora"))
        // PS 的 mpeg1/2 走 npff 专属 mime（平台 OMX mpeg2 实测坏，见提取器注释）
        assertEquals("mpeg1video", NpFfmpegCodecs.videoCodecInTable("video/x-np-mpeg1"))
        assertEquals("mpeg2video", NpFfmpegCodecs.videoCodecInTable("video/x-np-mpeg2"))
        // WMV2 走 libnplegacy 专用链（既定裁定），不得被 npffmpeg 抢
        assertNull(NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_WMV2))
        // libnplegacy 的 ASP mime 同理
        assertNull(NpFfmpegCodecs.videoCodecInTable(MediaNames.MIME_MPEG4_ASP))
        // 标准集仍在 NextLib FfmpegRenderer，不归本表
        assertNull(NpFfmpegCodecs.videoCodecInTable("video/avc"))
        assertNull(NpFfmpegCodecs.videoCodecInTable("video/mpeg2"))
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
        // E207：RM/NUT 音频族按"一个解码器一个 mime"直接入表
        assertEquals("cook", NpFfmpegCodecs.audioCodecInTable("audio/x-cook", emptyList()))
        assertEquals("sipr", NpFfmpegCodecs.audioCodecInTable("audio/x-sipr", emptyList()))
        assertEquals("atrac3", NpFfmpegCodecs.audioCodecInTable("audio/x-atrac3", emptyList()))
        assertEquals("wavpack", NpFfmpegCodecs.audioCodecInTable("audio/x-wavpack", emptyList()))
        assertEquals("tta", NpFfmpegCodecs.audioCodecInTable("audio/x-tta", emptyList()))
        // atrac3al/pal 提取器产出独立 mime 但不入表：留给探测给 A11 提示，不错误路由
        assertNull(NpFfmpegCodecs.audioCodecInTable("audio/x-atrac3al", emptyList()))
        assertNull(NpFfmpegCodecs.audioCodecInTable("audio/x-atrac3pal", emptyList()))
        // mp1/mp2 由 npff 软解补位（平台普遍无 L2 解码器，无人认领会冻结媒体时钟）
        assertEquals("mp1", NpFfmpegCodecs.audioCodecInTable("audio/mpeg-L1", emptyList()))
        assertEquals("mp2", NpFfmpegCodecs.audioCodecInTable("audio/mpeg-L2", emptyList()))
        // 表外 mime 一律 null
        assertNull(NpFfmpegCodecs.audioCodecInTable("audio/mp3", emptyList()))
    }

    private fun wavHeader(tag: Int, blockAlign: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val bytes = ByteArray(18 + extra.size)
        fun put16(offset: Int, v: Int) {
            bytes[offset] = (v and 0xff).toByte()
            bytes[offset + 1] = ((v ushr 8) and 0xff).toByte()
        }
        put16(0, tag)
        put16(12, blockAlign)
        put16(16, extra.size)
        extra.copyInto(bytes, 18)
        return bytes
    }

    @Test
    fun `WAVEFORMATEX 只在完整头时提供 tag 与 blockAlign`() {
        val wmav1 = wavHeader(0x0160, 4096)
        val full = NpFfmpegCodecs.waveFormatExOf(listOf(wmav1))
        assertEquals(0x0160, full?.tag)
        assertEquals(4096, full?.blockAlign)
        assertTrue(NpFfmpegCodecs.hasWaveFormatEx(listOf(wmav1)))

        // 只有 2 字节裸 tag（历史测试/内部用法）：能路由，但不算完整头
        assertFalse(NpFfmpegCodecs.hasWaveFormatEx(listOf(byteArrayOf(0x60, 0x01))))
        assertEquals(
            "wmav1",
            NpFfmpegCodecs.audioCodecInTable("audio/x-ms-wma", listOf(byteArrayOf(0x60, 0x01))),
        )

        // Media3Avi 的 codec-data-only 头两字节碰巧像 tag：cbSize 不自洽 → 不冒充完整头
        val codecData = ByteArray(24).also { it[0] = 0x60; it[1] = 0x01 }
        assertFalse(NpFfmpegCodecs.hasWaveFormatEx(listOf(codecData)))
        assertEquals("wmav2", NpFfmpegCodecs.audioCodecInTable("audio/x-ms-wma", listOf(codecData)))

        // WMA Pro/Lossless 的完整头同样可解析（解码器侧据此剥头/取 blockAlign）
        assertEquals(0x0162, NpFfmpegCodecs.waveFormatExOf(listOf(wavHeader(0x0162, 1024)))?.tag)
        assertEquals(0x0163, NpFfmpegCodecs.waveFormatExOf(listOf(wavHeader(0x0163, 2048)))?.tag)
    }

    @Test
    fun `wma 族判据覆盖三个 mime`() {
        assertTrue(NpFfmpegCodecs.isWmaFamily(MediaNames.MIME_WMA))
        assertTrue(NpFfmpegCodecs.isWmaFamily(MediaNames.MIME_WMAPRO))
        assertTrue(NpFfmpegCodecs.isWmaFamily(MediaNames.MIME_WMALOSSLESS))
        assertFalse(NpFfmpegCodecs.isWmaFamily("audio/mpeg"))
        assertFalse(NpFfmpegCodecs.isWmaFamily(null))
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
