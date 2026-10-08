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
 * 装载结果。
 *
 * [accepted] = false 表示 uri 被 scheme 白名单拒绝（E57/E76 的信任边界）；
 * [resumedFromMs] > 0 表示这次是**按断点续播**，值即实际生效的起点——
 * 播放页据此弹"已从上次位置继续"的左上角提示，无需自己再查一次历史。
 */
internal data class SessionStart(
    val accepted: Boolean,
    val resumedFromMs: Long = 0L,
    /**
     * 记忆里的外挂字幕**打不开**（文件被移走 / SAF 授权失效）。
     *
     * 播放页据此提示一次「字幕文件已失效，请重新挂载」——此前是静默回退原地址，
     * 用户只看到"没有字幕"，以为是自己没挂上（L7）。
     */
    val subtitleMissing: Boolean = false,
)

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
 * @return [SessionStart]；`accepted = false` = uri 被 scheme 白名单拒绝，调用方自行兜底
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
    /**
     * false = **只装载、不动播放状态**（E101）：类 PC 右栏接管画面时用。
     *
     * 右栏的常驻语义是"陪着你浏览"，不是"点了一次播放"——用户刚在播放页暂停、
     * 退回浏览态，右栏一挂载就 `play()` 会把暂停覆盖掉（模拟器实测）。
     * 默认 true 保迷你条 / 详情页内嵌的既有语义不变。
     */
    autoPlay: Boolean = true,
    onSubtitleMatched: ((String) -> Unit)? = null,
): SessionStart {
    if (!isPlayableUriScheme(item.uri)) return SessionStart(accepted = false)
    player.awaitReady()
    val sameMedia = player.currentUri.value == item.uri
    if (!sameMedia) {
        val queue = resolveFolderQueue(registry, item.uri, item.id)
        // 先 id 再 uri（与 E79 同一纪律）：`.cue` 分轨的 N 轨共享同一 uri，
        // uri 优先会永远命中队首那轨——点「第二轨」播「第一轨」（批次 8 实测）。
        // id 唯一命中本轨；id 不中（重扫换号）才退到 uri。
        val authoritative = queue.firstOrNull { it.id == item.id && it.id.isNotEmpty() }
            ?: queue.firstOrNull { it.uri == item.uri }
        // 断点的**读键**必须与写键同源：落盘走 `session.open(authoritative ?: item)`，
        // 而深链/通知重建的路由条目 sourceId 恒为空 → `keyOf` 得到 ":ms-x"，
        // 库里存的是 "local:ms-x"，冷启动续播因此静默从头播（模拟器实测）。
        val keyedItem = (authoritative ?: item).takeIf { it.id.isNotEmpty() } ?: item
        val saved =
            if (keyedItem.id.isNotEmpty()) history.positionOf(HistoryEntry.keyOf(keyedItem)) ?: 0L else 0L
        // L5：断点已经看到底（≥「看完」线）时不恢复——否则播完落盘的片尾位置会在下次
        // 打开时被原样套用，用户看到"刚点进去就结束"。库里那行**不清空**：它同时是
        // 「已看 x% / 播放历史」的展示依据（见 HistoryRepository.isWatchedToEnd）。
        val resumable =
            saved > RESUME_MIN_MS && !HistoryRepository.isWatchedToEnd(saved, keyedItem.durationMs)
        val startMs = if (restorePosition && resumable) saved else 0L
        // 断点经 setQueue 的 startPositionMs 下发（prepare 时套用）——事后 seekTo 在
        // 控制器未就绪时会被丢弃，续播会静默回到 0（模拟器实测）
        session.open(
            item = authoritative ?: item,
            queueOfFolder = queue,
            startPositionMs = startMs,
        )
        player.setSpeed(settings.playbackSpeed.first())
        var subtitleMissing = false
        if (item.id.isNotEmpty()) {
            val savedSubtitle = settings.subtitleUri(item.id).first()
            // 同名字幕自动挂载（批次 3 §12.6）：无记忆时按 ASS > SSA > SRT > VTT
            // 找同目录同名文件；命中即记忆，之后与手动挂载走同一恢复链路
            val subtitle = savedSubtitle ?: resolveSiblingSubtitle(context, item.uri)?.also {
                onSubtitleMatched?.invoke(it)
            }
            subtitle?.let {
                val prepared = prepareSubtitleUri(context, it.toUri())
                // 只有"记住过的字幕挂不上"才提示：同名自动匹配落空等于"这文件夹里没有
                // 字幕"，不是失效——对没主动挂过字幕的用户报错是噪音（L7）
                subtitleMissing = savedSubtitle != null && prepared.sourceMissing
                player.setSubtitle(prepared.uri)
            }
        }
        player.play()
        return SessionStart(accepted = true, resumedFromMs = startMs, subtitleMissing = subtitleMissing)
    } else {
        // 会话里已经是同一个媒体（从迷你条/内嵌播放器点回来）。
        // **已播完的必须从头起**：media3 在 STATE_ENDED 上 `play()` 是空操作，
        // 不退位就永远停在结束画面——用户看到"点进来没反应"。
        // E101：`autoPlay = false` 的宿主（类 PC 右栏）连退位都不做——它是"接管画面"，
        // 不是一次播放意图，暂停/结束状态原样保留给用户决定。
        if (autoPlay) {
            if (!restorePosition || player.state.value == PlaybackState.Ended) player.seekTo(0L)
            player.play()
        }
        // 会话本来就在播同一媒体：进度是连续的，不算"从断点续播"，不弹提示。
        return SessionStart(accepted = true)
    }
}

/** 断点起播门槛：低于它视为"没看过"，从头播（与历史落盘门槛同为 10s 之一半的宽松档） */
private const val RESUME_MIN_MS = 5_000L
