package com.noneplayer.plugins.player.exo

import com.noneplayer.core.media.probe.PlatformDecoderSupport.DecoderCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-08：兼容档选择器的「能力快照 → 保留候选下标」纯决策桥。
 *
 * 这里不能用真实的 `MediaCodecInfo`（纯 JVM 不可构造），所以用 `hardwareFlags` 表达候选，
 * 断言 [npKeptCompatDecoderIndices] 的保留结果。矩阵与 core 侧 CapabilityMatrixTest 对齐：
 * 硬解存在 / 仅平台软解 / 仅扩展 / 全部不支持 / 查询异常。
 */
class NpCodecSelectorDecisionTest {

    private fun kept(
        hardwareFlags: List<Boolean>,
        hardwareAvailable: Boolean,
        softwareAvailable: Boolean,
        extensionHandles: Boolean,
        queryFailed: Boolean = false,
    ): List<Int> = npKeptCompatDecoderIndices(
        hardwareFlags,
        DecoderCapability(
            hardwareAvailable = hardwareAvailable,
            softwareAvailable = softwareAvailable,
            extensionHandles = extensionHandles,
            queryFailed = queryFailed,
        ),
    )

    @Test
    fun `hardware available keeps only hardware accelerated candidates`() {
        assertEquals(listOf(1), kept(listOf(false, true), true, true, false))
    }

    @Test
    fun `platform software only is kept and the selector is not empty`() {
        // REV-08 回归点：兼容档不能把唯一可用的平台软解过滤空。
        val kept = kept(listOf(false, false), hardwareAvailable = false, softwareAvailable = true, extensionHandles = false)
        assertEquals(listOf(0, 1), kept)
        assertTrue("仅平台软解时选择器不得为空", kept.isNotEmpty())
    }

    @Test
    fun `extension only lets the platform renderer stand aside`() {
        assertTrue(kept(listOf(false), false, true, true).isEmpty())
    }

    @Test
    fun `nothing supported keeps an empty list`() {
        assertTrue(kept(emptyList(), false, false, false).isEmpty())
    }

    @Test
    fun `query failure keeps every candidate`() {
        val kept = kept(listOf(true, false), false, false, false, queryFailed = true)
        assertEquals(listOf(0, 1), kept)
        assertTrue("查询异常时不得返回空", kept.isNotEmpty())
    }

    @Test
    fun `unknown query wins over hardware flags`() {
        // queryFailed=true 时即使快照声称有硬解，也必须全留（KEEP_UNKNOWN 优先）。
        assertEquals(listOf(0, 1), kept(listOf(false, true), true, true, true, queryFailed = true))
    }

    // ---- A18：解码器回退过滤包装（DecoderFallbackAwareSelector 的纯决策部分）----

    @Test
    fun `unarmed fallback state passes candidates through untouched`() {
        // 平时（未武装）必须直通：包装层不得改变 REV-08 的保留结果。
        val state = DecoderFallbackState()
        val candidates = listOf("c2.android.avc.decoder", "OMX.qcom.video.decoder.avc")
        assertEquals(candidates, state.filterCandidates("video/avc", candidates))
    }

    @Test
    fun `soft only video mime hides platform candidates for that mime only`() {
        val state = DecoderFallbackState()
        state.armSoftOnlyVideo("local:1", "video/mp4v-es")
        assertTrue(
            state.filterCandidates("video/mp4v-es", listOf("OMX.qcom.video.decoder.mpeg4")).isEmpty(),
        )
        // 其它 mime 不受影响（避免下一次装到 H.264 时被误伤）
        assertEquals(
            listOf("c2.android.avc.decoder"),
            state.filterCandidates("video/avc", listOf("c2.android.avc.decoder")),
        )
    }

    @Test
    fun `excluded codec is removed but other candidates are kept`() {
        val state = DecoderFallbackState()
        state.armExcludedCodec("local:1", "video/hevc", "OMX.qcom.video.decoder.hevc")
        assertEquals(
            listOf("c2.android.hevc.decoder"),
            state.filterCandidates(
                "video/hevc",
                listOf("OMX.qcom.video.decoder.hevc", "c2.android.hevc.decoder"),
            ),
        )
    }

    @Test
    fun `excluding the only candidate lets the extension renderer take over`() {
        val state = DecoderFallbackState()
        state.armExcludedCodec("local:1", "video/avc", "OMX.qcom.video.decoder.avc")
        assertTrue(
            state.filterCandidates("video/avc", listOf("OMX.qcom.video.decoder.avc")).isEmpty(),
        )
    }

    @Test
    fun `cleared fallback state returns to pass through`() {
        val state = DecoderFallbackState()
        state.armExcludedCodec("local:1", "video/avc", "OMX.qcom.video.decoder.avc")
        state.clear()
        assertEquals(
            listOf("OMX.qcom.video.decoder.avc"),
            state.filterCandidates("video/avc", listOf("OMX.qcom.video.decoder.avc")),
        )
    }
}
