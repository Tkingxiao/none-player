package com.noneplayer.core.media.probe

import com.noneplayer.core.media.probe.PlatformDecoderSupport.DecoderCapability
import com.noneplayer.core.media.probe.PlatformDecoderSupport.DecoderPolicy
import com.noneplayer.core.media.probe.PlatformDecoderSupport.applyVideoDecoderPolicy
import com.noneplayer.core.media.probe.PlatformDecoderSupport.decideVideoDecoderPolicy
import com.noneplayer.core.media.probe.PlatformDecoderSupport.extensionHandlesVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 独立验收（REV-08「能力矩阵一致」）。
 *
 * 验收口径：**可注入能力快照 + 决策内核**（纯 JVM，不 mock android.media 真机查询）。
 * 五组能力矩阵必须与「探测侧可播判定 / 播放侧兼容档选择器」同一规则：
 *
 * | 能力快照                                   | 期望决策              | 期望保留候选         |
 * |-------------------------------------------|-----------------------|----------------------|
 * | 有硬解                                    | HARDWARE_ONLY         | 只留硬解             |
 * | 仅平台软解、扩展接不了                    | PLATFORM_ALL          | 全留（**非空**）     |
 * | 仅平台软解、扩展可解                      | FILTER_TO_EXTENSION   | 过滤成空（平台不认领）|
 * | 全部不支持                                | PLATFORM_ALL（空候选） | 空（明确不支持）     |
 * | 查询异常（queryFailed）                   | KEEP_UNKNOWN          | 全留（不判死）       |
 *
 * 解码路径维度（H.264 硬解、Simple mp4v 平台软解保底、ASP 专用扩展链）：决策内核与 mime
 * **无关**（mime 只通过 `extensionHandles` 快照字段进入决策），因此矩阵按能力快照构造，
 * 并以 [Scenario] 标注路径维度；mime 侧单独验收 [extensionHandlesVideo] 的 JVM 安全项。
 */
class R3CapabilityMatrixAcceptanceTest {

    /** 候选解码器替身：任意类型的候选都能进泛型内核，纯 JVM 可构造 */
    private data class Candidate(val name: String, val hardware: Boolean)

    /** 混合候选（含硬解）：用于“有硬解/查询异常”场景，候选必须与能力快照一致 */
    private val mixedCandidates = listOf(
        Candidate("platform-software-old", hardware = false),
        Candidate("platform-hardware", hardware = true),
        Candidate("platform-software-new", hardware = false),
    )

    /** 仅平台软解候选：用于“仅平台软解”场景，避免能力快照与候选列表自相矛盾 */
    private val softwareOnlyCandidates = listOf(
        Candidate("platform-software-old", hardware = false),
        Candidate("platform-software-new", hardware = false),
    )

    private fun keptCandidates(
        capability: DecoderCapability,
        candidates: List<Candidate> = mixedCandidates,
    ): List<String> =
        applyVideoDecoderPolicy(decideVideoDecoderPolicy(capability), candidates) { it.hardware }
            .map { it.name }

    // ---------------- 五组能力矩阵 ----------------

    @Test
    fun matrixHardwarePresentKeepsOnlyHardwareCandidates() {
        // 组 1：平台有硬解 → 只返回硬解（扩展是否可解不改变硬解优先）
        val cases = listOf(
            DecoderCapability(hardwareAvailable = true, softwareAvailable = true, extensionHandles = false),
            DecoderCapability(hardwareAvailable = true, softwareAvailable = false, extensionHandles = false),
            DecoderCapability(hardwareAvailable = true, softwareAvailable = true, extensionHandles = true),
        )
        cases.forEach { capability ->
            assertEquals(
                "有硬解时必须选择 HARDWARE_ONLY：$capability",
                DecoderPolicy.HARDWARE_ONLY,
                decideVideoDecoderPolicy(capability),
            )
            assertEquals(
                "有硬解时只保留硬解候选：$capability",
                listOf("platform-hardware"),
                keptCandidates(capability),
            )
        }
    }

    @Test
    fun matrixSoftwareOnlyPlatformKeepsNonEmptyFallback() {
        // 组 2（核心防回归）：平台只有软解、扩展接不了 → 必须保留平台软解，绝不能返回空列表
        val capability = DecoderCapability(
            hardwareAvailable = false,
            softwareAvailable = true,
            extensionHandles = false,
        )
        assertEquals(DecoderPolicy.PLATFORM_ALL, decideVideoDecoderPolicy(capability))
        val kept = keptCandidates(capability, softwareOnlyCandidates)
        assertEquals("仅平台软解时必须全留", softwareOnlyCandidates.map { it.name }, kept)
        assertTrue("仅平台软解时兼容档选择器不得返回空列表（旧实现回归点）", kept.isNotEmpty())
    }

    @Test
    fun matrixExtensionOnlyDoesNotClaimPlatformDecoding() {
        // 组 3：仅平台软解 + 扩展确定可解 → 平台不认领（过滤成空），交给扩展渲染器
        val capability = DecoderCapability(
            hardwareAvailable = false,
            softwareAvailable = true,
            extensionHandles = true,
        )
        assertEquals(DecoderPolicy.FILTER_TO_EXTENSION, decideVideoDecoderPolicy(capability))
        assertEquals(
            "扩展可解时平台候选应被过滤成空（不假装平台可解）",
            emptyList<String>(),
            keptCandidates(capability, softwareOnlyCandidates),
        )
    }

    @Test
    fun matrixAllUnsupportedStaysExplicitlyUnsupported() {
        // 组 4：全部不支持 → 明确不支持（候选为空时输出必须为空，不得凭空造出可解候选）
        val capability = DecoderCapability(
            hardwareAvailable = false,
            softwareAvailable = false,
            extensionHandles = false,
        )
        // 规约：没有 NONE_SUPPORTED 常量，用「空候选列表 in → 空列表 out」表达明确不支持
        assertEquals(DecoderPolicy.PLATFORM_ALL, decideVideoDecoderPolicy(capability))
        assertEquals(
            "全部不支持时不得把空候选变成非空（不误判为可播）",
            emptyList<String>(),
            applyVideoDecoderPolicy(decideVideoDecoderPolicy(capability), emptyList<Candidate>()) { it.hardware },
        )
        // 平台没有任何候选时，任何决策下保留结果都必须为空
        DecoderPolicy.entries.forEach { policy ->
            assertEquals(
                "空候选列表在 $policy 下必须保持为空",
                0,
                applyVideoDecoderPolicy(policy, emptyList<Candidate>()) { it.hardware }.size,
            )
        }
    }

    @Test
    fun matrixQueryFailedKeepsUnknownCandidatesAndNeverDies() {
        // 组 5：查询异常 → KEEP_UNKNOWN，保留全部候选（不判死、不返回空）
        val capability = DecoderCapability(
            hardwareAvailable = false,
            softwareAvailable = false,
            extensionHandles = false,
            queryFailed = true,
        )
        assertEquals(DecoderPolicy.KEEP_UNKNOWN, decideVideoDecoderPolicy(capability))
        val kept = keptCandidates(capability)
        assertEquals("查询异常时必须保留全部候选", mixedCandidates.map { it.name }, kept)
        assertTrue("查询异常时不得返回空（不判死）", kept.isNotEmpty())
        // 即使扩展字段/软硬解字段的组合很极端，queryFailed 也必须是最高优先级
        assertEquals(
            "queryFailed 优先级最高",
            DecoderPolicy.KEEP_UNKNOWN,
            decideVideoDecoderPolicy(
                DecoderCapability(hardwareAvailable = true, softwareAvailable = true, extensionHandles = true, queryFailed = true),
            ),
        )
    }

    // ---------------- 解码路径维度（H.264 / Simple mp4v / ASP） ----------------

    /** 路径维度标注：决策内核与 mime 无关，mime 通过 extensionHandles 快照字段进入决策 */
    private data class Scenario(
        val path: String,
        val capability: DecoderCapability,
        val expectedPolicy: DecoderPolicy,
    )

    @Test
    fun matrixDecodingPathScenariosAgreeWithCapabilityKernel() {
        val scenarios = listOf(
            // H.264：平台硬解优先
            Scenario(
                path = "H.264（平台硬解）",
                capability = DecoderCapability(hardwareAvailable = true, softwareAvailable = true, extensionHandles = true),
                expectedPolicy = DecoderPolicy.HARDWARE_ONLY,
            ),
            // Simple Profile（mp4v-es）：平台软解保底，扩展接不了
            Scenario(
                path = "Simple/mp4v-es（平台软解保底）",
                capability = DecoderCapability(hardwareAvailable = false, softwareAvailable = true, extensionHandles = false),
                expectedPolicy = DecoderPolicy.PLATFORM_ALL,
            ),
            // Advanced Simple Profile：平台只有软解且扩展专用链可解 → 平台不认领
            Scenario(
                path = "ASP（扩展专用链）",
                capability = DecoderCapability(hardwareAvailable = false, softwareAvailable = true, extensionHandles = true),
                expectedPolicy = DecoderPolicy.FILTER_TO_EXTENSION,
            ),
            // 查询异常：任何编码都不得判死
            Scenario(
                path = "任意编码（能力查询异常）",
                capability = DecoderCapability(hardwareAvailable = false, softwareAvailable = false, extensionHandles = false, queryFailed = true),
                expectedPolicy = DecoderPolicy.KEEP_UNKNOWN,
            ),
        )
        scenarios.forEach { scenario ->
            assertEquals(
                "路径「${scenario.path}」的决策必须来自同一份能力快照",
                scenario.expectedPolicy,
                decideVideoDecoderPolicy(scenario.capability),
            )
        }
    }

    @Test
    fun matrixExtensionHandlesVideoUsesSameJvmSafeRules() {
        // MJPEG 走 Media3Avi 位图路径：纯 JVM 可判定（不依赖 native）
        assertTrue("video/mjpeg 必须由扩展位图路径接手", extensionHandlesVideo("video/mjpeg"))
        // 空 mime 无法判定 → false（不能把「查不到」当「能解」）
        assertFalse(extensionHandlesVideo(null))
        assertFalse(extensionHandlesVideo(""))
        assertFalse(extensionHandlesVideo("   "))
        // 注意：WMV2/ASP 的 extension 值依赖 FFmpeg/legacy native 加载，纯 JVM 下必为 false，
        // 属环境限制不是规则，故不做断言（与实现方口径一致）。
    }
}
