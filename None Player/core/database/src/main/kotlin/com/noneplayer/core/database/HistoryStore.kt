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
    /**
     * A1-3 断点失效校验的第一路指纹：落盘那一刻的文件大小（字节），0 = 当时未知。
     *
     * 文件被替换/重录后仍沿用旧断点，位置会落在"另一部片子"的中间——比不恢复更糟。
     */
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    /** A1-3 第二路指纹：落盘那一刻的文件修改时间（epoch 秒），0 = 当时未知 */
    @ColumnInfo(name = "last_modified") val lastModifiedSec: Long = 0,
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

    /**
     * 历史条数（设置页「清除播放缓存」的计数）。
     *
     * 走 `COUNT(*)` 而不是把 `recent()` 整表收下来 `.size`：计数行只有一个 Int，
     * 不必让设置页持有一份与库页同尺寸的列表快照。
     */
    @Query("SELECT COUNT(*) FROM playback_history")
    fun count(): Flow<Int>

    @Query("SELECT * FROM playback_history WHERE media_key = :key LIMIT 1")
    suspend fun byKey(key: String): PlaybackHistoryEntity?

    /** E209：系统播放恢复（onPlaybackResumption）用的冷查询；不依赖 recent 流首帧 */
    @Query("SELECT * FROM playback_history ORDER BY last_played_at DESC LIMIT 1")
    suspend fun mostRecent(): PlaybackHistoryEntity?

    @Query("SELECT position_ms FROM playback_history WHERE media_key = :key LIMIT 1")
    suspend fun positionOf(key: String): Long?

    /** A1-3：断点 + 当时的文件指纹，一次读齐（恢复前要比对） */
    @Query(
        "SELECT position_ms AS positionMs, size_bytes AS sizeBytes, last_modified AS lastModifiedSec " +
            "FROM playback_history WHERE media_key = :key LIMIT 1",
    )
    suspend fun breakpointOf(key: String): Breakpoint?

    @Query("SELECT EXISTS(SELECT 1 FROM playback_history WHERE media_key = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: PlaybackHistoryEntity)

    @Update
    suspend fun update(row: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE media_key = :key")
    suspend fun delete(key: String)

    /** L16：全量键（孤儿清理用——只取键，不把整行收下来） */
    @Query("SELECT media_key FROM playback_history")
    suspend fun allHistoryKeys(): List<String>

    /**
     * R0/REV-01：按 mime 前缀取历史键。扫描未覆盖某类媒体（如音频权限被拒）时，
     * 用它把该类别的历史行排除在孤儿清理之外；返回的是完整 media_key。
     */
    @Query("SELECT media_key FROM playback_history WHERE mime LIKE :pattern")
    suspend fun historyKeysWithMimePattern(pattern: String): List<String>

    @Query("DELETE FROM playback_history WHERE media_key IN (:keys)")
    suspend fun deleteHistoryKeys(keys: List<String>)

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

    /**
     * `play_count + 1` 与 `last_played_at` 的原地自增。
     *
     * 必须是**单条 UPDATE**而不是「读出来 → copy → 整行写回」：周期落点（`upsert`）
     * 与「进入播放」（`markPlayed`）会并发到达，读改写会把对方刚写下的 `position_ms`
     * 用旧值盖回去（断点回退几秒），`play_count` 也会丢自增。返回受影响行数，
     * 供 [markPlayed] 判断是「新条」还是「已有条」。
     */
    @Query(
        "UPDATE playback_history SET play_count = play_count + 1, last_played_at = :now " +
            "WHERE media_key = :key",
    )
    suspend fun bumpPlay(key: String, now: Long): Int

    /**
     * A24：周期落点的窄更新——只写位置/进度/最近播放时间，不读出再整行覆盖 16 列。
     *
     * 返回受影响行数；0 = 行不存在（例如首次落点早于 `markPlayed`），调用方回退整行
     * upsert。与 [bumpPlay] 一样用单条 UPDATE，避免和“进入播放”的并发读改写互相覆盖。
     */
    @Query(
        "UPDATE playback_history SET position_ms = :positionMs, " +
            "watched_ratio = :watchedRatio, last_played_at = :now WHERE media_key = :key",
    )
    suspend fun updateProgress(key: String, positionMs: Long, watchedRatio: Float, now: Long): Int

    /**
     * 「进入播放」写入（§8.3/E12）：已有行只自增不覆盖其它列；没有行才落一条快照。
     * 整个判断 + 写入包在事务里，与 [upsert] 的读改写互斥。
     */
    @Transaction
    suspend fun markPlayed(row: PlaybackHistoryEntity, now: Long) {
        if (bumpPlay(row.mediaKey, now) == 0) {
            insert(row.copy(playCount = 1, lastPlayedAt = now))
        }
    }

    /** 收藏键（按收藏时间倒序——聚合与我的页横排都按它排） */
    @Query("SELECT media_key FROM favorites ORDER BY added_at DESC")
    fun favoriteKeys(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_key = :key)")
    fun isFavorite(key: String): Flow<Boolean>

    @Query("SELECT * FROM favorites")
    suspend fun allFavorites(): List<FavoriteEntity>

    /** L16：全量收藏键（孤儿清理用） */
    @Query("SELECT media_key FROM favorites")
    suspend fun allFavoriteKeys(): List<String>

    /**
     * R0/REV-01：按 mime 前缀取收藏键——收藏表本身没有 mime，靠历史行补；
     * 没有历史行的收藏（mime 未知）保守地一并返回，宁可多留不可误删。
     */
    @Query(
        "SELECT f.media_key FROM favorites f LEFT JOIN playback_history h " +
            "ON f.media_key = h.media_key WHERE h.mime IS NULL OR h.mime LIKE :pattern",
    )
    suspend fun favoriteKeysWithMimePattern(pattern: String): List<String>

    @Query("DELETE FROM favorites WHERE media_key IN (:keys)")
    suspend fun deleteFavoriteKeys(keys: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(row: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE media_key = :key")
    suspend fun removeFavorite(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_key = :key)")
    suspend fun isFavoriteNow(key: String): Boolean

    /**
     * E209：收藏开关的原子实现。旧实现由 Repository 先读 [isFavoriteNow] 再分两次
     * 写，连点/并发时返回状态与表内最终状态可能相反。check 与写在同一事务里。
     */
    @Transaction
    suspend fun toggleFavorite(key: String, now: Long): Boolean {
        return if (isFavoriteNow(key)) {
            removeFavorite(key)
            false
        } else {
            addFavorite(FavoriteEntity(mediaKey = key, addedAt = now))
            true
        }
    }
}

/** 一行断点及其文件指纹快照（A1-3）；指纹列为 0 表示当时未知、不参与比对 */
internal data class Breakpoint(
    val positionMs: Long,
    val sizeBytes: Long,
    val lastModifiedSec: Long,
)

@Database(
    entities = [PlaybackHistoryEntity::class, FavoriteEntity::class],
    version = 2,
    // §8.3：导出 schema JSON（`schemas/` 目录，由 room.schemaLocation 指定）。
    // 关掉导出后，任何一次实体变更都只能靠肉眼比对写迁移；导出后升级路径可被 diff 校验。
    exportSchema = true,
)
internal abstract class NonePlayerDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        /**
         * v1 → v2（A1-3）：`playback_history` 补两列文件指纹。
         *
         * `ALTER TABLE ADD COLUMN` 带 DEFAULT，旧行自动填 0 = "当时未知"，
         * 比对规则把 0 当"不参与"，所以**旧用户的续播不会被这次升级清掉**。
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_history ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playback_history ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): NonePlayerDatabase =
            Room.databaseBuilder(context, NonePlayerDatabase::class.java, "none_player.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
