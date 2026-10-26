package com.noneplayer.plugins.player.exo

/**
 * E245：软解渲染链的分段耗时（纯 Kotlin，JVM 可测）。
 *
 * 为什么要分段：帧计数只说"掉了几帧"，掉在哪一段要看得见——JNI 送包（send）、
 * 原生命令排空与解码等待（recv）、YUV 拷进 media3 输出缓冲（copy）、上屏（render）。
 * 只测总耗时会把这些混成一锅，也解释不了"队列贴上限"到底是解码慢还是拷贝慢。
 *
 * 全部为累计纳秒；摘要时换算 ms 并给每帧均值——同一段夹具的两轮 A/B 直接对
 * 这两个数就行。计时窗口只包住被测调用本身，不包日志与判空。
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

    /** 每轮只在第一次送包时定起点，换集重 build 解码器时随 reset 一起清。 */
    @Synchronized
    fun onFirstInput(nowNs: Long) {
        if (startedAtNs < 0) startedAtNs = nowNs
    }

    @Synchronized
    fun onSend(costNs: Long) {
        sendNs += costNs
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
    }

    /** 一行摘要，字段顺序固定（两轮 A/B 直接 diff）；ms 保留一位小数，首帧取整。 */
    fun summaryLine(): String {
        val s = snapshot()
        val per = if (s.frames > 0) s.frames.toDouble() else 1.0
        fun avg(us: Long) = String.format(java.util.Locale.US, "%.2f", us / 1_000.0 / per)
        return "renderer=" + s.renderer +
            " n=" + s.frames +
            " send=" + avg(s.sendUs) + "ms" +
            " recv=" + avg(s.recvUs) + "ms" +
            " copy=" + avg(s.copyUs) + "ms" +
            " rend=" + avg(s.renderUs) + "ms" +
            (if (s.firstFrameUs >= 0) " first=" + s.firstFrameUs / 1_000 + "ms" else " first=-")
    }
}
