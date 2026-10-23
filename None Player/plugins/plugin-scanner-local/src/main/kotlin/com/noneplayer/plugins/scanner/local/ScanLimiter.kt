package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.channels.Channel

/**
 * 动态许可器：令牌桶用 [Channel] 实现，许可数可增可减且天然有上限。
 *
 * 为什么不用 [kotlinx.coroutines.sync.Semaphore]：它是**受跟踪**的许可器——只有自己
 * `acquire()` 过的数量才能 `release()`，凭空多 release 一个直接抛
 * `IllegalStateException: The number of released permits cannot be greater than N`。
 * 而自适应调参需要在运行中「凭空上调」许可（tuner 判定加档 → 直接多给几个），真机第一轮
 * 就撞上这条：`lane tune kind=COVER grow permits=5` 之后立刻 ISE，整轮扫描降级为失败。
 * 也不用 `java.util.concurrent.Semaphore`：它的 `acquire()` 阻塞**线程**，上千个等待项会把
 * `Dispatchers.IO` 的线程池吃光。
 *
 * 这里的令牌就是 channel 里的元素：`acquire()` 收一个、`release()` 投一个；channel 容量 = cap
 * 天然给出上限（满了 `trySend` 失败 = 不许再涨），等待是挂起而不是阻塞线程。
 *
 * [permits] 只用于日志：真实增减由 acquire/release 同步完成，调用方（tuner 回调）不要另算。
 */
internal class ScanLimiter(capacity: Int, initial: Int) {
    private val cap = capacity.coerceAtLeast(1)
    private val tokens = Channel<Unit>(cap)

    /** 当前可用许可数（仅日志口径；高并发下允许瞬时不准） */
    @Volatile
    var permits: Int = initial.coerceIn(1, cap)
        private set

    init {
        repeat(permits) { tokens.trySend(Unit) }
    }

    /** 取一个许可；没有就挂起等待（不阻塞线程）。 */
    suspend fun acquire() {
        tokens.receive()
        permits--
    }

    /**
     * 归还 / 新增一个许可：已到 cap 返回 false。
     *
     * 两种情况都走这里，这正是 kotlinx 的 Semaphore 做不到的部分：
     * ① 放行路径归还刚借出的那个；② tuner 上调时「凭空新增」一个。到 cap 后拒绝增长即可，
     * 调用方（tuner 回调）忽略返回值——tuner 已经按自己的 cap 钳过，撞上这里属正常。
     */
    fun release(): Boolean = if (permits >= cap) {
        false
    } else {
        tokens.trySend(Unit).isSuccess.also { if (it) permits++ }
    }

    /** 借一个许可执行 [block]：块内正常返回还是抛异常，许可都归还。 */
    suspend fun withPermit(block: suspend () -> Unit) {
        acquire()
        try {
            block()
        } finally {
            release()
        }
    }
}
