package com.noneplayer.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(
    name = "none_player_settings",
)

/** 媒体库排序方式 */
enum class SortMode(val label: String) {
    NAME("名称"),
    ADDED("最新添加"),
    DURATION("时长"),
    SIZE("大小"),
}

/**
 * 应用设置仓库——内核级服务，跨插件共享的用户偏好持久化。
 *
 * 选择 Preferences DataStore：设置项均为简单键值对，
 * Proto DataStore 的 schema 维护成本在这里不划算。
 * 单例由 `preferencesDataStore` 委托保证。
 */
class AppSettings(private val context: Context) {

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SORT_MODE = stringPreferencesKey("library_sort_mode")
        val KEY_SORT_ASC = booleanPreferencesKey("library_sort_asc")
        val KEY_SCAN_FOLDER = stringPreferencesKey("scan_folder_uri")
        val KEY_SCAN_FOLDERS = stringPreferencesKey("scan_folder_uris")
        val KEY_GRID_COLUMNS = stringPreferencesKey("library_grid_columns")
        val KEY_LIBRARY_VIEW = stringPreferencesKey("library_view")
        val KEY_PLAYBACK_SPEED = stringPreferencesKey("playback_speed")

        fun playbackPosKey(mediaId: String) = longPreferencesKey("playback_pos_$mediaId")
        fun subtitleKey(mediaId: String) = stringPreferencesKey("subtitle_$mediaId")
    }

    // ---- 外观 ----

    /** 外观模式，默认跟随系统 */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    // ---- 媒体库 ----

    /** 排序方式，默认最新添加 */
    val sortMode: Flow<SortMode> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_SORT_MODE]) {
            SortMode.NAME.name -> SortMode.NAME
            SortMode.DURATION.name -> SortMode.DURATION
            SortMode.SIZE.name -> SortMode.SIZE
            else -> SortMode.ADDED
        }
    }

    suspend fun setSortMode(mode: SortMode) {
        context.dataStore.edit { it[KEY_SORT_MODE] = mode.name }
    }

    /** 是否升序，默认降序 */
    val sortAscending: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SORT_ASC] ?: false }

    suspend fun setSortAscending(ascending: Boolean) {
        context.dataStore.edit { it[KEY_SORT_ASC] = ascending }
    }

    /** 网格列数（"2" 或 "3"），默认双列 */
    val gridColumns: Flow<Int> = context.dataStore.data
        .map { it[KEY_GRID_COLUMNS]?.toIntOrNull() ?: 2 }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { it[KEY_GRID_COLUMNS] = columns.toString() }
    }

    // ---- 媒体库视图（全部视频 / 按文件夹） ----

    /** 主页视图模式，默认全部视频 */
    val libraryView: Flow<LibraryView> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_LIBRARY_VIEW]) {
            LibraryView.FOLDERS.name -> LibraryView.FOLDERS
            else -> LibraryView.VIDEOS
        }
    }

    suspend fun setLibraryView(view: LibraryView) {
        context.dataStore.edit { it[KEY_LIBRARY_VIEW] = view.name }
    }

    // ---- 扫描目录（SAF 授权的目录树列表，空列表 = 不扫描） ----

    /** 多目录列表（"\n" 连接存储）；旧版单目录键 [KEY_SCAN_FOLDER] 读取时自动迁移，首次写盘后清除 */
    val scanFolderUris: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            val stored = prefs[KEY_SCAN_FOLDERS]
            if (stored != null) {
                stored.split('\n').filter { it.isNotBlank() }
            } else {
                prefs[KEY_SCAN_FOLDER]?.let { listOf(it) } ?: emptyList()
            }
        }

    suspend fun setScanFolders(uris: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCAN_FOLDERS] = uris.joinToString("\n")
            prefs.remove(KEY_SCAN_FOLDER)
        }
    }

    // ---- 播放记忆（断点续播） ----

    /** 读取某媒体的上次播放位置（毫秒），无记录为 0 */
    fun playbackPosition(mediaId: String): Flow<Long> = context.dataStore.data
        .map { it[playbackPosKey(mediaId)] ?: 0L }

    suspend fun savePlaybackPosition(mediaId: String, positionMs: Long) {
        context.dataStore.edit { it[playbackPosKey(mediaId)] = positionMs.coerceAtLeast(0) }
    }

    /** 用户偏好的默认倍速（跨会话记忆），默认 1.0 */
    val playbackSpeed: Flow<Float> = context.dataStore.data
        .map { it[KEY_PLAYBACK_SPEED]?.toFloatOrNull() ?: 1f }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed.toString() }
    }

    /** 某媒体挂载的外挂字幕地址（跨会话记忆），未挂载为 null */
    fun subtitleUri(mediaId: String): Flow<String?> = context.dataStore.data
        .map { it[subtitleKey(mediaId)] }

    suspend fun setSubtitleUri(mediaId: String, uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(subtitleKey(mediaId)) else prefs[subtitleKey(mediaId)] = uri
        }
    }
}
