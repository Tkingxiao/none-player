package com.noneplayer.core.database

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * R2-1/REV-06 测试用假 DAO：内存表 + 可编程失败开关。
 *
 * 用内存实现而不是 Room in-memory：本模块单测是**纯 JVM**（无 Robolectric），
 * 而失败注入必须能精确控制「第几次读/哪一次写」失败。每个开关都是独立字段，
 * 置 null 即恢复正常，便于验证「失败 → 恢复」的完整路径。
 *
 * [history] / [favorites] 用 LinkedHashMap 保插入序；查询侧按 DAO 契约排序
 * （历史按 last_played_at 倒序、收藏按 added_at 倒序），避免用例依赖插入顺序。
 */
internal class FakeHistoryDao : HistoryDao {

    /** 历史表：media_key -> 行 */
    val history = linkedMapOf<String, PlaybackHistoryEntity>()

    /** 收藏表：media_key -> 行 */
    val favorites = linkedMapOf<String, FavoriteEntity>()

    /** 写调用序列（核对「失败后成功只多写一次」/「失败不重复写」） */
    val writeCalls = mutableListOf<String>()

    // ---- 读失败开关 -------------------------------------------------------

    /** recent 流：还剩几次可重试失败（IOException）；每失败一次自减 */
    var recentFailuresRemaining = 0

    /** recent 流被订阅的次数（断言重试次数 / 结构性错误不重试） */
    var recentReadCount = 0

    /** recent 失败时抛的异常（默认 IOException；可换成 CancellationException 等） */
    var recentFailure: Throwable = IOException("模拟：历史查询 IO 失败")

    /**
     * recent 流的结构性错误（非 IO，不可重试）。
     *
     * 非 null 时流先 emit 一次已落库数据再抛错——用来验证「结构错误结束该流、
     * 但 stateIn 保留最后可信值、不 emit 空表」。
     */
    var recentStructuralError: Throwable? = null

    /** count 流：还剩几次可重试失败 */
    var countFailuresRemaining = 0
    var countFailure: Throwable = IOException("模拟：历史计数 IO 失败")
    var countError: Throwable? = null

    /** favoriteKeys 流：还剩几次可重试失败 */
    var favoriteKeysFailuresRemaining = 0
    var favoriteKeysFailure: Throwable = IOException("模拟：收藏查询 IO 失败")
    var favoriteKeysError: Throwable? = null

    /** isFavorite 流：还剩几次可重试失败 */
    var isFavoriteFailuresRemaining = 0
    var isFavoriteFailure: Throwable = IOException("模拟：收藏状态 IO 失败")
    var isFavoriteError: Throwable? = null

    // ---- 写失败开关 -------------------------------------------------------

    var insertError: Throwable? = null
    var updateError: Throwable? = null
    var deleteError: Throwable? = null
    var clearError: Throwable? = null
    var bumpPlayError: Throwable? = null
    var allHistoryKeysError: Throwable? = null
    var mimePatternError: Throwable? = null
    var addFavoriteError: Throwable? = null
    var removeFavoriteError: Throwable? = null
    var isFavoriteNowError: Throwable? = null
    var positionOfError: Throwable? = null
    var breakpointOfError: Throwable? = null
    var existsError: Throwable? = null
    var favoriteDeleteError: Throwable? = null

    private fun fail(error: Throwable?): Nothing = throw (error ?: IOException("未配置的失败"))

    private fun maybeFail(error: Throwable?) {
        if (error != null) fail(error)
    }

    // ---- 历史读 -----------------------------------------------------------

    override fun recent(): Flow<List<PlaybackHistoryEntity>> = flow {
        recentReadCount++
        if (recentFailuresRemaining > 0) {
            recentFailuresRemaining--
            fail(recentFailure)
        }
        emit(history.values.sortedByDescending { it.lastPlayedAt })
        // 结构错误：已落库数据先读到一次，随后查询结构坏了 → 验证「最后可信值被保留」
        maybeFail(recentStructuralError)
    }

    override fun count(): Flow<Int> = flow {
        maybeFail(countError)
        if (countFailuresRemaining > 0) {
            countFailuresRemaining--
            fail(countFailure)
        }
        emit(history.size)
    }

    override suspend fun byKey(key: String): PlaybackHistoryEntity? = history[key]

    override suspend fun positionOf(key: String): Long? {
        maybeFail(positionOfError)
        return history[key]?.positionMs
    }

    override suspend fun breakpointOf(key: String): Breakpoint? {
        maybeFail(breakpointOfError)
        return history[key]?.let { Breakpoint(it.positionMs, it.sizeBytes, it.lastModifiedSec) }
    }

    override suspend fun exists(key: String): Boolean {
        maybeFail(existsError)
        return key in history
    }

    // ---- 历史写 -----------------------------------------------------------

    override suspend fun insert(row: PlaybackHistoryEntity) {
        writeCalls += "insert:${row.mediaKey}"
        maybeFail(insertError)
        history[row.mediaKey] = row
    }

    override suspend fun update(row: PlaybackHistoryEntity) {
        writeCalls += "update:${row.mediaKey}"
        maybeFail(updateError)
        history[row.mediaKey] = row
    }

    override suspend fun delete(key: String) {
        writeCalls += "delete:$key"
        maybeFail(deleteError)
        history.remove(key)
    }

    override suspend fun allHistoryKeys(): List<String> {
        maybeFail(allHistoryKeysError)
        return history.keys.toList()
    }

    override suspend fun historyKeysWithMimePattern(pattern: String): List<String> {
        maybeFail(mimePatternError)
        val prefix = pattern.removeSuffix("%")
        return history.values.filter { it.mime.startsWith(prefix) }.map { it.mediaKey }
    }

    override suspend fun deleteHistoryKeys(keys: List<String>) {
        writeCalls += "deleteHistoryKeys:${keys.size}"
        maybeFail(deleteError)
        keys.forEach { history.remove(it) }
    }

    override suspend fun clear() {
        writeCalls += "clear"
        maybeFail(clearError)
        history.clear()
    }

    override suspend fun bumpPlay(key: String, now: Long): Int {
        writeCalls += "bumpPlay:$key"
        maybeFail(bumpPlayError)
        val row = history[key] ?: return 0
        history[key] = row.copy(playCount = row.playCount + 1, lastPlayedAt = now)
        return 1
    }

    // ---- 收藏 -------------------------------------------------------------

    override fun favoriteKeys(): Flow<List<String>> = flow {
        maybeFail(favoriteKeysError)
        if (favoriteKeysFailuresRemaining > 0) {
            favoriteKeysFailuresRemaining--
            fail(favoriteKeysFailure)
        }
        emit(favorites.values.sortedByDescending { it.addedAt }.map { it.mediaKey })
    }

    override fun isFavorite(key: String): Flow<Boolean> = flow {
        maybeFail(isFavoriteError)
        if (isFavoriteFailuresRemaining > 0) {
            isFavoriteFailuresRemaining--
            fail(isFavoriteFailure)
        }
        emit(key in favorites)
    }

    override suspend fun allFavorites(): List<FavoriteEntity> = favorites.values.toList()

    override suspend fun allFavoriteKeys(): List<String> {
        maybeFail(allHistoryKeysError)
        return favorites.keys.toList()
    }

    override suspend fun favoriteKeysWithMimePattern(pattern: String): List<String> {
        maybeFail(mimePatternError)
        val prefix = pattern.removeSuffix("%")
        return favorites.keys.filter { key ->
            val row = history[key]
            // 与真实 SQL 对齐：没有历史行的收藏（mime 未知）保守保留
            row == null || row.mime.startsWith(prefix)
        }
    }

    override suspend fun deleteFavoriteKeys(keys: List<String>) {
        writeCalls += "deleteFavoriteKeys:${keys.size}"
        maybeFail(favoriteDeleteError)
        keys.forEach { favorites.remove(it) }
    }

    override suspend fun addFavorite(row: FavoriteEntity) {
        writeCalls += "addFavorite:${row.mediaKey}"
        maybeFail(addFavoriteError)
        favorites[row.mediaKey] = row
    }

    override suspend fun removeFavorite(key: String) {
        writeCalls += "removeFavorite:$key"
        maybeFail(removeFavoriteError)
        favorites.remove(key)
    }

    override suspend fun isFavoriteNow(key: String): Boolean {
        maybeFail(isFavoriteNowError)
        return key in favorites
    }
}
