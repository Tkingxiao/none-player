package com.noneplayer.plugins.player.exo

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A18：解码器初始化失败的一次性受控回退。
 *
 * 钉四件事：
 * 1. 从 `PlaybackException.message` 容错解析失败轨的 mime 与平台 codec 名
 *    （media3 的 `DecoderInitializationException` 跨 binder 只保留 message，
 *    形如 `"Decoder init failed: <codecName>, Format(1, video/avc, ...)"`）；
 * 2. 音/视频分流：音频轨解码失败绝不进"视频不可解"也不触发视频回退；
 * 3. 回退决策：视频解码失败最多重试一次，mp4v 走软解链，其余排除失败 codec；
 * 4. 状态（排除的 codec / 只走软解的 mime / 本次媒体已回退过）按媒体键校验、
 *    切换媒体即复位，不污染下一条。
 */
class DecoderFallbackTest {

    // ---- ① message 解析（容错，任何输入都不许抛）----

    @Test
    fun parsesVideoCodecNameAndMime() {
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: OMX.qcom.video.decoder.avc, Format(1, video/avc, null, -1, null)",
        )
        assertEquals("video/avc", parsed.mime)
        assertEquals("OMX.qcom.video.decoder.avc", parsed.codecName)
    }

    @Test
    fun parsesAudioMime() {
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: OMX.google.aac.decoder, Format(2, audio/mp4a-latm, null, -1, null)",
        )
        assertEquals("audio/mp4a-latm", parsed.mime)
        assertEquals("OMX.google.aac.decoder", parsed.codecName)
    }

    @Test
    fun bracketFormHasNoCodecName() {
        // 无可用解码器时 media3 用 "Decoder init failed: [<errorCode>], <format>"
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: [-10000], Format(1, video/mp4v-es, null, -1, null)",
        )
        assertEquals("video/mp4v-es", parsed.mime)
        assertNull(parsed.codecName)
    }

    @Test
    fun parseToleratesNullAndBlankMessage() {
        val fromNull = parseDecoderFailureMessage(null)
        assertNull(fromNull.mime)
        assertNull(fromNull.codecName)

        val fromBlank = parseDecoderFailureMessage("")
        assertNull(fromBlank.mime)
        assertNull(fromBlank.codecName)
    }

    @Test
    fun parseToleratesUnrelatedText() {
        val parsed = parseDecoderFailureMessage("MediaCodecVideoRenderer error, index=0")
        assertNull(parsed.mime)
        assertNull(parsed.codecName)
    }

    // ---- ② 分类：只有 DECODER_INIT_FAILED / EXCEEDS_CAPABILITIES 可回退 ----

    @Test
    fun decoderInitOnVideoIsVideoInit() {
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: c2.android.avc.decoder, Format(1, video/avc, null, -1, null)",
        )
        assertEquals(
            DecoderFailureKind.VIDEO_DECODER_INIT,
            classifyDecoderFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, parsed),
        )
    }

    @Test
    fun decoderInitOnAudioIsAudioInit() {
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: OMX.google.aac.decoder, Format(2, audio/mp4a-latm, null, -1, null)",
        )
        assertEquals(
            DecoderFailureKind.AUDIO_DECODER_INIT,
            classifyDecoderFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, parsed),
        )
    }

    @Test
    fun exceedsCapabilitiesOnVideoAndAudio() {
        val video = parseDecoderFailureMessage(
            "MediaCodecVideoRenderer error, index=0, format=Format(1, video/hevc, null, -1, null)",
        )
        assertEquals(
            DecoderFailureKind.VIDEO_EXCEEDS_CAPABILITIES,
            classifyDecoderFailure(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                video,
            ),
        )
        val audio = parseDecoderFailureMessage(
            "MediaCodecAudioRenderer error, format=Format(2, audio/opus, null, -1, null)",
        )
        assertEquals(
            DecoderFailureKind.AUDIO_EXCEEDS_CAPABILITIES,
            classifyDecoderFailure(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                audio,
            ),
        )
    }

    @Test
    fun unparsableMessageStillRetryableAsVideo() {
        // 容错：解析不出 mime 时按视频处理——只做"同媒体重试一次"，绝不误判成音频
        val parsed = parseDecoderFailureMessage("MediaCodecVideoRenderer error, index=0")
        val kind = classifyDecoderFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, parsed)
        assertEquals(DecoderFailureKind.VIDEO_DECODER_INIT, kind)
        assertFalse(isAudioDecoderFailure(kind, parsed.mime))
    }

    @Test
    fun otherCodesAreNotRetryable() {
        val parsed = parseDecoderFailureMessage(
            "Decoder init failed: c2.android.avc.decoder, Format(1, video/avc, null, -1, null)",
        )
        val kind = classifyDecoderFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, parsed)
        assertEquals(DecoderFailureKind.OTHER, kind)
        assertFalse(shouldRetryDecoderFallback(kind, alreadyRetriedThisMedia = false))
    }

    @Test
    fun audioKindDoesNotTriggerVideoRetry() {
        assertFalse(
            shouldRetryDecoderFallback(DecoderFailureKind.AUDIO_DECODER_INIT, alreadyRetriedThisMedia = false),
        )
        assertFalse(
            shouldRetryDecoderFallback(
                DecoderFailureKind.AUDIO_EXCEEDS_CAPABILITIES,
                alreadyRetriedThisMedia = false,
            ),
        )
    }

    @Test
    fun audioFailureMappingIsRecognizedFromKindOrMime() {
        assertTrue(isAudioDecoderFailure(DecoderFailureKind.AUDIO_DECODER_INIT, null))
        assertTrue(isAudioDecoderFailure(DecoderFailureKind.OTHER, "audio/mpeg"))
        assertFalse(isAudioDecoderFailure(DecoderFailureKind.VIDEO_DECODER_INIT, "video/avc"))
        assertFalse(isAudioDecoderFailure(DecoderFailureKind.OTHER, null))
    }

    // ---- ③ 回退决策与"只一次"闸门 ----

    @Test
    fun shouldRetryOnceForVideoInit() {
        assertTrue(
            shouldRetryDecoderFallback(DecoderFailureKind.VIDEO_DECODER_INIT, alreadyRetriedThisMedia = false),
        )
        assertFalse(
            shouldRetryDecoderFallback(DecoderFailureKind.VIDEO_DECODER_INIT, alreadyRetriedThisMedia = true),
        )
    }

    @Test
    fun planExcludesFailedCodecForNonMp4v() {
        val plan = fallbackPlan(
            DecoderFailureKind.VIDEO_DECODER_INIT,
            ParsedDecoderFailure(mime = "video/avc", codecName = "OMX.qcom.video.decoder.avc"),
        )
        assertEquals(DecoderFallbackAction.RETRY_WITHOUT_FAILED_CODEC, plan.action)
        assertEquals("OMX.qcom.video.decoder.avc", plan.codecName)
    }

    @Test
    fun planRoutesMp4vToSoftChain() {
        val plan = fallbackPlan(
            DecoderFailureKind.VIDEO_DECODER_INIT,
            ParsedDecoderFailure(mime = "video/mp4v-es", codecName = "OMX.qcom.video.decoder.mpeg4"),
        )
        assertEquals(DecoderFallbackAction.RETRY_WITH_SOFT_ONLY, plan.action)
        assertEquals("video/mp4v-es", plan.mime)
    }

    @Test
    fun planGivesUpWhenNothingToExclude() {
        val plan = fallbackPlan(
            DecoderFailureKind.VIDEO_DECODER_INIT,
            ParsedDecoderFailure(mime = null, codecName = null),
        )
        assertEquals(DecoderFallbackAction.RETRY_WITHOUT_FAILED_CODEC, plan.action)
        assertNull(plan.codecName)
    }

    @Test
    fun planGivesUpForAudio() {
        val plan = fallbackPlan(
            DecoderFailureKind.AUDIO_DECODER_INIT,
            ParsedDecoderFailure(mime = "audio/mp4a-latm", codecName = "OMX.google.aac.decoder"),
        )
        assertEquals(DecoderFallbackAction.GIVE_UP, plan.action)
    }

    @Test
    fun gateAllowsOneAttemptPerMedia() {
        val gate = DecoderFallbackGate()
        assertTrue(gate.canRetry(DecoderFailureKind.VIDEO_DECODER_INIT, "local:1"))
        gate.markAttempted("local:1")
        assertFalse(gate.canRetry(DecoderFailureKind.VIDEO_DECODER_INIT, "local:1"))
        // 切到另一条媒体后复位
        gate.onMediaChanged()
        assertTrue(gate.canRetry(DecoderFailureKind.VIDEO_DECODER_INIT, "local:2"))
    }

    @Test
    fun gateNeverRetriesAudioEvenWhenReset() {
        val gate = DecoderFallbackGate()
        assertFalse(gate.canRetry(DecoderFailureKind.AUDIO_DECODER_INIT, "local:1"))
        gate.onMediaChanged()
        assertFalse(gate.canRetry(DecoderFailureKind.AUDIO_EXCEEDS_CAPABILITIES, "local:1"))
    }

    // ---- ④ 进程内回退状态：媒体键校验 + 切换复位 ----

    @Test
    fun stateExposesArmedExclusionUntilCleared() {
        val state = DecoderFallbackState()
        assertFalse(state.isArmed)
        state.armExcludedCodec("local:1", "video/avc", "OMX.qcom.video.decoder.avc")
        assertTrue(state.isArmed)
        assertEquals("OMX.qcom.video.decoder.avc", state.excludedCodecNameFor("video/avc"))
        assertNull(state.excludedCodecNameFor("video/hevc"))
        assertTrue(state.isArmedFor("local:1"))
        assertFalse(state.isArmedFor("local:2"))
        state.clear()
        assertFalse(state.isArmed)
        assertNull(state.excludedCodecNameFor("video/avc"))
    }

    @Test
    fun stateSoftOnlyVideoAppliesToMp4vOnly() {
        val state = DecoderFallbackState()
        state.armSoftOnlyVideo("local:1", "video/mp4v-es")
        assertTrue(state.isSoftOnlyVideo("video/mp4v-es"))
        assertFalse(state.isSoftOnlyVideo("video/avc"))
        assertFalse(state.isSoftOnlyVideo("audio/mpeg"))
    }
}
