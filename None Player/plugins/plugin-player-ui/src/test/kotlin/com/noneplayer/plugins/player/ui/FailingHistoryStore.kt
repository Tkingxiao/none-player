package com.noneplayer.plugins.player.ui

import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.PlaybackHistoryStore
import com.noneplayer.core.media.MediaItem
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** 一次 `record` 调用的参数快照（失败调用也记录，便于断言"失败不排队、不重复写错键"）。 */
internal data class RecordCall(
    val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val countPlay: Boolean,
)

/**
 * R2 存储降级替身：实现 [PlaybackHistoryStore]，用失败开关注入 IO 故障。
 *
 * 失败语义**刻意分成两种**，对应两条不同的验收面：
 *
 * 1. **写失败抛 [IOException]**（`failWrites = true`）：这不是接口契约允许的行为
 *    （契约要求 `record`/`markPlayed` 返回 false、不抛），而是故意模拟"故障从存储层
 *    深处逃逸"。用来验收会话的异步落盘任务必须被隔离（`PlaybackSession` 的 scope 带
 *    `CoroutineExceptionHandler`），不能击穿 `persistPosition`/`open` 的调用方。
 *
 * 2. **读失败返回 null**（`failReads = true`）：接口契约明确"读失败返回 null、不抛"。
 *    `ensureSessionPlaying` 据此把断点当成 0、从头顶起播——抛异常反而会违反契约，
 *    也就测不到"断点降级为 0"这条验收点。
 *
 * 内存 [breakpoints] 代替 Room 的历史表；只有**成功**写入才会出现在里面。
 */
internal class FailingHistoryStore : PlaybackHistoryStore {

    private val lock = Any()

    /** 成功落盘的历史表：mediaKey → positionMs（断点语义，与 HistoryEntry.keyOf 同键） */
    private val breakpoints = linkedMapOf<String, Long>()

    private val recordCalls = mutableListOf<RecordCall>()

    private val recordAttemptCounter = AtomicInteger()
    private val markPlayedAttemptCounter = AtomicInteger()
    private val readCallCounter = AtomicInteger()

    /** 写失败：record / markPlayed 抛 [failure]（模拟故障逃逸，验收会话侧隔离） */
    @Volatile
    var failWrites: Boolean = false

    /** 读失败：positionOf* 返回 null（接口契约内的失败表达，验收断点降级为 0） */
    @Volatile
    var failReads: Boolean = false

    @Volatile
    var failure: Throwable = IOException("模拟：历史存储 IO 失败")

    /** `record` 被调用次数（含失败），用于断言"失败没有排队/重放" */
    val recordAttempts: Int get() = recordAttemptCounter.get()

    /** `markPlayed` 被调用次数（含失败） */
    val markPlayedAttempts: Int get() = markPlayedAttemptCounter.get()

    /** 读断点被调用次数（含失败） */
    val readCalls: Int get() = readCallCounter.get()

    fun breakpointOf(mediaKey: String): Long? = synchronized(lock) { breakpoints[mediaKey] }

    fun breakpointsSnapshot(): Map<String, Long> = synchronized(lock) { breakpoints.toMap() }

    val recordCallList: List<RecordCall> get() = synchronized(lock) { recordCalls.toList() }

    /** 预置一条断点（模拟"库里本来有进度"） */
    fun seedBreakpoint(item: MediaItem, positionMs: Long) {
        synchronized(lock) { breakpoints[HistoryEntry.keyOf(item)] = positionMs }
    }

    override suspend fun record(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long,
        countPlay: Boolean,
    ): Boolean {
        recordAttemptCounter.incrementAndGet()
        synchronized(lock) {
            recordCalls += RecordCall(HistoryEntry.keyOf(item), positionMs, durationMs, countPlay)
        }
        if (failWrites) throw failure
        synchronized(lock) { breakpoints[HistoryEntry.keyOf(item)] = positionMs }
        return true
    }

    override suspend fun markPlayed(item: MediaItem): Boolean {
        markPlayedAttemptCounter.incrementAndGet()
        if (failWrites) throw failure
        return true
    }

    override suspend fun positionOfIfIntact(item: MediaItem): Long? {
        readCallCounter.incrementAndGet()
        if (failReads) return null
        return synchronized(lock) { breakpoints[HistoryEntry.keyOf(item)] }
    }

    override suspend fun positionOf(mediaKey: String): Long? {
        readCallCounter.incrementAndGet()
        if (failReads) return null
        return synchronized(lock) { breakpoints[mediaKey] }
    }
}
