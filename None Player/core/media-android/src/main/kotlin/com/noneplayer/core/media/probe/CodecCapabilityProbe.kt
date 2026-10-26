package com.noneplayer.core.media.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * 平台编解码能力采集与实时闸门（R0/T1）。
 *
 * 采集口径对齐方案 §6.3：
 *  - decoder/encoder 分开，按**每个 mime** 记录 profileLevels / VideoCapabilities /
 *    码控模式 / 并发实例 / Surface 声明；
 *  - 不做组件名前缀匹配（`c2.qti` / `c2.mtk` 不是能力依据）；
 *  - 关键路径按具体 [Format] 问 `findDecoderForFormat` / `findEncoderForFormat`
 *    **真正会选中的那一个**，并保留三态 UNKNOWN。
 *
 * `capture()` 可以落盘作加速缓存，但 `decoderDecision` / `encoderDecision` 永远现场查询，
 * 缓存命中不能替代这里的复核。
 */
object CodecCapabilityProbe {

    private const val MIME_HEVC = "video/hevc"

    fun capture(appVersion: String? = null): CodecCapabilitySnapshot {
        val errors = mutableListOf<String>()
        val infos = runCatching { codecList().codecInfos }
            .getOrElse {
                return CodecCapabilitySnapshot(
                    device = deviceIdentity(appVersion),
                    codecs = emptyList(),
                    errors = listOf("MediaCodecList: ${it.message}"),
                )
            }
        val codecs = infos.map { info -> readCodec(info, errors) }
        return CodecCapabilitySnapshot(
            device = deviceIdentity(appVersion),
            codecs = codecs,
            errors = errors.toList(),
        )
    }

    /** 平台当前会选中的解码器；查询异常或无候选返回 null。 */
    fun selectedDecoderName(format: Format): String? =
        runCatching {
            val platform = platformFormat(format) ?: return@runCatching null
            codecList().findDecoderForFormat(platform)
        }.getOrNull()

    /**
     * 按具体 Format 检查平台将被选中的解码器。HEVC 会从 `hvcC` 读 Main/Main10/level
     * 并与解码器声明求交；读不出档位时返回 UNKNOWN，而不是放行成 SUPPORTED。
     */
    fun decoderDecision(format: Format): CapabilityDecision {
        val mime = format.sampleMimeType
        if (mime.isNullOrBlank()) return unsupported("format has no mime")
        val selected = selectedDecoderName(format)
            ?: return unsupported("no platform decoder for $mime")
        val codec = readCodecByName(selected)
            ?: return CapabilityDecision(
                CapabilityVerdict.UNKNOWN,
                selected,
                listOf("selected decoder not enumerated"),
            )
        return CodecCapabilityDecider.evaluate(codec, requirementsFor(format))
    }

    /**
     * 枚举该 Format 的全部平台解码器候选，平台选中者排第一，其后硬解优先。
     * 每个候选都带确定/未知/不支持三态，供 A4 的 profile 求交和转码选码使用。
     */
    fun decoderCandidates(format: Format): List<CodecCandidate> {
        val mime = format.sampleMimeType ?: return emptyList()
        val selected = selectedDecoderName(format)
        val requirements = requirementsFor(format)
        val candidates = runCatching {
            codecList().codecInfos.asSequence()
                .filterNot { it.isEncoder }
                .mapNotNull { info ->
                    runCatching { readCodec(info, mutableListOf()) }.getOrNull()
                }
                .filter { it.type(mime) != null }
                .toList()
        }.getOrDefault(emptyList())
        return candidates
            .sortedWith(
                compareByDescending<CodecCapability> { it.name == selected }
                    .thenByDescending { it.isHardwareAccelerated == true }
                    .thenBy { it.isAlias == true }
                    .thenBy { it.name },
            )
            .map { codec ->
                CodecCandidate(
                    codec = codec,
                    decision = CodecCapabilityDecider.evaluate(codec, requirements),
                    selectedByPlatform = codec.name == selected,
                )
            }
    }

    /**
     * 编码器闸门：默认要求 Surface 输入（P2 的 Surface→Surface 路径）。
     * `bitrateMode` 只在调用方明确要求时检查；缺失时由调用方按 CQ→VBR 回退。
     */
    fun encoderDecision(
        mime: String,
        width: Int,
        height: Int,
        frameRate: Double? = null,
        bitrate: Int? = null,
        profile: ProfileRequirement? = null,
        bitDepth: Int? = null,
        chromaSubsampling: String? = null,
        bitrateMode: BitrateMode? = null,
        requireSurface: Boolean = true,
    ): CapabilityDecision {
        val platform = runCatching {
            MediaFormat.createVideoFormat(mime, width, height).apply {
                frameRate?.takeIf { it > 0 }?.let { setFloat(MediaFormat.KEY_FRAME_RATE, it.toFloat()) }
                bitrate?.takeIf { it > 0 }?.let { setInteger(MediaFormat.KEY_BIT_RATE, it) }
                bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it.androidValue) }
            }
        }.getOrNull() ?: return unsupported("invalid encoder format $mime ${width}x$height")

        val selected = runCatching { codecList().findEncoderForFormat(platform) }.getOrNull()
            ?: return unsupported("no platform encoder for $mime ${width}x$height")
        val codec = readCodecByName(selected)
            ?: return CapabilityDecision(
                CapabilityVerdict.UNKNOWN,
                selected,
                listOf("selected encoder not enumerated"),
            )
        return CodecCapabilityDecider.evaluate(
            codec,
            CodecRequirements(
                mime = mime,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrate = bitrate,
                profile = profile,
                bitDepth = bitDepth,
                chromaSubsampling = chromaSubsampling,
                requireSurface = requireSurface,
                bitrateMode = bitrateMode,
            ),
        )
    }

    @OptIn(UnstableApi::class)
    private fun requirementsFor(format: Format): CodecRequirements {
        val mime = format.sampleMimeType.orEmpty()
        var profile: ProfileRequirement? = null
        var streamProfileUnknown = false
        var bitDepth: Int? = format.colorInfo?.lumaBitdepth?.takeIf { it > 0 }
        var chromaSubsampling: String? = null
        if (mime.equals(MIME_HEVC, ignoreCase = true)) {
            val verdict = format.initializationData.firstOrNull()?.let(HevcProfileParser::parse)
            if (verdict?.androidProfile != null) {
                profile = ProfileRequirement(setOf(verdict.androidProfile), verdict.level)
                if (bitDepth == null) {
                    bitDepth = when (verdict.androidProfile) {
                        CodecProfiles.HEVC_MAIN,
                        CodecProfiles.HEVC_MAIN_STILL,
                        -> 8

                        CodecProfiles.HEVC_MAIN10,
                        CodecProfiles.HEVC_MAIN10_HDR10,
                        CodecProfiles.HEVC_MAIN10_HDR10_PLUS,
                        -> 10

                        else -> null
                    }
                }
            } else {
                streamProfileUnknown = true
            }
        } else {
            val codec = format.codecs
            val parsedProfile = CodecStreamProfileParser.parse(mime, codec, format.initializationData)
            if (parsedProfile != null) {
                profile = ProfileRequirement(setOf(parsedProfile.profile), parsedProfile.level)
                if (bitDepth == null) bitDepth = parsedProfile.bitDepth
                chromaSubsampling = parsedProfile.chromaSubsampling
            } else if (mime.equals("video/mp4v-es", ignoreCase = true)) {
                val mpeg4Header = format.initializationData.firstOrNull()
                val mpeg4 = mpeg4Header?.let(Mpeg4ProfileParser::parse)
                when (mpeg4?.required) {
                    Mpeg4ProfileParser.RequiredProfile.SIMPLE -> {
                        profile = ProfileRequirement(
                            setOf(MediaCodecInfo.CodecProfileLevel.MPEG4ProfileSimple),
                        )
                        if (bitDepth == null) bitDepth = 8
                    }

                    Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE -> {
                        profile = ProfileRequirement(
                            setOf(MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple),
                        )
                        if (bitDepth == null) bitDepth = 8
                    }

                    Mpeg4ProfileParser.RequiredProfile.UNKNOWN -> streamProfileUnknown = true
                    else -> Unit
                }
            } else if (mime.startsWith("video/", ignoreCase = true) &&
                mime in setOf("video/avc", "video/x-vnd.on2.vp9", "video/av01")
            ) {
                streamProfileUnknown = true
            }
        }
        return CodecRequirements(
            mime = mime,
            width = format.width.takeIf { it > 0 },
            height = format.height.takeIf { it > 0 },
            frameRate = format.frameRate.takeIf { it > 0f }?.toDouble(),
            bitrate = format.bitrate.takeIf { it > 0 },
            profile = profile,
            bitDepth = bitDepth,
            chromaSubsampling = chromaSubsampling,
            streamProfileUnknown = streamProfileUnknown,
        )
    }

    @OptIn(UnstableApi::class)
    private fun platformFormat(format: Format): MediaFormat? {
        val mime = format.sampleMimeType ?: return null
        return runCatching {
            if (mime.startsWith("audio/", ignoreCase = true)) {
                MediaFormat.createAudioFormat(
                    mime,
                    if (format.sampleRate > 0) format.sampleRate else 48_000,
                    if (format.channelCount > 0) format.channelCount else 2,
                )
            } else {
                MediaFormat.createVideoFormat(
                    mime,
                    maxOf(format.width, 1),
                    maxOf(format.height, 1),
                ).apply {
                    if (format.frameRate > 0f) {
                        setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate)
                    }
                    if (format.bitrate > 0) {
                        setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
                    }
                }
            }
        }.getOrNull()
    }

    private fun readCodecByName(name: String): CodecCapability? = runCatching {
        codecList().codecInfos.firstOrNull { it.name == name }
    }.getOrNull()?.let { readCodec(it, mutableListOf()) }

    private fun readCodec(info: MediaCodecInfo, errors: MutableList<String>): CodecCapability {
        val types = runCatching { info.supportedTypes }
            .getOrElse {
                errors += "${info.name}: supportedTypes ${it.message}"
                return CodecCapability(
                    name = info.name,
                    isEncoder = info.isEncoder,
                    isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        null
                    },
                    isSoftwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        null
                    },
                    isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isVendor else null,
                    isAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isAlias else null,
                    types = emptyList(),
                )
            }
            .mapNotNull { mime ->
                runCatching { readType(info, mime) }
                    .onFailure { errors += "${info.name}/$mime: ${it.message}" }
                    .getOrNull()
            }
        return CodecCapability(
            name = info.name,
            isEncoder = info.isEncoder,
            isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                null
            },
            isSoftwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                null
            },
            isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isVendor else null,
            isAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isAlias else null,
            types = types,
        )
    }

    private fun readType(info: MediaCodecInfo, mime: String): CodecTypeCapability {
        val caps = info.getCapabilitiesForType(mime)
        val profileLevels = runCatching {
            caps.profileLevels.orEmpty()
                .map { ProfileLevel(it.profile, it.level) }
                .distinct()
                .sortedWith(compareBy({ it.profile }, { it.level }))
        }.getOrDefault(emptyList())
        val video = runCatching { caps.videoCapabilities }.getOrNull()?.let(::readVideoCapability)
        val bitrateModes = if (info.isEncoder) {
            val encoderCaps = runCatching { caps.encoderCapabilities }.getOrNull()
            buildSet {
                if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    add(BitrateMode.CBR)
                }
                if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                    add(BitrateMode.VBR)
                }
                if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true) {
                    add(BitrateMode.CQ)
                }
            }
        } else {
            emptySet()
        }
        val colorFormats = runCatching { caps.colorFormats.toList() }.getOrDefault(emptyList())
        return CodecTypeCapability(
            mime = mime,
            profileLevels = profileLevels,
            video = video,
            maxInstances = runCatching { caps.maxSupportedInstances }.getOrDefault(0),
            surfaceColorFormat = colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface),
            bitrateModes = bitrateModes,
            colorFormats = colorFormats,
            bitDepths = CodecProfiles.bitDepthsFor(mime, profileLevels),
        )
    }

    private fun readVideoCapability(
        video: MediaCodecInfo.VideoCapabilities,
    ): VideoCapability? = runCatching {
        VideoCapability(
            minWidth = video.supportedWidths.lower,
            maxWidth = video.supportedWidths.upper,
            minHeight = video.supportedHeights.lower,
            maxHeight = video.supportedHeights.upper,
            minFrameRate = video.supportedFrameRates?.lower?.toDouble() ?: 0.0,
            maxFrameRate = video.supportedFrameRates?.upper?.toDouble() ?: 0.0,
            minBitrate = video.bitrateRange.lower,
            maxBitrate = video.bitrateRange.upper,
            widthAlignment = video.widthAlignment,
            heightAlignment = video.heightAlignment,
        )
    }.getOrNull()

    private fun deviceIdentity(appVersion: String?): CodecDeviceIdentity = CodecDeviceIdentity(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        fingerprint = Build.FINGERPRINT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        board = Build.BOARD,
        hardware = Build.HARDWARE,
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            null
        },
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        appVersion = appVersion,
    )

    private fun codecList(): MediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

    private val BitrateMode.androidValue: Int
        get() = when (this) {
            BitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            BitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            BitrateMode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
        }

    private fun unsupported(reason: String): CapabilityDecision =
        CapabilityDecision(CapabilityVerdict.UNSUPPORTED, null, listOf(reason))
}
