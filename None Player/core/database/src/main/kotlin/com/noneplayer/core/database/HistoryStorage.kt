package com.noneplayer.core.database

import android.database.sqlite.SQLiteException
import java.io.IOException

/**
 * R2-1/REV-06：持久化层当前是否健康——供设置页副题、日志与验收测试观察。
 *
 * 语义：
 * - [healthy] = true：最近一次读写**成功**（[failures] 归零）；
 * - [healthy] = false：最近一次操作失败，[operation] 是操作名（"recent"/"record"…），
 *   [message] 是异常消息，[failures] 是**连续**失败次数；
 * - 失败期间读流会按 [HistoryRetryPolicy] 自动重试，成功后回到 healthy，
 *   状态变化本身通过 `HistoryRepository.storageStatus` 的 StateFlow 发出。
 */
data class HistoryStorageStatus(
    val healthy: Boolean = true,
    val operation: String? = null,
    val message: String? = null,
    val failures: Int = 0,
)

/**
 * R2-1/REV-06：读流重试策略。
 *
 * [retryDelaysMs] 是**第 n 次重试前的等待**：默认 1s/2s/5s/10s（有界，最多 4 次），
 * 用完之后再失败就当作不可恢复（结构性）错误，记录状态并结束该流——保留 stateIn
 * 上一次的可信值，而不是把失败 emit 成空列表（那会触发"空但完整"的误判，
 * 让孤儿清理/迁移把用户数据清掉）。
 *
 * 生产用默认值；单测注入更短的序列（虚拟时间下瞬时跑完）。
 */
data class HistoryRetryPolicy(
    val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    init {
        require(retryDelaysMs.all { it >= 0L }) { "重试延迟不能为负：$retryDelaysMs" }
    }

    companion object {
        /** 有界退避：1s → 2s → 5s → 10s；用完仍失败即放弃该流 */
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L)
    }
}

/**
 * 区分「可重试的 IO 故障」与「结构性错误」。
 *
 * - [IOException]：磁盘/游标层面的暂时性 IO 失败（Room 包装过的 `SQLiteException`
 *   有时也以它出现）→ 退避重试；
 * - [SQLiteException]：锁竞争（`SQLiteDatabaseLockedException`）这类暂时性 DB 错误 →
 *   退避重试；磁盘满等永久错误重试也会在有限次数后放弃，不会无限循环；
 * - 其余（`IllegalStateException`、schema 损坏、逻辑错误…）→ 重试无意义，直接结束流。
 */
internal fun isRetryableStorageFailure(error: Throwable): Boolean =
    error is IOException || error is SQLiteException
