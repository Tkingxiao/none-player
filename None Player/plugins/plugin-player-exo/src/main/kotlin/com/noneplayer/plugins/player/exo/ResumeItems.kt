package com.noneplayer.plugins.player.exo

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.noneplayer.core.database.HistoryEntry

/**
 * E209：系统「最近媒体/恢复播放」入口的 media3 条目。
 *
 * mediaId 用**来源内 id**（与正常 `ExoVideoPlayer.toMediaItem` 同一口径）；uri/mime/
 * 标题/封面来自历史快照，保证系统通知与恢复面板无需再查库。
 */
@OptIn(UnstableApi::class)
internal fun HistoryEntry.toResumeMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { null })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .apply { coverUri?.let { setArtworkUri(it.toUri()) } }
                .build(),
        )
        .build()
