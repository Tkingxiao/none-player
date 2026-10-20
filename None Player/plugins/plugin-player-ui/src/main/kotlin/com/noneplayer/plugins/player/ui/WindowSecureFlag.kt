package com.noneplayer.plugins.player.ui

import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * 窗口级 `FLAG_SECURE` 的单一持有者（A14）。
 *
 * 播放页与内嵌详情页是两个独立的组合，各自都会按「禁止截屏」设置加/清这个 flag。
 * 旧实现里内嵌页 `onDispose` 无条件 `clearFlags`，会把播放页刚设上的禁止截屏清掉；
 * 这里是引用计数：只有最后一个持有者释放时才真正清除。
 *
 * 用 `WeakHashMap<Window, Int>` 而不是单个计数：Activity 换窗口/重建时，旧窗口的
 * 计数不会把新窗口的 flag 带错。
 */
internal object WindowSecureFlag {

    private val counts = WeakHashMap<Window, Int>()

    @Synchronized
    fun acquire(window: Window) {
        val next = (counts[window] ?: 0) + 1
        counts[window] = next
        if (next == 1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Synchronized
    fun release(window: Window) {
        val current = counts[window] ?: 0
        // 没持有过就不动窗口：调用方可能晚于另一处 owner 的清理，别越权清别人的 flag。
        if (current <= 0) return
        if (current == 1) {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[window] = current - 1
        }
    }
}
