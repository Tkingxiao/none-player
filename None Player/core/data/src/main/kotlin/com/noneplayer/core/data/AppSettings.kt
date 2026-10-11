package com.noneplayer.core.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * E65 修复：此前未配置 corruptionHandler——偏好文件一旦因异常关机/存储故障等损坏，
 * 所有暴露的 Flow（themeMode/sortMode/scanFolderUris/subtitleUri 等）都会抛
 * IOException 终止，且消费端（MainActivity/LibraryScreen 等）均未加 .catch{}，
 * 表现为相关界面彻底无法渲染。改为"损坏即重置为空偏好"，让应用能继续启动
 * （代价是损坏当次会丢失用户偏好，但远好于直接崩溃不可用）。
 */
/**
 * 当前唯一媒体来源的 id（A2-8）。
 *
 * per-media 记忆键是 `sourceId:mediaId`，而深链契约与路由条目常常没有 sourceId——
 * 补前缀时必须有**一个**地方说清"默认算哪个来源"。这里就是那一个地方：
 * 本地扫描器（`LocalMediaSource.sourceId`）、DataStore 旧键搬迁、读取侧的键推导
 * 三处都引用它，不再各写一份字面量。
 */
const val LOCAL_MEDIA_SOURCE_ID = "local"

/**
 * 双击步长的默认值与档位（L14 自定义双击步长）。
 *
 * 文件级公开：`AppSettings` 的 companion 是 private（键不外泄），
 * 而这两样是**跨模块契约**（设置页要列档位、播放页要取默认值）。
 */
const val DEFAULT_DOUBLE_TAP_STEP_MS = 10_000L
val DOUBLE_TAP_STEP_OPTIONS_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

// ---- 字幕样式默认值与量程（E131，跨模块契约：播放页渲染与设置面板共用）----

/** 默认字幕颜色：白（0xFFFFFFFF） */
const val DEFAULT_SUBTITLE_COLOR_ARGB = 0xFFFFFFFFL

/** 文字字幕大小倍率区间（对齐参考示例的 1.0 基准，可往两边调） */
const val SUBTITLE_SCALE_MIN = 0.5f
const val SUBTITLE_SCALE_MAX = 2.0f

/**
 * 可选字幕颜色（对齐参考示例的色板：白 / 红 / 黄 / 绿 / 青 / 品红）。
 *
 * 顺序与参考图一致；白为首选（默认选中）。数值是 ARGB，渲染侧直接 `Color(argb)`。
 */
val SUBTITLE_COLOR_OPTIONS = listOf(
    0xFFFFFFFFL, // 白
    0xFFFF3B30L, // 红
    0xFFFFEB3BL, // 黄
    0xFF34C759L, // 绿
    0xFF32ADE6L, // 青
    0xFFFF2D95L, // 品红
)

/** 字幕延时档位（毫秒；0 = 复位）。对齐参考示例的 −1.0s / −0.1s / 0 / +0.1s / +1.0s */
val SUBTITLE_DELAY_STEPS_MS = listOf(-1_000L, -100L, 0L, 100L, 1_000L)

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
        val KEY_MOTION_LEVEL = intPreferencesKey("motion_level")
        val KEY_SORT_MODE = stringPreferencesKey("library_sort_mode")
        val KEY_SORT_ASC = booleanPreferencesKey("library_sort_asc")
        val KEY_SCAN_FOLDER = stringPreferencesKey("scan_folder_uri")
        val KEY_SCAN_FOLDERS = stringPreferencesKey("scan_folder_uris")
        val KEY_GRID_COLUMNS = stringPreferencesKey("library_grid_columns")
        val KEY_LIST_VIEW_STYLE = stringPreferencesKey("library_view_style")
        val KEY_PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_HARDWARE_DECODE = booleanPreferencesKey("decode_hardware_preferred")
        val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val KEY_BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_GESTURES_ENABLED = booleanPreferencesKey("playback_gestures")

        // ---- 字幕样式（E131：对齐参考示例的「字幕设置」面板）----
        // 与 `subtitle_<mediaKey>` 那族**per-media** 记忆不同：样式是**全局偏好**
        // （用户挑的字体颜色/大小不该每换一部片就重置），所以不带 mediaKey 后缀。
        val KEY_SUBTITLE_ENABLED = booleanPreferencesKey("subtitle_enabled")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val KEY_SUBTITLE_SCALE = stringPreferencesKey("subtitle_scale")
        val KEY_SUBTITLE_ITALIC = booleanPreferencesKey("subtitle_italic")

        /**
         * 播放页禁止截屏/录屏（FLAG_SECURE，C18）。
         *
         * A1-6：此前是播放页组合内的 `rememberSaveable`——每次进页都回到"允许"，
         * 用户为隐私打开的开关在一次退页后被静默清掉。隐私类开关的语义是偏好，
         * 不是本次播放的临时状态，故收进设置持久化（生效范围仍是播放页窗口）。
         */
        val KEY_SCREENSHOT_BLOCKED = booleanPreferencesKey("screenshot_blocked")

        /** 双击画面左右侧的快进/快退步长（L14 自定义双击步长，毫秒） */
        val KEY_DOUBLE_TAP_STEP_MS = longPreferencesKey("double_tap_step_ms")

        /** 均衡器开关与各段归一增益（A2-11） */
        val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        // 各段归一增益编码成 "0.4,0.2,0,-0.1,-0.3" 字符串——preferences 没有列表键，
        // 与画面记忆 `display_*` 同一手法（见 [displayKey]）
        val KEY_EQ_LEVELS = stringPreferencesKey("eq_levels")

        // E73 / A2-8（已落地）：per-media 键按 `sourceId:mediaId` 分命名空间——
        // subtitle_ / display_ / audio_only_ 三族的键值现在都是 `<前缀><sourceId>:<id>`，
        // 与 Room 的 media_key 同一条约定；旧格式由 migratePerMediaKeyNamespaceOnce()
        // 一次性重排（按当前唯一来源 "local" 补前缀），同时清掉归属不明的空后缀孤儿键。
        // 断点族（playback_pos_ / playback_at_）已在 E11 搬进 Room，不在此列。
        fun subtitleKey(mediaKey: String) = stringPreferencesKey("subtitle_$mediaKey")

        /** 画面显示记忆（批次 3）："<比例模式>|<镜像0/1>|<旋转0/180>"，整体读写 */
        fun displayKey(mediaKey: String) = stringPreferencesKey("display_$mediaKey")

        /** 仅听音频记忆（§5.1）：下次进同一文件直接进入音频形态 */
        fun audioOnlyKey(mediaKey: String) = booleanPreferencesKey("audio_only_$mediaKey")

        /** 手动选择的弹幕文件（批次 6）：按视频 uri 记忆，渲染层优先于同名自动匹配 */
        fun danmakuManualKey(videoUri: String) = stringPreferencesKey("danmaku_manual_$videoUri")

        /** 弹幕本地屏蔽词（批次 6）：换行分隔的原始串 */
        val KEY_DANMAKU_BLOCKLIST = stringPreferencesKey("danmaku_blocklist")

        /** §8.3：历史迁移完成标记（E11：迁移后 DataStore 只留设置项） */
        val KEY_HISTORY_MIGRATED = booleanPreferencesKey("history_migrated")

        /** A2-8：per-media 键已按 `sourceId:mediaId` 重排的一次性标记 */
        val KEY_PER_MEDIA_KEY_MIGRATED = booleanPreferencesKey("per_media_key_migrated")

        /** 旧键重排时补的命名空间前缀（唯一真相见文件级 [LOCAL_MEDIA_SOURCE_ID]） */
        private const val LEGACY_SOURCE_ID = LOCAL_MEDIA_SOURCE_ID

        /**
         * A2-8 要重排的三族 per-media 键前缀。
         *
         * 为什么现在动：`id` 只在**来源内**唯一。今天只有本地一个来源所以没爆，
         * 但接进网盘/DLNA 之后 `ms-68` 会跨源串记忆——同一把钥匙开别人的锁。
         * Room 侧从第一天就是 `media_key = "<sourceId>:<mediaId>"`（E73 口径），
         * DataStore 侧补齐后两处的约定才是一句话。
         */
        val PER_MEDIA_PREFIXES = listOf("subtitle_", "display_", "audio_only_")

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

    /**
     * 「性能模式」档位序（R13）：0 轻量 / 1 平衡 / 2 全效，默认平衡。
     *
     * 存档位序而不是语义串，是因为这里（`core:data`）不该依赖 `core:ui`——
     * 真正的档位定义（倍率、模糊半径、开关）是 `NpMotionQuality.fromLevel(level)`，
     * 由 app 层读出来后喂给 `NonePlayerTheme`。越界值在读取时就夹回，
     * 以免手工改偏好文件把界面推进一个不存在的档位。
     */
    val motionLevel: Flow<Int> = context.dataStore.data
        .map { (it[KEY_MOTION_LEVEL] ?: 1).coerceIn(0, 2) }

    suspend fun setMotionLevel(level: Int) {
        context.dataStore.edit { it[KEY_MOTION_LEVEL] = level.coerceIn(0, 2) }
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

    /** 网格列数（紧凑档用，"1"/"2"/"3"），默认双列（设计稿 `.tier-compact .grid.v`） */
    val gridColumns: Flow<Int> = context.dataStore.data
        .map { it[KEY_GRID_COLUMNS]?.toIntOrNull() ?: 2 }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { it[KEY_GRID_COLUMNS] = columns.toString() }
    }

    // ---- 媒体库视图 ----
    // （"主页视图模式"已随标题行视图切换的移除而删除：`library_view` 键不再读取，
    // 旧安装里残留的 FOLDERS 值曾把首页钉死在文件夹界面。KEY_LIST_VIEW_STYLE
    // 的 "library_view_style" 是另一个键，勿混淆。）

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

    /**
     * 双击步长（L14）：默认 10s = 批次 2 的既有手感，改档只影响**双击**，
     * 不动横滑 seek 与键盘 `←/→`（那是 §8.4 的固定 ±5s / ±30s 语义）。
     */
    val doubleTapStepMs: Flow<Long> = context.dataStore.data
        .map { it[KEY_DOUBLE_TAP_STEP_MS] ?: DEFAULT_DOUBLE_TAP_STEP_MS }

    suspend fun setDoubleTapStepMs(stepMs: Long) {
        context.dataStore.edit { it[KEY_DOUBLE_TAP_STEP_MS] = stepMs.coerceIn(1_000L, 60_000L) }
    }

    // ---- 均衡器（A2-11）----
    //
    // 存的是**归一增益**（-1f..1f / 段），不是 mB 绝对值：真实量程各机型不同，
    // 由引擎侧映射（见 `VideoPlayer.setEqualizerLevels`）。段数变化（换设备）时
    // 多出来的段按 0f 平、少掉的段忽略，不做迁移。

    val eqEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_EQ_ENABLED] ?: false }

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_EQ_ENABLED] = enabled }
    }

    val eqLevels: Flow<List<Float>> = context.dataStore.data
        .map { raw ->
            raw[KEY_EQ_LEVELS]
                ?.split(',')
                ?.mapNotNull { it.toFloatOrNull() }
                ?: emptyList()
        }

    suspend fun setEqLevels(levels: List<Float>) {
        context.dataStore.edit { it[KEY_EQ_LEVELS] = levels.joinToString(",") }
    }

    /** 播放页禁止截屏/录屏（默认关 = 允许，与 C18 现状一致） */
    val screenshotBlocked: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SCREENSHOT_BLOCKED] ?: false }

    suspend fun setScreenshotBlocked(blocked: Boolean) {
        context.dataStore.edit { it[KEY_SCREENSHOT_BLOCKED] = blocked }
    }

    /** 启用音量/亮度滑动手势 */
    val gesturesEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_GESTURES_ENABLED] ?: true }

    suspend fun setGesturesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GESTURES_ENABLED] = enabled }
    }

    // ---- 扫描目录（SAF 授权的目录树列表，空列表 = 不扫描） ----

    /** 多目录列表（"\n" 连接存储）；旧版单目录键 [KEY_SCAN_FOLDER] 读取时自动迁移，首次写盘后清除 */
    /**
     * 扫描目录的进程级快照（E105「首帧同步」手法）。
     *
     * 存在理由：`LibraryScreen` 每次重进组合（切 Tab、从播放页返回）都要用这份值判
     * "用户配过目录没有"，而 DataStore 的首读要等一帧。用 `emptyList()` 当收集初值
     * 等于把"还没读到"当成"确实没配"，于是每次切页都闪一帧「选择要观看的文件夹」
     * 空态（2026-10-11 用户实报，实机抓帧在案）。这里在首次收集后留一份无阻塞可读的
     * 最新值，UI 侧拿它当初值即可。
     *
     * `null` = **本进程还没读过**（冷启的头一两帧），调用方必须把它和"空列表"区分开。
     */
    private val _scanFolderUrisSnapshot = MutableStateFlow<List<String>?>(null)

    /** 无阻塞读；未落地时为 null（由 UI 出骨架，不出任何"未配置"结论） */
    val scanFolderUrisSnapshot: List<String>? get() = _scanFolderUrisSnapshot.value

    val scanFolderUris: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            val stored = prefs[KEY_SCAN_FOLDERS]
            if (stored != null) {
                stored.split('\n').filter { it.isNotBlank() }
            } else {
                prefs[KEY_SCAN_FOLDER]?.let { listOf(it) } ?: emptyList()
            }
        }.onEach { _scanFolderUrisSnapshot.value = it }

    suspend fun setScanFolders(uris: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCAN_FOLDERS] = uris.joinToString("\n")
            prefs.remove(KEY_SCAN_FOLDER)
        }
    }

    // ---- 播放记忆（断点续播；E11 后仅剩一次性迁移来源） ----

    /**
     * 全部播放进度（mediaId → 毫秒）。
     *
     * E11 起正片进度已迁入 Room 历史（`HistoryRepository`），媒体库的
     * 「继续观看 / 已看 x% / 进度线」统一改读历史；这里只保留**一次性迁移来源**：
     * `LocalMediaSource` 首次扫描完成后把它交给
     * `HistoryRepository.migrateFromDataStore` 搬进 Room，随即
     * `clearPlaybackMemory()` 清掉旧键——DataStore 不再随媒体数无限增长。
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
     * 全部"最近播放时间"（mediaId → epoch 毫秒）。同为 E11 一次性迁移来源
     * （写入历史行的 `last_played_at`）——迁完之后不再有 UI 直接消费：
     * 「播放历史 / 最近播放」的排序改读 `HistoryEntry.lastPlayedAt`。
     */
    val playbackLastAt: Flow<Map<String, Long>> = context.dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { (key, _) ->
                    key.name.startsWith("playback_at_") && key.name != "playback_at_"
                }
                .associate { (key, value) -> key.name.removePrefix("playback_at_") to (value as? Long ?: 0L) }
        }

    // ---- 历史迁移（§8.3 / E11）----

    /**
     * 历史迁移标记：首次扫描完成后把 DataStore 的断点一次性搬进 Room
     * （`HistoryRepository.migrateFromDataStore`），随即清掉旧键并置位。
     * 之后 DataStore 只保留设置项（E11：键不再随媒体数无限增长）。
     */
    val historyMigrated: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_HISTORY_MIGRATED] ?: false }

    suspend fun setHistoryMigrated(value: Boolean) {
        context.dataStore.edit { it[KEY_HISTORY_MIGRATED] = value }
    }

    /**
     * A2-8 一次性搬迁：给 per-media 键加 `sourceId:` 命名空间，并清掉空后缀孤儿键。
     *
     * 整段在**一次 edit 事务**里完成——中途不会出现"旧键已删、新键未写"的窗口；
     * 置位后幂等短路。旧键一律按 [LEGACY_SOURCE_ID]（当前唯一来源"local"）补前缀，
     * 与读取侧 `mediaMemoryKey` 在 sourceId 缺失时的同一推断对齐。
     *
     * 空后缀孤儿（`audio_only_` / `display_` / `subtitle_`）来自"深链没带 id"的历史
     * 路径（E109 实测：那条脏值会把冷进误判成仅听音频态，还会被写回真键扩散）——
     * 归属不明的键没有可信语义，删除比留着更安全。
     */
    suspend fun migratePerMediaKeyNamespaceOnce() {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_PER_MEDIA_KEY_MIGRATED] == true) return@edit
            val renames = mutableListOf<Pair<String, Any>>()
            val drops = mutableListOf<androidx.datastore.preferences.core.Preferences.Key<*>>()
            val legacyPrefix = "$LEGACY_SOURCE_ID:"
            prefs.asMap().forEach { (key, value) ->
                val name = key.name
                val fam = PER_MEDIA_PREFIXES.firstOrNull { name.startsWith(it) } ?: return@forEach
                val tail = name.removePrefix(fam)
                when {
                    tail.isEmpty() -> drops += key
                    tail.startsWith(legacyPrefix) -> Unit
                    else -> {
                        renames += "$fam$legacyPrefix$tail" to value
                        drops += key
                    }
                }
            }
            renames.forEach { (newName, value) ->
                when (value) {
                    is String -> prefs[stringPreferencesKey(newName)] = value
                    is Boolean -> prefs[booleanPreferencesKey(newName)] = value
                    is Int -> prefs[intPreferencesKey(newName)] = value
                    is Long -> prefs[longPreferencesKey(newName)] = value
                    else -> drops += stringPreferencesKey(newName)
                }
            }
            drops.forEach { prefs.remove(it) }
            prefs[KEY_PER_MEDIA_KEY_MIGRATED] = true
        }
    }

    /** 清除全部播放记忆（断点续播进度 + 最近播放时间），用于设置页「清除播放缓存」。 */
    suspend fun clearPlaybackMemory() {
        context.dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith("playback_pos_") || it.name.startsWith("playback_at_") }
                .forEach { prefs.remove(it) }
        }
    }

    /** 用户偏好的默认倍速（跨会话记忆），默认 1.0 */
    val playbackSpeed: Flow<Float> = context.dataStore.data
        .map { it[KEY_PLAYBACK_SPEED]?.toFloatOrNull() ?: 1f }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed.toString() }
    }

    // ---- 字幕样式（E131）----

    /** 字幕总开关（对齐参考示例的「开启字幕」）。关掉后文本字幕一律不渲染。 */
    val subtitleEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SUBTITLE_ENABLED] ?: true }

    suspend fun setSubtitleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SUBTITLE_ENABLED] = enabled }
    }

    /** 字幕文字颜色（ARGB 十进制字符串；默认白）。存字符串是为了与既有偏好同风格。 */
    val subtitleColorArgb: Flow<Long> = context.dataStore.data
        .map { it[KEY_SUBTITLE_COLOR]?.toLongOrNull() ?: DEFAULT_SUBTITLE_COLOR_ARGB }

    suspend fun setSubtitleColorArgb(argb: Long) {
        context.dataStore.edit { it[KEY_SUBTITLE_COLOR] = argb.toString() }
    }

    /** 文字字幕大小倍率（0.5–2.0，默认 1.0） */
    val subtitleScale: Flow<Float> = context.dataStore.data
        .map { (it[KEY_SUBTITLE_SCALE]?.toFloatOrNull() ?: 1f).coerceIn(SUBTITLE_SCALE_MIN, SUBTITLE_SCALE_MAX) }

    suspend fun setSubtitleScale(scale: Float) {
        context.dataStore.edit {
            it[KEY_SUBTITLE_SCALE] = scale.coerceIn(SUBTITLE_SCALE_MIN, SUBTITLE_SCALE_MAX).toString()
        }
    }

    /** 文字字幕用斜体（对齐参考示例的「文字字幕用斜体」） */
    val subtitleItalic: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SUBTITLE_ITALIC] ?: false }

    suspend fun setSubtitleItalic(italic: Boolean) {
        context.dataStore.edit { it[KEY_SUBTITLE_ITALIC] = italic }
    }

    /** 某媒体挂载的外挂字幕地址（跨会话记忆），未挂载为 null */
    fun subtitleUri(mediaKey: String): Flow<String?> = context.dataStore.data
        .map { it[subtitleKey(mediaKey)] }

    suspend fun setSubtitleUri(mediaKey: String, uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(subtitleKey(mediaKey)) else prefs[subtitleKey(mediaKey)] = uri
        }
    }

    /** 某媒体的画面显示记忆（比例|镜像|旋转），未设置过为 null */
    fun displaySettings(mediaKey: String): Flow<String?> = context.dataStore.data
        .map { it[displayKey(mediaKey)] }

    suspend fun setDisplaySettings(mediaKey: String, value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(displayKey(mediaKey)) else prefs[displayKey(mediaKey)] = value
        }
    }

    /**
     * 某媒体的仅听音频记忆（§5.1）；**没记过为 null**（E129 / 审查 #1、#2）。
     *
     * 为什么是 `Boolean?` 而不是"缺省 false"：`false` 必须专指"用户明确选了视频形态"。
     * 若把"从未选择过"也压成 `false`，播放页就无法按媒体类型给音频文件一个默认形态
     * （音频默认仅听、视频默认带画面）——要么音频永远进不了仅听模式，要么把上一个
     * 媒体的模式带进下一个媒体。同族的 [displaySettings] / [subtitleUri] 也是
     * "没记过 = null" 这一条约定。
     */
    fun audioOnlyPreference(mediaKey: String): Flow<Boolean?> = context.dataStore.data
        .map { it[audioOnlyKey(mediaKey)] }

    suspend fun setAudioOnlyPreferred(mediaKey: String, value: Boolean) {
        context.dataStore.edit { it[audioOnlyKey(mediaKey)] = value }
    }

    // ---- 弹幕（批次 6，plugin-danmaku）----
    // 渲染层尚未接入（PlaybackOverlay 落地时消费）；设置面板先行提供入口，
    // 与 scanner 的模式一致——偏好集中在本类，插件只贡献 UI 区块。

    /** 弹幕总开关，默认开 */
    val danmakuEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[booleanPreferencesKey("danmaku_enabled")] ?: true }

    suspend fun setDanmakuEnabled(enabled: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("danmaku_enabled")] = enabled }
    }

    /**
     * 显示区域 = 弹幕层占屏幕高度的比例上限。规格四档：
     * 1/4 (0.25) / 1/3 (0.33) / 半屏 (0.5) / 全屏 (1.0)，默认半屏。
     */
    val danmakuAreaFraction: Flow<Float> = context.dataStore.data
        .map { it[stringPreferencesKey("danmaku_area")]?.toFloatOrNull() ?: 0.5f }

    suspend fun setDanmakuAreaFraction(fraction: Float) {
        context.dataStore.edit { it[stringPreferencesKey("danmaku_area")] = fraction.toString() }
    }

    /** 滚动速度倍率（1.0 = 标准速度），默认 1.0 */
    val danmakuSpeed: Flow<Float> = context.dataStore.data
        .map { it[stringPreferencesKey("danmaku_speed")]?.toFloatOrNull() ?: 1f }

    suspend fun setDanmakuSpeed(speed: Float) {
        context.dataStore.edit { it[stringPreferencesKey("danmaku_speed")] = speed.toString() }
    }

    /** 同屏密度上限（条数），0 = 不限，默认 0 */
    val danmakuMaxOnScreen: Flow<Int> = context.dataStore.data
        .map { it[intPreferencesKey("danmaku_density")] ?: 0 }

    suspend fun setDanmakuMaxOnScreen(max: Int) {
        context.dataStore.edit { it[intPreferencesKey("danmaku_density")] = max }
    }

    /** 字号倍率（1.0 = 标准），默认 1.0 */
    val danmakuFontScale: Flow<Float> = context.dataStore.data
        .map { it[stringPreferencesKey("danmaku_font_scale")]?.toFloatOrNull() ?: 1f }

    suspend fun setDanmakuFontScale(scale: Float) {
        context.dataStore.edit { it[stringPreferencesKey("danmaku_font_scale")] = scale.toString() }
    }

    /** 不透明度（0..1），默认 1.0 */
    val danmakuAlpha: Flow<Float> = context.dataStore.data
        .map { it[stringPreferencesKey("danmaku_alpha")]?.toFloatOrNull() ?: 1f }

    suspend fun setDanmakuAlpha(alpha: Float) {
        context.dataStore.edit { it[stringPreferencesKey("danmaku_alpha")] = alpha.toString() }
    }

    /** 本地屏蔽词表：换行分隔的原始串 */
    val danmakuBlocklistRaw: Flow<String> = context.dataStore.data
        .map { it[KEY_DANMAKU_BLOCKLIST] ?: "" }

    suspend fun setDanmakuBlocklistRaw(raw: String) {
        context.dataStore.edit { it[KEY_DANMAKU_BLOCKLIST] = raw }
    }

    /** 屏蔽词已拆分成词表（按换行/逗号切，去空白去重）；空串 = 无屏蔽 */
    val danmakuBlocklist: Flow<List<String>> = danmakuBlocklistRaw.map { raw ->
        raw.split('\n', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 某视频手动选择的弹幕文件地址；null = 未手动选择（走同名自动匹配） */
    fun danmakuManualUri(videoUri: String): Flow<String?> = context.dataStore.data
        .map { it[danmakuManualKey(videoUri)] }

    suspend fun setDanmakuManualUri(videoUri: String, uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(danmakuManualKey(videoUri)) else prefs[danmakuManualKey(videoUri)] = uri
        }
    }
}
