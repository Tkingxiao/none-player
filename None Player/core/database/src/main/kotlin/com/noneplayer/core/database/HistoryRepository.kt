package com.noneplayer.core.database

import android.content.Context
import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /** 时间倒序的全部历史 */
    val recent: Flow<List<HistoryEntry>> = dao.recent().map { rows -> rows.map { it.toEntry() } }

    /**
     * 历史条数——设置页「清除播放缓存」的计数与按钮文案。
     *
     * 「继续观看」的口径**不在这里**：它是库页基于 `recent` + 时长算出的 `fractionOf`，
     * 曾经这里另有一条 `continueWatching` 查询用 0.95 阈值，与 UI 的 0.99 各说各话
     * （且从未被消费）。同一份语义保留一处实现，避免调阈值时只改一边。
     */
    val count: Flow<Int> = dao.count()

    /** 收藏键集合（卡片红心 / 菜单文案 / 我的页联动都读它） */
    val favoriteKeys: Flow<Set<String>> = dao.favoriteKeys().map { it.toSet() }

    /** 收藏键（按收藏时间倒序）——聚合列表与我的页横排的排序依据 */
    val favorites: Flow<List<String>> = dao.favoriteKeys()

    fun isFavorite(mediaKey: String): Flow<Boolean> = dao.isFavorite(mediaKey)

    /** 断点读取（装载链恢复用；无记录返回 null） */
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
            ),
            now = now,
        )
    }

    /** 收藏开关；返回切换后的状态 */
    suspend fun toggleFavorite(item: MediaItem): Boolean {        val key = HistoryEntry.keyOf(item)
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
                ),
            )
            migrated++
        }
        return migrated
    }

    companion object {
        /** 已看比例达到该值视为"看完"：不再出现在继续观看里（§8.3） */
        const val WATCHED_DONE_RATIO = 0.95f

        /** E12：进度门槛——低于它且未完看时不落盘（切集瞬间的 0 附近位置不污染历史） */
        const val MIN_PERSIST_POSITION_MS = 10_000L

        /** 纯函数（可单测）：已看比例；时长未知时按 0 处理（完看判定只在时长可信时成立） */
        fun watchedRatio(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0L) return 0f
            return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

        /** 纯函数（可单测）：这次进度值不值得写（E12：<10s 且未完看不写） */
        fun shouldPersist(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs <= 0L) return false
            if (positionMs >= MIN_PERSIST_POSITION_MS) return true
            return durationMs > 0L && watchedRatio(positionMs, durationMs) >= WATCHED_DONE_RATIO
        }
    }
}
