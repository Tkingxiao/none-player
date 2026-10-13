package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFailureTest {

    @Test
    fun `trackLine 三行拼接与分隔符`() {
        val failure = PlaybackFailure.videoCodecUnsupported("AVI", "MPEG-4 Visual (Xvid/DivX)", "MP3")
        assertEquals(
            "容器：AVI ｜ 视频：MPEG-4 Visual (Xvid/DivX) ｜ 音频：MP3",
            failure.trackLine,
        )
    }

    @Test
    fun `trackLine 缺项跳过`() {
        val failure = PlaybackFailure.containerUnsupported("RealMedia (RMVB)")
        assertEquals("容器：RealMedia (RMVB)", failure.trackLine)
        assertNull(PlaybackFailure.unexpected("x").trackLine)
    }

    @Test
    fun `容器名映射：已知扩展名与未知扩展名`() {
        assertEquals("AVI", MediaNames.containerName("file:///a/b/x.avi"))
        assertEquals("Matroska (MKV)", MediaNames.containerName("content://x/y.mkv"))
        assertEquals("RealMedia (RMVB)", MediaNames.containerName("file:///m/old.rmvb"))
        assertEquals("XYZ", MediaNames.containerName("file:///m/weird.xyz"))
        assertNull(MediaNames.containerName("file:///no-extension"))
    }

    @Test
    fun `编码名映射：常见与未知 mime`() {
        assertEquals("H.264 (AVC)", MediaNames.videoCodecName("video/avc"))
        assertEquals("MPEG-4 Visual (Xvid/DivX)", MediaNames.videoCodecName("video/mp4v-es"))
        assertEquals("WMV2", MediaNames.videoCodecName(MediaNames.MIME_WMV2))
        assertEquals("MPEG-4 ASP (Qpel)", MediaNames.videoCodecName(MediaNames.MIME_MPEG4_ASP))
        assertEquals("WMV (VC-1)", MediaNames.videoCodecName("video/x-ms-wmv"))
        assertEquals("H.265 (HEVC)", MediaNames.videoCodecName("video/hevc"))
        assertEquals("AAC", MediaNames.audioCodecName("audio/mp4a-latm"))
        assertEquals("MP3", MediaNames.audioCodecName("audio/mpeg"))
        // 未知 mime 原样透出（好过丢失信息）
        assertEquals("video/alien", MediaNames.videoCodecName("video/alien"))
    }
}
