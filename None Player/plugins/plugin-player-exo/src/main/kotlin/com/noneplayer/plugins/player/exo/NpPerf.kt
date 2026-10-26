package com.noneplayer.plugins.player.exo

import android.os.SystemClock
import android.util.Log

/**
 * 切换起播链路打点——**默认静默**，`adb shell setprop log.tag.NpPerf DEBUG` 才输出。
 *
 * 存在理由：切集耗时的判定必须是"同条件 A/B"（《设计文档.md》§9.7），而从外部只看得见
 * 结果画面、看不见"探测多久、装载多久、什么时候出首帧"。这一串 `+Nms` 相对时距就是那把
 * 尺子，让它留在代码里（而不是临时加了又删）是为了下次复测还能拿到同一份口径。
 *
 * 两端都要打：装载链在 UI 侧（[ExoVideoPlayer]），首帧在会话侧（[PlaybackService] 里的
 * ExoPlayer）——media3 不把 `onRenderedFirstFrame` 转发给 `MediaController`，控制器侧
 * 只能看到 `STATE_READY`。两侧同进程，`elapsedRealtime` 可直接对齐（logcat 时间戳同源）。
 */
internal class NpPerfClock {
    private val tag = "NpPerf"

    /** -1 = 本轮未起表；未经 [begin] 的回调不该被算进任何一次切换 */
    @Volatile
    private var t0 = -1L
    private val on get() = Log.isLoggable(tag, Log.DEBUG)

    fun begin(label: String) {
        if (on) {
            t0 = SystemClock.elapsedRealtime()
            Log.d(tag, "begin $label")
        }
    }

    fun mark(label: String) {
        val start = t0
        if (start < 0 || !on) return
        Log.d(tag, "$label +${SystemClock.elapsedRealtime() - start}ms")
    }

    /** 首帧即终点：收表，之后的同类回调不再算数 */
    fun end(label: String) {
        mark(label)
        t0 = -1L
    }
}

/** 会话侧独立记程：没有对应的 begin，只报绝对时刻供 logcat 时间戳对齐。 */
internal object NpPerf {
    private const val TAG = "NpPerf"

    /** E245：帧摘要限频；逐帧打日志会反过来改变被测时序。 */
    private const val FRAME_SUMMARY_INTERVAL_MS = 2_000L

    private var lastFrameSummaryAtMs = 0L
    private var lastFrameSummaryLine: String? = null

    private val on get() = Log.isLoggable(TAG, Log.DEBUG)

    fun sessionMark(label: String) {
        if (on) Log.d(TAG, "session $label")
    }

    /**
     * E245：软解渲染链帧摘要（输入/解出/上屏/丢弃/队列深度）。
     *
     * [force] 用于 disable/release——收尾必须落一条最终读数，否则片尾那几帧的
     * 丢弃与最深积压会丢；非强制调用按 2s 限频且同一行不重复打。
     */
    fun frameSummary(stats: NpFrameStats, force: Boolean = false) {
        if (!on) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFrameSummaryAtMs < FRAME_SUMMARY_INTERVAL_MS) return
        val line = stats.summaryLine()
        if (!force && line == lastFrameSummaryLine) return
        lastFrameSummaryAtMs = now
        lastFrameSummaryLine = line
        Log.d(TAG, "frames $line")
    }
}
