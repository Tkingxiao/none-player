package com.noneplayer.plugins.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.MediaSourceProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * E203-C（契约 §8.5）：库页「按需封面」的窗口计算与分发。
 *
 * 背景：扫描不再等待封面（[com.noneplayer.kernel.api.ScanPhase.COVERS] 之后抽帧在后台跑），
 * 卡片第一次出现时封面多半还没落地。UI 若只被动等 Coil 现场抽帧，急速滚动就会长期占位。
 * 这里把「用户马上要看 / 再滚两屏会看到」的条目 id 主动交给来源
 * （[MediaSourceProvider.requestCovers]），让后台按最高优先级把它们抽出来。
 *
 * 三层职责：
 * 1. [coverWindow] / [coverDemandItems]：纯函数，把「可见区间 + 滚动方向」换算成 id 窗口；
 * 2. [groupCoverIdsBySource]：按来源分组，跨来源一次批量发，未知来源跳过；
 * 3. [CoverDemandEffect]：Compose 副作用，snapshotFlow + 去抖 + 窗口内容去重后分发。
 *
 * 渲染兜底**不变**：`coverUri == null` 时卡片仍走 Coil 现场抽帧 + [CoverFrameFailureMemo]，
 * 预备窗口只提高命中率，不是渲染的前置条件（预备窗口为空/来源未实现 requestCovers 都能正常显示）。
 *
 * ## 维护提示：leaderSlots 魔数
 * 纯函数只能看到「条目列表」，看不到网格里的头部条目，所以"内容项之前有多少个槽位"
 * （leaderSlots）必须由调用方算准。**谁在内容项之前增删了 item，就必须同步改下面两处**，
 * 否则整张封面窗口会平移，表现为「滚到某处开始不预热 / 预热到错位的条目」：
 * - LibraryScreen.kt `private fun FolderBrowser(`（函数声明在 LibraryScreen.kt:1833，
 *   前导槽位计算在 LibraryScreen.kt:1867-1873）：槽位 = 无子目录提示(0/1) + 控件行(1)
 *   + 选集入口(0/1) + 子文件夹数（`node.children.size`，每个子文件夹占一槽）。
 * - LibraryScreen.kt `private fun AggregateList(`（函数声明在 LibraryScreen.kt:2399，
 *   前导槽位计算在 LibraryScreen.kt:2466-2470）：槽位 = 标题 + 控件行 + 副标题(3)
 *   + 空态(0/1) + 选集入口(0/1)。
 * 首页 HomeView 不接本窗口（每条 rail 都是 take(8)，一屏内恒可见，预热不增加命中）——
 * 若今后 rail 改成不限条数，再按同样方式接入。
 */

/** 滚动方向：只有「确实在滚且首可见槽位真的动了」才有方向，其余一律 [IDLE]。 */
enum class CoverScrollDirection {
    /** 静止或方向不明：两侧都按默认预备窗口取。 */
    IDLE,

    /** 向下/向后滚（序号变大）：未来一侧加宽到 2 屏。 */
    FORWARD,

    /** 向上/向前滚（序号变小）：回看一侧加宽到 2 屏。 */
    BACKWARD,
}

/** 静止时的预备窗口：可见区前后各 1 屏（契约 §8.5）。 */
const val COVER_PRELOAD_SCREENS = 1

/** 急速滚动时，滚动方向那一侧加到 2 屏（契约 §8.5）。 */
const val COVER_FAST_SCROLL_SCREENS = 2

/** 窗口去抖：滚动中的每帧布局都算一次窗口没有意义，150ms 内只认最后一次（契约 §8.5）。 */
const val COVER_DEMAND_DEBOUNCE_MS = 150L

/**
 * 一次封面预热窗口——条目列表里的**闭区间**序号。
 *
 * 空窗口用 `lastIndex < firstIndex` 表示（[isEmpty]），而不是用 null：调用方链式取值更省事。
 */
data class CoverWindow(val firstIndex: Int, val lastIndex: Int) {

    /** 窗口里没有任何条目。 */
    val isEmpty: Boolean get() = lastIndex < firstIndex

    /** 窗口覆盖多少个条目（空窗口为 0）。 */
    val size: Int get() = if (isEmpty) 0 else lastIndex - firstIndex + 1
}

/**
 * 由「可见区间 + 滚动方向」算出预热窗口。
 *
 * @param itemCount 列表总条数（<=0 时返回空窗口）。
 * @param firstVisibleItem 可见区第一条的**条目序号**（不是网格槽位；调用方先用
 *   [mediaIndexRange] 把槽位换算成条目序号）。
 * @param visibleItemCount 可见区里有几条（网格里可见槽位换算后的条目数，至少 1）。
 * @param direction 滚动方向：默认档两侧各 1 屏；[CoverScrollDirection.FORWARD] 把
 *   「未来」一侧加到 2 屏，[CoverScrollDirection.BACKWARD] 把「回看」一侧加到 2 屏。
 *
 * 「一屏」不按像素算，而按**可见条目数**算——网格列数/列表行高在一屏内是稳定的，
 * 用条数折算天然适配紧凑档 2 列与平板 5 列，不用另外把列数传进来。
 */
fun coverWindow(
    itemCount: Int,
    firstVisibleItem: Int,
    visibleItemCount: Int,
    direction: CoverScrollDirection = CoverScrollDirection.IDLE,
): CoverWindow {
    if (itemCount <= 0) return CoverWindow(0, -1)
    val visible = visibleItemCount.coerceAtLeast(1)
    val first = firstVisibleItem.coerceIn(0, itemCount - 1)
    val before = if (direction == CoverScrollDirection.BACKWARD) {
        COVER_FAST_SCROLL_SCREENS
    } else {
        COVER_PRELOAD_SCREENS
    }
    val after = if (direction == CoverScrollDirection.FORWARD) {
        COVER_FAST_SCROLL_SCREENS
    } else {
        COVER_PRELOAD_SCREENS
    }
    val start = (first - before * visible).coerceAtLeast(0)
    val end = (first + visible - 1 + after * visible).coerceAtMost(itemCount - 1)
    return CoverWindow(start, end)
}

/**
 * 由相邻两次采样的首可见槽位判方向。
 *
 * 采样来自 `snapshotFlow`，间隔通常是一帧，所以「序号动了」本身就是「滚得快」的证据：
 * 一根手指慢慢拖时，一帧往往连一个条目都滚不过去，首可见槽位不动 → [CoverScrollDirection.IDLE]，
 * 两侧仍按 1 屏取（多提一点也无害，来源对未命中会自己丢）。fling 阶段一帧跨好几项 → 方向明确。
 *
 * @param previousFirstVisibleSlot 上一次采样的首可见槽位；-1 表示还没有基准（首帧）。
 */
fun coverScrollDirection(
    isScrollInProgress: Boolean,
    firstVisibleSlot: Int,
    previousFirstVisibleSlot: Int,
): CoverScrollDirection {
    if (!isScrollInProgress) return CoverScrollDirection.IDLE
    if (previousFirstVisibleSlot < 0 || firstVisibleSlot < 0) return CoverScrollDirection.IDLE
    return when {
        firstVisibleSlot > previousFirstVisibleSlot -> CoverScrollDirection.FORWARD
        firstVisibleSlot < previousFirstVisibleSlot -> CoverScrollDirection.BACKWARD
        else -> CoverScrollDirection.IDLE
    }
}

/**
 * 把 LazyGrid 的**槽位**可见区间换算成**条目序号**区间。
 *
 * 两个网格的前几行都是非媒体项（聚合页：标题 / 控件行 / 副标题 / 空态 / 选集入口；
 * 文件夹页：无子目录提示 / 控件行 / 选集入口 / 子文件夹），它们也占槽位。
 * [leaderSlots] 就是「内容项之前有多少个槽位」，调用方按自己的分支算一遍传进来。
 *
 * @return null 表示可见区里还没有内容项（或列表为空）。
 */
fun mediaIndexRange(
    itemCount: Int,
    leaderSlots: Int,
    firstVisibleSlot: Int,
    lastVisibleSlot: Int,
): IntRange? {
    if (itemCount <= 0) return null
    if (firstVisibleSlot < 0 || lastVisibleSlot < firstVisibleSlot) return null
    val leader = leaderSlots.coerceAtLeast(0)
    // 可见区整块还停在内容项之前：这一帧没有需要预热的条目
    if (lastVisibleSlot < leader) return null
    val first = (maxOf(firstVisibleSlot, leader) - leader).coerceIn(0, itemCount - 1)
    val last = (lastVisibleSlot - leader).coerceIn(first, itemCount - 1)
    return first..last
}

/**
 * 窗口 + 取料一步到位：把网格可见槽位换算成条目序号，再按方向扩窗口，最后切出条目。
 *
 * 返回的条目**不去重、不过滤类型**：去重/按来源分组在 [groupCoverIdsBySource]，
 * 音频与否由来源自己判（契约 §8.3：来源跳过 `msa-` 音频）。
 */
fun coverDemandItems(
    items: List<MediaItem>,
    leaderSlots: Int,
    firstVisibleSlot: Int,
    lastVisibleSlot: Int,
    direction: CoverScrollDirection = CoverScrollDirection.IDLE,
): List<MediaItem> {
    val range = mediaIndexRange(items.size, leaderSlots, firstVisibleSlot, lastVisibleSlot) ?: return emptyList()
    val window = coverWindow(items.size, range.first, range.last - range.first + 1, direction)
    if (window.isEmpty) return emptyList()
    return items.subList(window.firstIndex, window.lastIndex + 1)
}

/**
 * 按 [MediaItem.sourceId] 分组，组内保持窗口顺序并去重（同一个 id 在一个窗口里只发一次）。
 *
 * 不在任何来源里的 sourceId 也会留在结果里——**跳过未知来源是分发方的责任**
 * （[CoverDemandEffect] 里 `firstOrNull` 找不到就什么都不做），纯函数不替它猜。
 */
fun groupCoverIdsBySource(items: List<MediaItem>): Map<String, List<String>> {
    if (items.isEmpty()) return emptyMap()
    val grouped = LinkedHashMap<String, LinkedHashSet<String>>()
    items.forEach { item ->
        grouped.getOrPut(item.sourceId) { LinkedHashSet() }.add(item.id)
    }
    return grouped.mapValues { (_, ids) -> ids.toList() }
}

/** 一帧采样：可见槽位区间 + 是否正在滚动。 */
internal data class CoverSlotSample(
    val firstVisibleSlot: Int,
    val lastVisibleSlot: Int,
    val scrolling: Boolean,
)

/**
 * 网格版封面预热：库页两处 [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] 各挂一个。
 *
 * @param items 网格内容项（**不含**头部/控件行/文件夹），顺序必须与网格内容顺序一致。
 * @param leaderSlots 内容项之前的槽位数（见 [mediaIndexRange]）。
 * @param sources 当前可用的媒体来源；按 sourceId 匹配，未知来源跳过。
 * @param state 网格滚动状态（由页面 `rememberLazyGridState()` 提升后传入）。
 */
@Composable
internal fun CoverDemandEffect(
    items: List<MediaItem>,
    leaderSlots: Int,
    sources: List<MediaSourceProvider>,
    state: LazyGridState,
) {
    CoverDemandPipeline(items = items, leaderSlots = leaderSlots, sources = sources) {
        val visible = state.layoutInfo.visibleItemsInfo
        CoverSlotSample(
            firstVisibleSlot = visible.firstOrNull()?.index ?: -1,
            lastVisibleSlot = visible.lastOrNull()?.index ?: -1,
            scrolling = state.isScrollInProgress,
        )
    }
}

/**
 * 分发管线：采样 → 去抖 → 窗口 → 分组 → 调 `requestCovers`。
 *
 * 只在「窗口内容真的变了」时才发请求（`lastSignature` 比较）：滚动停下后的重复重组、
 * 进度表刷新之类的重组不该反复打扰来源；但列表本身变了（`items` 变化）会重跑一次，
 * 因为新条目同样需要预热。
 */
@OptIn(FlowPreview::class)
@Composable
private fun CoverDemandPipeline(
    items: List<MediaItem>,
    leaderSlots: Int,
    sources: List<MediaSourceProvider>,
    sample: () -> CoverSlotSample,
) {
    LaunchedEffect(items, leaderSlots, sources) {
        var previousFirstSlot = -1
        var lastSignature: List<String> = emptyList()
        snapshotFlow { sample() }
            .debounce(COVER_DEMAND_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { current ->
                val direction = coverScrollDirection(
                    isScrollInProgress = current.scrolling,
                    firstVisibleSlot = current.firstVisibleSlot,
                    previousFirstVisibleSlot = previousFirstSlot,
                )
                previousFirstSlot = current.firstVisibleSlot
                val grouped = groupCoverIdsBySource(
                    coverDemandItems(
                        items = items,
                        leaderSlots = leaderSlots,
                        firstVisibleSlot = current.firstVisibleSlot,
                        lastVisibleSlot = current.lastVisibleSlot,
                        direction = direction,
                    ),
                )
                if (grouped.isEmpty()) return@collect
                val signature = grouped.flatMap { (sourceId, ids) -> ids.map { "$sourceId:$it" } }
                if (signature == lastSignature) return@collect
                lastSignature = signature
                grouped.forEach { (sourceId, ids) ->
                    // 未知来源（来源被卸载 / id 对不上）直接跳过：不猜、不报错
                    sources.firstOrNull { it.sourceId == sourceId }?.requestCovers(ids)
                }
            }
    }
}
