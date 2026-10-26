package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `失败类别与 retryable 按环节区分`() {
        val audio = PlaybackFailure.audioOutput()
        assertEquals(PlaybackFailureCategory.AUDIO_OUTPUT, audio.category)
        assertTrue(audio.retryable)

        val render = PlaybackFailure.videoRender()
        assertEquals(PlaybackFailureCategory.VIDEO_RENDER, render.category)
        assertTrue(render.retryable)

        val decode = PlaybackFailure.videoCodecUnsupported("AVI", "Xvid", "MP3")
        assertEquals(PlaybackFailureCategory.VIDEO_DECODE, decode.category)
        assertFalse("永久性编码失败不应给重试", decode.retryable)

        val drm = PlaybackFailure.drm()
        assertEquals(PlaybackFailureCategory.DRM, drm.category)
        assertFalse(drm.retryable)

        val missing = PlaybackFailure.fileMissing()
        assertEquals(PlaybackFailureCategory.IO, missing.category)
        assertFalse(missing.retryable)

        assertTrue(PlaybackFailure.ioTransient().retryable)
        assertTrue(PlaybackFailure.videoDecodeTransient().retryable)
    }

    @Test
    fun `具体能力不足属于不可重试的视频解码失败`() {
        val failure = PlaybackFailure.videoCapabilityUnsupported(
            container = "MP4",
            videoCodec = "H.265 (HEVC)",
            audioCodec = "AAC",
            detail = "bit depth 10 not supported",
        )
        assertEquals(PlaybackFailureCategory.VIDEO_DECODE, failure.category)
        assertFalse(failure.retryable)
        assertTrue(failure.reason.contains("bit depth 10"))
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
        assertEquals("WMV3 (WMV9)", MediaNames.videoCodecName("video/x-ms-wmv"))
        assertEquals("H.265 (HEVC)", MediaNames.videoCodecName("video/hevc"))
        assertEquals("AAC", MediaNames.audioCodecName("audio/mp4a-latm"))
        assertEquals("MP3", MediaNames.audioCodecName("audio/mpeg"))
        // 未知 mime 原样透出（好过丢失信息）
        assertEquals("video/alien", MediaNames.videoCodecName("video/alien"))
    }
}
