package com.noneplayer.plugins.scanner.local

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import com.noneplayer.core.database.HistoryEntry
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.AviDuration
import com.noneplayer.core.media.CueSheet
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.SubtitleCharset
import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.MetadataEnricher
import com.noneplayer.kernel.api.ProcessLifecycle
import com.noneplayer.kernel.api.ScanFolderController
import com.noneplayer.kernel.api.ScanPhase
import com.noneplayer.kernel.api.ScanProgress
import com.noneplayer.kernel.api.ScanResult
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次本地扫描的摘要（供设置页展示） */
data class LocalScanSummary(
    val added: Int,
    val removed: Int,
    val total: Int,
    val durationMs: Long,
    /** 非正常路径的说明（如 E24 拒绝扫描）；正常扫描为 null */
    val note: String? = null,
)

/**
 * 单条采集分支的产物 + 不完整原因（R0/REV-01）。
 *
 * `warnings` 非空表示该分支没有覆盖它本该覆盖的范围（Provider 抛错、SAF 遍历失败、
 * 遍历预算耗尽…）。完整性沿聚合一路传到提交段：只要有一个 warning，本次扫描就
 * **不允许**清理历史/收藏/缩略图、也不允许替换旧快照——宁可显示不完整的结果。
 */
private data class QuerySlice<T>(
    val items: List<T>,
    val warnings: List<String> = emptyList(),
)

/**
 * 隐藏目录遍历的一个「目录任务」：一条车道取一个，处理完把子目录回灌队列。
 *
 * [inheritedHidden] 是 E198 下钻判据的传递值（该目录是否已位于用户显式选中的隐藏分支内）。
 */
private data class WalkTask(
    val treeUri: Uri,
    val docId: String,
    val inheritedHidden: Boolean,
    /** 父目录列表里看到的 LAST_MODIFIED（毫秒；tree 根没有父列表，给 0）。W5 复用时当 mtime 判据 */
    val lastModifiedMs: Long = 0L,
    /** 目录深度（tree 根 = 0）：provider 把父/自身当子目录返回时会成环，靠它设上限兜底 */
    val depth: Int = 0,
)

/**
 * `MediaStore.Files` 里「无扩展名/未知类型」的一行。
 *
 * 游标段只物化数据、不做 I/O，读文件头（最贵的一半）留到按车道并发的第二段。
 * [mimeByName] 非 null 表示扩展名已能判定容器，不需要开流嗅探（needsSniff 收窄）。
 */
private data class UnknownRow(
    val id: String,
    val name: String,
    val title: String,
    val size: Long,
    val addedAt: Long,
    val uri: String,
    val folder: String?,
    val mimeByName: String?,
)

/**
 * 一次扫描的车道预算：设备拓扑（[ScanCpuTopology]）+ 上一轮调好的起跑许可（[ScanLanePresets]）。
 *
 * 为什么把「起跑值」和「上限」分开：起跑值来自上一轮的 [ScanLaneTuner.Summary.bestPermits]，
 * 让第二轮直接站在 knee 上（省掉几个窗口的试探）；上限按设备能力给——walk 是 I/O 型负载，
 * 许可能涨到 2 倍起跑值仍值得试；封面/时长是解码型，过量车道只会换来 GC 抖动。
 */
/**
 * [CoverNegativeSink] → [ScanCoverNegativeCache] 的薄适配器。
 *
 * 泵只认 `isFailed/markFailed/flush`（这样泵能脱离具体缓存类单测）；时间口径由这里统一为
 * 墙钟毫秒，泵自己不造时间源。
 */
private class NegativeCacheSink(private val cache: ScanCoverNegativeCache) : CoverNegativeSink {
    override fun isFailed(key: String, nowMs: Long): Boolean = cache.isFailed(key, nowMs)

    override fun markFailed(key: String, nowMs: Long): Boolean = cache.markFailed(key, nowMs)

    override fun flush() {
        cache.flush()
    }
}

private class LaneBudget(
    val topology: ScanCpuTopology.Topology,
    val presets: Map<LaneKind, Int>,
) {
    val cores: Int = topology.cores

    /** 本轮各阶段的收尾快照；扫描成功后落盘成下一轮的起跑许可。 */
    val summaries = java.util.concurrent.ConcurrentHashMap<LaneKind, ScanLaneTuner.Summary>()

    fun initial(kind: LaneKind, fallback: Int): Int = (presets[kind] ?: fallback).coerceAtLeast(1)

    fun capOf(kind: LaneKind, initial: Int): Int = when (kind) {
        // 目录遍历与嗅探都是「binder/文件 IO 密集、CPU 轻」的负载：加车道收益直接，
        // 上限给到 24 够覆盖 8 核设备（ioWorkers 的 2 倍）仍有富余，同时挡住无限膨胀。
        LaneKind.WALK, LaneKind.SNIFF -> (initial * 2).coerceAtLeast(8).coerceAtMost(WALK_LANE_CAP_MAX)
        // 解码型（抽帧/读时长）：+2 档足够，上限 8 是「过量车道触发 GC 抖动」的经验边界。
        LaneKind.COVER, LaneKind.DURATION -> (initial + 2).coerceAtLeast(4).coerceAtMost(ITEM_LANE_CAP_MAX)
    }

    fun remember(kind: LaneKind, summary: ScanLaneTuner.Summary) {
        summaries[kind] = summary
    }
}

/**
 * 逐窗口取证：把「车道数 → 吞吐」曲线打成一串 `lane tick` 行。
 *
 * 为什么调用方自己采一份样本：窗口吞吐/利用率是 [ScanLaneTuner] 的**内部**数据（它只在
 * 加档/锁死/降档时自己打日志），而它是 task-6 的交付、不为取证改它。这里并行采一份自己的
 * 窗口样本（完成数差分 + [ScanProcCpu] tick 差分），只在三种时刻打一行：许可数变了 /
 * 吞吐创新高 / 每 [HEARTBEAT_WINDOWS] 个窗口心跳一次——一轮扫描几十行，真机上足以读出曲线
 * 又不会把 logcat 刷满。
 */
private class LaneWindowSampler(
    private val kind: LaneKind,
    private val cores: Int,
    private val tuner: ScanLaneTuner,
    private val log: (String) -> Unit,
) {
    private val done = AtomicLong(0L)
    private var lastDone = 0L
    private var lastAtNs = System.nanoTime()
    private var lastTicks = ScanProcCpu.ticks()
    private var peakThr = 0.0
    private var lastLoggedPermits = -1

    /** 每干完一件活调一次（与 [ScanLaneTuner.onItemDone] 成对调用）。 */
    fun onItemDone() {
        done.incrementAndGet()
    }

    /** 监视协程每窗口调一次：`window` 是 1 起的窗口序号。 */
    fun tick(window: Int) {
        val nowNs = System.nanoTime()
        val elapsedMs = (nowNs - lastAtNs) / 1_000_000L
        val total = done.get()
        val inWindow = total - lastDone
        lastDone = total
        lastAtNs = nowNs
        val ticksNow = ScanProcCpu.ticks()
        val util = ScanProcCpu.utilization(lastTicks, ticksNow, elapsedMs, cores)
        lastTicks = ticksNow
        if (elapsedMs <= 0L || inWindow <= 0L) return
        val thr = inWindow * 1000.0 / elapsedMs
        val newPeak = thr > peakThr
        if (newPeak) peakThr = thr
        val permits = tuner.permits
        val permitsChanged = lastLoggedPermits >= 0 && permits != lastLoggedPermits
        val heartbeat = window % HEARTBEAT_WINDOWS == 0
        if (!newPeak && !permitsChanged && !heartbeat) return
        val reason = when {
            permitsChanged && permits > lastLoggedPermits -> "grow"
            permitsChanged -> "degrade"
            newPeak -> tuner.finish().ceilingReason
            else -> "heartbeat"
        }
        lastLoggedPermits = permits
        log(
            "lane tick kind=" + kind + " w=" + window + " permits=" + permits +
                " thr=" + String.format(Locale.US, "%.1f", thr) + "/s" +
                " util=" + (if (util < 0.0) "n/a" else String.format(Locale.US, "%.2f", util)) +
                " win=" + elapsedMs + "ms reason=" + reason,
        )
    }

    /** 阶段收尾一行：`lane tune kind=… permits=… best=… thr=…/s util=… target=… reached=… ceiling=…` */
    fun finishLine(summary: ScanLaneTuner.Summary): String =
        "lane tune kind=" + kind +
            " permits=" + summary.permits +
            " best=" + summary.bestPermits +
            " thr=" + String.format(Locale.US, "%.1f", summary.bestThr) + "/s" +
            " util=" + String.format(Locale.US, "%.2f", summary.utilAvg) +
            " target=" + (if (summary.reachedTarget) "reached" else "unreached") +
            " reached=" + summary.reachedTarget +
            " ceiling=" + summary.ceilingReason

    companion object {
        /** 心跳间隔：300ms 窗口 × 10 ≈ 3s 一行，扫描全程仍能看到曲线走势。 */
        const val HEARTBEAT_WINDOWS = 10
    }
}

/**
 * 本地媒体来源——基于 MediaStore 的设备媒体扫描（视频 + 音频）。
 *
 * 门控策略：仅在用户通过 SAF 授权了扫描目录后才扫描（未选择目录时
 * scan 为空操作），目录列表由 [AppSettings.scanFolderUris] 持久化。
 *
 * 缩略图：视频在扫描时把系统缩略图缓存到 cacheDir（已存在则复用），
 * 媒体卡直接读文件，避免大视频现场抽帧卡顿；音频没有系统缩略图，
 * 改用相册封面 URI（见 [audioCoverUri]），取不到就留空由 UI 出占位图。
 *
 * 增删同步：注册 MediaStore ContentObserver（视频与音频两个集合），文件变化去抖 2s 后自动重扫。
 */
class LocalMediaSource(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val eventBus: EventBus,
    val settings: AppSettings,
    /** 富化链来源（E34：扫描期按注册顺序串行执行 MetadataEnricher） */
    private val registry: ExtensionRegistry,
    /** §8.3/E11：断点迁移的目标仓库 */
    private val history: HistoryRepository,
    /** E25：进程前台/后台状态；null = 不跟随（测试/降级路径，保持旧行为） */
    private val processLifecycle: ProcessLifecycle? = null,
    /** R0/REV-12 测试 seam：媒体库 SDK 版本（默认取运行时；注入以便离线测旧 Provider 行为） */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** R0/REV-01 测试 seam：隐藏目录遍历预算（默认 [HIDDEN_WALK_BUDGET]；注入以便测预算耗尽） */
    private val hiddenWalkBudget: Int = HIDDEN_WALK_BUDGET,
    /** 目录级增量索引开关（生产默认开；测试默认关，见 ScanTestSupport 的 ScanHarness） */
    private val walkIndexEnabled: Boolean = true,
    /** 索引文件（默认 filesDir/scan_walk_index.json，见 scan-contract §3；测试可注入临时文件，避免用例间串味） */
    private val walkIndexFile: File = File(context.filesDir, "scan_walk_index.json"),
    /**
     * 索引 TTL：**叶子目录不复查的最长保鲜期**，超过就别复用。
     *
     * 复用判据是「父列表里该子目录的 mtime」（见 [queryHiddenDocuments] 的 walk 目录分支）：
     * 在已索引的叶子目录里**同名覆盖写**一个文件不会改动父目录 mtime，因此该文件的新 size/
     * mtime/mime 在 TTL 到期前不会被发现——这就是保鲜期的含义。
     *
     * 为什么 30 分钟够用：一次叶子复检只有 1 次 provider IPC + 逐项 size/mtime 比对，读头由基线
     * 复用挡掉（不会重新嗅探），代价远小于信息陈旧；同时 30 分钟也足够把隐藏目录重复遍历的开销摊薄。
     *
     * 默认值统一在 [ScanWalkIndex.DEFAULT_TTL_MS]：改这里会让两处口径脱钩，要调请改 ScanWalkIndex。
     */
    private val walkIndexTtlMs: Long = ScanWalkIndex.DEFAULT_TTL_MS,
) : MediaSourceProvider, CacheProvider, ScanFolderController {

    override val sourceId: String = LOCAL_MEDIA_SOURCE_ID
    override val displayName: String = "本地媒体"

    /** 缩略图缓存目录（唯一落盘缓存，清掉可再生成——下次扫描时重建） */
    private val thumbsDir get() = File(context.cacheDir, "video_thumbs")

    override val cacheLabel: String = "缩略图缓存"

    override fun cacheSizeBytes(): Long = thumbsDir.listFiles()?.sumOf { it.length() } ?: 0L

    override fun clearCache(): Long {
        val before = cacheSizeBytes()
        thumbsDir.listFiles()?.forEach { it.delete() }
        // E205⑫：否定缓存同属「可再生的缓存」——用户显式清缓存必须一并清掉，否则坏文件
        // 的失败结论带着 7 天 TTL 存活过这次操作（clear() 的 KDoc 一直声称会接到这里）。
        // 进程内 5 分钟 memo 同属该口径（E208 审查补），否则清完缓存仍不会立刻重试。
        coverNegativeCache.clear()
        coverFailures.clear()
        aviDurationFailures.clear()
        return before
    }

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    override fun observeMedia(): Flow<List<MediaItem>> = _media.asStateFlow()

    /**
     * **扫描索引快照**：上次扫描结果的落盘副本（`filesDir/scan_index.json`）。
     *
     * 存在理由（两件事共用一份存储）：
     *  - 冷启动首帧：媒体列表此前只在内存里，进程一死就要等一整轮全量扫描
     *    （大库是分钟级）——"不加载好不进去"（用户实报）。快照让 UI 挂载即有内容，
     *    扫描在后台做增量刷新。
     *  - 增量扫描的对照组：`scanInternal` 以快照为基线做**按 id 对比**——
     *    未变的项直接复用（跳过富化/嗅探/封面生成），只对新增与变更项跑完整管线。
     *    `added/removed` 统计因此在跨进程的两次扫描之间也成立（旧实现每次冷启都为 0）。
     *
     * 写入时机：每次扫描完成、发布之后。失败（磁盘满/IO 错）不拖垮扫描——缓存不是真相。
     */
    private val snapshotFile get() = File(context.filesDir, "scan_index.json")

    /** R0/A33：快照落盘存取（原子替换 + 字节限额 + 旧快照保留，提取自本类） */
    private val snapshotStore = ScanSnapshotStore(snapshotFile, dispatchers.io)

    /** R0/REV-03：扫描提交代际门（目录集变化使在途扫描的结果整体作废） */
    private val commitGate = ScanCommitGate()

    /**
     * 目录级增量索引（[ScanWalkIndex]）。
     *
     * `by lazy`：关闭索引时（[walkIndexEnabled] = false）连对象都不建、更不读盘——
     * 既有用例与「用户关掉加速」路径零成本。
     */
    private val walkIndex by lazy { ScanWalkIndex(walkIndexFile, walkIndexTtlMs, dispatchers.io) }

    /** 快照/本进程扫描是否已产出过一次数据——`mediaSnapshot()` 的判据（区分"还没扫"与"确实空"） */
    @Volatile
    private var hasSnapshot = false

    override fun mediaSnapshot(): List<MediaItem>? =
        if (hasSnapshot || _lastScan.value != null) _media.value else null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** A25：扫描进度（active/processed/total），UI 用它替代本地 15s 假收尾。 */
    private val _scanProgress = MutableStateFlow(ScanProgress())
    override fun observeScanProgress(): Flow<ScanProgress> = _scanProgress.asStateFlow()

    /** A25：当前实际持有扫描闸门的协程；cancelScan() 只取消它。 */
    @Volatile
    private var activeScanJob: Job? = null

    /**
     * 取消哨兵。
     *
     * 只靠 [Job.cancel] 收不住：SAF 遍历与游标循环里存在**没有挂起点**的长段
     * （一次 `contentResolver.query` 拿回几千行后逐行处理），挂起式取消要等到
     * 下一个挂起点才生效——用户看到的就是"点了取消没反应"（实报）。这里再置一个
     * 显式标志，让这些同步长循环在每次迭代开头用 [ensureNotCancelled] 主动退出。
     */
    @Volatile
    private var cancelRequested = false

    override fun cancelScan() {
        // 顺序不能反：先置标志再取消 job。反过来时，同步长循环可能在
        // 「job 已取消、标志尚未置位」的窗口里又跑完一整段（几万次 IPC）
        cancelRequested = true
        activeScanJob?.cancel()
        // 封面泵跟着一起停：队列里全是这份列表的条目，取消后继续抽只会白占许可
        coverPump.cancel()
    }

    /**
     * 封面按需优先（kernel API [MediaSourceProvider.requestCovers]，E203-B §8.2）。
     *
     * 约定：幂等、不阻塞、**不在调用线程做 IO**——这里只把 id 插进内存队列，文件是否存在、
     * 有没有否定结论，全部留到泵的工作协程里判定。
     */
    override fun requestCovers(itemIds: List<String>) {
        coverPump.request(itemIds)
    }

    /** E203-B 测试 seam：等封面泵排空（生产代码不调用）。 */
    internal suspend fun awaitCoverPumpIdle(timeoutMs: Long = 10_000L) {
        coverPump.awaitIdle(timeoutMs)
    }

    /** 取消点（非挂起路径）：[cancelScan] 置位后立刻抛 [CancellationException]，由 scan() 收口。 */
    private fun ensureNotCancelled() {
        if (cancelRequested) throw CancellationException("扫描已取消")
    }

    private val _lastScan = MutableStateFlow<LocalScanSummary?>(null)
    val lastScan: StateFlow<LocalScanSummary?> = _lastScan.asStateFlow()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaChanges = MutableStateFlow(0L)
    private var contentObserver: ContentObserver? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var lifecycleJob: kotlinx.coroutines.Job? = null

    /** 上一次扫描完成时刻（[SystemClock.elapsedRealtime] 基准，A5/P2-2）；0 = 还没扫过 */
    @Volatile
    private var lastScanFinishedAt = 0L

    /** A25：辅助 I/O 失败短 TTL（缩略图生成 / AVI 时长读头）。 */
    private val coverFailures = ScanFailureMemo()
    private val aviDurationFailures = ScanFailureMemo()

    /**
     * 封面否定缓存（E203-A）：记住「这个文件抽不出封面」，跨扫描/跨重启都别再重复付费。
     *
     * 落盘 `filesDir/cover_negative.json`；key 是文件指纹（`id|size|mtime`），文件被替换后
     * 指纹跟着变、缓存自然失效，不需要任何失效钩子。
     */
    private val coverNegativeCache = ScanCoverNegativeCache(
        File(context.filesDir, ScanCoverNegativeCache.FILE_NAME),
    )
    private val coverNegativeSink: CoverNegativeSink = NegativeCacheSink(coverNegativeCache)

    /**
     * 封面后台泵（E203-B）：封面从此**不在扫描关键路径上**。
     *
     * 旧实现（attachCovers）在提交段之前逐项抽帧：真机 1283 项 ≈ 9.8s，用户感知就是
     * 「扫描慢」，而这一段的产物只是列表里的 coverUri。现在扫描只负责遍历 + 提交，封面交给这个泵：
     *  - 扫描期许可 `max(2, decodeWorkers/2)`，不跟遍历抢 IPC；扫描收尾自动放开到
     *    `decodeWorkers()`（放开时机由泵的监视协程按 [scanning] 判断，见 CoverPump）；
     *  - UI 调 [requestCovers] 的 id 插到队首（急速滚动时先出可见项的封面）；
     *  - 抽到的封面按 250ms 节流批量回写 [_media]，排空/静默后落一次快照；
     *  - 取消扫描只清队列、不写否定结论（被取消 ≠ 抽不出来）。
     */
    private val coverPump = CoverPump(
        dispatchers = dispatchers,
        workers = ScanParallelism.decodeWorkers(),
        produceCover = { item ->
            val cover = coverFor(item)
            if (cover != null) coverFailures.markOk(item.uri) else coverFailures.markFailed(item.uri)
            cover
        },
        applyCovers = { covers ->
            // E205：回写与扫描提交段并发——读列表前记代际，写回必须仍在同一代际内完成；
            // 被提交插队就对着新列表重试合并。无锁 RMW 会把旧列表整表覆盖掉新扫描结果
            //（增删/时长补读全部回退），代际门内的「复核+写回」杜绝这一窗口。
            applyCoversUnderCommitGate(commitGate, covers, { _media.value }, { _media.value = it })
        },
        currentItems = { _media.value },
        saveSnapshot = { items -> saveSnapshot(items) },
        isScanning = { _scanning.value },
        isCoverFailed = { item -> coverFailures.hasFailed(item.uri) },
        negative = coverNegativeSink,
        // requestCovers 只给 id：在当前列表里找条目，找不到说明已被删，自然丢弃
        resolveItem = { id -> _media.value.firstOrNull { it.id == id } },
    )
    /**
     * 扫描互斥闸门。
     *
     * `_scanning` 是给 UI 看的 StateFlow，**不能当锁用**：scan() 跑在
     * `dispatchers.io`（多线程），`if (_scanning.value) …` 与随后的 `_scanning.value = true`
     * 之间没有原子性。下拉刷新、选目录、`onStart`、ContentObserver 去抖四条路径
     * 可以同时通过那道判断，跑出两份全量 MediaStore 查询；后完成的那次还会用陈旧结果
     * 覆盖 `_media` 并广播一条假的 MediaScanned。判重只认这个 CAS 闸门。
     */
    private val scanGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * R0/REV-03 测试 seam：提交段（发布列表 / 清理历史 / 写快照）之前的挂起点。
     *
     * 生产为 null；回归测试用它暂停"旧扫描"，再让目录集变化（removeScanFolder）
     * 使该次扫描作废，验证陈旧结果不会回灌列表、快照或历史。
     */
    @Volatile
    internal var beforeCommitHook: (suspend () -> Unit)? = null

    /**
     * A25 测试 seam：首扫的“基础列表已发布、封面尚未补齐”时点。
     *
     * 生产为 null；回归测试用它验证首扫在封面阶段前已有内容（UI 不再停在骨架）。
     */
    @Volatile
    internal var beforeCoversHook: (suspend () -> Unit)? = null

    // R0/REV-13：可用性按"可见类别"判定——只拒了音频不应把已授权的视频一起封死；
    // 已有 SAF 目录授权时即便没有媒体权限，来源依然可用（隐藏目录走 SAF 遍历）。
    override suspend fun isAvailable(): Boolean = MediaAccessPolicy.current(context, sdkInt).isAvailable

    init {
        // 取证落盘（E216：应用外部专属目录 scan_trace.log）——internal 在 release
        // 非 debuggable 包上 adb 拉不出来；外部专属目录 adb shell 可直读，无需 root。
        ScanTrace.install(resolveScanTraceDir(context))
    }

    override suspend fun scan(): ScanResult = withContext(dispatchers.io) {
        if (!acquireScanGate()) {
            return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }
        val scanJob = currentCoroutineContext()[Job]
        activeScanJob = scanJob
        // 新一轮扫描重置取消哨兵：上一轮可能被取消过
        cancelRequested = false
        _scanning.value = true
        // 新扫描顶掉上一轮的封面泵：旧队列针对的是旧列表，继续抽只会浪费许可
        coverPump.cancel()
        // 阶段 1/6（PREPARING）：等闸门 + 解析授权目录 + 算增量基线
        _scanProgress.value = ScanProgress(active = true, phase = ScanPhase.PREPARING)
        try {
            // E211：旧版本的扫描目录在主设置里；扫描是本应用唯一消费方，
            // 在 IO 这段启动路径做一次性迁移（构造器里做会留下异步 DataStore 写者）。
            settings.migrateScanFoldersFromLegacy()
            val folderUriStrs = settings.scanFolderUris.first()
            if (folderUriStrs.isEmpty()) {
                return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
            }
            // E59 修复：scan() 本体现在整体包一层 runCatching——任何异常（如 OEM
            // MediaStore 列缺失、Provider 查询失败）都不再向上冒泡崩溃插件 onStart()
            // 乃至拖垮整个内核启动，而是落成一条带原因的失败摘要。
            // R0/REV-03：记下本次扫描开始时的提交代际；目录集在扫描期间变化时
            // 本次结果整体作废（提交段复核，见 scanInternal）
            val generation = commitGate.currentGeneration()
            val startedAt = SystemClock.elapsedRealtime()
            ScanTrace.log(
                "scan enter " + ScanParallelism.describe() + " folders=" + folderUriStrs.size +
                    " generation=" + generation,
            )
            // 成功/取消/失败三分支只有成功才打耗时收尾行（真机对比基线用）
            var succeeded = true
            val result = runCatching { scanInternal(folderUriStrs, generation) }
                .getOrElse { t ->
                    if (t is CancellationException) {
                        // 取消**不是失败**：不给失败摘要、不留错误 note，只写一条「已取消」
                        // 说明并交回 0 计数结果。旧实现把 CancellationException 原样抛给
                        // 调用方，UI 侧那层 runCatching 会把它记成"重扫失败"（脏提示）。
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        Log.i(TAG, "扫描已取消：在取消点退出，本轮不提交任何结果（${elapsed}ms）")
                        ScanTrace.log("scan cancelled elapsedMs=" + elapsed + "，本轮不提交任何结果")
                        succeeded = false
                        _lastScan.value = LocalScanSummary(
                            added = 0,
                            removed = 0,
                            total = _media.value.size,
                            durationMs = elapsed,
                            note = "已取消",
                        )
                        ScanResult(
                            sourceId,
                            added = 0,
                            updated = 0,
                            removed = 0,
                            durationMs = elapsed,
                        )
                    } else {
                        Log.w(TAG, "扫描异常，已降级为失败摘要而非崩溃", t)
                        ScanTrace.log("scan failed " + t.javaClass.simpleName + ": " + t.message)
                        succeeded = false
                        val failSummary = LocalScanSummary(
                            added = 0,
                            removed = 0,
                            total = _media.value.size,
                            durationMs = 0,
                            note = "扫描失败：${t.message ?: t.javaClass.simpleName}",
                        )
                        _lastScan.value = failSummary
                        ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
                    }
                }
            // 封面泵收尾：成功才放开到全速（scanFinished 只打日志，真正的放开在扫描复位后
            // 由泵的监视协程做），失败/取消直接清队列——旧列表可能已经作废
            if (succeeded) coverPump.scanFinished() else coverPump.cancel()
            val elapsedTotal = SystemClock.elapsedRealtime() - startedAt
            if (succeeded) {
                // 真机取证的最终口径：一行可 grep 的总耗时（与取消行共用同一个起始时刻）
                Log.i(TAG, "scan done elapsed=" + elapsedTotal + "ms items=" + _media.value.size)
                ScanTrace.log("scan done elapsed=" + elapsedTotal + "ms items=" + _media.value.size)
            } else {
                ScanTrace.log("scan exit elapsedMs=" + elapsedTotal + " media=" + _media.value.size)
            }
            result
        } finally {
            _scanning.value = false
            _scanProgress.value = ScanProgress()
            // 哨兵随本轮一起复位：下一轮 scan() 开头还会再置一次，两道都留着
            cancelRequested = false
            if (activeScanJob === scanJob) activeScanJob = null
            scanGate.set(false)
            // A5/P2-2：记下「这次扫描完成于何时」，回前台的补扫据此去重
            lastScanFinishedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 抢扫描闸门。拿到返回 true；有人正在扫则短轮询等它结束——E67 的语义保留：
     * ContentObserver 的 debounce 已经消费掉那次发射，等待方不白丢这批变更。
     *
     * A25：超时直接返回 false，由调用方这一次跳过；**不在超时后再 CAS 抢一次**——
     * 那会在持有方刚结束时再跑一遍全量查询，等于 N 个等待者排队跑 N 次。持有方
     * 的扫描已经覆盖了等待窗口内的变更。
     */
    private suspend fun acquireScanGate(): Boolean {
        if (scanGate.compareAndSet(false, true)) return true
        var waited = 0L
        while (scanGate.get()) {
            // 还在扫且已经等满预算：这次直接跳过，不再追加一次全量查询。
            if (waited >= SCAN_WAIT_TIMEOUT_MS) return false
            delay(SCAN_WAIT_POLL_MS)
            waited += SCAN_WAIT_POLL_MS
        }
        // 观察到闸门已释放（等待预算内）：做一次 CAS 抢占；抢不到说明又有人开跑。
        return scanGate.compareAndSet(false, true)
    }

    private suspend fun scanInternal(folderUriStrs: List<String>, generation: Long): ScanResult {
        val startedAt = System.currentTimeMillis()
        // 并发预算取证（真机 logcat 一行定位"这台机器到底开了几条车道、起跑几条、上限几条"）：
        // 起跑值优先用上一轮调好的（ScanLanePresets），没有就用按核数推的默认值（ScanParallelism）。
        // cover 的 floor 取大核数：抽帧是 CPU 重活，小核扛不住，起跑值再低也不该低于大核数。
        val topology = ScanCpuTopology.detect()
        val lanes = LaneBudget(topology, ScanLanePresets.load(context.filesDir))
        val walkStart = lanes.initial(LaneKind.WALK, ScanParallelism.ioWorkers(topology.cores))
        val coverStart = lanes.initial(
            LaneKind.COVER,
            ScanParallelism.decodeWorkers(topology.cores)
                .coerceAtLeast(topology.bigCores.coerceIn(2, 6)),
        )
        val laneDescribe = "cores=" + topology.cores +
            " big=" + topology.bigCores +
            " soc=" + topology.socModel +
            " io=" + ScanParallelism.ioWorkers(topology.cores) +
            " decode=" + ScanParallelism.decodeWorkers(topology.cores) +
            " walkCap=" + lanes.capOf(LaneKind.WALK, walkStart) +
            " coverCap=" + lanes.capOf(LaneKind.COVER, coverStart) +
            " walkStart=" + walkStart +
            " coverStart=" + coverStart
        Log.i(TAG, "scan prepare " + laneDescribe + " folders=" + folderUriStrs.size)
        ScanTrace.log("scan prepare " + laneDescribe + " folders=" + folderUriStrs.size)
        // 取证：六阶段的进入/离开与耗时。真机上 logcat 抓不到时，adb pull
        // /sdcard/Android/data/<pkg>/files/scan_trace.log 即可（E216 外部专属目录）
        var phaseMark = System.currentTimeMillis()
        fun tracePhase(msg: String) {
            val now = System.currentTimeMillis()
            ScanTrace.log("phase=" + msg + " elapsedMs=" + (now - phaseMark))
            phaseMark = now
        }
        // E24：无法解析的目录绝不回退"全量扫描整机"；全部无法解析时拒绝扫描
        // E68 修复：先剔除已被系统撤销持久化授权的目录，摘要里与"docId 畸形"区分开
        val revoked = revokedFolders(folderUriStrs)
        val liveFolders = folderUriStrs - revoked
        val scopes = liveFolders.map { scanScopeFromTree(it) }
        val validScopes = scopes.filterNot { it is ScanScope.Unresolvable }
        if (validScopes.isEmpty()) {
            Log.w(TAG, "授权目录均无法解析，已拒绝扫描: $folderUriStrs")
            _lastScan.value = LocalScanSummary(
                added = 0,
                removed = 0,
                total = _media.value.size,
                durationMs = 0,
                note = if (revoked.isNotEmpty()) {
                    "授权目录已被系统撤销或无法解析，已拒绝扫描（不会扫描其他位置）"
                } else {
                    "授权目录无法解析，已拒绝扫描（不会扫描其他位置）"
                },
            )
            return ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }
        tracePhase("PREPARING done validFolders=" + validScopes.size + " revoked=" + revoked.size)
        val previousList = _media.value
        // 增量基线：上次发布（或本进程从快照恢复）的列表。按 id 复用未变项——
        // 富化 / 容器嗅探 / 封面生成只对新增与变更项跑；未变项连无扩展名文件的
        // 文件头读取都跳过（那是逐文件 I/O，大库上是最贵的一段）。
        val baseline = previousList.associateBy { it.id }
        val previousIds = baseline.keys

        // R0/REV-01：完整性沿聚合传递。任一采集分支没覆盖它本该覆盖的范围
        // （Provider 抛错、SAF 遍历失败、遍历预算耗尽、目录被跳过/撤权）时，
        // 本次扫描只能"发布部分结果"，不得清理历史/收藏/缩略图，也不得替换快照。
        // R0/REV-13：媒体权限按类别分别判定（视频/音频/仅选中/所有文件访问/SAF）。
        // 只查询当前可见的类别；未授权的类别按"本次未覆盖"处理（见 incompleteReasons）。
        val access = MediaAccessPolicy.current(context, sdkInt)
        val warnings = mutableListOf<String>()
        // 阶段 2/6（MEDIA_STORE）：视频/音频集合查询（README：这一段的耗时由 Provider 决定）
        _scanProgress.value = ScanProgress(active = true, phase = ScanPhase.MEDIA_STORE)
        tracePhase("MEDIA_STORE enter")
        val rawStore = queryMediaStore(validScopes, access)
        warnings += rawStore.warnings
        val storeItems = rawStore.items
            // 垃圾文件过滤（§10 批次 4）：下载中的 .part/.tmp 等半成品不进库
            .filterNot { isJunkFile(it.title, it.sizeBytes) }
            .map { fresh ->
                // 富化（E34 的消费端）：插件按注册顺序串行富化，失败不拖垮扫描。
                // 未变项直接沿用基线实例（带上它的富化结果与封面地址）。
                val prev = baseline[fresh.id]
                if (prev != null && prev.unchangedFrom(fresh)) {
                    prev
                } else {
                    // 容器嗅探（§12.4，批次 8）：无扩展名/错扩展名按文件头判容器，
                    // mime 改对才会被引擎的 Extractor 链正确认领
                    sniffMimeCorrection(enrichAll(fresh))
                }
            }
        tracePhase("MEDIA_STORE done storeItems=" + storeItems.size)
        // 无扩展名文件拾取（Files 集合查询 + 文件头嗅探）——同样支持基线复用
        val unknownSlice = sniffUnknownFiles(validScopes, baseline, lanes)
        warnings += unknownSlice.warnings
        val unknownItems = unknownSlice.items
        tracePhase("MEDIA_STORE sniff done unknownItems=" + unknownItems.size)
        // L14：MediaStore 跳过隐藏目录（`.` 前缀），这里用 SAF 遍历把其中的媒体补齐。
        // 只补 MediaStore 看不到的那部分——按「相对路径 + 文件名」与已收项去重，
        // 非隐藏文件仍以 MediaStore 为唯一来源，不重复采集。
        // 去重键剥扩展名：无扩展名拾取（`msf-`）的标题已剥过扩展名，隐藏项用的是
        // 全文件名——不统一口径时同一文件可以同时以两种 id 进库（双份文件的来源之一）。
        fun dedupKey(item: MediaItem): String? =
            item.folder?.let { "$it/${item.title.substringBeforeLast('.')}" }

        val storePaths = (storeItems + unknownItems).asSequence()
            .mapNotNull(::dedupKey)
            .toHashSet()
        tracePhase("HIDDEN_WALK enter folders=" + liveFolders.size)
        val hiddenSlice = queryHiddenDocuments(liveFolders, baseline, lanes)
        warnings += hiddenSlice.warnings
        val hiddenItems = hiddenSlice.items
            .filterNot { item ->
                val path = dedupKey(item)
                path != null && path in storePaths
            }
            // 富化与 MediaStore 项同链路（短标题/集号对隐藏目录里的剧集一样有用）；
            // 复用项是基线**同一实例**（=== 判定），跳过；新增/变更项才走富化
            .map { item -> if (item === baseline[item.id]) item else enrichAll(item) }
        tracePhase("HIDDEN_WALK done hiddenItems=" + hiddenItems.size)
        // 跨来源/多授权树重叠时裸 id 会互相吞；用 sourceId:id。
        val baseItems = (storeItems + unknownItems + hiddenItems).distinctBy { it.identity }
        // 阶段 4/6（COVERS）：本阶段**不再同步抽帧**（E203-B）——封面已转后台 [coverPump]。
        // 仍上报一帧：一是 UI 的阶段文案要连贯，二是 `processed=0/total=N` 恰好表达
        // 「N 项待后台补封面」，与旧语义（这里是抽帧进度）不会混淆。
        _scanProgress.value = ScanProgress(active = true, total = baseItems.size, phase = ScanPhase.COVERS)
        Log.i(TAG, "scan start ${ScanParallelism.describe()} items=${baseItems.size}")
        ScanTrace.log("scan start " + ScanParallelism.describe() + " items=" + baseItems.size)
        tracePhase("COVERS enter items=" + baseItems.size + " handedOff=1")
        // A25：**首扫**先发布基础列表（富化/嗅探已完成、封面待补），UI 不再等全库
        // 抽帧。只在 previousList 为空时做——增量扫描已有旧列表可见，提前发布反而
        // 会和 REV-03 的“提交段整份复核”抢时序。这里同样过代际门；目录集在首扫
        // 期间变化时这次发布会被拒绝，最终提交也会丢弃（新扫描会重发）。
        if (previousList.isEmpty()) {
            commitGate.commitIfCurrent(generation) { _media.value = baseItems }
            // 首帧已发布：封面泵立刻对这批条目开工（不阻塞扫描，并发由泵自己管）
            coverPump.publish(baseItems)
        }
        beforeCoversHook?.invoke()
        // 阶段 5/6（DURATION）：AVI 时长补读。此前是 `.map { withAviDuration() }` 串行段
        val items = fillAviDurations(baseItems, lanes)
        tracePhase("DURATION done items=" + items.size)
        _scanProgress.value = ScanProgress(
            active = true,
            processed = items.size,
            total = items.size,
            phase = ScanPhase.DURATION,
        )
        // [诊断] 发布前自检：同 id 重复会以 Lazy 重复 key 直接打崩 UI（实报闪退），
        // 这里留一行日志把缺陷暴露在数据层，而不是让它到界面上炸（正常应为零输出）
        run {
            val dupKeys = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupKeys.isNotEmpty()) {
                Log.e(TAG, "[诊断] 发布列表出现重复 id：${dupKeys.take(5)}（共 ${dupKeys.size} 个）")
            }
        }
        // §8.3/E11：首次扫描把 DataStore 的断点迁入 Room，随后清旧键
        migrateHistoryOnce(items)
        // E30（B7 收尾）：按 id 集合差集统计，而非净值——
        // 同时增删时净值会互相抵消（新增 3 删 2 显示为 +1/0）
        val newIds = items.map { it.id }.toSet()
        val skippedCount = scopes.size - validScopes.size
        // R0/REV-01：两类"不完整"必须分开处理——
        // ①结构性失败（Provider 抛错/返回 null、SAF 遍历失败或超预算、目录跳过/撤权）：
        //    本轮结果不可信，整轮禁止清理历史/收藏/缩略图，也不替换快照；
        // ②权限缺口（只拒了某一类）：可见类别仍是可信的完整结果，照常清理孤儿，
        //    但被拒类别（如音频）的历史与收藏必须原样保留（白名单见 protectedKeys）。
        val structuralReasons = buildList {
            if (skippedCount > 0) add("$skippedCount 个目录无法解析，已跳过")
            if (revoked.isNotEmpty()) add("${revoked.size} 个目录授权已被系统撤销，请重新选择或移除")
            warnings.distinct().forEach { add(it) }
        }
        val permissionReasons = access.deniedCategories().map { "未授予${it}权限，本次扫描未覆盖$it" }
        val incompleteReasons = structuralReasons + permissionReasons
        // 只有"所有类别都被覆盖且无任何故障"才算完整：快照替换与缩略图清理走这道门。
        val complete = incompleteReasons.isEmpty()
        // 孤儿清理的硬门禁只看结构性失败（权限缺口由白名单兜住）。
        val canPrune = structuralReasons.isEmpty()
        val deniedMimePrefixes = buildList {
            if (!access.canQueryVideo) add("video/")
            if (!access.canQueryAudio) add("audio/")
        }

        // 阶段 6/6（COMMIT）：发布列表 / 清理历史与缩略图 / 写快照
        _scanProgress.value = ScanProgress(
            active = true,
            processed = items.size,
            total = items.size,
            phase = ScanPhase.COMMIT,
        )
        tracePhase("COMMIT enter items=" + items.size + " complete=" + complete)
        // R0/REV-03 测试 seam：提交段之前的挂起点（生产为 null）
        beforeCommitHook?.invoke()

        // ——提交段（REV-03）：在同一串行段内复核代际，再一次性完成
        // 发布列表 / 清理历史与缩略图 / 写快照。目录集在扫描期间变化（add/remove）
        // 时代际已自增，这里返回 null——陈旧结果绝不回灌 UI、快照或历史。——
        val committed = commitGate.commitIfCurrent(generation) {
            _media.value = items
            if (complete) {
                // B7：清理已删除视频对应的缩略图缓存（音频走相册封面、不落缓存）。
                // L14：SAF 拾取的隐藏项也有缩略图，键一并纳入白名单，否则每轮扫描都会被删掉重建
                val validThumbKeys = items.mapNotNull { thumbKeyOf(it.id) }.toSet()
                File(context.cacheDir, "video_thumbs")
                    .listFiles()
                    ?.forEach { f -> if (f.nameWithoutExtension !in validThumbKeys) f.delete() }
            }
            // L16/R0/REV-01：孤儿清理——结构性失败时整轮不清（结果不可信）；权限缺口时
            // 只清"可见类别"，被拒类别的历史/收藏键加入白名单，避免音频权限被拒
            // 就把整个音频库的断点清空。
            if (canPrune) {
                // R2-1/REV-06：读失败（null）时**跳过本轮清理**——宁可不删，也不能把
                // 读不出来的历史键当成孤儿键批量清空。
                val protectedKeys = history.protectedKeysForMimePrefixes(deniedMimePrefixes)
                if (protectedKeys == null) {
                    Log.w(TAG, "孤儿清理跳过：受保护历史键读取失败（存储降级），本轮保留全部历史/收藏")
                } else {
                    val validKeys = items.map { HistoryEntry.keyOf(it) }.toSet() + protectedKeys
                    val pruned = history.pruneOrphans(validKeys, sourceId)
                    if (pruned > 0) Log.i(TAG, "孤儿清理（L16）：删除 $pruned 条失效播放历史")
                    // E209：per-media 键（字幕/显示/仅听/弹幕手选）同样按本轮列表淘汰。
                    // 只在完整扫描后做——权限缺口时看不到类别的键无法区分"已删"与"本轮没看到"。
                    // 显式放到 IO：DataStore edit 若沿调用方的 Unconfined/main 恢复，
                    // Robolectric 的 runBlocking 会把主线程占住（扫描级测试会死等）。
                    if (complete) {
                        val removed = withContext(Dispatchers.IO) {
                            runCatching {
                                settings.prunePerMediaKeys(
                                    validMediaKeys = validKeys,
                                    validUris = items.mapTo(HashSet()) { it.uri },
                                )
                            }.getOrDefault(0)
                        }
                        if (removed > 0) {
                            Log.i(TAG, "per-media 键清理（E209）：删除 $removed 项")
                        }
                    }
                }
            }
            if (!complete) {
                Log.w(TAG, "本次扫描不完整，保留未覆盖范围的历史/收藏与旧快照：${incompleteReasons.joinToString("；")}")
            }
            val summary = LocalScanSummary(
                added = (newIds - previousIds).size,
                removed = (previousIds - newIds).size,
                total = items.size,
                durationMs = System.currentTimeMillis() - startedAt,
                note = buildList {
                    addAll(incompleteReasons)
                    if (!complete) add("本次扫描不完整")
                }.joinToString("；").ifEmpty { null },
            )
            _lastScan.value = summary
            eventBus.publish(KernelEvent.MediaScanned(sourceId = sourceId, count = items.size))
            // 落盘基线：只在完整扫描时写（内容有变化才写——列表相等是 O(n) 比较，
            // 比一次几 MB 的 JSON 写便宜得多）；不完整时保留旧快照，绝不半量覆盖。
            // 顺序稳定性前提：queryHiddenDocuments 已对结果做稳定排序（快照比较是**保序**的，
            // 车道完成顺序不定会让每轮都判定「变了」→ 每轮重写几 MB 快照）
            val snapshotChanged = complete && items != previousList
            if (snapshotChanged) saveSnapshot(items)
            ScanResult(
                sourceId = sourceId,
                added = summary.added,
                updated = 0,
                removed = summary.removed,
                durationMs = summary.durationMs,
            )
        }
        if (committed != null) {
            tracePhase("COMMIT done items=" + items.size)
            // 车道数沉淀：只有完整扫描的调参结果才值得复用（取消/不完整时覆盖范围不全，
            // 吞吐曲线天然偏低，拿它当下一轮起跑值只会把并发越调越小）。写失败只记 trace。
            if (complete) saveLanePresets(lanes)
            // 最终列表已发布：交给封面泵补齐（首扫在首帧发布时已交过一次，publish 幂等；
            // 增量扫描只有这里会交）
            coverPump.publish(items)
            return committed
        }
        Log.i(TAG, "扫描结果已过期（扫描期间目录集发生变化），丢弃本次结果")
        ScanTrace.log("scan discarded 扫描期间目录集发生变化，本次结果作废")
        return ScanResult(
            sourceId = sourceId,
            added = 0,
            updated = 0,
            removed = 0,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * 垃圾文件过滤（§10 批次 4）：`.part`/`.tmp`/`.crdownload`/隐藏/0 字节排除。
     *
     * `.nomedia` 目录与隐藏文件本就被 MediaStore 的媒体集合跳过（扫描全走 MediaStore
     * 查询），这里再挡一道是防御异常 OEM 实现把半成品塞进游标——下载器创建的
     * `xxx.mp4.part` 一旦被索引，库里就会出现"点开打不开"的条目。
     */
    /**
     * MediaStore 经常给 AVI 填 0 时长。只在缺时长、且看起来是 AVI 时读文件头。
     */
    private fun MediaItem.withAviDuration(): MediaItem {
        if (!needsAviDuration()) return this
        if (aviDurationFailures.hasFailed(uri)) return this
        val ms = runCatching {
            context.contentResolver.openInputStream(uri.toUri())?.use { AviDuration.read(it) }
        }.getOrNull() ?: 0L
        if (ms <= 0L) {
            aviDurationFailures.markFailed(uri)
            return this
        }
        aviDurationFailures.markOk(uri)
        return copy(durationMs = ms)
    }

    /**
     * [withAviDuration] 的前置判据（抽出来供分车道前置筛选）。
     *
     * 判据与旧实现逐条一致：缺时长 + 看起来是 AVI + 不在失败短 TTL 里。
     */
    private fun MediaItem.needsAviDuration(): Boolean {
        if (durationMs > 0L) return false
        val avi = title.endsWith(".avi", ignoreCase = true) ||
            mimeType.contains("avi", ignoreCase = true) ||
            mimeType.equals("video/x-msvideo", ignoreCase = true)
        return avi && !aviDurationFailures.hasFailed(uri)
    }

    /**
     * AVI 时长补读（原先是 `.map { withAviDuration() }` 串行段）。
     *
     * MediaStore 对 AVI 常填 0 时长，只能逐文件 `openInputStream` 读头部——几百个 AVI
     * 就是几百次阻塞读排在扫描尾段。这里与封面共用同一份解码预算
     * （[ScanParallelism.decodeWorkers]）：抢同一批解码器的两段工作不该各开一份线程数。
     *
     * 调度用**动态许可**而不是按大小分车道：读头成本 ≈ 每项常数（打开文件 + 解析帧头），
     * 与字节数弱相关；按字节数静态分车道会让一个大文件独占一条车道，其余小项挤在剩下的
     * 车道里，空转的那条车道等于白白丢掉并发度（真机实测封面段因此慢 1.55×，见 [coverPump] 的类注释）。
     * 结果按索引回填（每项只碰自己的下标，不共享可变表）。
     */
    private suspend fun fillAviDurations(items: List<MediaItem>, lanes: LaneBudget): List<MediaItem> {
        val candidates = items.withIndex().filter { (_, item) -> item.needsAviDuration() }
        val done = AtomicInteger(items.size - candidates.size)
        if (candidates.isEmpty()) return items
        // 先报一次：这段没有可预知的进度增量，但 UI 不能停在 COVERS 的尾值
        _scanProgress.value = ScanProgress(
            active = true,
            processed = done.get(),
            total = items.size,
            phase = ScanPhase.DURATION,
        )
        val result = items.toTypedArray()
        // 许可数 = 解码预算的起跑值；每个候选项一个协程，抢到许可才真正读头
        val durationStart = lanes.initial(LaneKind.DURATION, ScanParallelism.decodeWorkers(lanes.cores))
        val durationStartedAt = System.nanoTime()
        val summary = runTunedItems(lanes, LaneKind.DURATION, candidates, durationStart) { (index, item) ->
            result[index] = item.withAviDuration()
            _scanProgress.value = ScanProgress(
                active = true,
                processed = done.incrementAndGet(),
                total = items.size,
                phase = ScanPhase.DURATION,
            )
        }
        lanes.remember(LaneKind.DURATION, summary)
        ScanTrace.log(
            "phase=DURATION done candidates=" + candidates.size + " lanes=" + summary.bestPermits +
                " items=" + items.size +
                " elapsedMs=" + (System.nanoTime() - durationStartedAt) / 1_000_000,
        )
        return result.toList()
    }

    private fun isJunkFile(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        if (name.startsWith('.')) return true
        val lower = name.lowercase()
        return JUNK_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * 增量复用判据：文件身份字段全部一致才复用基线实例。
     *
     * `lastModifiedSec == 0`（来源没给这个字段，如 Files 集合查询）时跳过该项比较；
     * `folder` 参与比较——移动文件不改大小与文件名，只有路径变。
     */
    private fun MediaItem.unchangedFrom(fresh: MediaItem): Boolean =
        title == fresh.title &&
            sizeBytes == fresh.sizeBytes &&
            folder == fresh.folder &&
            addedAtMs == fresh.addedAtMs &&
            (fresh.lastModifiedSec == 0L || lastModifiedSec == fresh.lastModifiedSec)

    // ---- 扫描索引快照（落盘基线：冷启动首帧 + 增量扫描对照组） ----

    /** 恢复上次扫描的落盘快照。只在本进程还没扫过、内存还没有列表时生效（防与并发扫描打架）。 */
    suspend fun restoreSnapshot() = withContext(dispatchers.io) {
        if (hasSnapshot || _lastScan.value != null || _media.value.isNotEmpty()) return@withContext
        val items = readSnapshot() ?: return@withContext
        if (_lastScan.value != null || _media.value.isNotEmpty()) return@withContext
        // [诊断] 快照自检（同"发布前自检"）：正常应为零输出
        run {
            val dupKeys = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupKeys.isNotEmpty()) {
                Log.e(TAG, "[诊断] 快照恢复列表出现重复 id：${dupKeys.take(5)}（共 ${dupKeys.size} 个）")
            }
        }
        // A25：先发布快照原样，AVI 时长补读挪到首帧之后——旧实现 map{withAviDuration()}
        // 会在 IO 线程上给每个 0 时长 AVI 串行读文件头，把首帧一起拖住。
        _media.value = items
        hasSnapshot = true
        Log.i(TAG, "扫描索引快照已恢复：${items.size} 项（冷启动首帧不再等全量扫描）")
        if (items.any { it.durationMs == 0L }) {
            observerScope.launch(dispatchers.io) {
                val filled = items.map { it.withAviDuration() }
                // 只在列表仍是快照原样（扫描尚未提交新列表）时替换，避免回灌旧时长。
                if (filled != items && _media.value == items) {
                    _media.value = filled
                    Log.i(TAG, "快照 AVI 时长后台补读完成：${filled.size} 项")
                }
            }
        }
    }

    /** 把当前列表写为下次启动的基线；失败静默——快照是加速增益，不是真相来源 */
    private suspend fun saveSnapshot(items: List<MediaItem>) {
        // R0/A33：提取到 ScanSnapshotStore。旧实现在 rename 失败支先 delete 旧快照再重试，
        // 重试再失败就连旧快照一起丢；新实现任何失败都只清临时文件、旧快照原封不动。
        snapshotStore.write(items)
    }

    /** 读快照；文件缺失/损坏/版本不符/超限返回 null（按"确实还没扫过"处理）。 */
    private suspend fun readSnapshot(): List<MediaItem>? = snapshotStore.read()

    /**
     * 富化链（E34 的消费端）：按注册顺序**串行**执行 `MetadataEnricher`，
     * 后一个看到前一个的输出（契约约定：实现必须幂等）；单个失败按原样跳过。
     */
    private suspend fun enrichAll(item: MediaItem): MediaItem {
        var current = item
        registry.query<MetadataEnricher>().forEach { enricher ->
            current = try {
                enricher.enrich(current)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                current
            }
        }
        return current
    }

    /**
     * 一段「按项并发 + 闭环调参」的执行器（封面 / 时长 / 嗅探共用）。
     *
     * 调度用**动态许可**而不是按大小分组：抽帧、读时长、读 64 字节头的成本都≈每项常数，
     * 与字节数弱相关；按字节数静态分组会让一个大文件独占一组，其余小项挤在剩下的组里，
     * 空转的那组等于白白丢掉并发度（真机实测封面段因此慢 1.55×，见 [coverPump] 的类注释）。
     *
     * 许可数由 [ScanLaneTuner] 每 [ScanLaneTuner.DEFAULT_WINDOW_MS] 按「吞吐涨幅」增减：
     * 涨得动就加，加档没收益就回落到历史最佳并锁死，避免在 knee 附近震荡。
     *
     * 返回本段收尾快照，供 trace 与 [ScanLanePresets] 落盘（下一轮起跑直接用）。
     */
    private suspend fun <T> runTunedItems(
        lanes: LaneBudget,
        kind: LaneKind,
        items: List<T>,
        initial: Int,
        body: suspend (T) -> Unit,
    ): ScanLaneTuner.Summary = coroutineScope {
        val cap = lanes.capOf(kind, initial)
        // 起跑值可能来自上一轮落盘（ScanLanePresets），必须再钳一次：上限可能因设备变化而变小
        val start = initial.coerceIn(1, cap)
        // 令牌桶许可器（不是 kotlinx 的 Semaphore）：它允许运行中「凭空上调」许可，
        // 而 kotlinx 的 release 只能归还自己 acquire 过的数量，上调即抛 ISE（真机已撞过）。
        val limiter = ScanLimiter(cap, start)
        // 收回许可必须**异步**：onPermitsChanged 在监视协程里被调用，同步 acquire 会把 tick 卡住，
        // 甚至与正在等许可的工作协程互锁（谁都不放行）。这里只投递「稍后收 n 个」的协程，
        // 阶段收尾统一取消——收不回来的许可随本段作废，不影响下一次扫描。
        val shrinkJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        val tuner = ScanLaneTuner(
            kind = kind,
            initialPermits = start,
            cap = cap,
            cores = lanes.cores,
            onPermitsChanged = { delta ->
                if (delta > 0) {
                    repeat(delta) { limiter.release() }
                } else if (delta < 0) {
                    shrinkJobs += launch(dispatchers.io) { repeat(-delta) { limiter.acquire() } }
                }
            },
            log = { line -> ScanTrace.log(line) },
        )
        val sampler = LaneWindowSampler(kind, lanes.cores, tuner) { line -> ScanTrace.log(line) }
        val startedAt = System.nanoTime()
        val monitor = launch(dispatchers.default) {
            var window = 0
            while (isActive) {
                delay(ScanLaneTuner.DEFAULT_WINDOW_MS)
                tuner.tick()
                window++
                sampler.tick(window)
            }
        }
        try {
            items.map { item ->
                async(dispatchers.io) {
                    limiter.withPermit {
                        // 取消点：抽帧/读头都是长阻塞调用，取消后不再接着干下一项
                        ensureNotCancelled()
                        currentCoroutineContext().ensureActive()
                        body(item)
                        sampler.onItemDone()
                        tuner.onItemDone()
                    }
                }
            }.awaitAll()
        } finally {
            monitor.cancel()
            shrinkJobs.toList().forEach { it.cancel() }
        }
        val summary = tuner.finish()
        val achieved = if (summary.utilAvg > 0.0) summary.permits * summary.utilAvg else -1.0
        ScanTrace.log(sampler.finishLine(summary))
        ScanTrace.log(
            "phase=" + kind + " perf util=" +
                (if (summary.utilAvg < 0.0) "n/a" else String.format(Locale.US, "%.2f", summary.utilAvg)) +
                " achievedParallelism=" + fmtParallelism(achieved) +
                " items=" + items.size +
                " elapsedMs=" + (System.nanoTime() - startedAt) / 1_000_000,
        )
        summary
    }

    /** 并发度展示：`n/a` 表示这次没采到 CPU 样本（真机上 /proc 被限制时会出现）。 */
    private fun fmtParallelism(v: Double): String =
        if (v < 0.0) "n/a" else String.format(Locale.US, "%.1fx", v)

    /**
     * 把本轮各阶段的 [ScanLaneTuner.Summary.bestPermits] 落盘，作为下一轮的起跑许可。
     *
     * 只在**完整扫描**后调用：取消/不完整遍历调出来的车道数没有参考价值（覆盖范围都不是全的，
     * 吞吐曲线自然偏低）。落盘失败只记 trace，绝不影响扫描结果——车道数只是性能参数。
     */
    private fun saveLanePresets(lanes: LaneBudget) {
        val map = lanes.summaries.mapValues { (_, summary) -> summary.bestPermits }
        if (map.isEmpty()) return
        val ok = runCatching { ScanLanePresets.save(context.filesDir, map) }.getOrDefault(false)
        val text = map.entries.sortedBy { it.key.name }
            .joinToString(",") { it.key.name + "=" + it.value }
        ScanTrace.log("lane presets saved=" + ok + " " + text)
    }

    /**
     * 封面生产口径（与 id 前缀一一对应，保证 `video_thumbs/<key>.jpg` 的键与
     * `thumbKeyOf` 的清理白名单一致）：
     * `ms-<id>` → 系统缩略图（低版本兜底抽帧）；`saf-…` → 文档 URI 缩略图 + 抽帧兜底。
     */
    private fun coverFor(item: MediaItem): String? = when {
        item.id.startsWith(VIDEO_ID_PREFIX) ->
            item.id.removePrefix(VIDEO_ID_PREFIX).toLongOrNull()
                ?.let { thumbnailFor(it, item.uri.toUri()) }

        item.id.startsWith(HIDDEN_ID_PREFIX) -> thumbnailFor(item.id, item.uri.toUri())
        else -> null
    }

    /**
     * §8.3/E11 一次性迁移：把 DataStore 的 `playback_pos_*` / `playback_at_*`
     * 读成 Room 历史行（行快照取自刚扫到的媒体列表），随即清旧键并置位——
     * 之后 DataStore 只保留设置项，断点键不再随媒体数无限增长。
     */
    private suspend fun migrateHistoryOnce(items: List<MediaItem>) {
        if (settings.historyMigrated.first()) return
        val positions = settings.playbackPositions.first()
        val lastAt = settings.playbackLastAt.first()
        // R2-1/REV-06：迁移失败（null）不置位、也不清 DataStore 旧键——下次扫描重试，
        // 避免"标志位已置但数据没迁进来"造成断点永久丢失。
        val migrated = history.migrateFromDataStore(items, positions, lastAt)
        if (migrated == null) {
            Log.w(TAG, "历史迁移（E11）失败（存储降级），保留 DataStore 旧键，下次扫描重试")
            return
        }
        settings.clearPlaybackMemory()
        settings.setHistoryMigrated(true)
        Log.i(TAG, "历史迁移（E11）：$migrated 条断点迁入 Room，DataStore 旧键已清理")
    }

    /**
     * E68：与系统持久化授权列表交叉核对，找出已被用户在系统设置中撤销的扫描目录。
     * 不在此处修改 settings——交给调用方决定提示文案，用户仍可手动移除。
     */
    private fun revokedFolders(folderUriStrs: List<String>): List<String> {
        val persisted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return folderUriStrs.filterNot { it in persisted }
    }

    /** 添加扫描目录：已存在则直接重扫，否则持久化后立即扫描（[ScanFolderController] 契约） */
    override suspend fun addScanFolder(uri: String) {
        val current = settings.scanFolderUris.first()
        if (uri !in current) {
            // R0/REV-03：目录集变化先作废在途扫描，再落盘新目录集
            commitGate.invalidate()
            settings.setScanFolders(current + uri)
        }
        scan()
    }

    /**
     * 移除一个扫描目录：释放其持久化授权；仍有其他目录时立即重扫，
     * 移除最后一个目录时清空媒体列表、缩略图缓存与落盘快照，回到未扫描引导态。
     * 不删除任何用户文件。
     */
    override suspend fun removeScanFolder(uri: String) {
        val current = settings.scanFolderUris.first()
        if (uri !in current) return
        val remaining = current - uri
        // R0/REV-03：目录集变化先作废在途扫描——被移除目录的在途结果不得回灌
        commitGate.invalidate()
        settings.setScanFolders(remaining)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (remaining.isEmpty()) {
            // E205：清空前先停泵并等它退出——泵排空的「落一次快照」会把刚删的快照
            // 写回（旧列表或空列表），撤销下面的 snapshotFile.delete()。
            coverPump.cancel()
            coverPump.awaitStopped()
            File(context.cacheDir, "video_thumbs").listFiles()?.forEach { it.delete() }
            _media.value = emptyList()
            _lastScan.value = null
            // 快照是"已扫描"的落盘证据——最后一个目录都被移除后它必须一起消失，
            // 否则下次冷启动会把已撤销目录的列表恢复出来
            hasSnapshot = false
            snapshotFile.delete()
        } else {
            // R0/REV-03：不能在这里 await scan()——在途扫描可能还持着 scanGate，
            // 阻塞式等待会把"移除目录"这个用户操作卡住最多 SCAN_WAIT_TIMEOUT_MS。
            // 代际已自增，后台重扫（拿到闸门后）才读新目录集，旧结果不会回灌。
            observerScope.launch {
                runCatching { scan() }
                    .onFailure { t -> Log.w(TAG, "移除目录后的重扫失败", t) }
            }
        }
    }

    /**
     * E77：对外暴露观察者是否已注册，供插件禁用/`Kernel.shutdown()` 路径的
     * 集成测试断言"onStop 后 ContentObserver 确已注销"，不必依赖反射读私有字段。
     * E25 起**当前已注册**而非"进程存活期内注册过"——退到后台时会被暂时注销。
     */
    val isObserving: Boolean get() = contentObserver != null

    /**
     * 监听 MediaStore 视频与音频集合的变化（文件添加/删除），去抖后自动重扫。
     * 由插件 onStart 调用；onStop 调用 [stopObserving]。
     *
     * 音频集合必须单独注册：两个集合的 URI 不同，只监听视频会漏掉"新增一首歌"
     * 这类变更，音频要等下次手动重扫才出现。
     *
     * E25：注入了 [processLifecycle] 时，观察者跟随前台/后台——退后台即注销
     * （不再在进程存活期内无谓持有），回前台重挂并补发一次变更，把后台期间
     * 错过的文件增删扫进来（走同一 2s 去抖通道）。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startObserving() {
        if (contentObserver != null) return
        registerObservers()
        observerJob = observerScope.launch {
            mediaChanges.debounce(2_000).collect {
                if (it > 0) scan()
            }
        }
        val lifecycle = processLifecycle ?: return
        lifecycleJob = observerScope.launch {
            lifecycle.isForeground.collect { foreground ->
                if (foreground && contentObserver == null) {
                    registerObservers()
                    // 回前台补扫是必要的：观察者注销期间 MediaStore 的事件收不到。
                    // 但快速往返（拉通知栏、切出去看一眼就回来）不该再来一遍全量查询——
                    // 距上次完成不足 MIN_RESCAN_INTERVAL_MS 时，中间根本没有「观察者没注册」的窗口。
                    if (SystemClock.elapsedRealtime() - lastScanFinishedAt >= MIN_RESCAN_INTERVAL_MS) {
                        mediaChanges.value = System.currentTimeMillis()
                    }
                } else if (!foreground && contentObserver != null) {
                    unregisterObservers()
                }
            }
        }
    }

    fun stopObserving() {
        unregisterObservers()
        observerJob?.cancel()
        observerJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
    }

    private fun registerObservers() {
        if (contentObserver != null) return
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                mediaChanges.value = System.currentTimeMillis()
            }
        }
        context.contentResolver.registerContentObserver(videoCollection(), true, contentObserver!!)
        context.contentResolver.registerContentObserver(audioCollection(), true, contentObserver!!)
    }

    private fun unregisterObservers() {
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
    }

    /** MediaStore 视频集合（API 26–28 用常量 URI，29+ 用带卷名的getContentUri） */
    // VOLUME_EXTERNAL 是 API 29 常量，但它是 String 常量会被内联；调用点都在
    // sdkInt >= Q 分支里（旧系统走 EXTERNAL_CONTENT_URI），运行时安全。
    @SuppressLint("InlinedApi")
    private fun videoCollection(): Uri =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** MediaStore 音频集合（与 [videoCollection] 同规则，id 前缀 "msa-" 与其区分） */
    @SuppressLint("InlinedApi")
    private fun audioCollection(): Uri =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * 多目录查询：逐目录按授权范围过滤后按 id 去重合并（父/子目录重叠安全）。
     * 视频与音频合入同一个列表、统一按加入时间倒序——UI 自己按 `MediaItem.kind`
     * 分流，数据层不做分组，免得两处排序口径各说各话。
     *
     * R0/REV-01：视频、音频两类查询各自兜异常——任一失败只让本次结果"不完整"
     * （调用方据此禁止清理历史/收藏/快照），而不是整轮扫描失败或静默返回空集。
     * R0/REV-13：只查询当前可见的类别——音频被拒时不再白查一遍音频集合，
     * 已授权的视频照常入库。
     */
    private fun queryMediaStore(scopes: List<ScanScope>, access: MediaAccess): QuerySlice<MediaItem> {
        val merged = LinkedHashMap<String, MediaItem>()
        val warnings = mutableListOf<String>()
        scopes.forEach { scope ->
            if (scope is ScanScope.Unresolvable) return@forEach
            if (access.canQueryVideo) {
                runCatching { queryScope(scope) }
                    .onSuccess { slice ->
                        slice.items.forEach { merged[it.id] = it }
                        warnings += slice.warnings
                    }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        Log.w(TAG, "视频查询失败（本次扫描不完整）", t)
                        warnings += "视频查询失败，本次扫描不完整"
                    }
            }
            if (access.canQueryAudio) {
                runCatching { queryAudioScope(scope) }
                    .onSuccess { slice ->
                        slice.items.forEach { merged[it.id] = it }
                        warnings += slice.warnings
                    }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        Log.w(TAG, "音频查询失败（本次扫描不完整）", t)
                        warnings += "音频查询失败，本次扫描不完整"
                    }
            }
        }
        return QuerySlice(merged.values.sortedByDescending { it.addedAtMs }, warnings.distinct())
    }

    /**
     * 授权范围 → MediaStore 过滤条件（R0/REV-02，实现已提取到 [ScanScopeQuery]）。
     *
     * Q+ 同时限定卷（主卷 [MediaStore.VOLUME_EXTERNAL_PRIMARY] / 可移动卷）与目录子树，
     * 目录前缀一律带 "/" 分隔符（`Movies/` 不再命中 `MoviesBackup/`）；pre-Q 没有
     * VOLUME_NAME/RELATIVE_PATH，改用完整存储根路径的 DATA 前缀。`%`/`_`/`\` 统一转义。
     * Unresolvable 返回恒假谓词 `1 = 0`（不是 null——null 会退化成全量查询）。
     */
    // VOLUME_EXTERNAL_PRIMARY 是 API 29 常量（String，会被内联）；调用点在 sdkInt >= Q
    // 分支里才用到它，pre-Q 分支不会执行到这行，运行时安全。
    @SuppressLint("InlinedApi")
    private fun scopeSelection(
        scope: ScanScope,
        relativePathColumn: String,
        dataColumn: String,
    ): Pair<String?, Array<String>?> {
        val spec = ScanScopeQuery.spec(
            scope = scope,
            sdkInt = sdkInt,
            relativePathColumn = relativePathColumn,
            dataColumn = dataColumn,
            volumeNameColumn = MediaStore.MediaColumns.VOLUME_NAME,
            primaryVolumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
            legacyPrimaryRoot = Environment.getExternalStorageDirectory().absolutePath,
        )
        return spec.selection to spec.selectionArgs.toTypedArray()
    }

    private fun queryScope(scope: ScanScope): QuerySlice<MediaItem> {
        val (selection, selectionArgs) = scopeSelection(
            scope,
            relativePathColumn = MediaStore.Video.Media.RELATIVE_PATH,
            dataColumn = MediaStore.Video.Media.DATA,
        )
        if (scope is ScanScope.Unresolvable) return QuerySlice(emptyList())

        // R0/REV-12：投影按 SDK 裁剪——旧 Provider（API 26–28）不认识 RELATIVE_PATH 时
        // 可能直接拒绝整条查询，视频/音频会在旧机上整体消失。Q+ 才带该列。
        val projection = MediaStoreProjection.video(sdkInt)
        val hasRelativePath = MediaStoreProjection.hasRelativePath(sdkInt)
        val items = mutableListOf<MediaItem>()
        // R0/REV-01：null 游标 ≠ 空结果。Provider 返回 null 表示"这次查询没给出答案"，
        // 按空结果处理会把整个视频类别当成"已删除"进而误清历史——必须记为不完整。
        val cursor = context.contentResolver.query(
            videoCollection(),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        ) ?: return QuerySlice(
            items = emptyList(),
            warnings = listOf("视频查询返回空结果，本次扫描不完整"),
        )
        cursor.use { cursor ->
            // E59 修复配套：7 个投影列此前 6 个用 getColumnIndexOrThrow，任何一个列在
            // 异常 OEM MediaStore 实现下缺失都会直接抛异常、中止整次扫描。统一改为
            // getColumnIndex + 安全默认值；只有 _ID 缺失时该行确实无法构造合法 MediaItem，
            // 跳过这一行而不是中止整个游标遍历。
            val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val addedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val relativeCol = if (hasRelativePath) {
                cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                val item = MediaItem(
                    id = "ms-$id",
                    sourceId = sourceId,
                    title = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        .orEmpty().ifEmpty { "未命名视频" },
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "video/mp4",
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    lastModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L,
                    addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                    // 封面不在这里生成：游标里逐项抽帧是**串行**的，几百部片子会把首扫
                    // 拖到分钟级。统一交给后台封面泵（[coverPump]）：扫描只发布列表，封面补在后台。
                    coverUri = null,
                    folder = folderOf(
                        dataPath = dataPath,
                        relativePath = if (relativeCol >= 0) {
                            cursor.getString(relativeCol)
                        } else {
                            null
                        },
                    ),
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                )
                items += item
            }
        }
        return QuerySlice(items)
    }

    /**
     * 音频集合查询——与 [queryScope] 同构：同一套授权范围过滤、同一套列缺失防御。
     *
     * id 用 "msa-" 前缀与视频的 "ms-" 区分：两个集合的 `_ID` 是彼此独立的自增序号，
     * 不加前缀合并时同号记录会互相覆盖（一首歌被顶掉一个视频）。
     *
     * mimeType 必须是真实的音频 MIME（audio/ 开头）——[com.noneplayer.core.media.MediaItem.kind]
     * 正是按它分流音频/视频，这里若回退成视频 MIME，UI 会把歌当视频排进播放列表。
     */
    private fun queryAudioScope(scope: ScanScope): QuerySlice<MediaItem> {
        val (selection, selectionArgs) = scopeSelection(
            scope,
            relativePathColumn = MediaStore.Audio.Media.RELATIVE_PATH,
            dataColumn = MediaStore.Audio.Media.DATA,
        )
        if (scope is ScanScope.Unresolvable) return QuerySlice(emptyList())

        // R0/REV-12：与视频同规则，按 SDK 裁剪投影（Q+ 才带 RELATIVE_PATH）。
        // ALBUM_ID 用于相册封面；ARTIST / TITLE / IS_MUSIC 一并取出备查，
        // 不按 IS_MUSIC 过滤：铃声、录音同样落在音频集合里。
        val projection = MediaStoreProjection.audio(sdkInt)
        val hasRelativePath = MediaStoreProjection.hasRelativePath(sdkInt)
        val items = mutableListOf<MediaItem>()
        // R0/REV-01：null 游标按"本次未覆盖"处理，不能当成"音频已全部删除"。
        val cursor = context.contentResolver.query(
            audioCollection(),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        ) ?: return QuerySlice(
            items = emptyList(),
            warnings = listOf("音频查询返回空结果，本次扫描不完整"),
        )
        cursor.use { cursor ->
            // 与视频一致：异常 OEM 实现下缺失的列不中止整次扫描，
            // 统一 getColumnIndex + 安全默认值；只有 _ID 缺失时该行跳过。
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relativeCol = if (hasRelativePath) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                // TITLE 才是"歌名"（DISPLAY_NAME 是文件名，常带艺人前缀与扩展名）；
                // 两者都空时才落占位文案。
                val title = (if (titleCol >= 0) cursor.getString(titleCol) else null)
                    .orEmpty()
                    .ifBlank { (if (nameCol >= 0) cursor.getString(nameCol) else null).orEmpty() }
                    .ifBlank { "未命名音频" }
                val item = MediaItem(
                    id = "msa-$id",
                    sourceId = sourceId,
                    title = title,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "audio/mpeg",
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    lastModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L,
                    addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                    coverUri = audioCoverUri(if (albumCol >= 0) cursor.getLong(albumCol) else 0L),
                    folder = folderOf(
                        dataPath = dataPath,
                        relativePath = if (relativeCol >= 0) {
                            cursor.getString(relativeCol)
                        } else {
                            null
                        },
                    ),
                )
                // `.cue` 分轨（§12.4/G4，批次 8）：同名 .cue 在场 → 一张专辑 N 轨
                items += cueTracksFor(item, dataPath)
            }
        }
        return QuerySlice(items)
    }

    /**
     * 从媒体记录提取展示用文件夹（如 "Movies"、"DCIM/Camera"）。
     *
     * E79：**优先 RELATIVE_PATH**。Android 10 起 [MediaStore.Video.Media.DATA] 受分区
     * 存储限制，取到 null 是常态；以前只看 DATA，导致 folder 全为 null，
     * "按文件夹分组"和"同文件夹连播"一起静默失效。RELATIVE_PATH 形如 "Movies/"，
     * 去掉尾部斜杠即为展示名；两者都拿不到时返回 null（调用方按"无目录"处理）。
     */
    private fun folderOf(dataPath: String, relativePath: String?): String? {
        relativePath
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (dataPath.isEmpty()) return null
        val dir = dataPath.substringBeforeLast('/')
            // E16：主存储前缀不硬编码——副存储/OTG 挂载点的根不是 /storage/emulated/0，
            // 固定前缀剥不掉时目录会显示成完整绝对路径
            .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
            .ifEmpty { "存储根目录" }
        return dir
    }

    /**
     * L14：隐藏目录（`.` 前缀）里的媒体拾取。
     *
     * MediaStore 的媒体集合**跳过隐藏目录**：模拟器实测把同一个 mp4 放进
     * `Movies/.nphidden/` 后，视频集合的 13 行里 0 行属于它——用户在手机上把片子
     * 收进 `.xxx/` 目录，库里便一条都看不见。而 SAF（`DocumentsContract`）的 children
     * 查询**不跳过隐藏项**（同一次实测里 `.nphidden` 与 `.thumbnails` 都照常返回）。
     *
     * 于是沿用户授权的每个 tree 递归：**目录一律下钻**（隐藏目录可能藏在非隐藏目录之下，
     * 如 `Movies/Series/.hidden`），**只收"路径中含 `.` 前缀分段"的文件**——非隐藏文件
     * 仍以 MediaStore 为唯一来源，绝不重复采集。返回项的 uri 是 document URI，引擎直接
     * 可播（media3 走 ContentResolver）；tree 的持久化读授权已覆盖其全部子孙。
     *
     * 遍历有 [HIDDEN_WALK_BUDGET] 硬预算：目录树里混进 `node_modules`/`.git` 这类巨型结构
     * 时，不把一次扫描拖成分钟级；超预算即停（fail-open，宁可少收也不卡死）。
     */
    private suspend fun queryHiddenDocuments(
        treeUriStrs: List<String>,
        baseline: Map<String, MediaItem>,
        lanes: LaneBudget,
    ): QuerySlice<MediaItem> {
        // 预算用原子量：车道并发消费时 `var budget` 会跑成负数，让「是否耗尽」的判定失真
        val budget = AtomicInteger(hiddenWalkBudget)
        val exhausted = AtomicBoolean(false)
        // 去重：tree 根重叠（父/子目录各自授权）时同一文档会被走到两次——
        // 此前无判重，同一文件以同一 `saf-` id 进列表两次（双份文件的来源之一）
        val seen = HashSet<String>()
        val seenLock = Any()
        // 并发容器：车道各自 append，顺序不再重要（下游按 id 去重/富化，与顺序无关）
        val found = ConcurrentLinkedQueue<MediaItem>()
        val warnings: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val discovered = AtomicInteger(0)
        val dirsDone = AtomicInteger(0)
        // 节流用真实单调时钟：SystemClock 在单元测试（Robolectric）里不随真实时间推进，
        // 拿它做节流会让「每 100ms 报一次」在测试里永远不触发（也让真机上被系统时钟调整带偏）
        val lastReportAt = AtomicLong(System.nanoTime())
        // 目录去重：同一个目录可能被重复入队（父子 tree 重叠授权 / 同一 tree 的不同 URI 形态 /
        // provider 把父目录或自身当 child 返回而成环）。重复入队不会重复入库（文件有 seen 去重），
        // 但会白吃遍历预算（逐行 budget.getAndDecrement）→ 提前 exhausted，静默漏文件。
        // key 必须带 inheritedHidden：同一 docId 在不同 hidden 上下文下的下钻许可不同（E198 判据依赖它）
        val dirSeen = HashSet<String>()
        val dirSeenLock = Any()
        val dupSkipped = AtomicInteger(0)
        val skipDepth = AtomicInteger(0)
        val maxDepth = AtomicInteger(0)

        // ——目录级增量索引（W1）——
        // 关闭时 walkIndex 走 lazy 分支根本不会被创建、更不读盘。
        // load() 内部先 clear() 再 putAll(磁盘)：上一轮取消/预算耗尽残留在**内存**里的条目
        // 每轮开头被自动洗掉——这也是「取消/不完整时不 save」仍然安全的原因。
        if (walkIndexEnabled) walkIndex.load()
        // 复用判据里的「现在」取一次就够：一轮 walk 内的时钟漂移没意义，且省掉每目录一次系统调用
        val walkNowMs = System.currentTimeMillis()
        val reusedDirs = AtomicInteger(0)
        val recordedDirs = AtomicInteger(0)

        ScanTrace.log("hidden walk enter trees=" + treeUriStrs.size + " budget=" + hiddenWalkBudget)

        // 阶段 3/6（HIDDEN_WALK）：入口先报一次。带点号目录多的机器上这一段能跑很久，
        // 旧实现整段没有任何进度，UI 只能一直停在 MEDIA_STORE 的尾值。
        _scanProgress.value = ScanProgress(active = true, total = 0, phase = ScanPhase.HIDDEN_WALK)

        /** 已上报过的 processed 值（0 = 还没报过真实数量），用于「首次发现必报一次」的判定 */
        val reportedProcessed = AtomicInteger(0)

        /** 上报节奏：首次发现条目必报一次，之后每 [PROGRESS_DIR_EVERY] 个目录或 [PROGRESS_EVERY_MS] 一次；total 恒 0（树深未知） */
        fun maybeReport() {
            val dirs = dirsDone.incrementAndGet()
            val found = discovered.get()
            val now = System.nanoTime()
            val sinceLastMs = (now - lastReportAt.get()) / 1_000_000
            // 只有几个目录的小隐藏树，整段既走不到 [PROGRESS_DIR_EVERY] 个目录、也常常不到 [PROGRESS_EVERY_MS] 毫秒，
            // 按纯节流就只剩入口那帧 processed=0——界面上依然是「一直 0」的观感。所以首次真的发现条目时立刻上报一次。
            val firstRealReport = reportedProcessed.get() == 0 && found > 0
            if (!firstRealReport && dirs % PROGRESS_DIR_EVERY != 0 && sinceLastMs < PROGRESS_EVERY_MS) return
            lastReportAt.set(now)
            reportedProcessed.set(found)
            _scanProgress.value = ScanProgress(
                active = true,
                processed = discovered.get(),
                total = 0,
                phase = ScanPhase.HIDDEN_WALK,
            )
        }

        /**
         * 索引命中复用：把缓存条目并入本轮 [found]。
         *
         * **不是**「把缓存内容直接 add 了事」——契约 §4.5 要求复用项仍过与首扫同一条流水线
         * （垃圾后缀 → 0 字节 → id 生成 → seen 去重 → 基线复用）。索引可能是旧版本规则写下的，
         * 少走一步就会把垃圾文件带进库，或者让 id/富化结果与首扫不一致。
         */
        fun mergeIndexedItems(indexed: List<ScanWalkIndex.Indexed>, treeUri: Uri) {
            for (entry in indexed) {
                val name = entry.name
                if (isJunkSuffix(name)) continue
                if (entry.sizeBytes <= 0L) continue
                val id = hiddenDocumentId(entry.docId)
                if (!synchronized(seenLock) { seen.add(id) }) continue
                val lmSec = entry.lastModifiedSec
                val prev = baseline[id]
                if (prev != null && prev.sizeBytes == entry.sizeBytes &&
                    prev.lastModifiedSec == lmSec && prev.title == name
                ) {
                    // 基线同实例复用：富化与封面都不用重跑（与首扫分支完全同一口径）
                    found.add(prev)
                    discovered.incrementAndGet()
                    continue
                }
                // mime 为 null = 首扫当时也没嗅出来（进不了库），复用同样跳过，绝不重新 readHead
                val mime = entry.mime ?: continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
                // 坑①（毫秒 vs 秒）：Indexed 只有秒级 lastModifiedSec，addedAtMs 的毫秒精度只能
                // 从基线兜底——unchangedFrom() 恰好比较 addedAtMs，兜错会让基线复用整批失效
                // （每轮重新富化 + 重抽封面，比不接索引还慢）。
                val addedAtMs = prev?.takeIf { it.lastModifiedSec == lmSec }?.addedAtMs
                    ?: (lmSec * 1000L)
                found.add(
                    MediaItem(
                        id = id,
                        sourceId = sourceId,
                        title = name,
                        uri = docUri.toString(),
                        mimeType = mime,
                        sizeBytes = entry.sizeBytes,
                        lastModifiedSec = lmSec,
                        addedAtMs = addedAtMs,
                        coverUri = null,
                        folder = folderOfDocument(entry.docId),
                    ),
                )
                discovered.incrementAndGet()
            }
        }

        coroutineScope {
            // 车道工作队列：每条车道串行处理「一个目录任务」，子目录回灌同一条队列。
            // 收敛条件是 pending 计数（入队 +1 / 处理完 -1，归零即 close），不是轮询空队列。
            val tasks = Channel<WalkTask>(Channel.UNLIMITED)
            val pending = AtomicInteger(0)

            fun enqueue(task: WalkTask) {
                pending.incrementAndGet()
                tasks.trySend(task)
            }

            /**
             * 处理一个目录：查 children、拾取隐藏文件、下钻子目录。
             *
             * E198（数据保护）判定逐条沿用旧实现：[shouldDescendDirectory] 决定普通根
             * 下的隐藏子目录**不下钻**，[treeRootEntersHiddenPath] 决定整支是否显式选中。
             */
            suspend fun processTask(task: WalkTask) {
                // 每个目录任务开头先过取消点：取消后不再发起任何 IPC
                currentCoroutineContext().ensureActive()
                ensureNotCancelled()
                if (budget.get() <= 0) {
                    exhausted.set(true)
                    return
                }
                // 目录级索引采集（W3）：本目录下「能进库的条目」与直接子目录数。
                // childDirs **每个目录行都计，包括被 E198 拒掉不下钻的**：leaf 判定必须保守——
                // 把非叶误判成叶子会让深层新增永久不可见（复用命中后不再下钻），比不复用严重得多。
                val dirItems = ArrayList<ScanWalkIndex.Indexed>()
                var childDirs = 0
                // partial = 本目录没走完整（预算耗尽 / childrenUri 构建失败 / query 抛错）：
                // 此时 dirItems 是残缺的，写进索引会让下一轮把残缺当完整结果复用 → 漏文件。
                var partial = false
                val childrenUri = runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(task.treeUri, task.docId)
                }.getOrNull() ?: run {
                    partial = true
                    warnings.add(WARN_HIDDEN_WALK_FAILED)
                    return
                }
                // E205：null 游标（provider 契约允许）≠ 空目录——与 query 抛异常同判为
                // 「本目录没走完整」。否则 childDirs==0、items 空，该目录被记成空叶子
                // 固化进 ScanWalkIndex，complete 误判还会让孤儿清理把这批文件的
                // 播放历史/收藏当孤儿删掉。
                val childCursor = runCatching {
                    context.contentResolver.query(
                        childrenUri,
                        HIDDEN_PROJECTION,
                        null,
                        null,
                        null,
                    )
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    Log.w(TAG, "隐藏目录遍历失败（本次扫描不完整）", t)
                    null
                }
                if (childCursor == null) {
                    partial = true
                    warnings.add(WARN_HIDDEN_WALK_FAILED)
                    return
                }
                childCursor.use { cursor ->
                        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val lmCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        while (cursor.moveToNext()) {
                            // 逐行取消点：一次 children 查询可能几千行，取消必须在这里立刻生效
                            currentCoroutineContext().ensureActive()
                            ensureNotCancelled()
                            if (budget.getAndDecrement() <= 0) {
                                exhausted.set(true)
                                partial = true
                                return
                            }
                            if (idCol < 0) continue
                            val childDocId = cursor.getString(idCol) ?: continue
                            val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                            if (name.isEmpty()) continue
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                            val hidden = task.inheritedHidden || name.startsWith(".")
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                childDirs++
                                // 数据保护（E198）：普通授权根下的隐藏子目录不下钻，只有显式选中的隐藏分支才继续深入
                                if (!shouldDescendDirectory(name, task.inheritedHidden, HIDDEN_DENY_DIRS)) continue
                                // 成环防护：provider 把父/自身当 child 返回时深度会无限涨，只靠
                                // HIDDEN_WALK_BUDGET 兜底是「2 万次 IPC」级别的等待
                                val childDepth = task.depth + 1
                                if (childDepth > MAX_WALK_DEPTH) {
                                    skipDepth.incrementAndGet()
                                    ScanTrace.log("walk skip depth doc=" + childDocId + " depth=" + childDepth)
                                    continue
                                }
                                val dirKey = childDocId + "|" + hidden
                                if (!synchronized(dirSeenLock) { dirSeen.add(dirKey) }) {
                                    dupSkipped.incrementAndGet()
                                    continue
                                }
                                if (childDepth > maxDepth.get()) maxDepth.set(childDepth)
                                // 父列表里看到的 LAST_MODIFIED（毫秒）是子目录 mtime 的唯一来源，
                                // 目录级增量索引（W5）就靠它与父列表比对判「目录没变」
                                val childLmMs = if (lmCol >= 0) cursor.getLong(lmCol) else 0L
                                // 增量索引（W2）：这个子目录上次被判定为叶子、且父列表里的 mtime 与本轮
                                // 看到的一致、hidden 上下文也一致 → 内容没变，直接合并缓存条目，省掉一次 IPC。
                                // 判据全部由 ScanWalkIndex.reusable() 复核（含 TTL 与 payloadDropped）。
                                if (walkIndexEnabled) {
                                    val hit = walkIndex.reusable(
                                        task.treeUri.toString(),
                                        childDocId,
                                        childLmMs,
                                        hidden,
                                        walkNowMs,
                                    )
                                    if (hit != null) {
                                        mergeIndexedItems(hit.items, task.treeUri)
                                        reusedDirs.incrementAndGet()
                                        // 复用命中不下钻、也**不递减遍历预算**：这一层没发生任何 IPC
                                        continue
                                    }
                                }
                                enqueue(
                                    WalkTask(
                                        treeUri = task.treeUri,
                                        docId = childDocId,
                                        inheritedHidden = hidden,
                                        lastModifiedMs = childLmMs,
                                        depth = childDepth,
                                    ),
                                )
                            } else if (hidden) {
                                if (isJunkSuffix(name)) continue
                                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                                if (sizeCol >= 0 && size <= 0L) continue
                                val id = hiddenDocumentId(childDocId)
                                if (!synchronized(seenLock) { seen.add(id) }) continue
                                // 未变项复用基线：跳过文件头嗅探（逐文件 I/O）
                                val lmSec = if (lmCol >= 0) cursor.getLong(lmCol) / 1000 else 0L
                                val prev = baseline[id]
                                if (prev != null && prev.sizeBytes == size && prev.lastModifiedSec == lmSec && prev.title == name) {
                                    // 未变项也进索引：它的 mime 只能取基线（首扫时嗅出来/MediaStore 给的），
                                    // 否则下一轮复用命中后又要重新 readHead 一遍——索引就白接了
                                    dirItems.add(indexedOf(childDocId, name, prev.mimeType, size, lmSec))
                                    found.add(prev)
                                    discovered.incrementAndGet()
                                    continue
                                }
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(task.treeUri, childDocId)
                                // mime 已是 video/*、audio/* 就用它；否则（application/octet-stream
                                // 之类）按文件头嗅探——认不出的直接跳过，不往库里塞"点开必失败"的项
                                val effectiveMime = if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                                    mime
                                } else {
                                    // 开流读头之前再过一次取消点：这才是真正的长 I/O
                                    ensureNotCancelled()
                                    readHead(docUri, ContainerSniffer.HEAD_BYTES)
                                        ?.let { ContainerSniffer.sniff(it) }
                                        ?: continue
                                }
                                // 索引只存**已判定**的 mime：下一轮复用就不会再为它开流读头
                                dirItems.add(indexedOf(childDocId, name, effectiveMime, size, lmSec))
                                found.add(
                                    MediaItem(
                                        id = id,
                                        sourceId = sourceId,
                                        title = name,
                                        uri = docUri.toString(),
                                        mimeType = effectiveMime,
                                        sizeBytes = size,
                                        // SAF 的 LAST_MODIFIED 是毫秒；断点指纹统一按秒（与 MediaStore 同单位）
                                        lastModifiedSec = lmSec,
                                        addedAtMs = if (lmCol >= 0) cursor.getLong(lmCol) else 0L,
                                        // 同 queryScope：封面统一由后台封面泵 coverPump 补齐
                                        coverUri = null,
                                        folder = folderOfDocument(childDocId),
                                    ),
                                )
                                discovered.incrementAndGet()
                            }
                        }
                    }
                // W3：只 record「走完整、且没被取消」的目录。leaf = 没有直接子目录（含被 E198 拒的），
                // 依据是契约 §3 的正确性边界：非叶目录的深层新增只改子目录 mtime、祖先自身 mtime 不动，
                // 所以非叶目录一律不给复用（下一轮照常下钻）。
                if (walkIndexEnabled && !partial && !cancelRequested) {
                    walkIndex.record(
                        ScanWalkIndex.Entry(
                            docId = task.docId,
                            treeUri = task.treeUri.toString(),
                            lastModifiedMs = task.lastModifiedMs,
                            inheritedHidden = task.inheritedHidden,
                            leaf = childDirs == 0,
                            items = dirItems,
                            walkedAtMs = walkNowMs,
                        ),
                    )
                    recordedDirs.incrementAndGet()
                }
            }

            // tree 根预解析（顺序、无 IPC）。E198：只有用户显式选到隐藏目录
            // （tree 根路径含 `.` 分段）时整支才算显式选中、允许下钻隐藏子目录。
            treeUriStrs.forEachIndexed { index, treeStr ->
                if (budget.get() <= 0) {
                    if (index < treeUriStrs.lastIndex) exhausted.set(true)
                    return@forEachIndexed
                }
                val treeUri = runCatching { treeStr.toUri() }.getOrNull() ?: run {
                    warnings.add(WARN_HIDDEN_WALK_FAILED)
                    return@forEachIndexed
                }
                val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                    ?: run {
                        warnings.add(WARN_HIDDEN_WALK_FAILED)
                        return@forEachIndexed
                    }
                val rootHidden = treeRootEntersHiddenPath(treeDocId)
                // 父子 tree 重叠授权时同一个根会来两次，这里与子目录走同一份 dirSeen
                val rootKey = treeDocId + "|" + rootHidden
                if (!synchronized(dirSeenLock) { dirSeen.add(rootKey) }) {
                    dupSkipped.incrementAndGet()
                    return@forEachIndexed
                }
                enqueue(WalkTask(treeUri, treeDocId, rootHidden, lastModifiedMs = 0L, depth = 0))
            }
            if (pending.get() == 0) {
                tasks.close()
            } else {
                // 车道数走**闭环调参**（此前是固定 ioWorkers()）：起跑值来自上一轮落盘的
                // presets（缺失时用 ioWorkers()），上限见 LaneBudget.capOf。这里起 cap 条常驻
                // worker，每处理一个目录先申请一个许可——「线程数」与「实际并发度」解耦：
                // 存储/Provider 慢的时候靠许可把并发压回去，不会一堆线程空转抢锁
                // （真机 1200 次串行 readHead 时多开车道确实毫无增益）。
                val walkStart = lanes.initial(LaneKind.WALK, ScanParallelism.ioWorkers(lanes.cores))
                val walkCap = lanes.capOf(LaneKind.WALK, walkStart)
                val startPermits = walkStart.coerceIn(1, walkCap)
                // 同 runTunedItems：令牌桶许可器，许可数可由调参在运行中增减
                val walkLimiter = ScanLimiter(walkCap, startPermits)
                // 收回许可必须异步：onPermitsChanged 在监视协程里被调用，同步 acquire 会把
                // tick 卡住甚至与等工作协程互锁；收不回来的许可随本段作废，不影响下次扫描。
                val walkShrink = java.util.Collections.synchronizedList(mutableListOf<Job>())
                val walkTuner = ScanLaneTuner(
                    kind = LaneKind.WALK,
                    initialPermits = startPermits,
                    cap = walkCap,
                    cores = lanes.cores,
                    onPermitsChanged = { delta ->
                        if (delta > 0) {
                            repeat(delta) { walkLimiter.release() }
                        } else if (delta < 0) {
                            walkShrink += launch(dispatchers.io) { repeat(-delta) { walkLimiter.acquire() } }
                        }
                    },
                    log = { line -> ScanTrace.log(line) },
                )
                val walkSampler = LaneWindowSampler(LaneKind.WALK, lanes.cores, walkTuner) { line ->
                    ScanTrace.log(line)
                }
                val walkStartedAt = System.nanoTime()
                // 忙时累加：「实际并发度」按 串行单位耗时 ÷ 墙钟 算（IPC 型负载，CPU util 不代表并发）。
                // 只累加**拿到许可后真正干活**的时间，排队等许可不算——否则并发度会虚高到 worker 数。
                val busyNs = AtomicLong(0L)
                val walkMonitor = launch(dispatchers.default) {
                    var window = 0
                    while (isActive) {
                        delay(ScanLaneTuner.DEFAULT_WINDOW_MS)
                        walkTuner.tick()
                        window++
                        walkSampler.tick(window)
                    }
                }
                val workers = List(walkCap) {
                    launch(dispatchers.io) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val task = tasks.receiveCatching().getOrNull() ?: break
                            try {
                                walkLimiter.withPermit {
                                    // 计时必须**在许可内**：taskStart 若在 withPermit 之前取，排队等许可
                                    // 的时间也会被算成忙时，achievedParallelism 就会虚高到接近 worker 数
                                    // （cap），把「实际吃满几个许可」这个结论直接毁掉。
                                    val taskStart = System.nanoTime()
                                    try {
                                        processTask(task)
                                    } finally {
                                        busyNs.addAndGet(System.nanoTime() - taskStart)
                                    }
                                }
                            } finally {
                                walkSampler.onItemDone()
                                walkTuner.onItemDone()
                                if (pending.decrementAndGet() == 0) tasks.close()
                                maybeReport()
                            }
                        }
                    }
                }
                // 收尾顺序：队列已关 → worker 全部 join → 才取消监视协程（否则最后一窗样本丢失）
                try {
                    workers.joinAll()
                } finally {
                    walkMonitor.cancel()
                    walkShrink.toList().forEach { it.cancel() }
                }
                val walkSummary = walkTuner.finish()
                lanes.remember(LaneKind.WALK, walkSummary)
                val wallNs = (System.nanoTime() - walkStartedAt).coerceAtLeast(1L)
                ScanTrace.log(walkSampler.finishLine(walkSummary))
                ScanTrace.log(
                    "phase=WALK perf util=" +
                        (if (walkSummary.utilAvg < 0.0) "n/a" else String.format(Locale.US, "%.2f", walkSummary.utilAvg)) +
                        " achievedParallelism=" + fmtParallelism(busyNs.get().toDouble() / wallNs) +
                        // 上界互证：achievedParallelism 不该超过收尾许可数。两个数对不上（明显更小）
                        // → 这一轮大部分时间在等许可排队，扩容没换来吞吐，看 tuner 的 ceiling 即可。
                        " permits=" + walkSummary.permits +
                        " dirs=" + dirsDone.get() +
                        " elapsedMs=" + wallNs / 1_000_000,
                )
            }
        }
        if (exhausted.get()) warnings.add("隐藏目录遍历预算耗尽，本次扫描不完整")
        // W4：只有「完整走完 + 无警告 + 未取消」才落盘。取消/预算耗尽时索引残缺或没覆盖全树，
        // 落盘等于让下一轮把残缺当完整结果复用（漏文件）；不落盘的代价只是下一轮重走一遍。
        // 反过来说，取消也不要 retainOnly：load() 每轮先 clear() 再 putAll(磁盘)，内存残留自有去处。
        val completeWalk = !exhausted.get() && warnings.isEmpty() && !cancelRequested
        if (walkIndexEnabled && completeWalk) walkIndex.save()
        // 真机判据：dirs 应≈实际目录数；是它的整数倍且 dupSkipped>0 → 重叠/重复入队；
        // dirs≈预算上限且 budgetLeft≈0 → provider 成环（深度/去重没兜住）
        ScanTrace.log(
            "hidden walk done dirs=" + dirsDone.get() +
                " entries=" + discovered.get() +
                " dupSkipped=" + dupSkipped.get() +
                " skipDepth=" + skipDepth.get() +
                " maxDepth=" + maxDepth.get() +
                " budgetLeft=" + budget.get() +
                " exhausted=" + exhausted.get() +
                " index=" + (if (walkIndexEnabled) "on" else "off") +
                " reused=" + reusedDirs.get() +
                " recorded=" + recordedDirs.get() +
                " saved=" + completeWalk +
                " ttlMin=" + (walkIndexTtlMs / 60_000L),
        )
        // 稳定排序：walk 用多条车道并发收集，`found` 的顺序由完成时序决定——而提交段的
        // `items != previousList` 是**保序**比较（见 scanInternal 的快照重写判定）。不排序 →
        // 每轮顺序都不同 → 每轮都判定「变了」→ 每轮重写几 MB 快照 JSON，增量反而更慢。
        // 按 addedAtMs 降序 + id 升序：id 唯一，排序结果完全确定。
        val sorted = found.sortedWith(
            compareByDescending<MediaItem> { it.addedAtMs }.thenBy { it.id },
        )
        return QuerySlice(sorted, warnings.toList().distinct())
    }

    /** 缩略图缓存：扫描时落盘为 JPEG，之后封面直接读文件 */
    private fun thumbnailFor(id: Long, videoUri: Uri): String? = runCatching {
        writeThumbnail("$id") {
            // Q+ 的系统缩略图并非对所有容器都有（部分 OEM 的 AVI/MKV 会抛异常或给 null）——
            // 此前只兜低版本，失败即永久占位图；这里把抽帧兜底提到两条路共用
            val bySystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(videoUri, Size(480, 270), null) }
                    .getOrNull()
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
            bySystem ?: createFrameFallback(videoUri)
        }
    }.getOrNull()

    /**
     * SAF/文档 URI 的封面（L14）：隐藏项没有 MediaStore 长整型 id，走不了旧缩略图接口。
     * Q+ 用 `loadThumbnail`，更低版本直接抽首帧；键用项 id（`saf-…`），与 MediaStore 的
     * 数字键天然分开，不会互相覆盖。
     */
    private fun thumbnailFor(key: String, docUri: Uri): String? = runCatching {
        writeThumbnail(key) {
            // 文档 URI 的 loadThumbnail 在部分 provider/版本上不可用（抛异常或返回 null），
            // 兜一句 MediaMetadataRetriever 抽首帧，避免隐藏目录里的项永远只有占位图
            val bySystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(docUri, Size(480, 270), null) }
                    .getOrNull()
            } else {
                null
            }
            bySystem ?: createFrameFallback(docUri)
        }
    }.getOrNull()

    /** 缩略图落盘：文件名命中即复用（0 字节视为坏图），否则用 [produce] 抽帧写 JPEG */
    private fun writeThumbnail(key: String, produce: () -> Bitmap?): String? {
        val dir = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val file = File(dir, "$key.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bitmap = produce() ?: return null
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        }
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * 音频封面：Android 没有音频系统缩略图接口，只能走相册封面 URI。
     *
     * 这里**只给 URI、不落盘**——与视频缩略图不同，媒体库大多只有一张专辑图，
     * 扫描期为每首歌各存一份完全相同的 JPEG 纯属浪费空间。
     * albumId<=0（无专辑信息）时返回 null，由 UI 出占位图。
     */
    private fun audioCoverUri(albumId: Long): String? =
        if (albumId > 0) {
            ContentUris.withAppendedId(ALBUM_ART_URI.toUri(), albumId).toString()
        } else {
            null
        }

    /** 旧系统无系统缩略图时的兜底：MediaMetadataRetriever 抽首帧 */
    private fun createFrameFallback(videoUri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()

// ================= 批次 8｜容器嗅探与 .cue 分轨（§12.4） =================

/** 读文件头（上限 [maxBytes]）；任何失败返回 null——嗅探永远 fail-open */
private fun readHead(uri: android.net.Uri, maxBytes: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val buf = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            val n = stream.read(buf, read, maxBytes - read)
            if (n <= 0) break
            read += n
        }
        buf.copyOf(read)
    }
}.getOrNull()

/**
 * 容器嗅探校正（§12.4「错扩展名」）：MediaStore 的 mimeType 按扩展名推，扩展名错了
 * 它跟着错。文件头**确凿**认出容器且与原 mime 不同源时才覆盖；认不出一律保留原值
 * （嗅探是 fail-open 的信息增益，不是权威重写）。
 */
private fun sniffMimeCorrection(item: MediaItem): MediaItem {
    // .cue 分轨与原文件同 uri，逐轨重复嗅探纯属浪费 I/O
    if (item.isClipped) return item
    // A25：MediaStore 已给出可信 media mime 时不再逐文件 openInputStream——大库首扫
    // 最贵的一步就是给每条已索引媒体开流读 64 字节。needsSniff 只对缺失/通用二进制/
    // 非媒体 mime 放行；已知媒体 mime 的“错扩展名”以 MediaStore 为准（失败面回到
    // 扩展名错误本身，交给播放器 extractor 兜底）。
    if (!ContainerSniffer.needsSniff(item.mimeType)) return item
    val head = readHead(item.uri.toUri(), ContainerSniffer.HEAD_BYTES) ?: return item
    val sniffed = ContainerSniffer.sniff(head) ?: return item
    if (sniffed.equals(item.mimeType, true)) return item
    if (sameContainerFamily(sniffed, item.mimeType)) return item
    return item.copy(mimeType = sniffed)
}

/** 同族容器视为一致（audio/x-wav ≡ audio/wav 这类别名），避免无意义的 mime 抖动 */
private fun sameContainerFamily(a: String, b: String): Boolean {
    val x = a.lowercase(); val y = b.lowercase()
    if (x == y) return true
    val wav = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    return x in wav && y in wav
}

/**
 * 「所有文件访问」是否已授予：API 30 以下没有这个能力。
 *
 * 单独抽出来用 [Build.VERSION.SDK_INT] 守卫，是因为能力门控本身走可注入的 [sdkInt]
 * （测试要模拟旧系统），而 lint 的 NewApi 数据流只认 `Build.VERSION.SDK_INT`。
 */
private fun allFilesAccessGranted(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        false
    }

/**
 * 拾取 MediaStore 不索引的无扩展名/未知类型文件（§12.4「扩展名缺失」）。
 *
 * 范围仍是授权目录（`MediaStore.Files` 集合 + `media_type = 0`，即非媒体行）；
 * 可见性需要"所有文件访问"（批次 6 的实测结论：只持 READ_MEDIA_* 时这些行
 * 根本不可见）——没授予就跳过，整条路径 fail-open。嗅探认不出容器的行不进库：
 * 没有任何 Extractor 认领的文件，塞进库只会变成"点开必失败"的垃圾条目。
 */
// VOLUME_EXTERNAL 是 API 29 常量（String，会被内联）；本函数先过 sdkInt < R 门控，
// 旧系统不会执行到用它的那行，运行时安全。
@SuppressLint("InlinedApi")
private suspend fun sniffUnknownFiles(
    scopes: List<ScanScope>,
    baseline: Map<String, MediaItem>,
    lanes: LaneBudget,
): QuerySlice<MediaItem> {
    // 能力门控（不是失败）：旧系统 / 无「所有文件访问」时这条分支本就不该覆盖任何范围
    if (sdkInt < Build.VERSION_CODES.R) return QuerySlice(emptyList())
    if (!allFilesAccessGranted()) {
        return QuerySlice(emptyList())
    }
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val found: MutableList<MediaItem> = java.util.Collections.synchronizedList(mutableListOf())
    val warnings = mutableListOf<String>()
    // 去重：目录范围父子重叠（同时授权父目录与子目录）时，同一行会被逐 scope 查两次——
    // 此前 `out +=` 无判重，同一文件以同一 id 进列表两次（用户实报"扫描会出现双份文件"）
    val seen = HashSet<String>()
    // 第一段：只做游标遍历（MediaStore IPC），把需要读文件头的行物化到内存。
    // 逐行 readHead 才是这条分支最贵的一半，交给第二段按车道并发。
    val sniffRows = mutableListOf<UnknownRow>()
    scopes.forEach { scope ->
        // Unresolvable 一律跳过（与 [queryScope] 同一道闸）：scopeSelection 对它返回
        // (null, null)，而 (null, null) 与"用户选的就是主存储根目录＝全量扫描"同形，
        // 拼进 selection 就只剩 `MEDIA_TYPE = 0` —— 那条查询会越过授权目录扫遍整机
        // 非媒体行，正违反 scopeSelection 注释里"任何分支都不得回退全量扫描"的不变量。
        if (scope is ScanScope.Unresolvable) return@forEach
        val (scopeSel, scopeArgs) = scopeSelection(
            scope,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = 0" +
            (scopeSel?.let { " AND $it" } ?: "")
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                selection,
                scopeArgs,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    // 游标段也是长循环（大库上千行）：取消在这里就要见效，不必等第二段
                    ensureNotCancelled()
                    if (idCol < 0 || nameCol < 0) continue
                    val id = "msf-${cursor.getLong(idCol)}"
                    if (!seen.add(id)) continue
                    val name = cursor.getString(nameCol).orEmpty()
                    val title = name.substringBeforeLast('.').ifBlank { name }
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val addedAt = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L
                    // 未变项（改名/改大小/改加入时间之外都不变）复用基线：跳过文件头读取
                    val prev = baseline[id]
                    if (prev != null && prev.title == title && prev.sizeBytes == size && prev.addedAtMs == addedAt) {
                        found.add(prev)
                        continue
                    }
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString()
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    sniffRows.add(
                        UnknownRow(
                            id = id,
                            name = name,
                            title = title,
                            size = size,
                            addedAt = addedAt,
                            uri = uri,
                            folder = folderOf(
                                dataPath = dataPath,
                                relativePath = if (relCol >= 0) cursor.getString(relCol) else null,
                            ),
                            // needsSniff 收窄：扩展名已能判定容器时不必开流读头
                            mimeByName = mimeOfFileName(name),
                        ),
                    )
                }
            }
        }.onFailure { t ->
            if (t is CancellationException) throw t
            Log.w(TAG, "无扩展名文件拾取失败（本次扫描不完整）", t)
            warnings += "无扩展名文件扫描不完整"
        }
    }
    // 第二段：动态许可并发（读 64 字节头与文件大小无关，等权调度即可，不按字节数装填）。
    // 起跑值/上限/许可增减都由闭环调参决定：Provider 读头快就自动多开，撞上内核串行化自动收回。
    if (sniffRows.isNotEmpty()) {
        val sniffStart = lanes.initial(LaneKind.SNIFF, ScanParallelism.ioWorkers(lanes.cores))
        val summary = runTunedItems(lanes, LaneKind.SNIFF, sniffRows, sniffStart) { row ->
            // 扩展名能定容器的直接采用；否则读 64 字节头嗅探，认不出即跳过（认不出的不进库）
            val mime = row.mimeByName ?: ContainerSniffer.sniff(
                readHead(row.uri.toUri(), ContainerSniffer.HEAD_BYTES) ?: return@runTunedItems,
            ) ?: return@runTunedItems
            found.add(
                MediaItem(
                    id = row.id,
                    sourceId = sourceId,
                    title = row.title,
                    uri = row.uri,
                    mimeType = mime,
                    sizeBytes = row.size,
                    addedAtMs = row.addedAt,
                    folder = row.folder,
                ),
            )
        }
        lanes.remember(LaneKind.SNIFF, summary)
    }
    return QuerySlice(found.toList(), warnings.distinct())
}

/**
 * `.cue` 分轨展开（§12.4 / §9.7 G4）：同名 `<音频名>.cue` 在场时，把单文件项
 * 展开为"一张专辑 N 轨"——每轨指向同一音频文件，窗口由 `clipStartUs/clipEndUs`
 * 圈定（引擎挂 ClippingConfiguration，G4 ④：WAV/FLAC 可 seek，无需
 * `allowUnseekableMedia` 特批）。cue 缺失/坏表/无权限读取时**原样返回单文件项**
 * ——分轨是增益不是门槛，绝不因为展开失败让文件从库里消失。
 *
 * G4 前置核对：① 仅听音频（批次 3 ✅）② 队列/选集 UI（批次 1 ✅）
 * ③ cue 文本 GB18030 转码（复用 SubtitleCharset ✅）④ allowUnseekableMedia 取舍（本注释）。
 */
private fun cueTracksFor(file: MediaItem, dataPath: String): List<MediaItem> {
    if (file.kind != MediaKind.AUDIO || file.durationMs <= 0L || dataPath.isBlank()) {
        return listOf(file)
    }
    val dir = dataPath.substringBeforeLast('/')
    val stem = dataPath.substringAfterLast('/').substringBeforeLast('.')
    val cueFile = File(dir, "$stem.cue")
    if (!cueFile.isFile) return listOf(file)
    val text = runCatching {
        // CUE 是纯文本但 GB18030 编码极常见（G4 ③）——复用字幕字符集试探
        val bytes = cueFile.readBytes()
        if (bytes.size > 1 shl 20) return listOf(file)
        SubtitleCharset.decode(bytes).text
    }.getOrNull() ?: return listOf(file)
    val sheet = CueSheet.parse(text) ?: return listOf(file)
    val fileEndUs = file.durationMs * 1000L
    return sheet.tracks.mapIndexedNotNull { index, track ->
        val endUs = (sheet.tracks.getOrNull(index + 1)?.startUs ?: fileEndUs)
            .coerceAtLeast(track.startUs)
        if (track.startUs >= endUs) return@mapIndexedNotNull null
        val label = track.title?.takeIf { it.isNotBlank() } ?: "Track %02d".format(track.number)
        val numberText = "%02d".format(track.number)
        file.copy(
            id = "${file.id}#t${track.number}",
            title = label,
            displayTitle = "${file.title} · $numberText · $label",
            durationMs = (endUs - track.startUs) / 1000L,
            clipStartUs = track.startUs,
            // 最后一轨到文件尾：clipEndUs 归 0（契约里 0 = 不设终点）
            clipEndUs = if (endUs >= fileEndUs) 0L else endUs,
        )
    }.ifEmpty { listOf(file) }
}

}

private val JUNK_SUFFIXES = listOf(".part", ".tmp", ".crdownload", ".!qb", ".downloading")

/** L14 遍历预算：单次扫描最多访问的文档数上限（防巨型目录把扫描拖成分钟级） */
private const val HIDDEN_WALK_BUDGET = 20_000

/** I/O 型车道（遍历/嗅探）的许可上限：8 核设备的 ioWorkers() 是 12，翻倍后 24 已足够覆盖富余。 */
private const val WALK_LANE_CAP_MAX = 24

/** 解码型车道（抽帧/读时长）的许可上限：再往上只会换来 GC 抖动。 */
private const val ITEM_LANE_CAP_MAX = 8

/**
 * 目录递归深度上限。
 *
 * 正常用户目录树深不过十几层；超过这个数只可能是 provider 把父目录/自身当 child 返回
 * （成环）或恶意构造的极深路径。没有这道闸时唯一兜底是 [HIDDEN_WALK_BUDGET] ——
 * 那意味着 2 万次 binder IPC 之后才停，一次扫描被拖到分钟级。
 */
private const val MAX_WALK_DEPTH = 64

/** L14 下钻黑名单：这些隐藏目录只装系统缓存 / 回收站，不可能有用户媒体 */
private val HIDDEN_DENY_DIRS = setOf(
    ".thumbnails", ".trash", ".Trash", ".recycle", ".trashbin", ".cache", ".temp",
)

/**
 * 文件名 → 容器 mime（无扩展名拾取分支的 needsSniff 收窄用）。
 *
 * 认出扩展名（表内）时直接给 mime，调用方省掉一次 openInputStream 读头；
 * 认不出（含无扩展名、点号开头的隐藏名）返回 null，仍走文件头嗅探——与旧实现一致。
 */
private fun mimeOfFileName(name: String): String? {
    // 点号开头的文件名（".mp4"）不以扩展名论：那不是扩展名，宁可读头确认
    if (name.startsWith('.')) return null
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext.isEmpty()) null else UNKNOWN_FILE_EXT_MIME[ext]
}

/** 半成品后缀（与 `isJunkFile` 共用 [JUNK_SUFFIXES]）：隐藏目录里的 .part 之类不该进库 */
private fun isJunkSuffix(name: String): Boolean {
    val lower = name.lowercase()
    return JUNK_SUFFIXES.any { lower.endsWith(it) }
}

/** 条目 id 的三种前缀：MediaStore 视频 / MediaStore 音频（含 .cue 分轨）/ SAF 隐藏目录文档 */
private const val VIDEO_ID_PREFIX = "ms-"
private const val AUDIO_ID_PREFIX = "msa-"
private const val HIDDEN_ID_PREFIX = "saf-"

/**
 * HIDDEN_WALK 阶段的上报节奏：每 16 个目录或 100ms，谁先到就报一次。
 *
 * 上限决定了 UI 抖动的上限；大目录树上不按条数报（一次 children 查询可能几千行，
 * 按行报会把 StateFlow 刷成噪音）。
 */
private const val PROGRESS_DIR_EVERY = 16
private const val PROGRESS_EVERY_MS = 100L

/** 隐藏目录遍历失败的统一告警文案（完整性判定按字面量透传到扫描摘要，勿改） */
private const val WARN_HIDDEN_WALK_FAILED = "隐藏目录遍历失败，本次扫描不完整"

/**
 * E205：封面回写的代际安全合并（[LocalMediaSource] 的 applyCovers 用）。
 *
 * 泵的回写（读列表 → 合并封面 → 写回）与扫描提交段并发：无锁 RMW 在「读之后、写之前」
 * 被提交插队，会把旧列表整份覆盖掉刚提交的新扫描结果（本轮的增删、AVI 时长补读全部
 * 回退）。写回必须发生在与读取时同一代际的串行段内（[ScanCommitGate.commitIfCurrent]
 * 的复核+执行同锁）；被提交插队（返回 null）就对着新列表重试合并——封面不丢、新结果
 * 也不被覆盖。重试上限只是防活锁兜底（invalidate 仅在目录集变化时发生，与 250ms 节流
 * 的回写撞满 8 次几乎不可能）；用尽则放弃本批，等下一次 flush 或扫描自然重放。
 *
 * @return 是否成功发布（无变化 / 写回成功为 true；重试用尽为 false）
 */
internal suspend fun applyCoversUnderCommitGate(
    gate: ScanCommitGate,
    covers: Map<String, String>,
    currentItems: suspend () -> List<MediaItem>,
    publish: suspend (List<MediaItem>) -> Unit,
    maxAttempts: Int = 8,
): Boolean {
    if (covers.isEmpty()) return true
    var attempt = 0
    while (true) {
        val generation = gate.currentGeneration()
        val current = currentItems()
        val merged = current.map { item -> covers[item.id]?.let { item.copy(coverUri = it) } ?: item }
        if (merged == current) return true
        if (gate.commitIfCurrent(generation) { publish(merged) } != null) return true
        if (++attempt >= maxAttempts) {
            Log.w(TAG, "封面回写代际重试用尽（$attempt 次），本批 ${covers.size} 条待下轮重放")
            return false
        }
    }
}

/** 隐藏目录遍历的 projection（SAF children 查询的固定五列） */
private val HIDDEN_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)

/**
 * 扩展名 → 容器 mime（无扩展名拾取分支的 needsSniff 收窄用）。
 *
 * 该分支原本对**每一行** `openInputStream` 读 64 字节头。扩展名已能判定容器时
 * （`movie.mp4` 这类被 MediaStore 漏判成 `media_type = 0` 的行）直接定容器，
 * 省掉一次开流；认不出的扩展名（含无扩展名）仍走文件头嗅探，与旧实现一致。
 * 只收"扩展名等于容器"的项，别名/有歧义的不收。
 */
private val UNKNOWN_FILE_EXT_MIME = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "mov" to "video/quicktime",
    "mkv" to "video/x-matroska",
    "webm" to "video/webm",
    "avi" to "video/avi",
    "ts" to "video/mp2t",
    "m2ts" to "video/mp2t",
    "flv" to "video/x-flv",
    "3gp" to "video/3gpp",
    "wmv" to "video/x-ms-wmv",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "opus" to "audio/opus",
    "amr" to "audio/amr",
    "mid" to "audio/midi",
)

/** 缩略图文件名键：MediaStore 项剥 `ms-`（沿用既有缓存名），SAF 项直接用整 id */
private fun thumbKeyOf(id: String): String? = when {
    id.startsWith(VIDEO_ID_PREFIX) -> id.removePrefix(VIDEO_ID_PREFIX)
    id.startsWith(HIDDEN_ID_PREFIX) -> id
    else -> null
}

/**
 * L14：SAF docId → 展示用相对目录（如 "Movies/.nphidden"）；卷根目录返回 null。
 *
 * `primary:Movies/.nphidden/secret.mp4` → "Movies/.nphidden"，与 MediaStore 的
 * RELATIVE_PATH 同口径（相对媒体根、不带首尾斜杠），文件夹树与同目录队列直接可用。
 */
internal fun folderOfDocument(docId: String): String? =
    docId.substringAfter(':', docId)
        .trim('/')
        .substringBeforeLast('/', "")
        .trim('/')
        .ifEmpty { null }

/**
 * 建一条目录遍历索引条目。
 *
 * 两个字段必须注意：`mime` 只传**已判定**的值（video/audio 的具体类型），下一轮复用命中后
 * 才能免掉一次开流读头；`lastModifiedSec` 是 SAF 毫秒时间戳整除 1000 的秒值（与 MediaStore 同单位，
 * 断点指纹也按秒比较）。
 */
private fun indexedOf(
    docId: String,
    name: String,
    mime: String?,
    sizeBytes: Long,
    lastModifiedSec: Long,
): ScanWalkIndex.Indexed = ScanWalkIndex.Indexed(
    docId = docId,
    name = name,
    mime = mime,
    sizeBytes = sizeBytes,
    lastModifiedSec = lastModifiedSec,
    // walk 只收「路径含 . 前缀分段」的条目，到这里的都是隐藏项
    hidden = true,
)

/**
 * L14：SAF docId → 稳定短 id（`saf-` + SHA-1 截断 16 位十六进制）。
 *
 * 断点/收藏/缩略图都认这个 id，所以必须**跨扫描稳定**（docId 不变则 id 不变），
 * 且是文件系统安全的短名（既进 Room 键，也进 `video_thumbs/<key>.jpg`）。
 */
internal fun hiddenDocumentId(docId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(docId.toByteArray())
    return HIDDEN_ID_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * 数据保护（E198）：用户显式选择的 SAF tree 根路径是否进入隐藏目录（`.` 前缀分段）。
 *
 * 只有把 tree 直接选到 `.foo`（或其下，如 `Movies/.nphidden/series`）时，整支才算
 * "显式选择"，允许下钻隐藏子目录；选的是 `Movies` 这类普通根，其下 `.xxx` 子目录不越权遍历。
 */
internal fun treeRootEntersHiddenPath(treeDocId: String): Boolean =
    treeDocId.substringAfter(':', treeDocId)
        .split('/')
        .any { it.startsWith(".") }

/**
 * 数据保护（E198）：是否下钻一个子目录。
 *
 * 命中黑名单不下钻；`.` 前缀目录**仅当已处于显式选中的隐藏分支内**（[inheritedHidden]）才下钻——
 * 普通授权根（如 `Movies`）下的隐藏子目录一律不进（MediaStore 本就跳过它们，越权遍历违背数据保护）。
 */
internal fun shouldDescendDirectory(
    name: String,
    inheritedHidden: Boolean,
    denyDirs: Set<String>,
): Boolean {
    if (name in denyDirs) return false
    if (name.startsWith(".")) return inheritedHidden
    return true
}

private const val TAG = "LocalMediaSource"

/**
 * 相册封面集合 URI。系统未公开常量（`MediaStore.Audio.Albums.ALBUM_ART` 不是它），
 * 但这是 MediaProvider 长期稳定的公开约定地址，读相册图只能用它。
 */
private const val ALBUM_ART_URI = "content://media/external/audio/albumart"

/** E67：scan() 命中并发守卫时的最长等待时长与轮询间隔 */
private const val SCAN_WAIT_TIMEOUT_MS = 10_000L
private const val SCAN_WAIT_POLL_MS = 100L

/** A5/P2-2：回前台补扫的最小间隔——比它更短的往返不值得再跑一次全量 MediaStore 查询 */
private const val MIN_RESCAN_INTERVAL_MS = 30_000L

/**
 * 用户授权目录（SAF tree）解析出的扫描范围（E24）。
 *
 * 关键约束：**任何分支都不得回退"全量扫描整机"**——
 * 非主存储（SD 卡 / OTG）按卷过滤；tree 无法解析时拒绝扫描（安全优先于可用性）。
 */
internal sealed interface ScanScope {
    /** 主存储（`primary:...`）；relativePath=null 表示用户选的就是存储根目录（全量是明确意图） */
    data class Primary(val relativePath: String?) : ScanScope

    /** 可移动卷（SD 卡 / OTG，如 `1234-5678:Movies`）；relativePath=null 表示卷根目录 */
    data class Volume(val name: String, val relativePath: String?) : ScanScope

    /** 无法解析（异常 / 非预期格式）→ 拒绝扫描 */
    data object Unresolvable : ScanScope
}

/** 纯函数：SAF tree docId → [ScanScope]（供单测） */
internal fun parseScanScope(docId: String): ScanScope {
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2 || parts[0].isBlank()) return ScanScope.Unresolvable
    val relativePath = parts[1].trim('/').ifEmpty { null }
    return if (parts[0] == "primary") {
        ScanScope.Primary(relativePath)
    } else {
        ScanScope.Volume(parts[0], relativePath)
    }
}

internal fun scanScopeFromTree(treeUriStr: String): ScanScope = try {
    parseScanScope(DocumentsContract.getTreeDocumentId(treeUriStr.toUri()))
} catch (_: Exception) {
    ScanScope.Unresolvable
}

/** 设置页展示用完整描述（如 "内部存储/Movies"）；无法解析时为 null */
internal fun describeTree(treeUriStr: String): String? = when (val scope = scanScopeFromTree(treeUriStr)) {
    is ScanScope.Primary -> scope.relativePath?.let { "内部存储/$it" } ?: "内部存储（根目录）"
    is ScanScope.Volume -> scope.relativePath?.let { "${scope.name}/$it" } ?: scope.name
    ScanScope.Unresolvable -> null
}
