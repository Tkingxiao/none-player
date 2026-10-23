package com.noneplayer.plugins.scanner.local

/**
 * 按**权重（通常取文件字节数）**把一组工作切成若干条串行车道的 LPT（Longest Processing
 * Time first）贪心分配。
 *
 * 为什么不是简单的 `chunked(n)` 轮转：封面/时长这类开销近似正比于文件大小的任务，
 * 轮转分布会让某条车道摊到一个 4GB 的巨型文件而后半程只剩它一条在跑，其余车道空转收尾。
 * LPT 先放大件、再把小件填谷，能把总时长压到接近 `max(最大单项, 总负载/车道数)` 的理论下界。
 *
 * 纯函数、无协程，便于单测：同权重保持输入相对顺序，结果条数恒等于 `lanes`（可能为空表）。
 */
internal object WeightedPartition {

    /**
     * @param lanes 车道数，<= 0 抛 [IllegalArgumentException]
     * @return 恰好 `lanes` 个列表；每条车道内部的任务顺序为"大件在前"
     */
    fun <T> partition(items: List<T>, lanes: Int, weight: (T) -> Long): List<List<T>> {
        require(lanes > 0) { "lanes must be > 0, was $lanes" }
        if (items.isEmpty()) return List(lanes) { emptyList() }

        // 稳定降序：同权重保留输入顺序（sortedByDescending 是稳定排序，但它按 weight 变成 key，
        // index 递增即稳定；这里显式带上下标更稳妥）
        val ordered = items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<T>> { weight(it.value) }.thenBy { it.index })
            .map { it.value }

        val loads = LongArray(lanes)
        val buckets = List(lanes) { ArrayList<T>() }

        for (item in ordered) {
            // 车道数被钳在 12 以内，线性扫最小负载比维护优先队列更省也更确定
            // （同负载取下标小的，保证同输入必得同输出）
            var lane = 0
            for (i in 1 until lanes) if (loads[i] < loads[lane]) lane = i
            buckets[lane].add(item)
            loads[lane] += weight(item).coerceAtLeast(0L)
        }
        return buckets
    }

    /** 预估最重车道的负载，用于日志/测试断言（分得好应接近 `总负载/lanes` 上界）。 */
    fun <T> maxLaneLoad(items: List<T>, lanes: Int, weight: (T) -> Long): Long {
        require(lanes > 0) { "lanes must be > 0, was $lanes" }
        if (items.isEmpty()) return 0L
        return partition(items, lanes, weight).maxOf { lane -> lane.sumOf { weight(it).coerceAtLeast(0L) } }
    }
}
