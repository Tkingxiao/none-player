package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 扫描提交代际门（R0/REV-03 从 [LocalMediaSource] 提取）。
 *
 * 解决的问题：一次全量扫描跑在 `Dispatchers.IO` 上、耗时可达分钟级；期间用户移除
 * 扫描目录（或目录集变化触发的下一次扫描）已经让**这次扫描的结果作废**，但它仍会
 * 在收尾时把陈旧列表发布给 UI、写回快照、甚至清掉刚被移除目录的历史——即"旧结果
 * 回灌"。旧实现只有扫描互斥闸门（防止两个扫描同时跑），没有"提交时点是否仍然有效"
 * 的判据。
 *
 * 语义：
 *  - [currentGeneration]：当前代际。扫描开始时记下它。
 *  - [invalidate]：目录集/授权变化时调用，代际自增并返回新值。
 *  - [commitIfCurrent]：在**同一把 Mutex 的串行段内**复核代际：仍等于传入值才执行
 *    [block]（发布列表 / 清理历史 / 写快照），否则返回 `null` 且不执行。
 *
 * 为什么是 Mutex 而不是只靠 `@Volatile`：`invalidate` 与 `commitIfCurrent` 的
 * "复核 + 执行"必须是同一个不可分割的串行段。否则存在竞态：提交段复核通过后、
 * 真正发布前，另一个线程 `invalidate` 成功——陈旧结果照样回灌。把两者放进同一把锁，
 * 语义就变成"要么整个提交在失效之前完成，要么整个提交被作废"。
 *
 * 注意：`block` 在持锁状态下执行（这是"串行提交段"的本意），因此 `block` 内**不得**
 * 再次调用 [invalidate] / [commitIfCurrent]（[Mutex] 不可重入，会死锁）。
 */
internal class ScanCommitGate(initialGeneration: Long = 0L) {

    /** 串行提交段的互斥锁：invalidate 与 commitIfCurrent 的复核+执行共用同一把。 */
    private val mutex = Mutex()

    /** 当前代际；无锁快路径读取（扫描开始处取基线）用 volatile 保证可见性。 */
    @Volatile
    private var generation: Long = initialGeneration

    /** 当前代际（无锁快照；仅用于"记下起点"，是否仍有效由 [commitIfCurrent] 复核）。 */
    fun currentGeneration(): Long = generation

    /**
     * 作废所有在途扫描的提交资格：代际 +1 并返回新值。
     *
     * 与 [commitIfCurrent] 串行：若某个提交段正在执行，本调用会等它结束再递增，
     * 保证"失效之前的提交"完整生效、"失效之后"的提交全部作废，不存在半途失效。
     */
    suspend fun invalidate(): Long = mutex.withLock {
        generation += 1
        generation
    }

    /**
     * 代际仍为 [generation] 时执行 [block] 并返回其结果；否则返回 `null` 且**不执行** [block]。
     *
     * 复核与 [block] 都在同一 Mutex 内——[block] 执行期间任何 [invalidate] 都会排队，
     * 不会出现"复核通过后、发布前被作废"的窗口。
     */
    suspend fun <T> commitIfCurrent(generation: Long, block: suspend () -> T): T? = mutex.withLock {
        // 参数 generation 遮蔽了属性，这里显式取属性做复核
        if (this.generation != generation) return@withLock null
        block()
    }
}
