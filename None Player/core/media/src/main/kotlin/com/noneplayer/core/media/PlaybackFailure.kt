package com.noneplayer.core.media

/**
 * 播放失败的结构化描述（E39 探测 / E41 体面失败）。
 *
 * 字段全部是可直接上屏的本地信息：容器 / 视频编码 / 音频编码 三行 + 原因 + 转码建议。
 * 由 [com.noneplayer.core.media.probe.FormatProbe] 或引擎错误映射构造，UI 直出不加工。
 */
data class PlaybackFailure(
    val title: String = "无法播放此文件",
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val reason: String,
    val suggestion: String? = TRANSCODE_SUGGESTION,
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
        )

        /** 容器能读但视频编码无法识别（WMV2/VC-1 等未映射编码） */
        fun unknownVideoCodec(container: String?, audioCodec: String?) = PlaybackFailure(
            container = container,
            audioCodec = audioCodec,
            reason = "没有解析到受支持的视频编码（常见于 WMV / VC-1 / RealVideo 等旧编码）。",
        )

        /** 本设备没有该视频编码的解码器（或档次/特性不受支持） */
        fun videoCodecUnsupported(
            container: String?,
            videoCodec: String?,
            audioCodec: String?,
        ) = PlaybackFailure(
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            reason = "本设备没有该视频编码可用的解码器（或解码器不支持该文件的编码档次/特性），None Player 也未内置视频软解。",
        )

        /** 文件不可读（损坏 / 截断 / 权限） */
        fun unreadable(container: String? = null) = PlaybackFailure(
            container = container,
            reason = "文件无法读取：可能已损坏、不完整或没有访问权限。",
        )

        fun fileMissing() = PlaybackFailure(
            reason = "文件不存在或已被移动，请重新扫描媒体库。",
            suggestion = null,
        )

        fun noPermission() = PlaybackFailure(
            reason = "没有读取该文件的权限，请在系统设置中授予媒体访问权限。",
            suggestion = null,
        )

        /** 兜底：拿不到结构化信息时仅展示引擎消息 */
        fun unexpected(detail: String) = PlaybackFailure(
            reason = detail,
            suggestion = null,
        )
    }
}

/** mime / 扩展名 → 可读编码与容器名（E41 三行文案的词汇表） */
object MediaNames {

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
        "video/x-ms-wmv" to "WMV (VC-1)",
        "video/x-ms-vc1" to "VC-1",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-flv" to "Sorenson H.263 (FLV1)",
        "video/mjpeg" to "Motion JPEG",
        "video/theora" to "Theora",
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
        "audio/amr" to "AMR",
        "audio/alac" to "ALAC",
        "audio/midi" to "MIDI",
    )
}
