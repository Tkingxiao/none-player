package com.noneplayer.core.database

import android.content.Context
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 播放历史 + 收藏的统一读写口（§8.3），实现 [PlaybackHistoryStore]。
 *
 * 放在 `:core:database` 而不是 `:core:data`：`core:data` 是 DataStore 设置层
 * （无 Android DB 依赖），Room 的 schema/DAO/迁移天然属于本模块；插件侧靠
 * `kernel.services.get<HistoryRepository>()` 取用（core-* 对插件是允许依赖，
 * 但走服务定位更贴合"内核级服务"的定位，见 §8.3）。
 *
 * **写入语义（§8.3/E12/A24）**：播放中每 15s 窄更新 + 暂停/切集/退页整行 upsert；`<10s 且未完看`
 * 不写（切集瞬间的 0 附近位置会污染"继续观看"）；`watchedRatio ≥ 0.95` 视为看完，
 * 从「继续观看」里过滤掉。
 *
 * **R2-1/REV-06 失败可观察恢复**（本类的核心不变量）：
 * - 读流（[recent]/[count]/[favoriteKeys]/[favorites]/[isFavorite]）遇到可重试错误会
 *   按 [HistoryRetryPolicy] 有界退避重试，**绝不把失败 emit 成空表**——空表是"确实没有
 *   记录"的语义，让孤儿清理/迁移据此删数据会造成真实数据丢失。重试耗尽或遇到结构性
 *   错误时，流记录状态后结束，`stateIn` 保留**上一次可信值**。
 * - 写操作（[record]/[markPlayed]/[toggleFavorite]/[clearHistory]/…）失败**不抛**
 *   （[CancellationException] 除外，必须原样重抛），返回值让调用方决定提示/重试。
 * - 每次成功都会把 [storageStatus] 恢复 `healthy`；每次失败都会带上操作名与消息，
 *   UI（设置页副题）和验收测试据此观察。
 */
class HistoryRepository private constructor(
    private val daoProvider: () -> HistoryDao,
    private val scope: CoroutineScope,
    private val retryPolicy: HistoryRetryPolicy,
) : PlaybackHistoryStore {

    /**
     * 生产构造：`Kernel.boot()` 同步注册用（E107）。
     *
     * 这里只保存建库**动作**，不真正建库（P1-4）：`dao` 是 `by lazy`，首次使用（通常是
     * [prime] 在启动画面期间的 IO 协程）才 `Room.databaseBuilder().build()`。
     */
    constructor(context: Context) : this(
        daoProvider = { NonePlayerDatabase.build(context.applicationContext).historyDao() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        retryPolicy = HistoryRetryPolicy(),
    )

    /**
     * 契约注入构造（R2-1/REV-06）：保留 `context` 入参以贴合生产签名，便于上层按
     * "同一个 Context 装配"的惯用法注入假 DAO。`context` 不参与 DAO 构建——
     * 注入 DAO 时不再需要真实 Context。
     */
    @Suppress("UNUSED_PARAMETER")
    internal constructor(
        context: Context,
        dao: HistoryDao,
        scope: CoroutineScope,
        retryPolicy: HistoryRetryPolicy = HistoryRetryPolicy(),
    ) : this(daoProvider = { dao }, scope = scope, retryPolicy = retryPolicy)

    /**
     * 纯 JVM 单测便捷构造：不接收 `Context`。
     *
     * 为什么需要它：单测运行在 mockable android.jar 上，`Context` 的 stub 构造器会抛
     * "Method <init> … not mocked"，而 `:core:database` 不引 Robolectric；契约构造器
     * 的 `context` 在注入 DAO 时并不被使用，因此额外提供这条无 Context 入口。
     */
    internal constructor(
        dao: HistoryDao,
        scope: CoroutineScope,
        retryPolicy: HistoryRetryPolicy = HistoryRetryPolicy(),
    ) : this(daoProvider = { dao }, scope = scope, retryPolicy = retryPolicy)

    private val dao: HistoryDao by lazy { daoProvider() }

    /** REV-06：持久化健康的可观察状态（设置页副题 / 日志 / 验收测试用） */
    private val _storageStatus = MutableStateFlow(HistoryStorageStatus())

    /** 当前存储健康状态；失败时 [HistoryStorageStatus.failures] 为连续失败次数 */
    val storageStatus: StateFlow<HistoryStorageStatus> = _storageStatus.asStateFlow()

    /**
     * 时间倒序的全部历史。
     *
     * **`by lazy` 而不是急切属性**（P1-4）：急切写 `dao.recent()` 会在构造本仓库时
     * 就把 `Room.databaseBuilder().build()` 跑到——而构造发生在 `Kernel.boot()` 的
     * **主线程同步段**（E107 要求核心服务同步注册）。现在建库/首查由 [prime] 在
     * 启动画面期间的 IO 协程里做。
     *
     * **`stateIn` 而不是把 DAO 流直接暴露**（P1-6）：库页与「我的」页各订一份时，
     * 一次写入会回灌两条全表查询。共享后 N 个收集者共用一条查询，
     * 无人收集 5s 后自动停订。
     *
     * **暴露 `StateFlow` 而不是 `Flow`**：`stateIn` 产出的本来就是 StateFlow，声明成
     * `Flow` 会逼调用方写 `collectAsStateWithLifecycle(initialValue = emptyList())`
     * ——那**第一帧的空列表**不是"确实没有记录"，而是"还没读到"，与 E126（scanFolderUris）
     * 和 E105（媒体列表首帧）是同一族坑，真机实报过后果：首页「继续观看」整节因这一帧
     * 消失，`LazyListState` 又按**下标**恢复，返回时正好落到下一节的边界上，
     * 用户看到的就是"进播放页再返回，首页莫名下滑、继续观看被挡住"。
     * 直接给 StateFlow，初值取现值，没有那一帧。
     *
     * REV-06：底层走 [resilientFlow]——可重试失败不 emit 空表，只保留上次可信值。
     */
    val recent: StateFlow<List<HistoryEntry>> by lazy {
        resilientFlow("recent") { dao.recent() }
            .map { rows -> rows.map { it.toEntry() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    /**
     * 历史条数——设置页「清除播放缓存」的计数与按钮文案。
     *
     * 「继续观看」的口径**不在这里**：它是库页基于 `recent` + 时长算出的 `fractionOf`，
     * 曾经这里另有一条 `continueWatching` 查询用 0.95 阈值，与 UI 的 0.99 各说各话
     * （且从未被消费）。同一份语义保留一处实现，避免调阈值时只改一边。
     *
     * REV-06：读流失败会退避重试；重试耗尽后结束，调用方保留旧计数。
     */
    val count: Flow<Int> by lazy { resilientFlow("count") { dao.count() } }

    /**
     * E209：系统「最近媒体/恢复播放」入口使用。必须走冷查询而不是 [recent].first()：
     * `recent` 是 WhileSubscribed 的 StateFlow，无订阅者时首值是空表，会让恢复永远没有条目。
     */
    suspend fun mostRecentForResumption(): HistoryEntry? =
        storageRead("resume") { dao.mostRecent() }?.toEntry()

    /** 收藏键的**唯一一条** DAO 订阅（P1-6）：下面两个视图都从它派生。
     *  StateFlow 的理由与 [recent] 同一条——见那里的说明。 */
    private val favoriteKeysFlow: StateFlow<List<String>> by lazy {
        resilientFlow("favoriteKeys") { dao.favoriteKeys() }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    /** 收藏键集合（卡片红心 / 菜单文案 / 我的页联动都读它） */
    val favoriteKeys: Flow<Set<String>> by lazy { favoriteKeysFlow.map { it.toSet() } }

    /** 收藏键（按收藏时间倒序）——聚合列表与我的页横排的排序依据 */
    val favorites: StateFlow<List<String>> by lazy { favoriteKeysFlow }

    fun isFavorite(mediaKey: String): Flow<Boolean> =
        resilientFlow("isFavorite") { dao.isFavorite(mediaKey) }

    /**
     * 预热（P1-4）：把"建库 + 第一次开库查询"放到启动画面期间的 IO 协程里做完，
     * 不留给 UI 首帧的收集动作。失败不抛——首帧收集时还会再试一次；
     * REV-06：失败会记入 [storageStatus]，便于诊断启动期存储问题。
     */
    suspend fun prime() {
        withContext(Dispatchers.IO) {
            storageRead("prime") { dao.count().first() }
        }
    }

    /**
     * 断点读取（装载链恢复用），实现 [PlaybackHistoryStore.positionOfIfIntact]。
     *
     * A1-3：**先比对文件指纹再交位置**——库里那行记录的 size/mtime 与当前条目对不上，
     * 说明文件被替换或重录过，此时旧位置可能落在另一部片子的中间，
     * **静默从头播**（不弹提示：用户关心的是"接着看"，而不是"为什么没接着"）。
     * 任一指纹有一侧为 0（未知/旧行）时该轴不参与比对，所以升级不会清掉老用户的续播。
     *
     * REV-06：读失败返回 `null`（与"没有断点"同出口）并记入 [storageStatus]；
     * 调用方（[SessionBootstrap]）据此从 0 起播，不崩不卡。
     */
    override suspend fun positionOfIfIntact(item: MediaItem): Long? =
        storageRead("positionOfIfIntact") {
            val row = dao.breakpointOf(HistoryEntry.keyOf(item)) ?: return@storageRead null
            if (!breakpointIntact(row.sizeBytes, row.lastModifiedSec, item.sizeBytes, item.lastModifiedSec)) {
                return@storageRead null
            }
            row.positionMs
        }

    /** 断点读取（无指纹校验）：仅供展示与迁移用，恢复链一律走 [positionOfIfIntact] */
    override suspend fun positionOf(mediaKey: String): Long? =
        storageRead("positionOf") { dao.positionOf(mediaKey) }

    /**
     * 记一次播放进度，实现 [PlaybackHistoryStore.record]。
     *
     * [countPlay] = true 时播放次数 +1（进入某项播放时调用一次；周期/暂停落点传 false，
     * 避免播放次数被落点次数灌水）。
     *
     * @return true = 已确认落库或按 E12 门槛本就不该落库；false = 写库失败（调用方不崩，
     * 周期落点会自然重试）
     */
    override suspend fun record(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long,
        countPlay: Boolean,
    ): Boolean {
        if (item.id.isEmpty() || item.uri.isEmpty()) return true
        if (!shouldPersist(positionMs, durationMs)) return true
        val written = storageWrite("record") {
            dao.upsert(
                rowFor(item, positionMs, durationMs, System.currentTimeMillis()),
                countPlay = countPlay,
            )
        }
        return written != null
    }

    /**
     * A24：周期落点走窄更新（position/ratio/last_played_at），避免每 15s 读整行再覆盖
     * 16 列。行不存在（首次落点早于 `markPlayed`，或旧数据被清）时回退整行 upsert，
     * 保持与 [record] 相同的插入语义；失败仍由 [storageWrite] 收口并返回 false。
     */
    override suspend fun persistProgress(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (item.id.isEmpty() || item.uri.isEmpty()) return true
        if (!shouldPersist(positionMs, durationMs)) return true
        val key = HistoryEntry.keyOf(item)
        val now = System.currentTimeMillis()
        val written = storageWrite("persistProgress") {
            val updated = dao.updateProgress(
                key = key,
                positionMs = positionMs,
                watchedRatio = watchedRatio(positionMs, durationMs),
                now = now,
            )
            if (updated == 0) {
                dao.upsert(rowFor(item, positionMs, durationMs, now), countPlay = false)
            }
            Unit
        }
        return written != null
    }

    /**
     * 记一次"开始播放"：`play_count + 1`、刷新 `last_played_at`，实现
     * [PlaybackHistoryStore.markPlayed]。与 [record] 分开是因为它不受落盘门槛约束——
     * 用户点开某项就算播过一次，哪怕只看 3 秒；而这种"播过但没进度"的项也应出现在历史里。
     *
     * @return true = 已确认落库；false = 写库失败
     */
    override suspend fun markPlayed(item: MediaItem): Boolean {
        if (item.id.isEmpty() || item.uri.isEmpty()) return true
        val key = HistoryEntry.keyOf(item)
        val now = System.currentTimeMillis()
        val written = storageWrite("markPlayed") {
            dao.markPlayed(
                PlaybackHistoryEntity(
                    mediaKey = key,
                    sourceId = item.sourceId,
                    title = item.title,
                    uri = item.uri,
                    mime = item.mimeType,
                    coverUri = item.coverUri,
                    folder = item.folder,
                    durationMs = item.durationMs,
                    positionMs = 0L,
                    watchedRatio = 0f,
                    playCount = 1,
                    lastPlayedAt = now,
                    width = item.width,
                    height = item.height,
                    sizeBytes = item.sizeBytes,
                    lastModifiedSec = item.lastModifiedSec,
                ),
                now = now,
            )
        }
        return written != null
    }

    /**
     * 收藏开关；返回切换后的状态。
     *
     * REV-06：读当前收藏态或写收藏失败时返回 `null`——UI 必须把 `null` 与
     * `true/false` 区分开（前者是"没保存成功"，不能提示"已加入/已取消收藏"）。
     */
    suspend fun toggleFavorite(item: MediaItem): Boolean? {
        val key = HistoryEntry.keyOf(item)
        // E209：读取与写入都收进 DAO 的 @Transaction，连点/并发不会出现
        // "返回已收藏但表里没有"的相反状态。
        return storageWrite("toggleFavorite") {
            dao.toggleFavorite(key, System.currentTimeMillis())
        }
    }

    /**
     * 清空播放历史；REV-06 起返回是否成功。
     *
     * @return true = 已清空；false = 写库失败（设置页不应提示"已清空"，也不应清
     * DataStore 旧键——用户数据还在 Room 里）
     */
    suspend fun clearHistory(): Boolean = storageWrite("clearHistory") { dao.clear() } != null

    /**
     * L16：清孤儿记录（数据卫生）。
     *
     * 重扫完成后，把播放历史 / 收藏里 `media_key` 已**不在本次扫描结果中**的行删掉。
     * 此前只有全量 [clearHistory]，没有按 id 删除的入口——文件在系统里删了、来源目录被
     * 移除了，历史行却永远留着，「清除播放缓存 N 条」的计数把死记录也算进去（数据不可信）。
     *
     * **调用方必须只在"扫描覆盖了全部授权目录"时才调**：某个目录的持久化授权被系统撤销、
     * 或 docId 解析失败时本轮跳过不删——那类故障是临时的，一次就把整个目录的断点静默清零
     * 比留下几条死记录更糟（见 `LocalMediaSource.scanInternal` 的 `notes.isEmpty()` 门）。
     *
     * `IN (:keys)` 受 SQLite 变量数上限约束（旧实现约 999），按 [DELETE_CHUNK] 批切分下发。
     *
     * REV-06：失败返回 0（本轮不清理、调用方不崩），状态记录到 [storageStatus]。
     *
     * @return 删除的**历史**行数（不含收藏）；失败为 0
     */
    suspend fun pruneOrphans(validKeys: Set<String>, sourceId: String): Int {
        require(sourceId.isNotBlank()) { "Orphan cleanup requires a source namespace" }
        return storageWrite("pruneOrphans") {
            val historyOrphans = orphanKeys(dao.allHistoryKeys(), validKeys, sourceId)
            historyOrphans.chunked(DELETE_CHUNK).forEach { dao.deleteHistoryKeys(it) }
            val favoriteOrphans = orphanKeys(dao.allFavoriteKeys(), validKeys, sourceId)
            favoriteOrphans.chunked(DELETE_CHUNK).forEach { dao.deleteFavoriteKeys(it) }
            historyOrphans.size
        } ?: 0
    }

    /**
     * R0/REV-01：取"本轮扫描未覆盖类别"的历史/收藏键，供孤儿清理做保护性白名单。
     *
     * 扫描不完整分两种：结构性失败（Provider 抛错/预算耗尽/目录撤权）→ 整轮不清；
     * 权限缺口（只拒了音频）→ 可见类别仍应正常清理，但**被拒类别**的行必须原样保留。
     * 这里按 mime 前缀（`audio/`、`video/`）把被拒类别的键捞出来；收藏表没有 mime，
     * 靠 LEFT JOIN 历史行补，查不到历史的收藏保守保留（宁可多留一条，不可误删）。
     *
     * REV-06：失败返回 `null`——调用方必须**跳过本轮孤儿清理**（宁可留死记录，
     * 不可把未覆盖类别的断点误删）。
     */
    suspend fun protectedKeysForMimePrefixes(prefixes: List<String>): Set<String>? =
        withContext(Dispatchers.IO) {
            storageRead("protectedKeys") {
                if (prefixes.isEmpty()) return@storageRead emptySet()
                buildSet {
                    prefixes.forEach { prefix ->
                        val pattern = "$prefix%"
                        addAll(dao.historyKeysWithMimePattern(pattern))
                        addAll(dao.favoriteKeysWithMimePattern(pattern))
                    }
                }
            }
        }

    /**
     * 一次性迁移（E11）：把 DataStore 的 `playback_pos_*` / `playback_at_*` 读成历史行。
     *
     * 行数据（标题/封面/时长…）取自**当前媒体列表**——迁移需要一个"条目快照"的来源，
     * 而扫描完成的时刻正好两样都有。已存在的行不覆盖（迁移只补历史，不盖新进度）。
     *
     * REV-06：失败返回 `null`——调用方**不得**置 `history_migrated` 标记、也不得清理
     * DataStore 旧键（下次扫描会重试，不能把用户断点弄丢）。
     *
     * @return 迁入条数；失败为 `null`
     */
    suspend fun migrateFromDataStore(
        items: List<MediaItem>,
        positions: Map<String, Long>,
        lastPlayedAt: Map<String, Long>,
    ): Int? = storageRead("migrateFromDataStore") {
        if (positions.isEmpty()) return@storageRead 0
        var migrated = 0
        items.forEach { item ->
            val position = positions[item.id] ?: return@forEach
            if (!shouldPersist(position, item.durationMs)) return@forEach
            val key = HistoryEntry.keyOf(item)
            if (dao.exists(key)) return@forEach
            dao.insert(
                PlaybackHistoryEntity(
                    mediaKey = key,
                    sourceId = item.sourceId,
                    title = item.title,
                    uri = item.uri,
                    mime = item.mimeType,
                    coverUri = item.coverUri,
                    folder = item.folder,
                    durationMs = item.durationMs,
                    positionMs = position,
                    watchedRatio = watchedRatio(position, item.durationMs),
                    playCount = 1,
                    lastPlayedAt = lastPlayedAt[item.id] ?: 0L,
                    width = item.width,
                    height = item.height,
                    sizeBytes = item.sizeBytes,
                    lastModifiedSec = item.lastModifiedSec,
                ),
            )
            migrated++
        }
        migrated
    }

    /**
     * REV-06：读流统一包装。
     *
     * 每次**成功 emit** 视为"存储当前可用"→ 恢复 healthy 并正常下发；失败时：
     * - [CancellationException]：原样重抛（取消是正常控制流，不是存储故障）；
     * - 可重试错误且还有重试预算 → 记录失败、退避等待后**重新订阅源流**（Room 的
     *   Flow 每次 `collect` 都会重新开查询，所以重订等价于重查）；
     * - 可重试错误但预算耗尽，或结构性错误 → 记录失败后**结束流**。stateIn 会保留
     *   上一次的值，读取方不会看到"空表"这种伪数据。
     */
    private fun <T> resilientFlow(operation: String, source: () -> Flow<T>): Flow<T> = flow {
        var retries = 0
        while (true) {
            try {
                source().collect { value ->
                    retries = 0
                    markStorageHealthy()
                    emit(value)
                }
                return@flow
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                markStorageFailure(operation, error)
                if (!isRetryableStorageFailure(error) || retries >= retryPolicy.retryDelaysMs.size) {
                    return@flow
                }
                delay(retryPolicy.retryDelaysMs[retries])
                retries++
            }
        }
    }

    /** REV-06：写操作统一出口——成功返回结果并恢复 healthy，失败返回 `null`（不抛） */
    private suspend fun <T> storageWrite(operation: String, block: suspend () -> T): T? =
        try {
            block().also { markStorageHealthy() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            markStorageFailure(operation, error)
            null
        }

    /** REV-06：单次读操作统一出口——失败返回 `null`（不抛），成功恢复 healthy */
    private suspend fun <T> storageRead(operation: String, block: suspend () -> T?): T? =
        try {
            block().also { markStorageHealthy() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            markStorageFailure(operation, error)
            null
        }

    private fun markStorageHealthy() {
        val current = _storageStatus.value
        if (current.healthy && current.failures == 0) return
        _storageStatus.value = HistoryStorageStatus()
    }

    private fun markStorageFailure(operation: String, error: Throwable) {
        _storageStatus.update { current ->
            HistoryStorageStatus(
                healthy = false,
                operation = operation,
                message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                failures = current.failures + 1,
            )
        }
    }

    companion object {
        /**
         * "看完"线：达到该比例即视为已看完。
         *
         * **全项目唯一的一份**：库页「继续观看 / 未看完 / 已看完」筛选、我的页进度标签、
         * 以及装载链的"不留断点"护栏（[isWatchedToEnd]）都引用这里。此前数据层写 0.95
         * 而 UI 侧硬编码 0.99，同一条线两个数——调一处必然让另一处失真（L5 的成因之一：
         * 播到 99% 的片子卡片还挂在"继续观看"里，点进去却因为断点已在片尾而直接结束）。
         */
        const val WATCHED_DONE_RATIO = 0.99f

        /** E12：进度门槛——低于它且未完看时不落盘（切集瞬间的 0 附近位置不污染历史） */
        const val MIN_PERSIST_POSITION_MS = 10_000L

        /** 构造历史行（record / persistProgress 回退共用，避免两处字段漂移） */
        private fun rowFor(
            item: MediaItem,
            positionMs: Long,
            durationMs: Long,
            now: Long,
        ): PlaybackHistoryEntity = PlaybackHistoryEntity(
            mediaKey = HistoryEntry.keyOf(item),
            sourceId = item.sourceId,
            title = item.title,
            uri = item.uri,
            mime = item.mimeType,
            coverUri = item.coverUri,
            folder = item.folder,
            durationMs = durationMs,
            positionMs = positionMs,
            watchedRatio = watchedRatio(positionMs, durationMs),
            playCount = 0,
            lastPlayedAt = now,
            width = item.width,
            height = item.height,
            sizeBytes = item.sizeBytes,
            lastModifiedSec = item.lastModifiedSec,
        )

        /** 纯函数（可单测）：已看比例；时长未知时按 0 处理（完看判定只在时长可信时成立） */
        fun watchedRatio(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0L) return 0f
            return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

        /**
         * 纯函数（可单测）：这条断点是否已经"看到底了"。
         *
         * 装载链据此**不恢复**断点（L5）：否则播完后落盘的片尾位置会在下次打开时被原样
         * 套用，用户看到的是"刚点进去就结束"。这里只在读取侧拦，**不把库里的进度清零**——
         * 行里的 `position_ms` 同时是"已看 x%"和「播放历史 / 最近播放」的展示依据，
         * 清零会让看完的片子从历史里消失。
         */
        fun isWatchedToEnd(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0L && watchedRatio(positionMs, durationMs) >= WATCHED_DONE_RATIO

        /** 纯函数（可单测）：这次进度值不值得写（E12：<10s 且未完看不写） */
        fun shouldPersist(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs <= 0L) return false
            if (positionMs >= MIN_PERSIST_POSITION_MS) return true
            return durationMs > 0L && watchedRatio(positionMs, durationMs) >= WATCHED_DONE_RATIO
        }

        /** 纯函数（可单测）：库里存在、但本次扫描结果里没有的键（孤儿） */
        fun orphanKeys(
            existing: Collection<String>,
            valid: Set<String>,
            sourceId: String? = null,
        ): List<String> = existing.filter {
            (sourceId == null || it.startsWith("$sourceId:")) && it !in valid
        }

        /**
         * 纯函数（可单测）：断点是否还落在**同一个文件**上（A1-3）。
         *
         * 两路指纹（大小 / 修改时间）各自独立判定：**有一侧是 0 就当这条轴没法比**，
         * 只有"两侧都有值且不相等"才判失效。理由——
         * ① v1 老行两列都是默认 0（升级不能顺手把老用户的续播清了）；
         * ② 某些来源拿不到 mtime 或 size（SAF 部分文档、云盘），拿不到就不该否决；
         * ③ 只有两轴都不可比时整体视为"可信"，等价于修复前的行为。
         */
        fun breakpointIntact(
            storedSizeBytes: Long,
            storedModifiedSec: Long,
            sizeBytes: Long,
            modifiedSec: Long,
        ): Boolean {
            val sizeComparable = storedSizeBytes > 0L && sizeBytes > 0L
            val timeComparable = storedModifiedSec > 0L && modifiedSec > 0L
            if (sizeComparable && storedSizeBytes != sizeBytes) return false
            if (timeComparable && storedModifiedSec != modifiedSec) return false
            return true
        }

        /** `IN (:keys)` 的单批键数上限：SQLite 变量数限制（约 999）留足余量 */
        private const val DELETE_CHUNK = 400

        /** 共享流在**最后一个收集者离开后**继续供值的时长（P1-6）：够跨过页面切换的空窗 */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
