package com.noneplayer.core.media

import androidx.compose.runtime.Immutable

/**
 * 媒体项——所有来源（本地扫描、网盘、DLNA……）的统一领域模型。
 *
 * 各插件的私有数据通过各自的方式映射为本模型，
 * 内核与扩展点契约只认 [MediaItem]，不感知来源细节。
 */
@Immutable
data class MediaItem(
    /** 来源内唯一 id；跨来源以 [sourceId] + [id] 区分 */
    val id: String,
    /** 所属媒体来源 id，对应 [com.noneplayer.kernel.api.MediaSourceProvider.sourceId] */
    val sourceId: String,
    val title: String,
    /** 播放地址（本地路径或 URL） */
    val uri: String,
    val mimeType: String = "video/mp4",
    /** 时长（毫秒），未知为 0 */
    val durationMs: Long = 0,
    /** 文件大小（字节），未知为 0 */
    val sizeBytes: Long = 0,
    /**
     * 文件最后修改时间（epoch **秒**，与 MediaStore `DATE_MODIFIED` 同单位），未知为 0。
     *
     * A1-3：断点失效校验的第二路指纹（`sizeBytes` 是第一路）。文件被替换/重录后
     * 沿用旧断点，比"从头播"更糟——位置可能落在另一部片子的中间。
     */
    val lastModifiedSec: Long = 0,
    /** 加入媒体库的时间戳（epoch 毫秒） */
    val addedAtMs: Long = 0,
    /** 封面缩略图地址（本地缓存文件路径或 URL），可能为空 */
    val coverUri: String? = null,
    /** 所属文件夹的相对路径（如 "DCIM/Camera"），用于按文件夹分组；未知为 null */
    val folder: String? = null,
    /** 视频像素宽（未知为 0；音频恒 0）——卡片 meta 的「分辨率」来源 */
    val width: Int = 0,
    /** 视频像素高（未知为 0；音频恒 0） */
    val height: Int = 0,
    /**
     * 富化后的展示标题（`plugin-enricher-filename` 写；E42）。
     * null / 空白 = 未富化，UI 退回 [title]（整条文件名）。
     */
    val displayTitle: String? = null,
    /** 季号（文件名解析；0 = 未知） */
    val seasonNumber: Int = 0,
    /** 集号（文件名解析；0 = 未知） */
    val episodeNumber: Int = 0,
    /** 视频编码（文件名解析，如 HEVC / AVC / AV1；null = 未知） */
    val videoCodec: String? = null,
    /**
     * `.cue` 分轨（§12.4，批次 8）：本项指向的播放窗口起点（微秒）。
     * 0 = 从文件头播（普通媒体项 / 分轨的首轨）。
     * 引擎侧映射 `MediaItem.ClippingConfiguration.startPositionUs`；
     * 播放位置相对窗口起点（seek/断点都是"第几分的第几轨内"，与整文件坐标无关）。
     */
    val clipStartUs: Long = 0,
    /** `.cue` 分轨：窗口终点（微秒）；0 = 到文件尾。两值同时为 0 即不裁剪 */
    val clipEndUs: Long = 0,
) {
    /** 是否为 `.cue` 分轨项（引擎按此挂 ClippingConfiguration） */
    val isClipped: Boolean get() = clipStartUs > 0 || clipEndUs > 0
}

/** 媒体种类：由 mimeType 判定（音频/视频），UI 用它决定角标、图标与分组 */
enum class MediaKind { VIDEO, AUDIO }

/** 由 [MediaItem.mimeType] 判定媒体种类；未知/视频一律视为 VIDEO */
val MediaItem.kind: MediaKind
    get() = if (mimeType.startsWith("audio/", ignoreCase = true)) MediaKind.AUDIO else MediaKind.VIDEO

/**
 * 卡片/列表要显示的标题：富化出短标题时用它，否则退回整条文件名（E42 验收项）。
 */
val MediaItem.cardTitle: String
    get() = displayTitle?.takeIf { it.isNotBlank() } ?: title

/**
 * 「第 N 集」标签（文件名解析出集号时才有）——设计稿卡片 meta 里的剧集角标。
 * 有季号时并成「第 1 季 第 2 集」，避免多季混排时歧义。
 */
val MediaItem.episodeLabel: String?
    get() = when {
        episodeNumber <= 0 -> null
        seasonNumber > 0 -> "第 $seasonNumber 季 第 $episodeNumber 集"
        else -> "第 $episodeNumber 集"
    }

/**
 * 卡片 meta 的「分辨率 / 质量」标签（设计稿 `v.res`：1080P / 4K / 无损）。
 *
 * 视频按像素高归档；音频只有"无损"这一档可从 MIME 判定，
 * 有损音频拿不到真实码率（MediaStore 的 BITRATE 在旧版本不可靠），
 * 宁可不显示也不编造「320kbps」——如实返回空串由调用方省略该段。
 */
val MediaItem.resolutionLabel: String
    get() = when {
        kind == MediaKind.AUDIO -> when (mimeType.substringBefore(';').trim().lowercase()) {
            "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/ape", "audio/alac" -> "无损"
            else -> ""
        }
        height >= 2160 -> "4K"
        height >= 1440 -> "1440P"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        height > 0 -> "${height}P"
        else -> ""
    }
