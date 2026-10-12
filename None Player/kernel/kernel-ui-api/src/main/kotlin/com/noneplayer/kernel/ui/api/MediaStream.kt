package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * 媒体列表（聚合全部来源）——**首帧就有值的版本**。
 *
 * 背景：库里三个页面（媒体库 / 我的 / 搜索）此前各自写
 * `collectAsStateWithLifecycle(initialValue = null)`——收集是异步的，**页面重进时的首帧
 * 必然拿到 null**，于是先渲染一帧"0 个视频 · 0 个文件夹"的骨架/引导页，叠上转场淡出，
 * 用户看到的就是"每切一次界面闪一帧模板似的空页"（2026-10-08 用户实报）。
 *
 * 两处起手都改成同步的：
 *  - `sources` 用 registry 的**同步查询**当初始值（`observe` 的首次发射也要等一帧）；
 *  - `media` 用各来源的 [MediaSourceProvider.mediaSnapshot] 拼首帧值——**拿不齐才回 null**
 *    （＝"确实还没扫过"，那是真骨架该出现的时候，冷启动语义不变）。
 *
 * @return null = 尚未有数据（调用方渲染骨架）；非 null = 可直接渲染的列表
 */
@Composable
fun rememberMediaItems(): List<MediaItem>? {
    val kernel = LocalKernel.current
    val sources by kernel.registry.observe<MediaSourceProvider>()
        .collectAsStateWithLifecycle(
            initialValue = remember { kernel.registry.query<MediaSourceProvider>() },
        )
    val initial = remember(sources) {
        val snapshots = sources.map { it.mediaSnapshot() }
        if (sources.isNotEmpty() && snapshots.all { it != null }) {
            // 首帧值走与 collect 路径同一条合并口径（含契约键去重，见下方合并注释）
            snapshots.filterNotNull().flatten().distinctBy { it.sourceId to it.id }
        } else {
            null
        }
    }
    val state by remember(sources) {
        if (sources.isEmpty()) {
            flowOf<List<MediaItem>?>(null)
        } else {
            // 合并 + **按契约键去重**：`MediaItem.id` 的语义就是"来源内唯一"（跨来源以
            // sourceId + id 区分），UI 的 Lazy 列表也以 `"$sourceId:$id"` 为 key。
            // 合并结果若出现同键两条，下层必然有缺陷，但**列表键冲突会直接把进程打崩**
            // （用户实报 release 扫描完成后闪退，栈即 Lazy 重复 key）——这里以数据层的
            // 契约为据去重：展示正确性由契约保证，缺陷用日志暴露（Log.e 带着重复键，
            // 排障时一眼可见），而不是让 UI 崩。
            combine(sources.map { it.observeMedia() }) { lists ->
                val merged = lists.flatMap { it }
                val deduped = merged.distinctBy { it.sourceId to it.id }
                if (deduped.size != merged.size) {
                    val dupKeys = merged.groupBy { "${it.sourceId}:${it.id}" }
                        .filterValues { it.size > 1 }
                        .keys
                    android.util.Log.e(
                        "MediaStream",
                        "媒体合并列表出现重复键（已按契约去重）：${dupKeys.take(5)}（共 ${dupKeys.size} 个键）",
                    )
                }
                deduped
            }
        }
    }.collectAsStateWithLifecycle(initialValue = initial)
    return state
}
