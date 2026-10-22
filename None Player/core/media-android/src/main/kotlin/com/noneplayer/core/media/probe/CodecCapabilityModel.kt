package com.noneplayer.core.media.probe

import java.security.MessageDigest

/**
 * 平台编解码能力快照（R0/T1，方案 §6.3）。
 *
 * 与 `Build.FINGERPRINT` 之类的设备级缓存不同，这里的判定单位是
 * **具体 Format + 具体 codec 声明**：同一设备上不同 codec 的 profile/位深/尺寸/
 * 码控能力可以完全不同，组件名前缀（`c2.qti` / `c2.mtk`）不能当能力依据。
 *
 * 三态语义（[CapabilityVerdict.UNKNOWN]）是承重墙：查询失败或字段缺失时不得冒充
 * “支持”或“不支持”。播放侧可对 UNKNOWN fail-open，转码/写盘前的关键闸门必须
 * 走实时复核，不能只信持久化快照。
 */
data class CodecCapabilitySnapshot(
    val device: CodecDeviceIdentity,
    val codecs: List<CodecCapability>,
    val errors: List<String> = emptyList(),
) {
    val decoders: List<CodecCapability> get() = codecs.filterNot { it.isEncoder }
    val encoders: List<CodecCapability> get() = codecs.filter { it.isEncoder }

    fun codecsFor(mime: String, encoder: Boolean): List<CodecCapability> =
        codecs.filter { it.isEncoder == encoder && it.type(mime) != null }

    /**
     * 持久化加速缓存键：设备指纹、API、codec 列表/能力摘要、应用版本任一变化即失效。
     * 命中缓存仍必须在关键 `configure`/`addTrack` 前用实时快照复核。
     */
    fun cacheKey(): String = sha256(buildString {
        append(device.fingerprint).append('|')
        append(device.sdkInt).append('|')
        append(device.appVersion ?: "-").append('|')
        codecs.sortedBy { it.name }.forEach { codec ->
            append(codec.name).append(if (codec.isEncoder) 'E' else 'D').append(':')
            codec.types.sortedBy { it.mime }.forEach { type ->
                append(type.mime).append(',')
                append(type.profileLevels.joinToString("/") { "${it.profile}:${it.level}" }).append(',')
                append(type.video?.cacheSignature() ?: "-").append(',')
                append(type.bitrateModes.sorted().joinToString("+")).append(',')
                append(type.maxInstances).append(',')
                append(type.surfaceColorFormat).append(';')
            }
            append('\n')
        }
    })

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class CodecDeviceIdentity(
    val sdkInt: Int,
    val release: String,
    val fingerprint: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String? = null,
    val socModel: String? = null,
    val supportedAbis: List<String> = emptyList(),
    val appVersion: String? = null,
)

data class CodecCapability(
    val name: String,
    val isEncoder: Boolean,
    /** API 29 以下 `MediaCodecInfo` 不提供硬件标记，保留 null 而不是伪造 false。 */
    val isHardwareAccelerated: Boolean?,
    val isSoftwareOnly: Boolean?,
    val isVendor: Boolean?,
    val isAlias: Boolean?,
    val types: List<CodecTypeCapability>,
) {
    fun type(mime: String): CodecTypeCapability? =
        types.firstOrNull { it.mime.equals(mime, ignoreCase = true) }
}

data class CodecTypeCapability(
    val mime: String,
    val profileLevels: List<ProfileLevel>,
    val video: VideoCapability?,
    val maxInstances: Int,
    /** `COLOR_FormatSurface` 已声明：编码器可接 `createInputSurface()`。 */
    val surfaceColorFormat: Boolean,
    val bitrateModes: Set<BitrateMode>,
    val colorFormats: List<Int>,
)

data class ProfileLevel(val profile: Int, val level: Int)

enum class BitrateMode { CBR, VBR, CQ }

data class VideoCapability(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val minFrameRate: Double,
    val maxFrameRate: Double,
    val minBitrate: Int,
    val maxBitrate: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
) {
    fun supportsWidth(width: Int): Boolean =
        width in minWidth..maxWidth && (widthAlignment <= 1 || width % widthAlignment == 0)

    fun supportsHeight(height: Int): Boolean =
        height in minHeight..maxHeight && (heightAlignment <= 1 || height % heightAlignment == 0)

    fun supportsSize(width: Int, height: Int): Boolean =
        supportsWidth(width) && supportsHeight(height)

    fun cacheSignature(): String =
        "$minWidth-$maxWidth/${minHeight}-$maxHeight/$minFrameRate-$maxFrameRate/" +
            "$minBitrate-$maxBitrate/${widthAlignment}x$heightAlignment"
}

/** 单条轨对平台 codec 的要求；只填调用方真正关心的字段。 */
data class CodecRequirements(
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val bitrate: Int? = null,
    val profile: ProfileRequirement? = null,
    /** 码流头部读不出 profile（缺 csd、hvcC 损坏或非 Android 已定义档位）。 */
    val streamProfileUnknown: Boolean = false,
    val requireSurface: Boolean = false,
    val bitrateMode: BitrateMode? = null,
    val minInstances: Int? = null,
)

data class ProfileRequirement(
    val profiles: Set<Int>,
    /** 码流声明的 level；null 表示不做 level 求交。 */
    val level: CodecLevel? = null,
)

/**
 * 可比较的 level。Android 的 level 常量在不同 API 上改过编码方式
 * （HEVC 现在用 `HEVCMainTierLevel*` / `HEVCHighTierLevel*`），
 * 不能直接比大小，也不该把旧常量硬编码进判定。
 */
data class CodecLevel(
    /** 1.0 / 2.1 / 5.2 / 6.2 等。 */
    val rank: Double,
    /** 0 = Main tier，1 = High tier；HEVC 以外恒为 0。 */
    val tier: Int = TIER_MAIN,
) {
    companion object {
        const val TIER_MAIN = 0
        const val TIER_HIGH = 1
    }
}

enum class CapabilityVerdict { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class CapabilityDecision(
    val verdict: CapabilityVerdict,
    val codecName: String?,
    val reasons: List<String> = emptyList(),
) {
    val isSupported: Boolean get() = verdict == CapabilityVerdict.SUPPORTED
}

/**
 * 快照求交规则。纯函数，不查询设备；生产路径用实时采集的 [CodecCapability] 喂入，
 * 单测直接构造快照。
 */
object CodecCapabilityDecider {

    fun evaluate(codec: CodecCapability, requirements: CodecRequirements): CapabilityDecision {
        val type = codec.type(requirements.mime)
            ?: return unsupported(codec.name, "mime ${requirements.mime} not declared")
        val reasons = mutableListOf<String>()
        var unknown = false

        when {
            requirements.streamProfileUnknown -> {
                unknown = true
                reasons += "stream profile unknown"
            }

            requirements.profile != null -> {
                val profile = requirements.profile
                if (type.profileLevels.isEmpty()) {
                    unknown = true
                    reasons += "profileLevels unavailable"
                } else {
                    val matching = type.profileLevels.filter {
                        CodecProfiles.matches(requirements.mime, profile.profiles, it.profile)
                    }
                    if (matching.isEmpty()) {
                        return unsupported(
                            codec.name,
                            "profile ${profile.profiles.joinToString("/")} not declared " +
                                "(${type.profileLevels.joinToString { "${it.profile}@${it.level}" }})",
                        )
                    }
                    profile.level?.let { required ->
                        val declaredLevels = matching.mapNotNull {
                            CodecProfiles.levelOf(requirements.mime, it.level)
                        }
                        when {
                            declaredLevels.isEmpty() -> {
                                unknown = true
                                reasons += "declared level has no rank"
                            }

                            declaredLevels.any { CodecProfiles.supports(required, it) } -> Unit

                            declaredLevels.size < matching.size -> {
                                unknown = true
                                reasons += "some declared levels have no rank"
                            }

                            else -> return unsupported(
                                codec.name,
                                "level ${declaredLevels.maxOf { it.rank }} < required ${required.rank}",
                            )
                        }
                    }
                }
            }
        }

        val needsVideoRanges = requirements.width != null ||
            requirements.height != null ||
            requirements.frameRate != null ||
            requirements.bitrate != null
        if (needsVideoRanges) {
            val video = type.video
            if (video == null) {
                unknown = true
                reasons += "videoCapabilities unavailable"
            } else {
                requirements.width?.let { width ->
                    if (!video.supportsWidth(width)) {
                        return unsupported(codec.name, "width $width outside ${video.minWidth}-${video.maxWidth}")
                    }
                }
                requirements.height?.let { height ->
                    if (!video.supportsHeight(height)) {
                        return unsupported(codec.name, "height $height outside ${video.minHeight}-${video.maxHeight}")
                    }
                }
                if (requirements.width != null && requirements.height != null &&
                    !video.supportsSize(requirements.width, requirements.height)
                ) {
                    return unsupported(codec.name, "size ${requirements.width}x${requirements.height} unsupported")
                }
                requirements.frameRate?.let { fps ->
                    if (video.maxFrameRate > 0 && (fps < video.minFrameRate || fps > video.maxFrameRate)) {
                        return unsupported(codec.name, "fps $fps outside ${video.minFrameRate}-${video.maxFrameRate}")
                    }
                }
                requirements.bitrate?.let { bitrate ->
                    if (video.maxBitrate > 0 && bitrate !in video.minBitrate..video.maxBitrate) {
                        return unsupported(codec.name, "bitrate $bitrate outside ${video.minBitrate}-${video.maxBitrate}")
                    }
                }
            }
        }

        if (requirements.requireSurface && !type.surfaceColorFormat) {
            return unsupported(codec.name, "surface input not declared")
        }

        requirements.bitrateMode?.let { mode ->
            if (type.bitrateModes.isEmpty()) {
                unknown = true
                reasons += "bitrate modes unavailable"
            } else if (mode !in type.bitrateModes) {
                return unsupported(
                    codec.name,
                    "bitrate mode $mode not supported (${type.bitrateModes.joinToString()})",
                )
            }
        }

        requirements.minInstances?.let { instances ->
            when {
                type.maxInstances <= 0 -> {
                    unknown = true
                    reasons += "max instances unavailable"
                }

                type.maxInstances < instances -> return unsupported(
                    codec.name,
                    "max instances ${type.maxInstances} < $instances",
                )
            }
        }

        return CapabilityDecision(
            verdict = if (unknown) CapabilityVerdict.UNKNOWN else CapabilityVerdict.SUPPORTED,
            codecName = codec.name,
            reasons = reasons,
        )
    }

    /**
     * 按调用方顺序取第一个 SUPPORTED；没有 SUPPORTED 时，只要存在 UNKNOWN 就返回
     * UNKNOWN（给调用方实时复核的机会），否则返回第一个确定的 UNSUPPORTED。
     */
    fun choose(
        candidates: List<CodecCapability>,
        requirements: CodecRequirements,
    ): CapabilityDecision {
        if (candidates.isEmpty()) {
            return CapabilityDecision(
                CapabilityVerdict.UNSUPPORTED,
                null,
                listOf("no codec for ${requirements.mime}"),
            )
        }
        val evaluated = candidates.map { it to evaluate(it, requirements) }
        evaluated.firstOrNull { it.second.verdict == CapabilityVerdict.SUPPORTED }
            ?.let { return it.second }
        evaluated.firstOrNull { it.second.verdict == CapabilityVerdict.UNKNOWN }
            ?.let { return it.second }
        return evaluated.first().second
    }

    private fun unsupported(name: String?, reason: String): CapabilityDecision =
        CapabilityDecision(CapabilityVerdict.UNSUPPORTED, name, listOf(reason))
}

/**
 * profile/level 的可比性规则。
 *
 * `MediaCodecInfo.CodecProfileLevel` 的 HEVC level 常量不是单调数值：当前 Android
 * 用 `HEVCMainTierLevel*`（1/4/16/…）与 `HEVCHighTierLevel*`（2/8/32/…）两套
 * 位序列，旧 API 的 `HEVCLevel*` 已不可依赖。这里统一换算成
 * “主版本.次版本 + tier”再比较；High tier 只在码流本身声明 High tier 时才是必需项。
 */
object CodecProfiles {

    // 与 android.media.MediaCodecInfo.CodecProfileLevel 的 HEVC 常量同值。
    const val HEVC_MAIN = 1
    const val HEVC_MAIN10 = 2
    const val HEVC_MAIN_STILL = 4
    const val HEVC_MAIN10_HDR10 = 0x1000
    const val HEVC_MAIN10_HDR10_PLUS = 0x2000

    fun matches(mime: String, required: Set<Int>, declared: Int): Boolean {
        if (declared in required) return true
        if (mime.equals("video/hevc", ignoreCase = true) && HEVC_MAIN10 in required) {
            return declared == HEVC_MAIN10_HDR10 || declared == HEVC_MAIN10_HDR10_PLUS
        }
        return false
    }

    fun levelOf(mime: String, level: Int): CodecLevel? = when {
        mime.equals("video/hevc", ignoreCase = true) ->
            HEVC_MAIN_TIER_LEVELS[level]?.let { CodecLevel(it, CodecLevel.TIER_MAIN) }
                ?: HEVC_HIGH_TIER_LEVELS[level]?.let { CodecLevel(it, CodecLevel.TIER_HIGH) }

        mime.equals("video/avc", ignoreCase = true) ->
            AVC_LEVELS[level]?.let { CodecLevel(it) }

        else -> null
    }

    /** Main tier 码流可被同等级或更高的任一 tier 承接；High tier 码流要求解码器也声明 High tier。 */
    fun supports(required: CodecLevel, declared: CodecLevel): Boolean =
        declared.rank >= required.rank &&
            (required.tier == CodecLevel.TIER_MAIN || declared.tier == CodecLevel.TIER_HIGH)

    private val HEVC_MAIN_TIER_LEVELS = mapOf(
        1 to 1.0,
        4 to 2.0,
        16 to 2.1,
        64 to 3.0,
        256 to 3.1,
        1024 to 4.0,
        4096 to 4.1,
        16384 to 5.0,
        65536 to 5.1,
        262144 to 5.2,
        1048576 to 6.0,
        4194304 to 6.1,
        16777216 to 6.2,
    )

    private val HEVC_HIGH_TIER_LEVELS = mapOf(
        2 to 1.0,
        8 to 2.0,
        32 to 2.1,
        128 to 3.0,
        512 to 3.1,
        2048 to 4.0,
        8192 to 4.1,
        32768 to 5.0,
        131072 to 5.1,
        524288 to 5.2,
        2097152 to 6.0,
        8388608 to 6.1,
        33554432 to 6.2,
    )

    private val AVC_LEVELS = mapOf(
        1 to 1.0,
        2 to 1.0,
        4 to 1.1,
        8 to 1.2,
        16 to 1.3,
        32 to 2.0,
        64 to 2.1,
        128 to 2.2,
        256 to 3.0,
        512 to 3.1,
        1024 to 3.2,
        2048 to 4.0,
        4096 to 4.1,
        8192 to 4.2,
        16384 to 5.0,
        32768 to 5.1,
        65536 to 5.2,
        131072 to 6.0,
        262144 to 6.1,
        524288 to 6.2,
    )
}

/**
 * 从 HEVC `hvcC`（ISO/IEC 14496-15）读码流声明的 profile/level。
 *
 * `findDecoderForFormat` 只看 mime + 尺寸，不区分 8bit/10bit；HEVC Main10 必须
 * 用 `general_profile_idc` 与解码器声明的 profileLevels 求交，否则会放行后首帧失败。
 * 只处理 Main / Main10 / MainStill 三档；Range Extensions 等 Android 未定义档位返回
 * `androidProfile = null`，由调用方按 UNKNOWN 处理。
 */
object HevcProfileParser {

    private const val CONFIGURATION_VERSION = 1
    private const val MIN_HVCC_SIZE = 13

    data class Verdict(
        val profileIdc: Int,
        val levelIdc: Int,
        val tier: Int,
        val androidProfile: Int?,
        val level: CodecLevel?,
    ) {
        val detail: String
            get() = "profile_idc=$profileIdc level_idc=$levelIdc tier=$tier " +
                "androidProfile=${androidProfile ?: "?"} level=${level?.rank ?: "?"}"
    }

    fun parse(hvcC: ByteArray): Verdict? {
        if (hvcC.size < MIN_HVCC_SIZE) return null
        if ((hvcC[0].toInt() and 0xFF) != CONFIGURATION_VERSION) return null
        val header = hvcC[1].toInt() and 0xFF
        val profileIdc = header and 0x1F
        val tierFlag = (header shr 5) and 0x01
        val levelIdc = hvcC[12].toInt() and 0xFF
        return Verdict(
            profileIdc = profileIdc,
            levelIdc = levelIdc,
            tier = tierFlag,
            androidProfile = hevcProfile(profileIdc),
            level = HEVC_LEVEL_RANK_BY_IDC[levelIdc]
                ?.let { CodecLevel(it, tierFlag) },
        )
    }

    private fun hevcProfile(profileIdc: Int): Int? = when (profileIdc) {
        1 -> CodecProfiles.HEVC_MAIN
        2 -> CodecProfiles.HEVC_MAIN10
        3 -> CodecProfiles.HEVC_MAIN_STILL
        else -> null
    }

    private val HEVC_LEVEL_RANK_BY_IDC = mapOf(
        30 to 1.0,
        60 to 2.0,
        63 to 2.1,
        90 to 3.0,
        93 to 3.1,
        120 to 4.0,
        123 to 4.1,
        150 to 5.0,
        153 to 5.1,
        156 to 5.2,
        180 to 6.0,
        183 to 6.1,
        186 to 6.2,
    )
}

data class CodecCandidate(
    val codec: CodecCapability,
    val decision: CapabilityDecision,
    val selectedByPlatform: Boolean,
)
