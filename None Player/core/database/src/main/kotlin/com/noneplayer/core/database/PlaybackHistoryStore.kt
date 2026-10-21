package com.noneplayer.core.database

import com.noneplayer.core.media.MediaItem

/**
 * R2-1/REV-06：播放历史持久层的**写入契约**（会话侧只依赖这 5 个方法）。
 *
 * 存在的理由：`PlaybackSession`/`SessionBootstrap` 只需要"记进度 / 记播放 / 读断点"，
 * 不需要知道 Room、收藏、孤儿清理这些细节。抽成接口后，会话侧可以在测试里注入
 * 会失败的内存实现，验证「持久化 IO 失败时会话不崩、不谎报、断点当 0 处理」。
 *
 * **失败语义（与 [HistoryRepository] 一致）**：
 * - [record]/[persistProgress]/[markPlayed]：返回**写入是否成功**。失败**不抛异常**（调用方周期重试）；
 *   但协程取消（[kotlinx.coroutines.CancellationException]）必须原样重抛。
 * - [positionOfIfIntact]/[positionOf]：读取失败返回 `null`（调用方按"没有断点"处理），
 *   与"确实没有记录"同一个出口；取消同样原样重抛。
 */
interface PlaybackHistoryStore {

    /**
     * 记一次播放进度；[countPlay] = true 时播放次数 +1（进入某项播放时调用一次，
     * 周期/暂停落点传 false，避免播放次数被落点次数灌水）。
     *
     * @return true = 已确认落库，或按 E12 门槛**本就不该落库**；false = 写库失败
     */
    suspend fun record(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long = item.durationMs,
        countPlay: Boolean = false,
    ): Boolean

    /**
     * A24：周期落点。生产实现只更新 position/ratio/last_played_at；默认退化为
     * [record]，让测试替身与旧的存储实现不必跟着改。行不存在时由生产实现回退整行
     * upsert，语义仍与 [record] 一致。
     */
    suspend fun persistProgress(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long = item.durationMs,
    ): Boolean = record(item, positionMs, durationMs)

    /**
     * 记一次"开始播放"：`play_count + 1`、刷新 `last_played_at`。不受落盘门槛约束
     * （用户点开就算播过，哪怕只看 3 秒）。
     *
     * @return true = 已确认落库；false = 写库失败
     */
    suspend fun markPlayed(item: MediaItem): Boolean

    /**
     * 读断点（带文件指纹校验，A1-3）：失败或指纹不符都返回 `null`，
     * 调用方据此"静默从头播"。
     */
    suspend fun positionOfIfIntact(item: MediaItem): Long?

    /** 读位置（无指纹校验，展示/迁移用）；失败返回 `null` */
    suspend fun positionOf(mediaKey: String): Long?
}
