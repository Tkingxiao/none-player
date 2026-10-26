package com.noneplayer.core.media.probe

/**
 * 从容器 codec string / 初始化数据提取平台能力闸门需要的最小码流要求。
 *
 * 解析失败返回 null：调用方必须把它当作 UNKNOWN，而不是默认成 8-bit 或
 * 4:2:0。这里不负责判断设备是否支持，只负责描述码流声明。
 */
internal object CodecStreamProfileParser {

    internal data class Parsed(
        val profile: Int,
        val level: CodecLevel? = null,
        val bitDepth: Int? = null,
        val chromaSubsampling: String? = null,
    )

    fun parse(mime: String, codecs: String?, initializationData: List<ByteArray>): Parsed? {
        if (codecs.isNullOrBlank()) {
            return when {
                mime.equals("video/avc", ignoreCase = true) -> parseAvcConfiguration(initializationData)
                else -> null
            }
        }
        val token = codecs.trim().substringBefore(',').trim()
        val parts = token.split('.')
        return when {
            mime.equals("video/avc", ignoreCase = true) -> parseAvcCodec(parts)
            mime.equals("video/x-vnd.on2.vp9", ignoreCase = true) -> parseVp9Codec(parts)
            mime.equals("video/av01", ignoreCase = true) -> parseAv1Codec(parts)
            else -> null
        }
    }

    private fun parseAvcCodec(parts: List<String>): Parsed? {
        if (parts.size < 2 || (parts[0] != "avc1" && parts[0] != "avc3")) return null
        val value = parts[1]
        if (value.length < 6) return null
        val profileIdc = value.substring(0, 2).toIntOrNull(16) ?: return null
        val levelIdc = value.substring(4, 6).toIntOrNull(16) ?: return null
        return Parsed(
            profile = avcProfile(profileIdc) ?: return null,
            level = avcLevel(levelIdc),
            bitDepth = avcBitDepth(profileIdc),
        )
    }

    private fun parseAvcConfiguration(initializationData: List<ByteArray>): Parsed? {
        val data = initializationData.firstOrNull()?.takeIf { it.size >= 4 } ?: return null
        // AVCDecoderConfigurationRecord: version, profile_idc, constraints, level_idc.
        if ((data[0].toInt() and 0xFF) == 1) {
            val profileIdc = data[1].toInt() and 0xFF
            val levelIdc = data[3].toInt() and 0xFF
            return Parsed(
                profile = avcProfile(profileIdc) ?: return null,
                level = avcLevel(levelIdc),
                bitDepth = avcBitDepth(profileIdc),
            )
        }
        // Annex-B SPS is deliberately not guessed here. A malformed or partial SPS
        // must remain UNKNOWN until a complete parser can validate its RBSP.
        return null
    }

    private fun parseVp9Codec(parts: List<String>): Parsed? {
        if (parts.size < 2 || parts[0] != "vp09") return null
        val profile = parts[1].toIntOrNull() ?: return null
        val bitDepth = parts.getOrNull(3)?.toIntOrNull()
        return Parsed(
            profile = profile,
            bitDepth = bitDepth?.takeIf { it == 8 || it == 10 || it == 12 },
            chromaSubsampling = parts.getOrNull(4)?.let(::vp9Chroma),
        )
    }

    private fun parseAv1Codec(parts: List<String>): Parsed? {
        if (parts.size < 4 || parts[0] != "av01") return null
        val profile = parts[1].toIntOrNull() ?: return null
        // av01.P.LLT.DD... ; the third field is bit depth.
        val bitDepth = parts[3].toIntOrNull()
            ?.takeIf { it == 8 || it == 10 || it == 12 }
        return Parsed(
            profile = profile,
            bitDepth = bitDepth,
            // AV1 CCC is retained as a normalized token; Android codec declarations
            // rarely expose an equivalent capability field, so the decider remains UNKNOWN.
            chromaSubsampling = parts.getOrNull(5)?.takeIf { it.length == 3 }?.let { "av1:$it" },
        )
    }

    private fun vp9Chroma(value: String): String? = when (value) {
        "00" -> "4:2:0"
        "01" -> "4:2:2"
        "02" -> "4:4:4"
        else -> null
    }

    private fun avcProfile(profileIdc: Int): Int? = when (profileIdc) {
        66 -> CodecProfiles.AVC_BASELINE
        77 -> CodecProfiles.AVC_MAIN
        88 -> CodecProfiles.AVC_EXTENDED
        100 -> CodecProfiles.AVC_HIGH
        110 -> CodecProfiles.AVC_HIGH10
        122 -> CodecProfiles.AVC_HIGH422
        244 -> CodecProfiles.AVC_HIGH444
        else -> null
    }

    private fun avcBitDepth(profileIdc: Int): Int? = when (profileIdc) {
        110, 122, 244 -> 10
        66, 77, 88, 100 -> 8
        else -> null
    }

    private fun avcLevel(levelIdc: Int): CodecLevel? = when (levelIdc) {
        10 -> CodecLevel(1.0)
        11 -> CodecLevel(1.1)
        12 -> CodecLevel(1.2)
        13 -> CodecLevel(1.3)
        20 -> CodecLevel(2.0)
        21 -> CodecLevel(2.1)
        22 -> CodecLevel(2.2)
        30 -> CodecLevel(3.0)
        31 -> CodecLevel(3.1)
        32 -> CodecLevel(3.2)
        40 -> CodecLevel(4.0)
        41 -> CodecLevel(4.1)
        42 -> CodecLevel(4.2)
        50 -> CodecLevel(5.0)
        51 -> CodecLevel(5.1)
        52 -> CodecLevel(5.2)
        60 -> CodecLevel(6.0)
        61 -> CodecLevel(6.1)
        62 -> CodecLevel(6.2)
        else -> null
    }
}
