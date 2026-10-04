package com.noneplayer.plugins.player.ui

import android.content.Context
import androidx.core.net.toUri
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.VideoPlayer
import kotlinx.coroutines.flow.first

/**
 * 会话装载/起播的**唯一**入口——播放页与「内嵌播放器」共用。
 *
 * 两处各写一份装载链，必然在某次修复里漂移（批次 3 的 `applyQueue` 字幕缺口就是这么
 * 出现的）。这里把公开语义钉死：
 *   ① 已在播同一媒体 → 只续播（已播完的从头起），**绝不重新 prepare**（B1）；
 *   ② 未在播 → 用**同文件夹队列**开播（连播/⏮⏭ 由 media3 免费提供），
 *      按 per-media 记忆恢复断点、倍速、字幕（含同名自动挂载）。
 *
 * @param onSubtitleMatched 同名自动挂载命中时的记忆落盘钩子（调用方用自己的 scope 执行，
 *   不阻塞装载；播放页与内嵌播放器都只是转发给 AppSettings）
 * @return false = uri 被 scheme 白名单拒绝（E57/E76 的信任边界），调用方自行兜底
 */
internal suspend fun ensureSessionPlaying(
    context: Context,
    registry: ExtensionRegistry,
    settings: AppSettings,
    history: HistoryRepository,
    player: VideoPlayer,
    session: PlaybackSession,
    item: MediaItem,
    /** false = 从头播（长按菜单「从头播放」）：不恢复断点，已播完的也退位 */
    restorePosition: Boolean = true,
    onSubtitleMatched: ((String) -> Unit)? = null,
): Boolean {
    if (!isPlayableUriScheme(item.uri)) return false
    player.awaitReady()
    val sameMedia = player.currentUri.value == item.uri
    if (!sameMedia) {
        val queue = resolveFolderQueue(registry, item.uri, item.id)
        val authoritative = queue.firstOrNull { it.uri == item.uri || it.id == item.id }
        // 断点的**读键**必须与写键同源：落盘走 `session.open(authoritative ?: item)`，
        // 而深链/通知重建的路由条目 sourceId 恒为空 → `keyOf` 得到 ":ms-x"，
        // 库里存的是 "local:ms-x"，冷启动续播因此静默从头播（模拟器实测）。
        val keyedItem = (authoritative ?: item).takeIf { it.id.isNotEmpty() } ?: item
        val saved =
            if (keyedItem.id.isNotEmpty()) history.positionOf(HistoryEntry.keyOf(keyedItem)) ?: 0L else 0L
        // 断点经 setQueue 的 startPositionMs 下发（prepare 时套用）——事后 seekTo 在
        // 控制器未就绪时会被丢弃，续播会静默回到 0（模拟器实测）
        session.open(
            item = authoritative ?: item,
            queueOfFolder = queue,
            startPositionMs = if (restorePosition && saved > 5_000) saved else 0L,
        )
        player.setSpeed(settings.playbackSpeed.first())
        if (item.id.isNotEmpty()) {
            val savedSubtitle = settings.subtitleUri(item.id).first()
            // 同名字幕自动挂载（批次 3 §12.6）：无记忆时按 ASS > SSA > SRT > VTT
            // 找同目录同名文件；命中即记忆，之后与手动挂载走同一恢复链路
            val subtitle = savedSubtitle ?: resolveSiblingSubtitle(context, item.uri)?.also {
                onSubtitleMatched?.invoke(it)
            }
            subtitle?.let { player.setSubtitle(prepareSubtitleUri(context, it.toUri())) }
        }
        player.play()
    } else {
        // 会话里已经是同一个媒体（从迷你条/内嵌播放器点回来）。
        // **已播完的必须从头起**：media3 在 STATE_ENDED 上 `play()` 是空操作，
        // 不退位就永远停在结束画面——用户看到"点进来没反应"。
        if (!restorePosition || player.state.value == PlaybackState.Ended) player.seekTo(0L)
        player.play()
    }
    return true
}
