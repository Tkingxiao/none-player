package com.noneplayer.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "此刻正在解码视频"的进程级快照（§9.4 播放中降档的输入）。
 *
 * 为什么要有这一份：降档的落点在 `:core:ui` 的主题层（`NonePlayerTheme` 里算
 * `生效档 = lowerOf(用户档, 设备档)` 再 `forPlayback()`），而知道"在不在解码"的
 * 只有播放会话——它在 `plugin-player-ui` 内部，`:app` 按依赖铁律够不着。
 * 与 [ProbeMemo] 同一个桥接理由：**写方在插件、读方在宿主**，共享点放在纯 Kotlin 的
 * `:core:media`。
 *
 * 只回答"有没有在解码"，不回答"在放哪一部"——后者一旦进来，这个标志就会随进度
 * 每 250ms 变一次，主题层读到它就是一次全应用重组，那是比降档想省的帧更大的开销。
 */
object NpDecoding {

    private val _decoding = MutableStateFlow(false)

    /** 主题层订阅；`true` = 视频正在解码，装饰类动画临时降一档 */
    val decoding: StateFlow<Boolean> = _decoding.asStateFlow()

    /** 写方：`PlaybackSession`（唯一的会话真相），幂等写同值不产生新值 */
    fun set(active: Boolean) {
        _decoding.value = active
    }
}
