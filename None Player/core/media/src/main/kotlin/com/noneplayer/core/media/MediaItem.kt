package com.noneplayer.core.media

/**
 * 媒体项——所有来源（本地扫描、网盘、DLNA……）的统一领域模型。
 *
 * 各插件的私有数据通过各自的方式映射为本模型，
 * 内核与扩展点契约只认 [MediaItem]，不感知来源细节。
 */
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
    /** 加入媒体库的时间戳（epoch 毫秒） */
    val addedAtMs: Long = 0,
    /** 封面缩略图地址（本地缓存文件路径或 URL），可能为空 */
    val coverUri: String? = null,
    /** 所属文件夹的相对路径（如 "DCIM/Camera"），用于按文件夹分组；未知为 null */
    val folder: String? = null,
)
