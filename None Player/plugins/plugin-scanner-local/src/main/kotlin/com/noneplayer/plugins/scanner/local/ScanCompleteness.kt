package com.noneplayer.plugins.scanner.local

/**
 * 一次扫描的「完整性覆盖」纯模型（R0 / REV-01）。
 *
 * 背景：扫描的多个子查询（MediaStore 视频/音频、无扩展名文件、SAF 隐藏目录遍历）
 * 都可能失败、被系统拒绝、或因为预算耗尽而提前返回。旧实现把这些情况当成
 * "目录里确实没有这些项"，随后 [com.noneplayer.core.database.HistoryRepository.pruneOrphans]
 * 会把对应的播放历史/收藏真正删掉——一次瞬时故障就能清空用户数据。
 *
 * 修法是把"是否完整"从各分支里显式收集出来：
 * - 每个分支失败/降级时通过 [ScanCoverageRecorder.warn] 记一条人话警告；
 * - 只有 [ScanCoverage.isComplete] 为真（没有任何警告）时，扫描才允许执行
 *   "删除类"提交：清理孤儿历史/收藏、删除缩略图、替换旧快照；
 * - 不完整时一律保留旧数据，并在扫描摘要里带上"本次扫描不完整"。
 *
 * 纯 Kotlin 数据类，不碰 Android API，可 JVM 单测。
 */
internal data class ScanCoverage(
    /** 导致本次扫描不完整的原因（中文短句，可直接拼进扫描摘要）。空表 = 完整。 */
    val warnings: List<String> = emptyList(),
) {
    /** 是否完整：只有一条警告都没有时才算完整。 */
    val isComplete: Boolean get() = warnings.isEmpty()

    /** 追加一条警告（空白消息忽略，保持不可变语义）。 */
    fun withWarning(message: String): ScanCoverage =
        if (message.isBlank()) this else ScanCoverage(warnings + message)
}

/**
 * 扫描过程中的警告收集器：各子查询把失败/降级原因丢进来，提交前用 [build] 取结果。
 *
 * 重复警告只保留一条（同一故障在多卷/多 scope 下可能被上报多次，摘要里重复没有意义）。
 * 非线程安全——扫描的收集阶段是单协程串行的。
 */
internal class ScanCoverageRecorder {
    private val warnings = mutableListOf<String>()

    /** 记录一条警告（空白消息忽略）。 */
    fun warn(message: String) {
        if (message.isNotBlank()) warnings += message
    }

    /** 合并一份已有的覆盖结果（例如子查询返回的 [ScanCoverage]）。 */
    fun record(coverage: ScanCoverage) {
        warnings += coverage.warnings
    }

    /** 收口：去重后的不可变快照。 */
    fun build(): ScanCoverage = ScanCoverage(warnings.distinct())
}
