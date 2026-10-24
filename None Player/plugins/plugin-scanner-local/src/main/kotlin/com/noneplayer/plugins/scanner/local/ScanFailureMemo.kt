package com.noneplayer.plugins.scanner.local

/**
 * A25：辅助 I/O（缩略图生成 / AVI 时长读头）的失败短 TTL。
 *
 * 失败项不是"永久不可用"：文件可能还在写入、Provider 短暂不可用。用 5 分钟 TTL
 * 允许之后再试一次，但同一进程内的连续扫描不要每次都为同一项重复开流。
 * 进程重启后缓存自然清空——跨冷启的否定缓存需要落盘，暂不做。
 *
 * LRU 上限 256，避免大库里坏文件把内存拖成无界。
 */
internal class ScanFailureMemo(
    private val ttlMs: Long = 5 * 60_000L,
    private val maxEntries: Int = 256,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val failedAt = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > maxEntries
    }

    fun hasFailed(key: String, now: Long = nowMs()): Boolean = synchronized(failedAt) {
        val at = failedAt[key] ?: return false
        if (now - at <= ttlMs) return true
        failedAt.remove(key)
        false
    }

    fun markFailed(key: String, now: Long = nowMs()) {
        synchronized(failedAt) { failedAt[key] = now }
    }

    fun markOk(key: String) {
        synchronized(failedAt) { failedAt.remove(key) }
    }

    /** 用户显式「清除缓存」：进程内短 TTL 结论也要失效，否则清完 5 分钟内仍不重试。 */
    fun clear() {
        synchronized(failedAt) { failedAt.clear() }
    }

    fun sizeForTest(): Int = synchronized(failedAt) { failedAt.size }
}
