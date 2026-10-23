package com.noneplayer.plugins.scanner.local

import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面失败结论的持久化落点（E203-B → E203-A 的 [ScanCoverNegativeCache]）。
 *
 * 之所以让 [CoverPump] 只依赖这个三方法接口：否定缓存与封面泵是两个人并行开发的（§8.4 归
 * scanner-index）。接上真实缓存只需要在 [LocalMediaSource] 里写一个转发的适配器，泵本身不用动；
 * 单测也能用内存假实现（见 CoverPumpTest）。
 */
internal interface CoverNegativeSink {
    /** 该文件指纹此前抽帧失败且未过 TTL（7 天）→ 本次不必再试。 */
    fun isFailed(key: String, nowMs: Long): Boolean

    /** 记一条失败结论；返回是否真的写进去了（重复记录返回 false 或 true 都无害）。 */
    fun markFailed(key: String, nowMs: Long): Boolean

    /** 显式落盘（真实实现内部还有 ≥1s 节流，见 §8.4）。 */
    fun flush()
}

/**
 * 封面后台泵（E203-B，契约 §8.1-§8.3）。
 *
 * **为什么存在**：旧实现把「逐项抽帧」写在 `scanInternal` 的提交段之前（[LocalMediaSource] 的
 * attachCovers），1283 项语料上这一段的耗时 ≈ 9.8s，用户看到的「扫描慢」有一大半其实是封面。
 * 现在扫描只负责遍历 + 提交，封面交给本类在后台补齐：扫描收尾不被抽帧拖住，抽帧也不会因为
 * 扫描结束而停——同一进程里继续跑到排空为止。
 *
 * **状态机**（单实例挂在 [LocalMediaSource] 上，跨扫描存活）：
 *  - [publish]：扫描发布了一版列表 → 登记「缺封面」的条目（跳过 `msa-` 音频、已有封面文件、
 *    已有失败结论的项）。同一条目在一轮泵里只登记一次，重复 publish 幂等。
 *  - [request]：UI 说「我马上要看这些」→ 插到队首（排队顺序 = UI 传入顺序），去重幂等。
 *  - [scanFinished]：扫描成功收尾。真正的「放开满速」由监视协程在 [isScanning] 变 false 后完成
 *    （许可从 `max(2, decodeWorkers/2)` 提到 `decodeWorkers`），避免和扫描抢 IPC。
 *  - [cancel]：新扫描开始 / 用户取消扫描 → 清空队列并停泵。**不写任何失败结论**：被取消的条目
 *    只是「这轮没轮到」，绝不是「抽不出封面」。
 *
 * **结果回流**：抽帧成功先攒在内存里，由监视协程按 [emitIntervalMs]（默认 250ms）批量回写一次
 * [applyCovers]，而不是每项都 emit——1283 项逐项 emit 会让媒体库页重组上千次。
 *
 * **落盘**：泵排空（静默 [idleMs]）后如果本轮真的补上了封面，调用一次 [saveSnapshot]，
 * 冷启动就能直接带着封面出图、不重抽。扫描进行中不落快照（提交段自己会写，抢同一份文件没意义）。
 *
 * **为什么用动态许可而不是按大小分组**：抽帧成本 ≈ 每项常数（打开 retriever + 取一帧），与文件
 * 字节数弱相关。真机实测（同一语料 1403 项）按字节数 LPT 分车道反而慢 1.55×：5.5GB 的大文件
 * 独占一条车道后，1200 个小条目只能挤在剩下几条里，空转的那条等于白丢并发。
 */
internal class CoverPump(
    private val dispatchers: DispatcherProvider,
    /** 抽帧许可上限 = 扫描收尾后的满速许可数（生产为 [ScanParallelism.decodeWorkers]） */
    private val workers: Int = ScanParallelism.decodeWorkers(),
    /** 逻辑核数，只用于 [ScanLaneTuner] 的利用率归一 */
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    /** 抽帧并落 cache dir；成功返回封面文件绝对路径，失败 null（生产为 coverFor） */
    private val produceCover: (MediaItem) -> String?,
    /** 节流后的批量回写：id → 封面路径（一次调用 = 一批） */
    private val applyCovers: suspend (Map<String, String>) -> Unit,
    /** 当前已发布的列表（写快照用） */
    private val currentItems: () -> List<MediaItem>,
    /** 快照落盘（复用 ScanSnapshotStore） */
    private val saveSnapshot: suspend (List<MediaItem>) -> Unit,
    /** 是否正在扫描：扫描中压低许可、且不落快照（提交段自己写） */
    private val isScanning: () -> Boolean = { false },
    /** 已有失败结论（进程内 memo / 否定缓存）→ 直接丢弃 */
    private val isCoverFailed: (MediaItem) -> Boolean = { false },
    /** 否定缓存落点（E203-A）。null 时失败结论只留在调用方的进程内 memo 里 */
    private val negative: CoverNegativeSink? = null,
    /**
     * 否定缓存 key：`id|sizeBytes|lastModifiedSec`（文件变了要重试，见 §8.4）。
     *
     * **格式只允许有一个定义**：这里直接借 [ScanCoverNegativeCache.keyOf] 的实现，不另写一份，
     * 否则哪天 key 格式变更会分叉成两份互不认的缓存（泵记的失败、缓存查的 key 对不上）。
     */
    private val negativeKeyOf: (MediaItem) -> String? = { item ->
        ScanCoverNegativeCache.keyOf(item.id, item.sizeBytes, item.lastModifiedSec)
    },
    /** 按 id 反查条目：UI 可能在 publish 之前就请求（冷启快照恢复的列表） */
    private val resolveItem: (String) -> MediaItem? = { null },
    /** 音频 id 前缀：音频封面走相册 URI，没有可生成的帧缩略图 */
    private val audioIdPrefix: String = "msa-",
    /** 回写节流窗口（契约要求 200–300ms 一批） */
    private val emitIntervalMs: Long = DEFAULT_EMIT_INTERVAL_MS,
    /** 静默多久算「排空」（契约：静默 2s 或排空后落一次快照） */
    private val idleMs: Long = DEFAULT_IDLE_MS,
    /** 进度行密度：每 N 项 */
    private val progressEvery: Int = DEFAULT_PROGRESS_EVERY,
    /** 进度行密度：每 N 毫秒 */
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** 队列/去重/待回写共用的锁（都只在内存里，临界区极短） */
    private val lock = Any()

    /** 待处理队列（队首 = UI 需求优先） */
    private val queue = ArrayDeque<String>()

    /** 队列内的 id（与 [knownIds] 的区别：出队即移除） */
    private val queuedIds = HashSet<String>()

    /** 本轮已经登记过的 id：publish/request 幂等的依据；一轮结束（队列排空）后清空 */
    private val knownIds = HashSet<String>()

    /** id → 最近一次看到的条目（抽帧要用它的 uri / sizeBytes / lastModifiedSec） */
    private val latest = ConcurrentHashMap<String, MediaItem>()

    /** 已抽好、还没回写进 `_media` 的封面（按 [emitIntervalMs] 成批 flush） */
    private val pendingCovers = LinkedHashMap<String, String>()

    private val done = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val applied = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)

    private var pumpJob: Job? = null

    /** 最近一次泵协程；cancel 会清空 pumpJob，但 join 还需要它（awaitStopped） */
    private var lastPumpJob: Job? = null

    /** 当前这一轮的许可器（只给测试/诊断看，非 null 时即「泵在跑」） */
    @Volatile
    private var limiterNow: ScanLimiter? = null

    private var lastActivityMs = 0L
    private var lastProgressAtMs = 0L
    private var lastProgressTotal = 0

    // ------------------------------------------------------------------
    // 状态机入口
    // ------------------------------------------------------------------

    /**
     * 扫描发布了一版列表：登记缺封面的条目并开泵。
     *
     * 只登记「真的需要抽」的项：音频（`msa-`）走相册封面、已有可用封面文件的直接跳过、
     * 已有失败结论的也不再排队。【幂等】：同一 id 一轮只登记一次。
     */
    fun publish(items: List<MediaItem>) {
        if (items.isEmpty()) return
        var added = 0
        items.forEach { item ->
            latest[item.id] = item
            if (!needsCover(item)) return@forEach
            synchronized(lock) {
                if (knownIds.add(item.id)) {
                    queue.addLast(item.id)
                    queuedIds.add(item.id)
                    added++
                }
            }
        }
        if (added > 0) ensureRunning()
    }

    /**
     * 扫描成功收尾。
     *
     * 这里**不**直接把许可拉满：真正的放开在监视协程里按 [isScanning] 判断一次（扫描 finally
     * 复位后才算结束）。留这一手是为了顺序正确——收尾瞬间扫描可能还在写快照。
     */
    fun scanFinished() {
        if (!isRunning()) return
        ScanTrace.log("cover pump scan finished pending=" + pendingCount())
    }

    /**
     * UI 需求：把 [itemIds] 插到队首（顺序 = 传入顺序），去重幂等。
     *
     * **不阻塞、不在调用线程做 IO**：这里只动内存结构，候选判定（文件是否存在 / 否定缓存）
     * 全部留到工作协程里做——UI 是主线程调用，一次 `File.exists()` 都不该在主线程上跑。
     *
     * 插队是真的插队：泵循环「先拿许可、再出队」，所以不会有任务提前摘出队列干等许可，
     * [request] 之后它必定排在下一次出队的最前面。
     */
    fun request(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        ScanTrace.log("cover pump demand n=" + itemIds.size)
        var added = 0
        synchronized(lock) {
            // 倒序 addFirst：request([a, b, c]) 最终队列是 a, b, c（保持 UI 给的优先级顺序）
            itemIds.asReversed().forEach { id ->
                if (knownIds.add(id)) {
                    queue.addFirst(id)
                    queuedIds.add(id)
                    added++
                }
            }
        }
        if (added > 0) ensureRunning()
    }

    /**
     * 新扫描开始 / 用户取消扫描：清空队列并停泵。
     *
     * 语义边界：**不写失败结论**（不碰否定缓存），因为「这轮没轮到」不等于「抽不出封面」；
     * 已经在抽的那一项即使跑完也不回写（协程已取消），它的缓存文件下次 publish 时会被原地复用。
     */
    fun cancel() {
        var job: Job? = null
        var had = false
        synchronized(lock) {
            had = queue.isNotEmpty() || pumpJob?.isActive == true
            queue.clear()
            queuedIds.clear()
            knownIds.clear()
            pendingCovers.clear()
            job = pumpJob
            pumpJob = null
        }
        job?.cancel()
        if (had) ScanTrace.log("cover pump cancelled")
    }

    /**
     * 停掉泵自己的作用域（`scope.cancel()`）。
     *
     * 当前**没有调用点**，这是有意的：[LocalMediaSource] 是应用级单例，泵的作用域随进程存活，
     * 扫描结束只要 [cancel] 就够了（清队列 + 停本轮），作用域本身没必要销毁——销毁了下次
     * publish/request 就没法再开泵。若将来出现「来源被真正销毁」的路径（例如多来源热插拔、
     * 用户移除本地来源），**必须在那条路径上调用本方法**，否则会留下一个永不退出的泵协程
     * 以及它引用的 [currentItems] 回调闭包。
     */
    fun close() {
        cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 诊断 / 测试 seam
    // ------------------------------------------------------------------

    internal fun isRunning(): Boolean = synchronized(lock) { pumpJob?.isActive == true }

    internal fun pendingCount(): Int = synchronized(lock) { queue.size }

    internal fun doneCount(): Int = done.get()

    internal fun failedCount(): Int = failed.get()

    internal fun appliedCount(): Int = applied.get()

    /** 当前许可数（扫描中 = `max(2, workers/2)`，扫描收尾后 = workers） */
    internal fun currentPermits(): Int = limiterNow?.permits ?: workers

    /** 等泵排空（测试与真机取证用）；超时返回而不是抛。 */
    internal suspend fun awaitIdle(timeoutMs: Long = 10_000L) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val job = synchronized(lock) { pumpJob }
                if (job == null || !job.isActive) return@withTimeoutOrNull
                delay(10L)
            }
        }
    }

    /**
     * E205：等泵**彻底退出**——[cancel] 只发取消信号且会把 [pumpJob] 清空，[awaitIdle]
     * 随即返回，但协程可能还挂在最后一次 flush/saveSnapshot 中途。removeScanFolder 的
     * 清空路径需要等它真正结束再删快照，否则排空收尾的落盘会把刚删的快照写回。
     */
    suspend fun awaitStopped() {
        synchronized(lock) { lastPumpJob }?.join()
    }

    // ------------------------------------------------------------------
    // 泵主体
    // ------------------------------------------------------------------

    private fun ensureRunning() {
        synchronized(lock) {
            if (pumpJob?.isActive == true) return
            pumpJob = scope.launch { runPump() }
            lastPumpJob = pumpJob
        }
    }

    /** 反复冲刷队列，直到静默 [idleMs] 后仍没有新任务为止。 */
    private suspend fun runPump() {
        // 计数按「一次泵」累计：runPump 可能跑多轮 runLoop（收尾窗口里又来了需求），
        // 若每轮清零，`cover pump idle total=` 与快照判定就只反映最后一轮，真机取证会误判。
        done.set(0)
        failed.set(0)
        applied.set(0)
        lastProgressTotal = 0
        while (currentCoroutineContext().isActive) {
            runLoop()
            // 收尾窗口里 UI 又塞了需求（或晚到的 publish）→ 接着泵，别留半截任务
            if (pendingCount() == 0) return
        }
    }

    private suspend fun runLoop() {
        val runStartedAt = nowMs()
        lastActivityMs = runStartedAt
        lastProgressAtMs = runStartedAt
        // 扫描中只给一半许可（别抢遍历的 IPC）；扫描已收尾就直接满速
        val scanning = isScanning()
        val startPermits = if (scanning) scanningPermits() else workers
        val limiter = ScanLimiter(workers, startPermits)
        limiterNow = limiter
        // 收回许可必须异步：onPermitsChanged 在监视协程里被调用，同步 acquire 会把 tick 卡住
        val shrinkJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
        val tuner = ScanLaneTuner(
            kind = LaneKind.COVER,
            initialPermits = startPermits,
            cap = if (scanning) scanningPermits() else workers,
            cores = cores,
            onPermitsChanged = { delta ->
                if (delta > 0) {
                    repeat(delta) { limiter.release() }
                } else if (delta < 0) {
                    shrinkJobs += scope.launch { repeat(-delta) { limiter.acquire() } }
                }
            },
            log = { line -> ScanTrace.log(line) },
        )
        ScanTrace.log(
            "cover pump start pending=" + pendingCount() + " permits=" + limiter.permits +
                " workers=" + workers + " scanning=" + scanning,
        )
        try {
            coroutineScope {
                val monitor = launch(dispatchers.default) { monitorLoop(limiter, tuner) }
                try {
                    while (true) {
                        if (pendingCount() == 0) {
                            if (inFlight.get() == 0 && nowMs() - lastActivityMs >= idleMs) break
                            delay(emitIntervalMs)
                            continue
                        }
                        // 许可不够就挂起在这里（不阻塞线程），监视协程继续回写封面/调参
                        limiter.acquire()
                        // **先拿许可、再出队**：反过来的话任务会被提前摘出队列干等许可，
                        // 此时 UI 的 request 就插不到它前面去了（契约 §8.2：来源当最高优先级）。
                        val id = pollNext() ?: run {
                            limiter.release()
                            continue
                        }
                        inFlight.incrementAndGet()
                        launch(dispatchers.io) {
                            try {
                                processUnit(id, tuner)
                            } finally {
                                inFlight.decrementAndGet()
                                limiter.release()
                                lastActivityMs = nowMs()
                            }
                        }
                    }
                } finally {
                    monitor.cancel()
                    shrinkJobs.forEach { it.cancel() }
                }
            }
            flushCovers()
            // 与扫描期 lane tune 行同格式，供真机 grep：lane tune kind=COVER permits=… best=…
            ScanTrace.log(tuner.summaryLine())
            ScanTrace.log(
                "cover pump idle total=" + (done.get() + failed.get()) + " failed=" + failed.get() +
                    " applied=" + applied.get() + " permits=" + limiter.permits +
                    " elapsedMs=" + (nowMs() - runStartedAt),
            )
            runCatching { negative?.flush() }
            saveSnapshotIfNeeded()
        } finally {
            if (limiterNow === limiter) limiterNow = null
            synchronized(lock) { if (queue.isEmpty()) knownIds.clear() }
        }
    }

    /** 监视协程：节流回写 + 进度日志 + 扫描收尾后放开满速 + 驱动调参窗口。 */
    private suspend fun monitorLoop(limiter: ScanLimiter, tuner: ScanLaneTuner) {
        val windowTicks = (ScanLaneTuner.DEFAULT_WINDOW_MS / emitIntervalMs).coerceAtLeast(1L)
        var tickInWindow = 0L
        var raisedToFull = false
        while (currentCoroutineContext().isActive) {
            delay(emitIntervalMs)
            flushCovers()
            logProgressIfDue()
            if (isScanning()) {
                // 扫描还在跑：许可由 tuner 在扫描压力下找 knee，上限就是扫描期许可
                tickInWindow++
                if (tickInWindow >= windowTicks) {
                    tickInWindow = 0L
                    tuner.tick()
                }
            } else if (!raisedToFull) {
                // 扫描已收尾：一次性放开到满速（再调参只会在 knee 附近震荡，收益为零）
                raisedToFull = true
                raiseToFull(limiter)
                ScanTrace.log("cover pump permits=" + limiter.permits + " scan finished, full speed")
            }
        }
    }

    private fun raiseToFull(limiter: ScanLimiter) {
        while (limiter.permits < workers) {
            if (!limiter.release()) break
        }
    }

    private fun pollNext(): String? = synchronized(lock) {
        val id = queue.removeFirstOrNull() ?: return@synchronized null
        queuedIds.remove(id)
        id
    }

    /** 处理一个 id：候选判定（文件/否定缓存）→ 抽帧 → 攒进待回写 / 记失败。 */
    private suspend fun processUnit(id: String, tuner: ScanLaneTuner) {
        // latest 里没有、resolveItem 也查不到（条目已被下一版列表淘汰）：直接放弃。
        // 此时 knownIds 里已经占着这个 id，本轮不会再重试——这是刻意的：id 都已经从可见列表
        // 消失了，为一个看不见的条目反复查列表只是浪费。它若重新出现，下一版 publish 会重新登记。
        val item = latest[id] ?: resolveItem(id)?.also { latest[id] = it } ?: return
        if (!needsCover(item)) return
        val known = item.coverUri
        val cover = if (known != null && File(known).isFile) known else produceCover(item)
        // 被取消（新扫描/用户取消）：这一项已经跑完的抽帧也不能落结论——不写否定缓存、不回写
        if (!currentCoroutineContext().isActive) return
        if (cover != null) {
            synchronized(lock) { pendingCovers[id] = cover }
            done.incrementAndGet()
            tuner.onItemDone()
        } else {
            failed.incrementAndGet()
            val key = negativeKeyOf(item)
            if (key != null) runCatching { negative?.markFailed(key, nowMs()) }
        }
    }

    /** 只判定「值不值得抽」：音频、已有可用封面、已有失败结论都不排队。 */
    private fun needsCover(item: MediaItem): Boolean {
        // 音频不抽视频帧：**先看 mimeType**，再看 id 前缀兜底。
        // 只认 `msa-` 前缀会漏掉 SAF 扫到的隐藏音频（id 是 `saf-…`），那在真机上就是
        // 一次白费的 MediaMetadataRetriever 调用（Robolectric 下甚至会直接挂住用例）。
        if (item.mimeType.startsWith("audio/")) return false
        if (item.id.startsWith(audioIdPrefix)) return false
        val cover = item.coverUri
        if (cover != null && File(cover).isFile) return false
        // 进程内失败 memo（TTL 5 分钟、LRU 256）：挡掉同进程内的重复重试
        if (isCoverFailed(item)) return false
        // 持久否定结论（跨进程、TTL 7 天）——**必须查**，否则否定缓存只写不读：
        // 进程重启后 1200 个坏文件照样每轮重抽一遍，用户拍板的那条需求等于没落地。
        // [CoverNegativeSink.isFailed] 是纯内存查表（ScanCoverNegativeCache 只在构造时 load 一次），
        // 因此这里不会给 publish/request（主线程调用）引入任何 IO。
        val key = negativeKeyOf(item) ?: return true
        return negative?.isFailed(key, nowMs()) != true
    }

    /** 把攒下的封面一次性回写进 `_media`（节流窗口到了、或泵要收尾了）。 */
    private suspend fun flushCovers() {
        var batch: Map<String, String>? = null
        synchronized(lock) {
            if (pendingCovers.isNotEmpty()) {
                batch = LinkedHashMap(pendingCovers)
                pendingCovers.clear()
            }
        }
        val ready = batch ?: return
        applyCovers(ready)
        applied.addAndGet(ready.size)
    }

    private fun logProgressIfDue() {
        val total = done.get() + failed.get()
        val elapsedSinceLog = nowMs() - lastProgressAtMs
        val byCount = total - lastProgressTotal >= progressEvery
        val byTime = total > lastProgressTotal && elapsedSinceLog >= progressIntervalMs
        if (!byCount && !byTime) return
        lastProgressTotal = total
        lastProgressAtMs = nowMs()
        ScanTrace.log(
            "cover pump progress done=" + done.get() + " failed=" + failed.get() +
                " pending=" + pendingCount(),
        )
    }

    /** 排空后落一次快照：冷启动带封面出图、不重抽（扫描中让位给提交段）。 */
    private suspend fun saveSnapshotIfNeeded() {
        if (applied.get() == 0) return
        if (isScanning()) {
            ScanTrace.log("cover pump snapshot skipped 扫描中，交给提交段")
            return
        }
        val ok = runCatching { saveSnapshot(currentItems()) }.isSuccess
        ScanTrace.log("cover pump snapshot saved=" + ok + " applied=" + applied.get())
    }

    /** 扫描期许可：一半解码线程（至少 2，至多 workers）。 */
    private fun scanningPermits(): Int = (workers / 2).coerceAtLeast(2).coerceAtMost(workers)
}

/** 回写节流窗口：契约 §8.3 要求 200–300ms 批量 emit 一次。 */
private const val DEFAULT_EMIT_INTERVAL_MS = 250L

/** 静默视为「排空」的窗口（契约：静默 2s 或排空后落一次快照）。 */
private const val DEFAULT_IDLE_MS = 2_000L

/** 进度行密度（契约：每 100 项或每 2s 一行 cover pump progress）。 */
private const val DEFAULT_PROGRESS_EVERY = 100
private const val DEFAULT_PROGRESS_INTERVAL_MS = 2_000L
