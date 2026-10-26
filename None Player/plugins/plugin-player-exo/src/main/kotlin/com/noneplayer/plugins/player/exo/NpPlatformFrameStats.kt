package com.noneplayer.plugins.player.exo

/**
 * E245：**平台 / NextLib 路径**的帧计数（纯 Kotlin，JVM 可测）。
 *
 * 为什么要另一份：`LegacyVideoRenderer` 的两条软解后端由我们自己的解码器循环驱动，
 * 帧数能直接数；平台 `MediaCodecVideoRenderer` 与 NextLib `FfmpegVideoRenderer` 的帧循环
 * 在库里，只能靠 media3 的分析回调拿——两边的口径必须分开记，否则 A/B 时会把
 * "库上报的口径"和"我们自己数的口径"混在同一行里比。
 *
 * 数据来源（见 [NpPlatformAnalytics]）：
 * - `onVideoEnabled` 开一轮（新解码器会话），连播切集另由 `onMediaItemTransition` 划界；
 * - 帧数/入队/上屏/跳过/丢弃来自 **1s 轮询** `ExoPlayer.videoDecoderCounters`（做切集基线差）；
 * - 首帧延迟在"上屏数首次大于 0"的轮询点记（±1s 粒度，回调在这台机器上不可靠）；
 * - `onVideoDisabled` 用 DecoderCounters 的权威值收尾并复位。
 *
 * 平台侧没有"解出帧数"：media3 1.11 的 DecoderCounters 只有入队/上屏/跳过/丢弃四项。
 */
internal class NpPlatformFrameStats {

    data class Snapshot(
        val decoder: String,
        val frames: Long,
        val dropped: Long,
        val firstFrameMs: Long,
        val inputs: Long,
        val rendered: Long,
        val skipped: Long,
    )

    private var decoder = "-"
    private var startedMs = -1L
    private var frames = 0L
    private var dropped = 0L
    private var firstFrameMs = -1L
    private var inputs = 0L
    private var rendered = 0L
    private var skipped = 0L

    /** 新一轮解码器会话：上一轮的读数不能带进来（换集/换轨都走这里）。 */
    @Synchronized
    fun onEnabled(nowMs: Long) {
        reset()
        startedMs = nowMs
    }

    @Synchronized
    fun onDecoder(name: String) {
        if (name.isNotBlank()) decoder = name
    }

    @Synchronized
    fun onFrames(count: Int) {
        if (count > 0) frames += count
    }

    @Synchronized
    fun onDropped(count: Int) {
        if (count > 0) dropped += count
    }

    /** 首帧只认第一次：之后的上屏不改写延迟读数。 */
    @Synchronized
    fun onFirstFrame(renderTimeMs: Long) {
        if (firstFrameMs >= 0 || startedMs < 0 || renderTimeMs < startedMs) return
        firstFrameMs = renderTimeMs - startedMs
    }

    /**
     * 轮询/收尾共用的计数入口：DecoderCounters 是库自己的权威累计值
     * （入队输入/上屏/丢弃/跳过，已减掉切集基线），直接覆盖本轮读数。
     * 注意 media3 1.11 的 DecoderCounters **没有**"解出帧数"字段（只有入队/上屏/跳过），
     * 平台路径拿不到 decoded 计数，就不编造一个。
     * [nowMs] 只用于首帧：上屏数首次大于 0 的那次轮询即"首帧已出"。
     */
    @Synchronized
    fun onCounters(inputs: Long, rendered: Long, dropped: Long, skipped: Long, nowMs: Long = -1) {
        this.inputs = inputs
        this.rendered = rendered
        this.dropped = dropped
        this.skipped = skipped
        if (rendered > frames) frames = rendered
        if (nowMs >= 0 && firstFrameMs < 0 && rendered > 0 && startedMs >= 0) {
            firstFrameMs = (nowMs - startedMs).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        decoder = decoder,
        frames = frames,
        dropped = dropped,
        firstFrameMs = firstFrameMs,
        inputs = inputs,
        rendered = rendered,
        skipped = skipped,
    )

    @Synchronized
    fun reset() {
        // 解码器名不清：连播切集时渲染器复用、不会重新报名字，清掉只会让读数变 `Platform:-`
        startedMs = -1L
        frames = 0L
        dropped = 0L
        firstFrameMs = -1L
        inputs = 0L
        rendered = 0L
        skipped = 0L
    }

    val isEmpty: Boolean
        get() = frames == 0L && dropped == 0L && rendered == 0L && inputs == 0L

    /** 一行摘要，字段顺序固定（与软解 `frames …` 行分开记，避免口径混淆）。 */
    fun summaryLine(): String {
        val s = snapshot()
        return "renderer=Platform:" + s.decoder +
            " n=" + s.frames +
            " drop=" + s.dropped +
            " in=" + s.inputs +
            " rend=" + s.rendered +
            " skip=" + s.skipped +
            (if (s.firstFrameMs >= 0) " first=" + s.firstFrameMs + "ms" else " first=-")
    }
}
