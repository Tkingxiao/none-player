package com.noneplayer.core.media.probe

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E231（错扩展名：mp3 改名 .mp4）起播判定的纯 JVM 钉子。
 *
 * 场景：MediaStore 按后缀给 `video/mp4` → `expectVideo=true`，但认领文件的解封装器
 * 只声明了音频轨（mp3/adts/flac…改名伪装成视频后缀的都走这条）。修复前
 * `decide()` 一律落「没有受支持的视频编码」（VIDEO_DECODE）瞬态拦截，起播被挡；
 * 修复后"已 endTracks 且只有音频轨"按**音频文件**判读（AUDIO_DECODE 分支）。
 *
 * 纯 JVM 环境查不到平台解码器（`platformAudioDecoderQuery` 三态为 null、软解库不可
 * 加载），所以音频分支在本测试里落的是 `audioCodecUnsupported`（AUDIO_DECODE，
 * 瞬态）——**分支走向**是断言对象：拿 AUDIO_DECODE 证明"按音频判读"，拿
 * VIDEO_DECODE 证明"仍按缺视频轨拦截"。真机上的可播结论由设备取证覆盖。
 */
class FormatProbeAudioOnlyDecisionTest {

    private fun audioOnlyOutput(): FormatProbe.ProbeOutput =
        FormatProbe.ProbeOutput().apply {
            track(0, C.TRACK_TYPE_AUDIO).format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                    .setSampleRate(44_100)
                    .setChannelCount(2)
                    .build(),
            )
        }

    @Test
    fun `video 扩展名 mime 但内容只有音频轨且轨道表已结束_按音频判读`() {
        val output = audioOnlyOutput().apply { endTracks() }
        val outcome = output.decide(containerName = "MP3", expectVideo = true)
        val failure = (outcome.result as FormatProbe.Result.Unplayable).failure
        // 修复前这里是 unknownVideoCodec（VIDEO_DECODE）
        assertEquals(
            com.noneplayer.core.media.PlaybackFailureCategory.AUDIO_DECODE,
            failure.category,
        )
        assertTrue(!outcome.cacheable) // 查询异常下的结论不缓存（A17 语义不变）
    }

    @Test
    fun `音频 mime 且轨道表已结束_维持音频判读`() {
        val output = audioOnlyOutput().apply { endTracks() }
        val outcome = output.decide(containerName = "MP3", expectVideo = false)
        val failure = (outcome.result as FormatProbe.Result.Unplayable).failure
        assertEquals(
            com.noneplayer.core.media.PlaybackFailureCategory.AUDIO_DECODE,
            failure.category,
        )
    }

    @Test
    fun `轨道表未结束仍按缺视频轨拿不准_不触发内容判定`() {
        // endTracks 没到 = 轨道表可能不全（读被截断），旧语义必须保住
        val output = audioOnlyOutput()
        val outcome = output.decide(containerName = "MP4", expectVideo = true)
        val failure = (outcome.result as FormatProbe.Result.Unplayable).failure
        assertEquals(
            com.noneplayer.core.media.PlaybackFailureCategory.VIDEO_DECODE,
            failure.category,
        )
        assertTrue(!outcome.cacheable)
    }
}
