package com.noneplayer.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

/**
 * 播放历史行（§8.3 的 `playback_history`）。
 *
 * 主键 `media_key = "<sourceId>:<mediaId>"`——按来源分命名空间（E73 的口径），
 * 新增数据源时 id 格式重叠也不会跨源串行。
 */
@Entity(tableName = "playback_history")
internal data class PlaybackHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "media_key") val mediaKey: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val uri: String,
    val mime: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    val folder: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "watched_ratio") val watchedRatio: Float,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
    // §8.3 草案 SQL 未含宽高；历史页卡片要出「1080P」档位，补两列（增量，不破坏草案语义）
    val width: Int,
    val height: Int,
)

/** 收藏行（§8.3 的 `favorites`）：只存键与时间，条目详情随媒体列表解析 */
@Entity(tableName = "favorites")
internal data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "media_key") val mediaKey: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
internal interface HistoryDao {

    @Query("SELECT * FROM playback_history ORDER BY last_played_at DESC")
    fun recent(): Flow<List<PlaybackHistoryEntity>>

    /** 继续观看：未看完（< 阈值）且进度过了起步线，按最近播放倒序 */
    @Query(
        "SELECT * FROM playback_history WHERE watched_ratio < :doneRatio " +
            "AND position_ms >= :minPositionMs ORDER BY last_played_at DESC",
    )
    fun continueWatching(doneRatio: Float, minPositionMs: Long): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE media_key = :key LIMIT 1")
    suspend fun byKey(key: String): PlaybackHistoryEntity?

    @Query("SELECT position_ms FROM playback_history WHERE media_key = :key LIMIT 1")
    suspend fun positionOf(key: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM playback_history WHERE media_key = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: PlaybackHistoryEntity)

    @Update
    suspend fun update(row: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE media_key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM playback_history")
    suspend fun clear()

    /**
     * upsert（API 26 的 SQLite 3.18 没有 UPSERT 语法，且 SQLite 3.24 才随 API 30
     * 到位——最小支持面下必须读改写三步走，包一层事务）。
     * [countPlay] = true 时本次计入一次播放（进入播放时算一次，周期落点不算）。
     */
    @Transaction
    suspend fun upsert(row: PlaybackHistoryEntity, countPlay: Boolean) {
        val existing = byKey(row.mediaKey)
        if (existing == null) {
            insert(row.copy(playCount = if (countPlay) 1 else 0))
        } else {
            update(row.copy(playCount = existing.playCount + if (countPlay) 1 else 0))
        }
    }

    /** 收藏键（按收藏时间倒序——聚合与我的页横排都按它排） */
    @Query("SELECT media_key FROM favorites ORDER BY added_at DESC")
    fun favoriteKeys(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_key = :key)")
    fun isFavorite(key: String): Flow<Boolean>

    @Query("SELECT * FROM favorites")
    suspend fun allFavorites(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(row: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE media_key = :key")
    suspend fun removeFavorite(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_key = :key)")
    suspend fun isFavoriteNow(key: String): Boolean
}

@Database(
    entities = [PlaybackHistoryEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class NonePlayerDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): NonePlayerDatabase =
            Room.databaseBuilder(context, NonePlayerDatabase::class.java, "none_player.db").build()
    }
}
