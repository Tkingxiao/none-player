package com.noneplayer.core.media

/** 播放失败所属环节（E209）：UI/日志据此决定文案与是否给重试。 */
enum class PlaybackFailureCategory {
    CONTAINER,
    VIDEO_DECODE,
    AUDIO_DECODE,
    VIDEO_RENDER,
    AUDIO_OUTPUT,
    IO,
    DRM,
    OTHER,
}

/**
 * 播放失败的结构化描述（E39 探测 / E41 体面失败）。
 *
 * 字段全部是可直接上屏的本地信息：容器 / 视频编码 / 音频编码 三行 + 原因 + 转码建议；
 * [category] 标明失败环节，[retryable] 决定 UI 是否给「重试」（永久性编码/容器/DRM
 * 失败重试没有意义，音轨/渲染/IO 的瞬时失败才给）。
 * 由 [com.noneplayer.core.media.probe.FormatProbe] 或引擎错误映射构造，UI 直出不加工。
 */
data class PlaybackFailure(
    val title: String = "无法播放此文件",
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val reason: String,
    val suggestion: String? = TRANSCODE_SUGGESTION,
    val category: PlaybackFailureCategory = PlaybackFailureCategory.OTHER,
    val retryable: Boolean = true,
) {
    /** "容器：AVI ｜ 视频：Xvid (MPEG-4 ASP) ｜ 音频：MP3"；三项全空为 null */
    val trackLine: String? = listOfNotNull(
        container?.let { "容器：$it" },
        videoCodec?.let { "视频：$it" },
        audioCodec?.let { "音频：$it" },
    ).takeIf { it.isNotEmpty() }?.joinToString(TRACK_SEPARATOR)

    companion object {
        const val TRANSCODE_SUGGESTION = "转码建议：H.264 High · AAC-LC · MP4 + faststart"
        private const val TRACK_SEPARATOR = " ｜ "

        /** 容器没有解封装支持（RMVB / WMV 等旧容器） */
        fun containerUnsupported(container: String?) = PlaybackFailure(
            container = container,
            reason = "该文件的封装格式暂无解封装支持（常见于 RMVB / WMV 等旧格式）。",
            category = PlaybackFailureCategory.CONTAINER,
            retryable = false,
        )

        /** 容器能读但视频编码无法识别（WMV2/VC-1 等未映射编码） */
        fun unknownVideoCodec(container: String?, audioCodec: String?) = PlaybackFailure(
            container = container,
            audioCodec = audioCodec,
            reason = "没有解析到受支持的视频编码（常见于 WMV / VC-1 / RealVideo 等旧编码）。",
            category = PlaybackFailureCategory.VIDEO_DECODE,
            retryable = false,
        )

        /**
         * 本设备没有该视频编码的解码器（或档次/特性不受支持）。
         *
         * 两条来源共用这条文案：起播前探测（平台 MediaCodec 与内置 FFmpeg 软解都不认该编码）
         * 与运行期解码错误（[com.noneplayer.core.media.PlaybackFailure] 的调用方按错误码映射）。
         * 因此只陈述"没有可用解码器"这一事实，不再断言"未内置软解"——内置 FFmpeg 软解
         * 只覆盖固定编码表，是否覆盖该文件由调用方判定。
         */
        fun videoCodecUnsupported(
            container: String?,
            videoCodec: String?,
            audioCodec: String?,
        ) = PlaybackFailure(
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            reason = "本设备没有该视频编码可用的解码器（或解码器不支持该文件的编码档次/特性）。",
            category = PlaybackFailureCategory.VIDEO_DECODE,
            retryable = false,
        )

        /**
         * 有解码器但**档次（profile）不受支持**——起播前就判出，避免崩在第一帧（§12.9.4）。
         * 典型：MPEG-4 ASP（Advanced Simple Profile，含 qpel/GMC）码流遇到仅 Simple Profile
         * 的设备解码器。[requiredProfile] 如 "Advanced Simple Profile"，[deviceProfiles]
         * 如 "Simple Profile"。
         */
        fun videoProfileUnsupported(
            container: String?,
            videoCodec: String?,
            audioCodec: String?,
            requiredProfile: String,
            deviceProfiles: String?,
        ) = PlaybackFailure(
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            reason = buildString {
                append("该文件需要「")
                append(requiredProfile)
                append("」档次，但本设备的解码器")
                if (deviceProfiles.isNullOrBlank()) {
                    append("不支持该档次")
                } else {
                    append("仅支持「")
                    append(deviceProfiles)
                    append("」")
                }
                append("。此类文件会在解码第一帧时失败。")
            },
            category = PlaybackFailureCategory.VIDEO_DECODE,
            retryable = false,
        )

        /** 平台声明有解码器，但该具体码流的 profile/level/位深/尺寸要求不满足。 */
        fun videoCapabilityUnsupported(
            container: String?,
            videoCodec: String?,
            audioCodec: String?,
            detail: String?,
        ) = PlaybackFailure(
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            reason = buildString {
                append("该文件的编码档次或位深超出本设备解码器能力")
                if (!detail.isNullOrBlank()) append("（").append(detail).append("）")
                append("。")
            },
            category = PlaybackFailureCategory.VIDEO_DECODE,
            retryable = false,
        )

        /** 文件不可读（损坏 / 截断 / 权限） */
        fun unreadable(container: String? = null) = PlaybackFailure(
            container = container,
            reason = "文件无法读取：可能已损坏、不完整或没有访问权限。",
            category = PlaybackFailureCategory.IO,
            retryable = true,
        )

        fun fileMissing() = PlaybackFailure(
            reason = "文件不存在或已被移动，请重新扫描媒体库。",
            suggestion = null,
            category = PlaybackFailureCategory.IO,
            retryable = false,
        )

        /** 音频编码本机不可解（批次 8：纯音频文件的探测分支） */
        fun audioCodecUnsupported(container: String?, audioCodec: String?) = PlaybackFailure(
            container = container,
            audioCodec = audioCodec,
            reason = "没有可用的解码器播放该音频编码（$audioCodec）。",
            suggestion = "可转码为 FLAC / AAC / MP3 后重试。",
            category = PlaybackFailureCategory.AUDIO_DECODE,
            retryable = false,
        )

        fun noPermission() = PlaybackFailure(
            reason = "没有读取该文件的权限，请在系统设置中授予媒体访问权限。",
            suggestion = null,
            category = PlaybackFailureCategory.IO,
            retryable = false,
        )

        /** 兜底：拿不到结构化信息时仅展示引擎消息 */
        fun unexpected(detail: String) = PlaybackFailure(
            reason = detail,
            suggestion = null,
            category = PlaybackFailureCategory.OTHER,
            retryable = true,
        )

        /** 音频输出（AudioTrack 初始化/写入/offload）失败：多为设备或资源瞬时问题 */
        fun audioOutput(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() }
                ?: "音频输出初始化或写入失败（可能是音频设备或系统资源暂时不可用）。",
            suggestion = null,
            category = PlaybackFailureCategory.AUDIO_OUTPUT,
            retryable = true,
        )

        /** 视频渲染/帧处理失败（Surface、帧处理器、特效链） */
        fun videoRender(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() }
                ?: "视频渲染或帧处理失败（可能是 Surface 或帧处理器异常）。",
            suggestion = null,
            category = PlaybackFailureCategory.VIDEO_RENDER,
            retryable = true,
        )

        /** DRM 保护内容：重试通常无意义，需换合法来源/授权 */
        fun drm(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() }
                ?: "受 DRM 保护的内容无法在此设备播放（授权或 DRM 组件不可用）。",
            suggestion = null,
            category = PlaybackFailureCategory.DRM,
            retryable = false,
        )

        /** 解码器被系统回收等可恢复的解码失败 */
        fun videoDecodeTransient(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() } ?: "解码器暂时不可用（可能被系统回收），重试可能恢复。",
            suggestion = null,
            category = PlaybackFailureCategory.VIDEO_DECODE,
            retryable = true,
        )

        /** 可重试的 IO/网络瞬时失败 */
        fun ioTransient(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() } ?: "读取失败，请稍后重试。",
            suggestion = null,
            category = PlaybackFailureCategory.IO,
            retryable = true,
        )

        /** 明确的访问/区域限制：重试无意义 */
        fun accessDenied(detail: String? = null) = PlaybackFailure(
            reason = detail?.takeIf { it.isNotBlank() } ?: "没有权限访问该内容。",
            suggestion = null,
            category = PlaybackFailureCategory.IO,
            retryable = false,
        )
    }
}

/** mime / 扩展名 → 可读编码与容器名（E41 三行文案的词汇表） */
object MediaNames {

    /**
     * WMV2 专用 mime。不要复用 [VIDEO] `video/x-ms-wmv`（显示名是 VC-1）或 `video/x-ms-vc1`。
     * 只有补出来的 WMV2 轨、软解渲染器和这里的显示名认它。
     */
    const val MIME_WMV2 = "video/x-np-wmv2"

    /**
     * 设备明确没有 MPEG-4 ASP、且软解可用时，才把 Qpel 流改成这个 mime。
     * Simple Profile 和设备已声明 ASP 的流保持 `video/mp4v-es`。
     */
    const val MIME_MPEG4_ASP = "video/x-np-mpeg4-asp"

    // ---- E204 解码白名单批：libnpffmpeg 认领的 mime（media3 无对应常量的自定义 mime）----
    // MIME_WMV2 走 libnplegacy 专用链（既定裁定），不在这里。

    /** WMV1（fourcc WMV1，AVI） */
    const val MIME_WMV1 = "video/x-ms-wmv1"

    /** WMV3/WMV9（fourcc WMV3/WMVP，AVI） */
    const val MIME_WMV3 = "video/x-ms-wmv"

    /** VC-1 Advanced Profile（fourcc WVC1/WMVA；media3 MatroskaExtractor 既有常量同值） */
    const val MIME_VC1 = "video/wvc1"

    /** WMA（wmav1/wmav2，tag 0x160/0x161——Media3Avi 与本插件对整族发这一个 mime） */
    const val MIME_WMA = "audio/x-ms-wma"

    /** WMA Pro（tag 0x162） */
    const val MIME_WMAPRO = "audio/x-ms-wmapro"

    /** WMA Lossless（tag 0x163） */
    const val MIME_WMALOSSLESS = "audio/x-ms-wmalossless"

    /** 从地址扩展名映射容器名；未知时返回大写的扩展名 */
    fun containerName(uriString: String): String? {
        val path = uriString.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() } ?: return null
        return CONTAINERS[ext] ?: ext.uppercase()
    }

    fun videoCodecName(mime: String?): String? = mime?.let { VIDEO_CODECS[it] ?: it }

    fun audioCodecName(mime: String?): String? = mime?.let { AUDIO_CODECS[it] ?: it }

    private val CONTAINERS = mapOf(
        "mp4" to "MP4", "m4v" to "MP4", "mov" to "QuickTime (MOV)",
        "mkv" to "Matroska (MKV)", "webm" to "WebM",
        "avi" to "AVI", "divx" to "AVI",
        "ts" to "MPEG-TS", "m2ts" to "MPEG-TS", "mts" to "MPEG-TS",
        "mpg" to "MPEG-PS", "mpeg" to "MPEG-PS", "vob" to "MPEG-PS",
        "flv" to "FLV", "wmv" to "WMV (ASF)", "asf" to "WMV (ASF)",
        "rm" to "RealMedia (RM)", "rmvb" to "RealMedia (RMVB)",
        "ogv" to "Ogg", "ogg" to "Ogg", "3gp" to "3GP",
    )

    private val VIDEO_CODECS = mapOf(
        "video/avc" to "H.264 (AVC)",
        "video/hevc" to "H.265 (HEVC)",
        "video/mp4v-es" to "MPEG-4 Visual (Xvid/DivX)",
        "video/3gpp" to "H.263 (3GP)",
        "video/mpeg2" to "MPEG-2",
        "video/mpeg" to "MPEG-1/2",
        "video/mp42" to "MS MPEG-4 v2",
        "video/mp43" to "MS MPEG-4 v3",
        // E204 白名单批：WMV 家族拆开展示（wmv3 与 vc1 是不同编码，旧口径混写）
        "video/x-ms-wmv" to "WMV3 (WMV9)",
        "video/x-ms-wmv1" to "WMV1",
        "video/wvc1" to "VC-1",
        "video/x-ms-vc1" to "VC-1",
        MIME_WMV2 to "WMV2",
        MIME_MPEG4_ASP to "MPEG-4 ASP (Qpel)",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-flv" to "Sorenson H.263 (FLV1)",
        "video/mjpeg" to "Motion JPEG",
        "video/theora" to "Theora",
        // E207 avformat 批：NpFfmpegExtractor 产出的轨 mime（与 NpFfmpegCodecs 表同源）
        "video/x-flv1" to "Sorenson H.263 (FLV1)",
        "video/x-vp6" to "VP6",
        "video/x-vp6f" to "VP6",
        "video/x-vp6a" to "VP6 (alpha)",
        "video/x-pn-realvideo" to "RealVideo 1.0",
        "video/x-pn-realvideo2" to "RealVideo G2",
        "video/x-pn-realvideo3" to "RealVideo 8",
        "video/x-pn-realvideo4" to "RealVideo 9",
        "video/x-ffv1" to "FFV1",
        "video/x-np-mpeg1" to "MPEG-1",
        "video/x-np-mpeg2" to "MPEG-2",
    )

    private val AUDIO_CODECS = mapOf(
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/true-hd" to "TrueHD",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/raw" to "PCM",
        "audio/x-ms-wma" to "WMA",
        MIME_WMAPRO to "WMA Pro",
        MIME_WMALOSSLESS to "WMA Lossless",
        "audio/amr" to "AMR",
        "audio/alac" to "ALAC",
        "audio/midi" to "MIDI",
        // E207 avformat 批：RM/NUT 音频族
        "audio/x-cook" to "RealAudio (Cook)",
        "audio/x-sipr" to "RealAudio (SIPR)",
        "audio/x-atrac3" to "ATRAC3",
        "audio/x-atrac3al" to "ATRAC3 AL",
        "audio/x-atrac3pal" to "ATRAC3+ LP",
        "audio/x-wavpack" to "WavPack",
        "audio/x-tta" to "TTA",
        // PS 的 MPEG-1/2 层音频（npff 软解补位）
        "audio/mpeg-L1" to "MP1",
        "audio/mpeg-L2" to "MP2",
    )
}
