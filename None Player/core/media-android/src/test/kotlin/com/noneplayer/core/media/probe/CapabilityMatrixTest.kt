package com.noneplayer.core.media.probe

import com.noneplayer.core.media.probe.PlatformDecoderSupport.DecoderCapability
import com.noneplayer.core.media.probe.PlatformDecoderSupport.DecoderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-08：解码能力快照 → 决策矩阵（纯 JVM，不触碰 MediaCodecList / MediaFormat）。
 *
 * 探测侧（[FormatProbe]）与播放侧兼容档选择器必须落在同一套规则上，因此这里只喂
 * [DecoderCapability] 布尔快照、断言 [PlatformDecoderSupport.decideVideoDecoderPolicy]
 * 与 [PlatformDecoderSupport.applyVideoDecoderPolicy] 的结果，不依赖任何设备。
 */
class CapabilityMatrixTest {

    /** 候选解码器的替身：只关心 hardwareAccelerated，与 media3 的 MediaCodecInfo 解耦 */
    private data class FakeCodec(val name: String, val hardware: Boolean)

    private fun decide(
        hardware: Boolean,
        software: Boolean,
        extension: Boolean,
        queryFailed: Boolean = false,
    ): DecoderPolicy = PlatformDecoderSupport.decideVideoDecoderPolicy(
        DecoderCapability(
            hardwareAvailable = hardware,
            softwareAvailable = software,
            extensionHandles = extension,
            queryFailed = queryFailed,
        ),
    )

    private fun keptIndices(
        hardware: Boolean,
        software: Boolean,
        extension: Boolean,
        flags: List<Boolean>,
        queryFailed: Boolean = false,
    ): List<Int> {
        val policy = decide(hardware, software, extension, queryFailed)
        val candidates = flags.mapIndexed { index, hw -> FakeCodec("codec$index", hw) }
        val kept = PlatformDecoderSupport.applyVideoDecoderPolicy(policy, candidates) { it.hardware }
        return kept.map { candidates.indexOf(it) }
    }

    // ---- 第 1 组：平台有硬解 ----

    @Test
    fun `hardware present keeps only hardware accelerated codecs`() {
        assertEquals(DecoderPolicy.HARDWARE_ONLY, decide(hardware = true, software = true, extension = false))
        assertEquals(listOf(0), keptIndices(true, true, false, listOf(true, false)))
    }

    @Test
    fun `hardware present wins even when extension also handles the format`() {
        assertEquals(DecoderPolicy.HARDWARE_ONLY, decide(hardware = true, software = true, extension = true))
        assertEquals(listOf(1), keptIndices(true, true, true, listOf(false, true)))
    }

    // ---- 第 2 组：仅平台软解（扩展接不了）→ 软解保底 ----

    @Test
    fun `platform software only is kept as last resort`() {
        assertEquals(DecoderPolicy.PLATFORM_ALL, decide(hardware = false, software = true, extension = false))
        assertEquals(listOf(0, 1), keptIndices(false, true, false, listOf(false, false)))
    }

    @Test
    fun `platform software only never yields an empty selector list`() {
        // REV-08 的回归点：旧实现把唯一可用的平台软解也过滤掉 → 选择器返回空。
        val kept = keptIndices(false, true, false, listOf(false, false, false))
        assertTrue("仅平台软解时选择器不得为空", kept.isNotEmpty())
    }

    // ---- 第 3 组：仅扩展可解 → 平台不认领 ----

    @Test
    fun `extension only lets platform candidates be filtered away`() {
        assertEquals(DecoderPolicy.FILTER_TO_EXTENSION, decide(hardware = false, software = true, extension = true))
        assertTrue(keptIndices(false, true, true, listOf(false)).isEmpty())
    }

    @Test
    fun `extension only without platform software keeps nothing`() {
        assertEquals(DecoderPolicy.FILTER_TO_EXTENSION, decide(hardware = false, software = false, extension = true))
        assertTrue(keptIndices(false, false, true, listOf(false, false)).isEmpty())
    }

    // ---- 第 4 组：全部不支持 ----

    @Test
    fun `nothing supported keeps empty list without inventing a policy`() {
        // 没有 NONE_SUPPORTED 常量：平台本来就没有候选，全留即空，播放链条交给扩展/后续渲染器。
        assertEquals(DecoderPolicy.PLATFORM_ALL, decide(hardware = false, software = false, extension = false))
        assertTrue(keptIndices(false, false, false, emptyList()).isEmpty())
    }

    // ---- 第 5 组：查询异常 / 无法判定 ----

    @Test
    fun `query failure keeps every candidate`() {
        assertEquals(
            DecoderPolicy.KEEP_UNKNOWN,
            decide(hardware = false, software = false, extension = false, queryFailed = true),
        )
        assertEquals(listOf(0, 1), keptIndices(false, false, false, listOf(true, false), queryFailed = true))
    }

    @Test
    fun `query failure is not interpreted as unsupported`() {
        // 查询异常时必须保留候选（不得判死、不得返回空），优先级高于任何能力位。
        assertEquals(
            DecoderPolicy.KEEP_UNKNOWN,
            decide(hardware = true, software = true, extension = true, queryFailed = true),
        )
        assertTrue(keptIndices(true, true, true, listOf(false), queryFailed = true).isNotEmpty())
    }

    @Test
    fun `policy is mime agnostic so ASP and H264 take the same path`() {
        // 决策只看能力快照、不看 mime：ASP(WMV2/mp4v-es) 与 H.264 只要能力相同就同路径。
        listOf("video/avc", "video/mp4v-es", "video/x-ms-wmv").forEach { mime ->
            assertEquals(
                "mime=$mime",
                DecoderPolicy.PLATFORM_ALL,
                decide(hardware = false, software = true, extension = false),
            )
        }
    }

    // ---- 扩展能力查询（JVM 安全子集）----

    @Test
    fun `mjpeg is always handled by the bitmap extension`() {
        assertTrue(PlatformDecoderSupport.extensionHandlesVideo("video/mjpeg"))
    }

    @Test
    fun `null or blank mime is never handled by extension`() {
        assertEquals(false, PlatformDecoderSupport.extensionHandlesVideo(null))
        assertEquals(false, PlatformDecoderSupport.extensionHandlesVideo(""))
    }
}
