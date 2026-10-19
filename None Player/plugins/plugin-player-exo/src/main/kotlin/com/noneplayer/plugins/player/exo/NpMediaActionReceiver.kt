package com.noneplayer.plugins.player.exo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noneplayer.kernel.api.NpMediaButtonAction

/**
 * E144-1：PiP 动作按钮（暂停 / 下一集）的广播入口。
 *
 * 旧通路是 `PendingIntent.getBroadcast` 指向 media3 的 `MediaButtonReceiver`（A2 的
 * 耳机/蓝牙媒体键入口）。真机取证发现该接收器在 API 26+ 会**丢弃所有非 PLAY 键**：
 *
 * ```
 * W MediaButtonReceiver: Ignore key event that is not a 'play' command on API 26 or
 *   above to avoid an 'ForegroundServiceDidNotStartInTimeException'
 * ```
 *
 * 于是小窗上的 ⏸ / ⏭ 点下去静默无操作（▶ 反而能用，因为那是 PLAY 键）。系统媒体栈
 * 这么做的理由是"非播放键不该冷启动前台服务"；小窗按钮出现时服务必然活着，这条
 * 保护对本场景没有意义，所以这里换一条**不经过系统媒体栈**的同进程通路：
 *
 * 广播 → 本接收器 → [NpMediaActionBridge] → 当前引擎实例的 [androidx.media3.session.MediaController]。
 *
 * 接收器 `exported=false`：PendingIntent 的发送方（SystemUI 的小窗按钮）借用本应用的
 * 身份执行，不需要导出。
 */
class NpMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NP_MEDIA_BUTTON) return
        val name = intent.getStringExtra(EXTRA_ACTION) ?: return
        val action = runCatching { NpMediaButtonAction.valueOf(name) }.getOrNull() ?: return
        NpMediaActionBridge.dispatch(action)
    }

    companion object {
        /** 本应用私有 action（不参与系统 `ACTION_MEDIA_BUTTON` 分发） */
        const val ACTION_NP_MEDIA_BUTTON = "com.noneplayer.app.action.NP_MEDIA_BUTTON"

        /** 附加的 [NpMediaButtonAction] 名称 */
        const val EXTRA_ACTION = "com.noneplayer.app.extra.NP_MEDIA_ACTION"
    }
}

/**
 * 接收器 ↔ 引擎实例的同进程桥。
 *
 * 接收器由系统按需实例化，拿不到当前页面持有的引擎引用；引擎在控制器连接建立时
 * 登记自己的分发器，`release()` 时按 owner 校验后摘除（避免旧实例把新实例的分发器
 * 清掉）。
 */
internal object NpMediaActionBridge {
    @Volatile
    private var owner: Any? = null

    @Volatile
    private var dispatcher: ((NpMediaButtonAction) -> Unit)? = null

    fun register(owner: Any, dispatch: (NpMediaButtonAction) -> Unit) {
        this.owner = owner
        this.dispatcher = dispatch
    }

    fun unregister(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            this.dispatcher = null
        }
    }

    fun dispatch(action: NpMediaButtonAction) {
        dispatcher?.invoke(action)
    }
}
