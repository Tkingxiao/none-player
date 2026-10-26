package com.noneplayer.plugins.player.exo

/**
 * E245：软解渲染链的分段耗时（纯 Kotlin，JVM 可测）。
 *
 * 为什么要分段：帧计数只说"掉了几帧"，掉在哪一段要看得见——JNI 送包（send）、
 * 原生命令排空与解码等待（recv）、YUV 拷进 media3 输出缓冲（copy）、上屏（render）。
 * 只测总耗时会把这些混成一锅，也解释不了"队列贴上限"到底是解码慢还是拷贝慢。
 *
 * 全部为累计纳秒；摘要时换算 ms 并给每帧均值——同一段夹具的两轮 A/B 直接对
 * 这几个数就行。计时窗口只包住被测调用本身，不包日志与判空。
 *
 * 平均值会被长尾抹平（偶发一次 40ms 卡顿 vs 稳定 2ms，均值可能一样），所以
 * send/render 两段另记直方图给 p50/p95：看"稳态"用均值，看"卡顿率"用 p95。
 * 桶宽 0.25ms、上限 64ms，超档样本并入末档并按 64ms 上报（宁可低估尾长，
 * 不伪造精确值）。
 */
internal class NpStageTiming(val renderer: String) {

    data class Snapshot(
        val renderer: String,
        val frames: Long,
        val sendUs: Long,
        val recvUs: Long,
        val copyUs: Long,
        val renderUs: Long,
        val firstFrameUs: Long,
        val renderP50Us: Long,
        val renderP95Us: Long,
        val sendP95Us: Long,
    ) {
        val isEmpty: Boolean
            get() = frames == 0L && sendUs == 0L && recvUs == 0L && copyUs == 0L && renderUs == 0L
    }

    private var frames = 0L
    private var sendNs = 0L
    private var recvNs = 0L
    private var copyNs = 0L
    private var renderNs = 0L
    /** -1 = 还没上过屏；首帧延迟 = 第一次送包到第一次上屏之间的墙钟 */
    private var firstFrameNs = -1L
    /** -1 = 还没送过包 */
    private var startedAtNs = -1L

    private val sendHist = Hist()
    private val renderHist = Hist()

    /**
     * E245：0.25ms 一档、0–64ms 的直方图。
     *
     * 只存计数（IntArray），每帧零分配——测量点不能自己成为掉帧源。
     * 分位取"所在桶的上界"，误差 ≤ 桶宽；落 overflow 的样本报档上限。
     */
    private class Hist {
        private val buckets = IntArray(BUCKETS)
        private var count = 0

        fun record(costNs: Long) {
            count++
            val idx = (costNs / BUCKET_NS).toInt()
            if (idx >= BUCKETS) buckets[BUCKETS - 1]++ else buckets[idx]++
        }

        /** 返回分位对应的桶上界（微秒）；没有样本返回 -1。 */
        fun percentile(p: Double): Long {
            if (count == 0) return -1
            val target = kotlin.math.ceil(p * count).toInt().coerceIn(1, count)
            var seen = 0
            for (i in buckets.indices) {
                seen += buckets[i]
                if (seen >= target) return ((i + 1) * BUCKET_NS) / 1_000
            }
            return (BUCKETS * BUCKET_NS) / 1_000
        }

        fun reset() {
            buckets.fill(0)
            count = 0
        }
    }

    private companion object {
        const val BUCKET_NS = 250_000L
        const val BUCKETS = 256
    }

    /** 每轮只在第一次送包时定起点，换集重 build 解码器时随 reset 一起清。 */
    @Synchronized
    fun onFirstInput(nowNs: Long) {
        if (startedAtNs < 0) startedAtNs = nowNs
    }

    @Synchronized
    fun onSend(costNs: Long) {
        sendNs += costNs
        sendHist.record(costNs)
        frames++
    }

    @Synchronized
    fun onRecv(costNs: Long) {
        recvNs += costNs
    }

    @Synchronized
    fun onCopy(costNs: Long) {
        copyNs += costNs
    }

    /** 首次上屏时把"送首包 → 上首屏"落成 [Snapshot.firstFrameUs]，之后不再改。 */
    @Synchronized
    fun onRender(nowNs: Long, costNs: Long) {
        renderNs += costNs
        renderHist.record(costNs)
        val start = startedAtNs
        if (firstFrameNs < 0 && start >= 0 && nowNs > start) firstFrameNs = nowNs - start
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        renderer = renderer,
        frames = frames,
        sendUs = sendNs / 1_000,
        recvUs = recvNs / 1_000,
        copyUs = copyNs / 1_000,
        renderUs = renderNs / 1_000,
        firstFrameUs = if (firstFrameNs < 0) -1 else firstFrameNs / 1_000,
        renderP50Us = renderHist.percentile(0.50),
        renderP95Us = renderHist.percentile(0.95),
        sendP95Us = sendHist.percentile(0.95),
    )

    @Synchronized
    fun reset() {
        frames = 0L
        sendNs = 0L
        recvNs = 0L
        copyNs = 0L
        renderNs = 0L
        firstFrameNs = -1L
        startedAtNs = -1L
        sendHist.reset()
        renderHist.reset()
    }

    /** 一行摘要，字段顺序固定（两轮 A/B 直接 diff）；ms 保留一位小数，首帧取整。 */
    fun summaryLine(): String {
        val s = snapshot()
        val per = if (s.frames > 0) s.frames.toDouble() else 1.0
        fun avg(us: Long) = String.format(java.util.Locale.US, "%.2f", us / 1_000.0 / per)
        fun pct(us: Long) = if (us < 0) "-" else String.format(java.util.Locale.US, "%.2f", us / 1_000.0) + "ms"
        return "renderer=" + s.renderer +
            " n=" + s.frames +
            " send=" + avg(s.sendUs) + "ms" +
            " recv=" + avg(s.recvUs) + "ms" +
            " copy=" + avg(s.copyUs) + "ms" +
            " rend=" + avg(s.renderUs) + "ms" +
            (if (s.firstFrameUs >= 0) " first=" + s.firstFrameUs / 1_000 + "ms" else " first=-") +
            " rend_p50=" + pct(s.renderP50Us) +
            " rend_p95=" + pct(s.renderP95Us) +
            " send_p95=" + pct(s.sendP95Us)
    }
}
