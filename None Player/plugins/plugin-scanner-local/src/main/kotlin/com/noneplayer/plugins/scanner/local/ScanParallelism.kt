package com.noneplayer.plugins.scanner.local

/**
 * 扫描并发预算：**主动探测宿主机核数**再分车道，而不是写死常数。
 *
 * 两类工作负载的甜点区不同：
 * - [ioWorkers]：SAF 目录查询、`readHead` 头部嗅探这类**阻塞 IPC/IO**，单次调用大部分时间
 *   在等 binder 回包，线程可以远多于核数；但放太多会让 MediaProvider 侧排队反而变慢，
 *   因此按 2×核数并钳在 [4, 12]。
 * - [decodeWorkers]：MediaMetadataRetriever 取封面/时长这类 **CPU + 解码器 + IO 混合**，
 *   并发越高争用解码器越严重，按 半核数 且钳在 [2, 6]（8 核机型给 4，与历史硬编码
 *   `THUMB_PARALLELISM = 4` 持平，不倒退）。
 *
 * 核数取自 [Runtime.getRuntime] 的 availableProcessors（受 cgroup/绑核影响，正是我们要的
 * "设备实际能用几个核"）；参数可注入，便于单测覆盖 1/2/64 核边界。
 */
internal object ScanParallelism {

    /** 阻塞 IPC/IO 车道数（目录查询、头部读取）。 */
    fun ioWorkers(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        (cores.coerceAtLeast(1) * 2).coerceIn(MIN_IO, MAX_IO)

    /** CPU/解码混合车道数（封面、时长）。 */
    fun decodeWorkers(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        (cores.coerceAtLeast(1) / 2).coerceIn(MIN_DECODE, MAX_DECODE)

    /** 一行日志，供真机 logcat 取证「按核数分配」。 */
    fun describe(cores: Int = Runtime.getRuntime().availableProcessors()): String =
        "cores=$cores io=${ioWorkers(cores)} decode=${decodeWorkers(cores)}"

    private const val MIN_IO = 4
    private const val MAX_IO = 12
    private const val MIN_DECODE = 2
    private const val MAX_DECODE = 6
}
