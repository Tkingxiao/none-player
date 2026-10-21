package com.noneplayer.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A19：最近一次 CPU 渲染 Surface 克隆失败的排障摘要。
 *
 * 克隆失败会退回原 Surface（播放不停），但会重新引入“CPU 连接滞留”的风险；
 * 这是用户可观察的诊断，不是静默降级。写方在 plugin-player-exo，读方在设置页
 * 「解码诊断」，共享点放在纯 Kotlin 的 core:media（与 [ProbeMemo] 同一理由）。
 */
object SurfaceCloneMemo {
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last.asStateFlow()

    fun recordFailure(tag: String, detail: String) {
        _last.value = "$tag · $detail"
    }

    fun clear() {
        _last.value = null
    }
}
