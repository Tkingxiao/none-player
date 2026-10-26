package com.noneplayer.plugins.player.exo

/**
 * E245：软解渲染链的帧级计数（纯 Kotlin，JVM 可测）。
 *
 * 存在理由：掉帧/背压只能从"输入多少、解出多少、上屏多少、排队多深"这一组数看出来；
 * 如果只在出问题时临时加 logcat，读数和口径每次都变。这里把口径固定成一份快照，
 * 由 [NpPerf] 限频输出摘要（禁止逐帧打日志——逐帧 I/O 会反过来改变被测性能）。
 *
 * 所有方法都在播放线程调用，但仍加锁：release/disable 可能来自主线程，
 * 摘要会把两个线程的计数读串。
 */
internal class NpFrameStats(val renderer: String) {

    data class Snapshot(
        val renderer: String,
        val inputFrames: Long,
        val decodedFrames: Long,
        val renderedFrames: Long,
        val droppedFrames: Long,
        val nativeErrors: Long,
        val queueDepthLast: Int,
        val queueDepthMax: Int,
        val queueDepthAvg: Double,
    ) {
        val isEmpty: Boolean
            get() = inputFrames == 0L && decodedFrames == 0L && renderedFrames == 0L && nativeErrors == 0L
    }

    private var inputFrames = 0L
    private var decodedFrames = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var nativeErrors = 0L
    private var queueDepthLast = 0
    private var queueDepthMax = 0
    private var queueDepthSum = 0L
    private var queueDepthSamples = 0L

    @Synchronized
    fun onInput() {
        inputFrames++
    }

    /** [queueDepth] = 解出这一帧后 readyOut 的积压深度（背压的唯一直接读数）。 */
    @Synchronized
    fun onDecoded(queueDepth: Int) {
        decodedFrames++
        queueDepthLast = queueDepth
        queueDepthSum += queueDepth
        queueDepthSamples++
        if (queueDepth > queueDepthMax) queueDepthMax = queueDepth
    }

    /** [dropped] 取 media3 输出缓冲的 skippedOutputBufferCount（解码器主动丢的帧）。 */
    @Synchronized
    fun onRendered(dropped: Int = 0) {
        renderedFrames++
        if (dropped > 0) droppedFrames += dropped
    }

    @Synchronized
    fun onNativeError() {
        nativeErrors++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        renderer = renderer,
        inputFrames = inputFrames,
        decodedFrames = decodedFrames,
        renderedFrames = renderedFrames,
        droppedFrames = droppedFrames,
        nativeErrors = nativeErrors,
        queueDepthLast = queueDepthLast,
        queueDepthMax = queueDepthMax,
        queueDepthAvg = if (queueDepthSamples == 0L) 0.0 else queueDepthSum.toDouble() / queueDepthSamples,
    )

    @Synchronized
    fun reset() {
        inputFrames = 0L
        decodedFrames = 0L
        renderedFrames = 0L
        droppedFrames = 0L
        nativeErrors = 0L
        queueDepthLast = 0
        queueDepthMax = 0
        queueDepthSum = 0L
        queueDepthSamples = 0L
    }

    /** 一行摘要，字段顺序固定，便于 logcat 里直接 diff 两轮 A/B。 */
    fun summaryLine(): String {
        val s = snapshot()
        return "renderer=" + s.renderer +
            " in=" + s.inputFrames +
            " dec=" + s.decodedFrames +
            " rend=" + s.renderedFrames +
            " drop=" + s.droppedFrames +
            " qavg=" + String.format(java.util.Locale.US, "%.2f", s.queueDepthAvg) +
            " qmax=" + s.queueDepthMax +
            " err=" + s.nativeErrors
    }
}
