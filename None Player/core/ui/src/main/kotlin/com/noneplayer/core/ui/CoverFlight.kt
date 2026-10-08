package com.noneplayer.core.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect

/**
 * M2「卡片 → 播放页」共享封面的**一次性交接**（2026-10-08 落地）。
 *
 * 为什么不用 Compose 官方的 `SharedTransitionLayout`：它的导航集成要求
 * navigation-compose 更晚的版本（本工程 2.8.5 的 AAR 里连 `SharedTransitionScope` /
 * `LocalNavAnimatedContentScope` 符号都没有，实测解包确认），升导航属"依赖受控升级"
 * 的另一个回合。这里用最轻的手绘版：卡片在被点击时记下**封面在窗口里的机位**与媒体 id，
 * 播放页落地时取走，把封面从那个机位补间到自己身上（`Shared=300` + `Emphasized`）。
 *
 * 只在档位允许时写入（§9.6 `sharedElement` 且非 Reduce Motion）；超时没被取走即作废——
 * 用户点了卡片又去干别的、过一会儿才进播放页时，不该突然飞出一张封面。
 */
object NpCoverFlight {

    /** 起飞后多久没被取走就作废（导航转场入场的量级 + 余量） */
    private const val TTL_MS = 1_500L

    class Takeoff(val mediaId: String, val from: Rect)

    @Volatile
    private var pending: Pair<Takeoff, Long>? = null

    /** 卡片侧：记下机位（[from] 为窗口坐标；没量到就当作没发生）。 */
    fun launch(mediaId: String, from: Rect) {
        pending = if (mediaId.isEmpty() || from.width <= 1f || from.height <= 1f) null
        else Takeoff(mediaId, from) to SystemClock.uptimeMillis()
    }

    /** 目标页取走（id 对得上且在 TTL 内）；无论成不成都会清空，不给第二次机会。 */
    fun take(mediaId: String): Takeoff? {
        val entry = pending ?: return null
        pending = null
        val (takeoff, at) = entry
        return takeoff.takeIf {
            it.mediaId == mediaId && SystemClock.uptimeMillis() - at <= TTL_MS
        }
    }

    /** 用户中途取消（如返回），作废当前交接 */
    fun cancel() {
        pending = null
    }
}
