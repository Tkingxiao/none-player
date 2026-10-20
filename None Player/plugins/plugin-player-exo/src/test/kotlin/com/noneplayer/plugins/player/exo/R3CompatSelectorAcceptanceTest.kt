package com.noneplayer.plugins.player.exo

import com.noneplayer.core.media.probe.PlatformDecoderSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 独立验收（REV-08）——**播放侧兼容档选择器桥** `npKeptCompatDecoderIndices`。
 *
 * 该函数是 `HardwareOnlyVideoCodecSelector` 与纯 JVM 测试之间的桥：能力快照 + 每个候选的
 * `hardwareAccelerated` 标记 → 应保留的候选下标（`MediaCodecInfo` 在纯 JVM 不可构造，
 * 因此只能测这一层，五组能力矩阵的验收主体在 core:media-android 的
 * `R3CapabilityMatrixAcceptanceTest`）。
 *
 * 核心防回归：**平台仅有软解时，兼容档选择器不得把唯一可用的解码器过滤成空列表**。
 */
class R3CompatSelectorAcceptanceTest {

    private fun capability(
        hardware: Boolean,
        software: Boolean,
        extension: Boolean,
        queryFailed: Boolean = false,
    ): PlatformDecoderSupport.DecoderCapability = PlatformDecoderSupport.DecoderCapability(
        hardwareAvailable = hardware,
        softwareAvailable = software,
        extensionHandles = extension,
        queryFailed = queryFailed,
    )

    @Test
    fun hardwareCandidatesWinWhenHardwareAvailable() {
        assertEquals(
            "有硬解时只保留硬解下标",
            listOf(1),
            npKeptCompatDecoderIndices(listOf(false, true), capability(hardware = true, software = true, extension = false)),
        )
        assertEquals(
            "有硬解时（即便扩展可解）仍只保留硬解下标",
            listOf(0),
            npKeptCompatDecoderIndices(listOf(true, false), capability(true, true, true)),
        )
    }

    @Test
    fun softwareOnlyPlatformKeepsNonEmptyFallback() {
        // 核心防回归：平台仅有软解、扩展接不了 → 必须保留平台软解（非空）
        val kept = npKeptCompatDecoderIndices(
            hardwareFlags = listOf(false, false),
            capability = capability(hardware = false, software = true, extension = false),
        )
        assertEquals("仅平台软解时必须保留全部候选", listOf(0, 1), kept)
        assertTrue("平台仅有软解时兼容档选择器不得返回空列表", kept.isNotEmpty())
    }

    @Test
    fun extensionOnlyLetsPlatformNotClaimCandidates() {
        assertEquals(
            "扩展确定可解时平台不认领（过滤成空，交给扩展渲染器）",
            emptyList<Int>(),
            npKeptCompatDecoderIndices(listOf(false), capability(false, software = true, extension = true)),
        )
    }

    @Test
    fun noPlatformCandidatesStaysEmpty() {
        // 全部不支持：平台候选本就没有 → 输出必须为空（明确不支持）
        assertEquals(
            "无平台候选时必须返回空列表",
            emptyList<Int>(),
            npKeptCompatDecoderIndices(emptyList(), capability(false, false, false)),
        )
    }

    @Test
    fun queryFailureKeepsAllCandidatesAndNeverDies() {
        assertEquals(
            "查询异常时保留全部候选（不判死、不返回空）",
            listOf(0, 1),
            npKeptCompatDecoderIndices(
                hardwareFlags = listOf(true, false),
                capability = capability(false, false, false, queryFailed = true),
            ),
        )
    }

    @Test
    fun hardwareFlagsAreRespectedInOrder() {
        // 混合候选：保留顺序必须与输入一致，避免把下标顺序打乱导致选择器取错解码器
        assertEquals(
            listOf(0, 2),
            npKeptCompatDecoderIndices(
                hardwareFlags = listOf(true, false, true),
                capability = capability(true, false, false),
            ),
        )
    }
}
