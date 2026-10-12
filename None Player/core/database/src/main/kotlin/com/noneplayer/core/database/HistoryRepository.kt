package com.noneplayer.core.database

import android.content.Context
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * 播放历史 + 收藏的统一读写口（§8.3）。
 *
 * 放在 `:core:database` 而不是 `:core:data`：`core:data` 是 DataStore 设置层
 * （无 Android DB 依赖），Room 的 schema/DAO/迁移天然属于本模块；插件侧靠
 * `kernel.services.get<HistoryRepository>()` 取用（core-* 对插件是允许依赖，
 * 但走服务定位更贴合"内核级服务"的定位，见 §8.3）。
 *
 * **写入语义（§8.3/E12）**：播放中每 5s + 暂停/切集/退页 upsert；`<10s 且未完看`
 * 不写（切集瞬间的 0 附近位置会污染"继续观看"）；`watchedRatio ≥ 0.95` 视为看完，
 * 从「继续观看」里过滤掉。
 */
class HistoryRepository(context: Context) {

    private val dao: HistoryDao by lazy { NonePlayerDatabase.build(context.applicationContext).historyDao() }

    /**
     * 共享作用域（A5/P1-6）：本仓库是进程级单例，作用域与它同寿、不取消。
     *
     * 存在的理由是 `stateIn` ——见下面 [recent]/[favoriteKeysFlow] 的说明。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     */
    val recent: Flow<List<HistoryEntry>> by lazy {
        dao.recent()
            .map { rows -> rows.map { it.toEntry() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    /**
     * 历史条数——设置页「清除播放缓存」的计数与按钮文案。
     *
     * 「继续观看」的口径**不在这里**：它是库页基于 `recent` + 时长算出的 `fractionOf`，
     * 曾经这里另有一条 `continueWatching` 查询用 0.95 阈值，与 UI 的 0.99 各说各话
     * （且从未被消费）。同一份语义保留一处实现，避免调阈值时只改一边。
     */
    val count: Flow<Int> by lazy { dao.count() }

    /** 收藏键的**唯一一条** DAO 订阅（P1-6）：下面两个视图都从它派生 */
    private val favoriteKeysFlow: Flow<List<String>> by lazy {
        dao.favoriteKeys().stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    /** 收藏键集合（卡片红心 / 菜单文案 / 我的页联动都读它） */
    val favoriteKeys: Flow<Set<String>> by lazy { favoriteKeysFlow.map { it.toSet() } }

    /** 收藏键（按收藏时间倒序）——聚合列表与我的页横排的排序依据 */
    val favorites: Flow<List<String>> by lazy { favoriteKeysFlow }

    fun isFavorite(mediaKey: String): Flow<Boolean> = dao.isFavorite(mediaKey)

    /**
     * 预热（P1-4）：把"建库 + 第一次开库查询"放到启动画面期间的 IO 协程里做完，
     * 不留给 UI 首帧的收集动作。失败不抛——首帧收集时还会再试一次。
     */
    suspend fun prime() {
        withContext(Dispatchers.IO) {
            runCatching { dao.count().first() }
        }
    }

    /**
     * 断点读取（装载链恢复用）。
     *
     * A1-3：**先比对文件指纹再交位置**——库里那行记录的 size/mtime 与当前条目对不上，
     * 说明文件被替换或重录过，此时旧位置可能落在另一部片子的中间，
     * **静默从头播**（不弹提示：用户关心的是"接着看"，而不是"为什么没接着"）。
     * 任一指纹有一侧为 0（未知/旧行）时该轴不参与比对，所以升级不会清掉老用户的续播。
     */
    suspend fun positionOfIfIntact(item: MediaItem): Long? {
        val row = dao.breakpointOf(HistoryEntry.keyOf(item)) ?: return null
        if (!breakpointIntact(row.sizeBytes, row.lastModifiedSec, item.sizeBytes, item.lastModifiedSec)) {
            return null
        }
        return row.positionMs
    }

    /** 断点读取（无指纹校验）：仅供展示与迁移用，恢复链一律走 [positionOfIfIntact] */
    suspend fun positionOf(mediaKey: String): Long? = dao.positionOf(mediaKey)

    /**
     * 记一次播放进度。[countPlay] = true 时播放次数 +1（进入某项播放时调用一次；
     * 周期/暂停落点传 false，避免播放次数被落点次数灌水）。
     */
    suspend fun record(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long = item.durationMs,
        countPlay: Boolean = false,
    ) {
        if (item.id.isEmpty() || item.uri.isEmpty()) return
        if (!shouldPersist(positionMs, durationMs)) return
        dao.upsert(
            PlaybackHistoryEntity(
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
                lastPlayedAt = System.currentTimeMillis(),
                width = item.width,
                height = item.height,
                sizeBytes = item.sizeBytes,
                lastModifiedSec = item.lastModifiedSec,
            ),
            countPlay = countPlay,
        )
    }

    /**
     * 记一次"开始播放"：`play_count + 1`、刷新 `last_played_at`。
     * 与 [record] 分开是因为它不受落盘门槛约束——用户点开某项就算播过一次，
     * 哪怕只看 3 秒；而这种"播过但没进度"的项也应出现在历史里。
     */
    suspend fun markPlayed(item: MediaItem) {
        if (item.id.isEmpty() || item.uri.isEmpty()) return
        val key = HistoryEntry.keyOf(item)
        val now = System.currentTimeMillis()
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

    /** 收藏开关；返回切换后的状态 */
    suspend fun toggleFavorite(item: MediaItem): Boolean {
        val key = HistoryEntry.keyOf(item)
        return if (dao.isFavoriteNow(key)) {
            dao.removeFavorite(key)
            false
        } else {
            dao.addFavorite(FavoriteEntity(mediaKey = key, addedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun clearHistory() = dao.clear()

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
     * @return 删除的**历史**行数（不含收藏）
     */
    suspend fun pruneOrphans(validKeys: Set<String>, sourceId: String): Int {
        require(sourceId.isNotBlank()) { "Orphan cleanup requires a source namespace" }
        val historyOrphans = orphanKeys(dao.allHistoryKeys(), validKeys, sourceId)
        historyOrphans.chunked(DELETE_CHUNK).forEach { dao.deleteHistoryKeys(it) }
        val favoriteOrphans = orphanKeys(dao.allFavoriteKeys(), validKeys, sourceId)
        favoriteOrphans.chunked(DELETE_CHUNK).forEach { dao.deleteFavoriteKeys(it) }
        return historyOrphans.size
    }

    /**
     * 一次性迁移（E11）：把 DataStore 的 `playback_pos_*` / `playback_at_*` 读成历史行。
     *
     * 行数据（标题/封面/时长…）取自**当前媒体列表**——迁移需要一个"条目快照"的来源，
     * 而扫描完成的时刻正好两样都有。已存在的行不覆盖（迁移只补历史，不盖新进度）。
     * @return 迁入条数
     */
    suspend fun migrateFromDataStore(
        items: List<MediaItem>,
        positions: Map<String, Long>,
        lastPlayedAt: Map<String, Long>,
    ): Int {
        if (positions.isEmpty()) return 0
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
        return migrated
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
