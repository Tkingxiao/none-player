package com.noneplayer.core.media.probe

import com.noneplayer.core.media.probe.CapabilityVerdict.SUPPORTED
import com.noneplayer.core.media.probe.CapabilityVerdict.UNKNOWN
import com.noneplayer.core.media.probe.CapabilityVerdict.UNSUPPORTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R0/T1：能力快照求交规则与 HEVC hvcC 解析的纯 JVM 测试。
 *
 * 决赛裁决单位是“具体 codec + 具体 Format”，所以所有夹具都直接构造
 * [CodecCapability]；不触碰 MediaCodecList，真机声明由 androidTest 导出。
 */
class CodecCapabilityDecisionTest {

    private val hevc = "video/hevc"
    private val avc = "video/avc"

    // MediaCodecInfo.CodecProfileLevel 的现行 level 常量（API 36）。
    private val hevcMainTierLevel4 = 1024
    private val hevcMainTierLevel51 = 65536
    private val hevcMainTierLevel62 = 16777216
    private val hevcHighTierLevel52 = 524288
    private val hevcHighTierLevel61 = 8388608
    private val avcLevel32 = 1024
    private val avcLevel4 = 2048
    private val avcLevel41 = 4096

    private fun video(
        minWidth: Int = 16,
        maxWidth: Int = 4096,
        minHeight: Int = 16,
        maxHeight: Int = 4096,
        minFps: Double = 1.0,
        maxFps: Double = 60.0,
        minBitrate: Int = 1,
        maxBitrate: Int = 120_000_000,
        widthAlign: Int = 2,
        heightAlign: Int = 2,
    ) = VideoCapability(
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        minFrameRate = minFps,
        maxFrameRate = maxFps,
        minBitrate = minBitrate,
        maxBitrate = maxBitrate,
        widthAlignment = widthAlign,
        heightAlignment = heightAlign,
    )

    private fun type(
        mime: String = hevc,
        profileLevels: List<ProfileLevel> = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcMainTierLevel51)),
        video: VideoCapability? = video(),
        maxInstances: Int = 1,
        surface: Boolean = false,
        bitrateModes: Set<BitrateMode> = emptySet(),
        bitDepths: Set<Int> = CodecProfiles.bitDepthsFor(mime, profileLevels),
    ) = CodecTypeCapability(
        mime = mime,
        profileLevels = profileLevels,
        video = video,
        maxInstances = maxInstances,
        surfaceColorFormat = surface,
        bitrateModes = bitrateModes,
        colorFormats = if (surface) listOf(0x7F000789) else emptyList(),
        bitDepths = bitDepths,
    )

    private fun codec(
        name: String = "c2.test.hevc.decoder",
        encoder: Boolean = false,
        hardware: Boolean? = true,
        type: CodecTypeCapability = type(),
    ) = CodecCapability(
        name = name,
        isEncoder = encoder,
        isHardwareAccelerated = hardware,
        isSoftwareOnly = hardware?.let { !it },
        isVendor = true,
        isAlias = false,
        types = listOf(type),
    )

    private fun requirements(
        mime: String = hevc,
        width: Int? = null,
        height: Int? = null,
        frameRate: Double? = null,
        bitrate: Int? = null,
        profile: ProfileRequirement? = ProfileRequirement(setOf(CodecProfiles.HEVC_MAIN10)),
        streamProfileUnknown: Boolean = false,
        requireSurface: Boolean = false,
        bitrateMode: BitrateMode? = null,
        minInstances: Int? = null,
        bitDepth: Int? = null,
    ) = CodecRequirements(
        mime = mime,
        width = width,
        height = height,
        frameRate = frameRate,
        bitrate = bitrate,
        profile = profile,
        streamProfileUnknown = streamProfileUnknown,
        requireSurface = requireSurface,
        bitrateMode = bitrateMode,
        minInstances = minInstances,
        bitDepth = bitDepth,
    )

    private fun evaluate(
        codec: CodecCapability = codec(),
        requirements: CodecRequirements = requirements(),
    ): CapabilityDecision = CodecCapabilityDecider.evaluate(codec, requirements)

    // ---- profile / level ----

    @Test
    fun `main10 stream is supported by main10 decoder`() {
        val decision = evaluate()
        assertEquals(SUPPORTED, decision.verdict)
        assertEquals("c2.test.hevc.decoder", decision.codecName)
    }

    @Test
    fun `main10 stream is rejected by main only decoder`() {
        val decision = evaluate(
            codec = codec(type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51)))),
        )
        assertEquals(UNSUPPORTED, decision.verdict)
        assertTrue(decision.reasons.single().contains("profile"))
    }

    @Test
    fun `main10 hdr10 declaration satisfies main10 requirement`() {
        val decision = evaluate(
            codec = codec(
                type = type(
                    profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10_HDR10, hevcMainTierLevel51)),
                ),
            ),
        )
        assertEquals(SUPPORTED, decision.verdict)
    }

    @Test
    fun `missing profileLevels is unknown rather than unsupported`() {
        val decision = evaluate(codec = codec(type = type(profileLevels = emptyList())))
        assertEquals(UNKNOWN, decision.verdict)
        assertTrue(decision.reasons.single().contains("profileLevels"))
    }

    @Test
    fun `ten bit stream is rejected by an eight bit decoder`() {
        val eightBit = codec(
            type = type(
                profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51)),
                bitDepths = setOf(8),
            ),
        )
        assertEquals(
            UNSUPPORTED,
            evaluate(eightBit, requirements(bitDepth = 10, profile = null)).verdict,
        )
    }

    @Test
    fun `missing bit depth declaration is unknown`() {
        val decision = evaluate(
            codec = codec(type = type(bitDepths = emptySet())),
            requirements = requirements(bitDepth = 10, profile = null),
        )
        assertEquals(UNKNOWN, decision.verdict)
    }

    @Test
    fun `avc codec string distinguishes high ten from high eight`() {
        val parsed = CodecStreamProfileParser.parse(
            mime = avc,
            codecs = "avc1.6E0032",
            initializationData = emptyList(),
        )
        assertEquals(CodecProfiles.AVC_HIGH10, parsed?.profile)
        assertEquals(10, parsed?.bitDepth)
        assertEquals(5.0, parsed?.level?.rank)
    }

    @Test
    fun `vp9 and av1 codec strings retain explicit bit depth`() {
        val vp9 = CodecStreamProfileParser.parse(
            "video/x-vnd.on2.vp9",
            "vp09.02.10.10.00",
            emptyList(),
        )
        assertEquals(
            10,
            vp9?.bitDepth,
        )
        assertEquals("4:2:0", vp9?.chromaSubsampling)
        assertEquals(
            10,
            CodecStreamProfileParser.parse("video/av01", "av01.0.08M.10", emptyList())?.bitDepth,
        )
    }

    @Test
    fun `truncated codec descriptions remain unknown`() {
        assertEquals(null, CodecStreamProfileParser.parse(avc, "avc1.6E", emptyList()))
        assertEquals(null, CodecStreamProfileParser.parse("video/av01", "av01.0.08M", emptyList()))
    }

    @Test
    fun `unreadable stream profile is unknown even when decoder declares main10`() {
        val decision = evaluate(requirements = requirements(streamProfileUnknown = true))
        assertEquals(UNKNOWN, decision.verdict)
    }

    @Test
    fun `level below the stream requirement is rejected`() {
        val decision = evaluate(
            codec = codec(type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcMainTierLevel4)))),
            requirements = requirements(
                profile = ProfileRequirement(
                    setOf(CodecProfiles.HEVC_MAIN10),
                    level = CodecLevel(5.1),
                ),
            ),
        )
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `level above the stream requirement is accepted`() {
        val decision = evaluate(
            codec = codec(type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcMainTierLevel62)))),
            requirements = requirements(
                profile = ProfileRequirement(setOf(CodecProfiles.HEVC_MAIN10), level = CodecLevel(5.1)),
            ),
        )
        assertEquals(SUPPORTED, decision.verdict)
    }

    @Test
    fun `high tier stream requires a high tier declaration`() {
        val highOnly = codec(
            type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcHighTierLevel61))),
        )
        val mainOnly = codec(
            type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcMainTierLevel62))),
        )
        val requirement = ProfileRequirement(
            setOf(CodecProfiles.HEVC_MAIN10),
            CodecLevel(5.1, CodecLevel.TIER_HIGH),
        )
        assertEquals(SUPPORTED, evaluate(highOnly, requirements(profile = requirement)).verdict)
        assertEquals(UNSUPPORTED, evaluate(mainOnly, requirements(profile = requirement)).verdict)
    }

    @Test
    fun `main tier stream accepts a high tier declaration at the same level`() {
        val high = codec(
            type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, hevcHighTierLevel52))),
        )
        val requirement = ProfileRequirement(setOf(CodecProfiles.HEVC_MAIN10), CodecLevel(5.1))
        assertEquals(SUPPORTED, evaluate(high, requirements(profile = requirement)).verdict)
    }

    @Test
    fun `avc level constants are compared by major minor rank`() {
        val high = 0x08
        val avcType = type(mime = avc, profileLevels = listOf(ProfileLevel(high, avcLevel4)))
        val avcCodec = codec(type = avcType)
        assertEquals(
            UNSUPPORTED,
            evaluate(avcCodec, requirements(mime = avc, profile = ProfileRequirement(setOf(high), CodecLevel(4.1)))).verdict,
        )
        assertEquals(
            SUPPORTED,
            evaluate(avcCodec, requirements(mime = avc, profile = ProfileRequirement(setOf(high), CodecLevel(3.2)))).verdict,
        )
    }

    @Test
    fun `declared level without a rank falls back to unknown`() {
        val decision = evaluate(
            codec = codec(type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN10, 99)))),
            requirements = requirements(
                profile = ProfileRequirement(setOf(CodecProfiles.HEVC_MAIN10), level = CodecLevel(5.1)),
            ),
        )
        assertEquals(UNKNOWN, decision.verdict)
    }

    @Test
    fun `mime missing from codec is unsupported`() {
        val decision = evaluate(requirements = requirements(mime = avc, profile = null))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    // ---- 尺寸 / 帧率 / 码率 ----

    @Test
    fun `size inside declared range is supported`() {
        val decision = evaluate(requirements = requirements(width = 1920, height = 1080))
        assertEquals(SUPPORTED, decision.verdict)
    }

    @Test
    fun `size outside range is rejected`() {
        val decision = evaluate(requirements = requirements(width = 5000, height = 1080))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `size violating alignment is rejected`() {
        val decision = evaluate(requirements = requirements(width = 1919, height = 1080))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `frame rate outside range is rejected`() {
        val decision = evaluate(requirements = requirements(width = 1920, height = 1080, frameRate = 120.0))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `bitrate outside range is rejected`() {
        val decision = evaluate(requirements = requirements(width = 1920, height = 1080, bitrate = 200_000_000))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `missing video capabilities with size requirement is unknown`() {
        val decision = evaluate(
            codec = codec(type = type(video = null)),
            requirements = requirements(width = 1920, height = 1080),
        )
        assertEquals(UNKNOWN, decision.verdict)
    }

    // ---- Surface / 码控 / 并发 ----

    @Test
    fun `surface input must be declared when required`() {
        assertEquals(
            UNSUPPORTED,
            evaluate(requirements = requirements(requireSurface = true)).verdict,
        )
        assertEquals(
            SUPPORTED,
            evaluate(
                codec = codec(type = type(surface = true)),
                requirements = requirements(requireSurface = true),
            ).verdict,
        )
    }

    @Test
    fun `bitrate mode must be declared when required`() {
        val cqCodec = codec(
            encoder = true,
            type = type(surface = true, bitrateModes = setOf(BitrateMode.CBR, BitrateMode.VBR, BitrateMode.CQ)),
        )
        assertEquals(
            SUPPORTED,
            evaluate(
                cqCodec,
                requirements(requireSurface = true, bitrateMode = BitrateMode.CQ),
            ).verdict,
        )
    }

    @Test
    fun `missing bitrate modes is unknown`() {
        val enc = codec(
            encoder = true,
            type = type(surface = true, bitrateModes = emptySet()),
        )
        val decision = evaluate(enc, requirements(requireSurface = true, bitrateMode = BitrateMode.CQ))
        assertEquals(UNKNOWN, decision.verdict)
    }

    @Test
    fun `bitrate mode absent from supported set is rejected`() {
        val enc = codec(
            encoder = true,
            type = type(surface = true, bitrateModes = setOf(BitrateMode.CBR, BitrateMode.VBR)),
        )
        val decision = evaluate(enc, requirements(requireSurface = true, bitrateMode = BitrateMode.CQ))
        assertEquals(UNSUPPORTED, decision.verdict)
    }

    @Test
    fun `concurrency requirement is checked`() {
        val decision = evaluate(
            codec = codec(type = type(maxInstances = 1)),
            requirements = requirements(minInstances = 2),
        )
        assertEquals(UNSUPPORTED, decision.verdict)
        assertEquals(
            SUPPORTED,
            evaluate(
                codec = codec(type = type(maxInstances = 4)),
                requirements = requirements(minInstances = 2),
            ).verdict,
        )
    }

    @Test
    fun `unavailable concurrency is unknown`() {
        val decision = evaluate(
            codec = codec(type = type(maxInstances = 0)),
            requirements = requirements(minInstances = 1),
        )
        assertEquals(UNKNOWN, decision.verdict)
    }

    // ---- 候选选择 ----

    @Test
    fun `choose prefers first supported candidate`() {
        val unsupported = codec(
            name = "bad",
            type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51))),
        )
        val supported = codec(name = "good")
        val decision = CodecCapabilityDecider.choose(listOf(unsupported, supported), requirements())
        assertEquals(SUPPORTED, decision.verdict)
        assertEquals("good", decision.codecName)
    }

    @Test
    fun `choose reports unknown when nothing is fully confirmed`() {
        val decision = CodecCapabilityDecider.choose(
            listOf(
                codec(
                    name = "unknown",
                    type = type(profileLevels = emptyList()),
                ),
                codec(
                    name = "bad",
                    type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51))),
                ),
            ),
            requirements(),
        )
        assertEquals(UNKNOWN, decision.verdict)
        assertEquals("unknown", decision.codecName)
    }

    @Test
    fun `choose returns first unsupported when every candidate is rejected`() {
        val decision = CodecCapabilityDecider.choose(
            listOf(
                codec(
                    name = "bad1",
                    type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51))),
                ),
                codec(
                    name = "bad2",
                    type = type(profileLevels = listOf(ProfileLevel(CodecProfiles.HEVC_MAIN, hevcMainTierLevel51))),
                ),
            ),
            requirements(),
        )
        assertEquals(UNSUPPORTED, decision.verdict)
        assertEquals("bad1", decision.codecName)
    }

    @Test
    fun `choose with no candidates is unsupported`() {
        val decision = CodecCapabilityDecider.choose(emptyList(), requirements())
        assertEquals(UNSUPPORTED, decision.verdict)
        assertNull(decision.codecName)
    }

    // ---- 快照缓存键 ----

    private fun snapshot(appVersion: String? = "1.0", codecs: List<CodecCapability> = listOf(codec())) =
        CodecCapabilitySnapshot(
            device = CodecDeviceIdentity(
                sdkInt = 34,
                release = "14",
                fingerprint = "test/fingerprint",
                manufacturer = "None",
                model = "Test",
                device = "test",
                board = "test",
                hardware = "test",
                appVersion = appVersion,
            ),
            codecs = codecs,
        )

    @Test
    fun `cache key is independent of codec enumeration order`() {
        val a = codec(name = "a")
        val b = codec(name = "b")
        assertEquals(snapshot(codecs = listOf(a, b)).cacheKey(), snapshot(codecs = listOf(b, a)).cacheKey())
    }

    @Test
    fun `cache key changes with app version fingerprint or capability`() {
        val base = snapshot().cacheKey()
        assertNotEquals(base, snapshot(appVersion = "1.1").cacheKey())
        assertNotEquals(
            base,
            snapshot().copy(device = snapshot().device.copy(fingerprint = "other")).cacheKey(),
        )
        assertNotEquals(
            base,
            snapshot(
                codecs = listOf(codec(type = type(profileLevels = emptyList()))),
            ).cacheKey(),
        )
    }

    @Test
    fun `cache key is a sha256 hex string`() {
        val key = snapshot().cacheKey()
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---- hvcC ----

    private fun hvcC(
        profileIdc: Int,
        levelIdc: Int,
        version: Int = 1,
        tier: Int = CodecLevel.TIER_MAIN,
    ): ByteArray = ByteArray(23).apply {
        this[0] = version.toByte()
        this[1] = (profileIdc or (tier shl 5)).toByte()
        this[12] = levelIdc.toByte()
    }

    @Test
    fun `hvcC main10 level 5_1 maps to profile and level`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 2, levelIdc = 153))
        assertEquals(2, verdict?.profileIdc)
        assertEquals(153, verdict?.levelIdc)
        assertEquals(CodecProfiles.HEVC_MAIN10, verdict?.androidProfile)
        assertEquals(CodecLevel(5.1, CodecLevel.TIER_MAIN), verdict?.level)
    }

    @Test
    fun `hvcC main level 4 maps to profile and level`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 1, levelIdc = 120))
        assertEquals(CodecProfiles.HEVC_MAIN, verdict?.androidProfile)
        assertEquals(CodecLevel(4.0), verdict?.level)
    }

    @Test
    fun `hvcC high tier flag is preserved`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 2, levelIdc = 183, tier = 1))
        assertEquals(1, verdict?.tier)
        assertEquals(CodecLevel(6.1, CodecLevel.TIER_HIGH), verdict?.level)
    }

    @Test
    fun `hvcC main still maps to android main still profile`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 3, levelIdc = 120))
        assertEquals(CodecProfiles.HEVC_MAIN_STILL, verdict?.androidProfile)
        assertEquals(4, verdict?.androidProfile)
    }

    @Test
    fun `hvcC range extension profile is not claimed as android profile`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 4, levelIdc = 120))
        assertEquals(4, verdict?.profileIdc)
        assertNull(verdict?.androidProfile)
        assertEquals(CodecLevel(4.0), verdict?.level)
    }

    @Test
    fun `hvcC unknown level keeps profile but drops level`() {
        val verdict = HevcProfileParser.parse(hvcC(profileIdc = 2, levelIdc = 255))
        assertEquals(CodecProfiles.HEVC_MAIN10, verdict?.androidProfile)
        assertNull(verdict?.level)
    }

    @Test
    fun `invalid or truncated hvcC is not parsed`() {
        assertNull(HevcProfileParser.parse(ByteArray(0)))
        assertNull(HevcProfileParser.parse(ByteArray(12)))
        assertNull(HevcProfileParser.parse(hvcC(profileIdc = 2, levelIdc = 153, version = 0)))
    }
}
