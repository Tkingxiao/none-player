package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem

/**
 * 历史/继续观看的领域条目（不把 Room 实体漏给插件）。
 *
 * [toMediaItem] 让"历史页续播"直接复用播放路由：`id` 就是 mediaId，
 * per-media 记忆（断点/字幕/画面/仅音频）在续播链上照常生效。
 */
data class HistoryEntry(
    val mediaKey: String,
    val sourceId: String,
    val mediaId: String,
    val title: String,
    val uri: String,
    val mimeType: String,
    val coverUri: String?,
    val folder: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** 已看比例（0..1）：继续观看过滤与卡片进度条都用它 */
    val watchedRatio: Float,
    val playCount: Int,
    val lastPlayedAt: Long,
    val width: Int,
    val height: Int,
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = mediaId,
        sourceId = sourceId,
        title = title,
        uri = uri,
        mimeType = mimeType,
        durationMs = durationMs,
        coverUri = coverUri,
        folder = folder,
        width = width,
        height = height,
    )

    companion object {
        /** 跨来源唯一键（E73 口径：按 sourceId 分命名空间） */
        fun keyOf(item: MediaItem): String = "${item.sourceId}:${item.id}"
    }
}

internal fun PlaybackHistoryEntity.toEntry(): HistoryEntry = HistoryEntry(
    mediaKey = mediaKey,
    sourceId = sourceId,
    mediaId = mediaKey.substringAfter(':', mediaKey),
    title = title,
    uri = uri,
    mimeType = mime,
    coverUri = coverUri,
    folder = folder,
    durationMs = durationMs,
    positionMs = positionMs,
    watchedRatio = watchedRatio,
    playCount = playCount,
    lastPlayedAt = lastPlayedAt,
    width = width,
    height = height,
)
