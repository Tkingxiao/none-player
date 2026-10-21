package com.noneplayer.plugins.library

import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID

/** A25：现场抽帧失败负缓存的 TTL 与上限（短 TTL，允许文件就绪后恢复）。 */
internal const val COVER_FRAME_FAILURE_TTL_MS = 60_000L
internal const val COVER_FRAME_FAILURE_MAX_ENTRIES = 256

/**
 * A25：只有“本地 MediaStore 来源、没有缓存封面、不是音频、近期没失败过”的视频才允许
 * 在 UI 里现场抽帧。抽帧是全分辨率 `MediaMetadataRetriever`，失败项若每次都重试，
 * 滚动/重进库页会把失败样本反复打开。
 */
internal fun shouldExtractFrame(
    sourceId: String,
    coverUri: String?,
    isAudio: Boolean,
    recentlyFailed: Boolean,
): Boolean = !isAudio &&
    coverUri == null &&
    sourceId == LOCAL_MEDIA_SOURCE_ID &&
    !recentlyFailed

/**
 * 现场抽帧失败负缓存：短 TTL + LRU 上限。
 *
 * 只在 UI 侧使用，不是权威数据；TTL 到期后允许再试一次，避免一次瞬时失败把封面永久
 * 钉死。线程安全：`onError` 当前在主线程，仍用锁兜住未来后台回调。
 */
internal object CoverFrameFailureMemo {
    private data class Entry(val atMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(
        COVER_FRAME_FAILURE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > COVER_FRAME_FAILURE_MAX_ENTRIES
    }

    @Synchronized
    fun hasFailed(uri: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[uri] ?: return false
        if (nowMs - entry.atMs <= COVER_FRAME_FAILURE_TTL_MS) return true
        entries.remove(uri)
        return false
    }

    @Synchronized
    fun markFailed(uri: String, nowMs: Long = System.currentTimeMillis()) {
        entries[uri] = Entry(nowMs)
    }

    @Synchronized
    fun sizeForTest(): Int = entries.size

    @Synchronized
    fun clearForTest() {
        entries.clear()
    }
}
