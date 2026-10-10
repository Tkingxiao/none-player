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
internal object NpPerf {
    private const val TAG = "NpPerf"

    /** -1 = 本轮未起表；未经 [begin] 的回调不该被算进任何一次切换 */
    private var t0 = -1L
    private val on get() = Log.isLoggable(TAG, Log.DEBUG)

    fun begin(label: String) {
        if (on) {
            t0 = SystemClock.elapsedRealtime()
            Log.d(TAG, "begin $label")
        }
    }

    fun mark(label: String) {
        val start = t0
        if (start < 0 || !on) return
        Log.d(TAG, "$label +${SystemClock.elapsedRealtime() - start}ms")
    }

    /** 首帧即终点：收表，之后的同类回调不再算数 */
    fun end(label: String) {
        mark(label)
        t0 = -1L
    }

    /** 会话侧独立记程：没有对应的 begin，只报绝对时刻供 logcat 时间戳对齐 */
    fun sessionMark(label: String) {
        if (on) Log.d(TAG, "session $label")
    }
}
