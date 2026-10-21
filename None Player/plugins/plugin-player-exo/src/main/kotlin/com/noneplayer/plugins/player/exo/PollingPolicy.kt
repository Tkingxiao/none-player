package com.noneplayer.plugins.player.exo

/** 播放/缓冲或字幕偏移需要位置窗时的轮询间隔。 */
internal const val POLL_ACTIVE_MS = 250L

/** 暂停、结束、空队列时的轮询间隔（A24：别让暂停态继续按 4Hz 打 binder）。 */
internal const val POLL_IDLE_MS = 1_000L

/**
 * A24：位置轮询节律。
 *
 * - 空队列：不读位置/时长/缓冲，只隔 1s 检查一次控制器是否又有媒体；
 * - 播放中、缓冲中、字幕偏移非 0：保持 250ms（偏移窗与缓冲条需要即时反馈）；
 * - 暂停/结束且无字幕偏移：1s。暂停态 seek 的结果仍会在 1s 内回显，不改变现有语义。
 */
internal fun pollIntervalMs(
    hasMedia: Boolean,
    playing: Boolean,
    buffering: Boolean,
    subtitleOffsetMs: Long,
): Long = when {
    !hasMedia -> POLL_IDLE_MS
    playing || buffering || subtitleOffsetMs != 0L -> POLL_ACTIVE_MS
    else -> POLL_IDLE_MS
}
