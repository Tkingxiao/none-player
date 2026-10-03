package com.noneplayer.core.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * E65 修复：此前未配置 corruptionHandler——偏好文件一旦因异常关机/存储故障等损坏，
 * 所有暴露的 Flow（themeMode/sortMode/scanFolderUris/playbackPosition 等）都会抛
 * IOException 终止，且消费端（MainActivity/LibraryScreen 等）均未加 .catch{}，
 * 表现为相关界面彻底无法渲染。改为"损坏即重置为空偏好"，让应用能继续启动
 * （代价是损坏当次会丢失用户偏好，但远好于直接崩溃不可用）。
 */
private val Context.dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(
    name = "none_player_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 媒体库排序方式 */
enum class SortMode(val label: String) {
    NAME("名称"),
    // 设计稿 SORTS 的文案是「最近添加」——「最新」是错字级偏差，逐字对齐
    ADDED("最近添加"),
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
        val KEY_LIST_VIEW_STYLE = stringPreferencesKey("library_view_style")
        val KEY_PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_HARDWARE_DECODE = booleanPreferencesKey("decode_hardware_preferred")
        val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val KEY_BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_GESTURES_ENABLED = booleanPreferencesKey("playback_gestures")

        // E73（跟踪中，未在本批次改 key 格式）：playback_pos_<mediaId> / subtitle_<mediaId>
        // 未按 sourceId 分命名空间。当前唯一数据源是本地扫描（LocalMediaSource 产出的
        // id 形如 "ms-123"），风险是潜在的——一旦新增网络/其他 MediaSourceProvider 且
        // id 格式重叠才会触发。此处暂不改 key 格式：那样做会让现有用户的断点续播/
        // 字幕记忆在升级后直接读不到旧 key（静默丢数据），代价比潜在风险本身更大。
        // 按《功能审核文档.md》§14.6 的建议，命名空间方案与 §8.3 的 :core:database
        // 迁移一起定稿（彼时本就要做一次性数据搬迁，可以把前缀加好）。
        fun playbackPosKey(mediaId: String) = longPreferencesKey("playback_pos_$mediaId")
        fun playbackAtKey(mediaId: String) = longPreferencesKey("playback_at_$mediaId")
        fun subtitleKey(mediaId: String) = stringPreferencesKey("subtitle_$mediaId")

        /** 最近搜索最多保留 5 条（设计稿 searchView 的 chips 一行放得下） */
        const val MAX_SEARCH_HISTORY = 5
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

    /**
     * 网格 / 列表视图风格（设计稿 viewToggle 的 `state.view`，四屏共用 + 跨会话记忆）。
     * 值为 "grid" 或 "list"。
     */
    val listViewStyle: Flow<String> = context.dataStore.data
        .map { it[KEY_LIST_VIEW_STYLE] ?: "grid" }

    suspend fun setListViewStyle(style: String) {
        context.dataStore.edit { it[KEY_LIST_VIEW_STYLE] = style }
    }

    // ---- 搜索（最近搜索 chips，设计稿 searchView） ----

    /** 最近搜索词，最新在前，最多 5 条 */
    val searchHistory: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            prefs[KEY_SEARCH_HISTORY]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        }

    suspend fun addSearchTerm(term: String) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            val next = (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY)
            prefs[KEY_SEARCH_HISTORY] = next.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { it.remove(KEY_SEARCH_HISTORY) }
    }

    // ---- 播放偏好（设置页「播放 / 后台与屏幕 / 手势」三组） ----

    /** 硬件解码优先：关闭后播放引擎走 CPU 软解（兼容性更好但更耗电） */
    val hardwareDecodePreferred: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_HARDWARE_DECODE] ?: true }

    suspend fun setHardwareDecodePreferred(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HARDWARE_DECODE] = enabled }
    }

    /** 自动连播下一集：当前条目结束后自动播放队列中的下一条 */
    val autoplayNext: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_AUTOPLAY_NEXT] ?: true }

    suspend fun setAutoplayNext(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTOPLAY_NEXT] = enabled }
    }

    /** 后台播放：退到列表后仍继续播放（关闭则退页即停） */
    val backgroundPlay: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_BACKGROUND_PLAY] ?: true }

    suspend fun setBackgroundPlay(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKGROUND_PLAY] = enabled }
    }

    /** 播放时保持屏幕常亮（仅播放中生效） */
    val keepScreenOn: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_KEEP_SCREEN_ON] ?: true }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    /** 启用音量/亮度滑动手势 */
    val gesturesEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_GESTURES_ENABLED] ?: true }

    suspend fun setGesturesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GESTURES_ENABLED] = enabled }
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

    /**
     * 全部播放进度（mediaId → 毫秒）。媒体库的「继续观看 / 已看 x% / 进度线 /
     * 未看完筛选」都从这一份 map 取数——逐条目各开一个 DataStore 订阅
     * 会在大库上退化成 N 条流，这里一次读全量再在内存里分发。
     */
    val playbackPositions: Flow<Map<String, Long>> = context.dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { (key, _) ->
                    key.name.startsWith("playback_pos_") && key.name != "playback_pos_"
                }
                .associate { (key, value) -> key.name.removePrefix("playback_pos_") to (value as? Long ?: 0L) }
        }

    /**
     * 全部"最近播放时间"（mediaId → epoch 毫秒）。
     * 「播放历史」聚合与「我的 · 最近播放」按它排序——设计稿按 `prog > 0` 过滤，
     * 顺序即"最近播放过"；只靠进度值排不出时间序。
     */
    val playbackLastAt: Flow<Map<String, Long>> = context.dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { (key, _) ->
                    key.name.startsWith("playback_at_") && key.name != "playback_at_"
                }
                .associate { (key, value) -> key.name.removePrefix("playback_at_") to (value as? Long ?: 0L) }
        }

    suspend fun savePlaybackPosition(mediaId: String, positionMs: Long) {
        context.dataStore.edit {
            it[playbackPosKey(mediaId)] = positionMs.coerceAtLeast(0)
            it[playbackAtKey(mediaId)] = System.currentTimeMillis()
        }
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
