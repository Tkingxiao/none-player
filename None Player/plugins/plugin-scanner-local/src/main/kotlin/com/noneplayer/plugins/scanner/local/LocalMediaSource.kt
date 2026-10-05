package com.noneplayer.plugins.scanner.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.CueSheet
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.SubtitleCharset
import com.noneplayer.kernel.api.CacheProvider
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.MetadataEnricher
import com.noneplayer.kernel.api.ProcessLifecycle
import com.noneplayer.kernel.api.ScanResult
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一次本地扫描的摘要（供设置页展示） */
data class LocalScanSummary(
    val added: Int,
    val removed: Int,
    val total: Int,
    val durationMs: Long,
    /** 非正常路径的说明（如 E24 拒绝扫描）；正常扫描为 null */
    val note: String? = null,
)

/**
 * 本地媒体来源——基于 MediaStore 的设备媒体扫描（视频 + 音频）。
 *
 * 门控策略：仅在用户通过 SAF 授权了扫描目录后才扫描（未选择目录时
 * scan 为空操作），目录列表由 [AppSettings.scanFolderUris] 持久化。
 *
 * 缩略图：视频在扫描时把系统缩略图缓存到 cacheDir（已存在则复用），
 * 媒体卡直接读文件，避免大视频现场抽帧卡顿；音频没有系统缩略图，
 * 改用相册封面 URI（见 [audioCoverUri]），取不到就留空由 UI 出占位图。
 *
 * 增删同步：注册 MediaStore ContentObserver（视频与音频两个集合），文件变化去抖 2s 后自动重扫。
 */
class LocalMediaSource(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val eventBus: EventBus,
    val settings: AppSettings,
    /** 富化链来源（E34：扫描期按注册顺序串行执行 MetadataEnricher） */
    private val registry: ExtensionRegistry,
    /** §8.3/E11：断点迁移的目标仓库 */
    private val history: HistoryRepository,
    /** E25：进程前台/后台状态；null = 不跟随（测试/降级路径，保持旧行为） */
    private val processLifecycle: ProcessLifecycle? = null,
) : MediaSourceProvider, CacheProvider {

    override val sourceId: String = "local"
    override val displayName: String = "本地媒体"

    /** 缩略图缓存目录（唯一落盘缓存，清掉可再生成——下次扫描时重建） */
    private val thumbsDir get() = File(context.cacheDir, "video_thumbs")

    override val cacheLabel: String = "缩略图缓存"

    override fun cacheSizeBytes(): Long = thumbsDir.listFiles()?.sumOf { it.length() } ?: 0L

    override fun clearCache(): Long {
        val before = cacheSizeBytes()
        thumbsDir.listFiles()?.forEach { it.delete() }
        return before
    }

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    override fun observeMedia(): Flow<List<MediaItem>> = _media.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastScan = MutableStateFlow<LocalScanSummary?>(null)
    val lastScan: StateFlow<LocalScanSummary?> = _lastScan.asStateFlow()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaChanges = MutableStateFlow(0L)
    private var contentObserver: ContentObserver? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var lifecycleJob: kotlinx.coroutines.Job? = null

    override suspend fun isAvailable(): Boolean = hasMediaPermission(context)

    override suspend fun scan(): ScanResult = withContext(dispatchers.io) {
        if (_scanning.value) {
            // E67 修复：扫描进行中到达的请求不再是无条件丢弃——短轮询等待当前扫描
            // 结束后重新发起一次，避免 ContentObserver 去抖触发的变更被永久遗漏
            // （debounce 已经消费了那次发射，之前不重试就等于丢了这批文件增删）。
            var waited = 0
            while (_scanning.value && waited < SCAN_WAIT_TIMEOUT_MS) {
                delay(SCAN_WAIT_POLL_MS)
                waited += SCAN_WAIT_POLL_MS.toInt()
            }
            if (_scanning.value) {
                return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
            }
        }
        val folderUriStrs = settings.scanFolderUris.first()
        if (folderUriStrs.isEmpty()) {
            return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }

        _scanning.value = true
        try {
            // E59 修复：scan() 本体现在整体包一层 runCatching——任何异常（如 OEM
            // MediaStore 列缺失、Provider 查询失败）都不再向上冒泡崩溃插件 onStart()
            // 乃至拖垮整个内核启动，而是落成一条带原因的失败摘要。
            runCatching { scanInternal(folderUriStrs) }
                .getOrElse { t ->
                    Log.w(TAG, "扫描异常，已降级为失败摘要而非崩溃", t)
                    val failSummary = LocalScanSummary(
                        added = 0,
                        removed = 0,
                        total = _media.value.size,
                        durationMs = 0,
                        note = "扫描失败：${t.message ?: t.javaClass.simpleName}",
                    )
                    _lastScan.value = failSummary
                    ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
                }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun scanInternal(folderUriStrs: List<String>): ScanResult {
        val startedAt = System.currentTimeMillis()
        // E24：无法解析的目录绝不回退"全量扫描整机"；全部无法解析时拒绝扫描
        // E68 修复：先剔除已被系统撤销持久化授权的目录，摘要里与"docId 畸形"区分开
        val revoked = revokedFolders(folderUriStrs)
        val liveFolders = folderUriStrs - revoked
        val scopes = liveFolders.map { scanScopeFromTree(it) }
        val validScopes = scopes.filterNot { it is ScanScope.Unresolvable }
        if (validScopes.isEmpty()) {
            Log.w(TAG, "授权目录均无法解析，已拒绝扫描: $folderUriStrs")
            _lastScan.value = LocalScanSummary(
                added = 0,
                removed = 0,
                total = _media.value.size,
                durationMs = 0,
                note = if (revoked.isNotEmpty()) {
                    "授权目录已被系统撤销或无法解析，已拒绝扫描（不会扫描其他位置）"
                } else {
                    "授权目录无法解析，已拒绝扫描（不会扫描其他位置）"
                },
            )
            return ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }
        val previousIds = _media.value.map { it.id }.toSet()
        val items = queryMediaStore(validScopes)
            // 垃圾文件过滤（§10 批次 4）：下载中的 .part/.tmp 等半成品不进库
            .filterNot { isJunkFile(it.title, it.sizeBytes) }
            // 元数据富化（E34 的消费端）：插件按注册顺序串行富化，失败不拖垮扫描
            .map { enrichAll(it) }
            // 容器嗅探（§12.4，批次 8）：无扩展名/错扩展名按文件头判容器，
            // mime 改对才会被引擎的 Extractor 链正确认领
            .map { sniffMimeCorrection(it) }
            // 无扩展名文件拾取与 .cue 分轨展开在 queryMediaStore 内完成
            // （前者的 Files 集合查询、后者的逐文件配对都需要 dataPath/范围参数）
            .let { items -> items + sniffUnknownFiles(validScopes) }
        // §8.3/E11：首次扫描把 DataStore 的断点迁入 Room，随后清旧键
        migrateHistoryOnce(items)
        // B7：清理已删除视频对应的缩略图缓存（音频走相册封面、不落缓存，故只看 "ms-" 前缀）
        val validIds = items.filter { it.id.startsWith("ms-") }.map { it.id.removePrefix("ms-") }.toSet()
        File(context.cacheDir, "video_thumbs")
            .listFiles()
            ?.forEach { f -> if (f.nameWithoutExtension !in validIds) f.delete() }
        _media.value = items
        // E30（B7 收尾）：按 id 集合差集统计，而非净值——
        // 同时增删时净值会互相抵消（新增 3 删 2 显示为 +1/0）
        val newIds = items.map { it.id }.toSet()
        val skippedCount = scopes.size - validScopes.size
        val notes = buildList {
            if (skippedCount > 0) add("$skippedCount 个目录无法解析，已跳过")
            if (revoked.isNotEmpty()) add("${revoked.size} 个目录授权已被系统撤销，请重新选择或移除")
        }
        val summary = LocalScanSummary(
            added = (newIds - previousIds).size,
            removed = (previousIds - newIds).size,
            total = items.size,
            durationMs = System.currentTimeMillis() - startedAt,
            note = notes.joinToString("；").ifEmpty { null },
        )
        _lastScan.value = summary
        eventBus.publish(KernelEvent.MediaScanned(sourceId = sourceId, count = items.size))
        return ScanResult(
            sourceId = sourceId,
            added = summary.added,
            updated = 0,
            removed = summary.removed,
            durationMs = summary.durationMs,
        )
    }

    /**
     * 垃圾文件过滤（§10 批次 4）：`.part`/`.tmp`/`.crdownload`/隐藏/0 字节排除。
     *
     * `.nomedia` 目录与隐藏文件本就被 MediaStore 的媒体集合跳过（扫描全走 MediaStore
     * 查询），这里再挡一道是防御异常 OEM 实现把半成品塞进游标——下载器创建的
     * `xxx.mp4.part` 一旦被索引，库里就会出现"点开打不开"的条目。
     */
    private fun isJunkFile(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        if (name.startsWith('.')) return true
        val lower = name.lowercase()
        return JUNK_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * 富化链（E34 的消费端）：按注册顺序**串行**执行 `MetadataEnricher`，
     * 后一个看到前一个的输出（契约约定：实现必须幂等）；单个失败按原样跳过。
     */
    private suspend fun enrichAll(item: MediaItem): MediaItem {
        var current = item
        registry.query<MetadataEnricher>().forEach { enricher ->
            current = runCatching { enricher.enrich(current) }.getOrDefault(current)
        }
        return current
    }

    /**
     * §8.3/E11 一次性迁移：把 DataStore 的 `playback_pos_*` / `playback_at_*`
     * 读成 Room 历史行（行快照取自刚扫到的媒体列表），随即清旧键并置位——
     * 之后 DataStore 只保留设置项，断点键不再随媒体数无限增长。
     */
    private suspend fun migrateHistoryOnce(items: List<MediaItem>) {
        if (settings.historyMigrated.first()) return
        val positions = settings.playbackPositions.first()
        val lastAt = settings.playbackLastAt.first()
        val migrated = history.migrateFromDataStore(items, positions, lastAt)
        settings.clearPlaybackMemory()
        settings.setHistoryMigrated(true)
        Log.i(TAG, "历史迁移（E11）：$migrated 条断点迁入 Room，DataStore 旧键已清理")
    }

    /**
     * E68：与系统持久化授权列表交叉核对，找出已被用户在系统设置中撤销的扫描目录。
     * 不在此处修改 settings——交给调用方决定提示文案，用户仍可手动移除。
     */
    private fun revokedFolders(folderUriStrs: List<String>): List<String> {
        val persisted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return folderUriStrs.filterNot { it in persisted }
    }

    /** 添加扫描目录：已存在则直接重扫，否则持久化后立即扫描 */
    suspend fun addScanFolder(uriStr: String) {
        val current = settings.scanFolderUris.first()
        if (uriStr !in current) settings.setScanFolders(current + uriStr)
        scan()
    }

    /**
     * 移除一个扫描目录：释放其持久化授权；仍有其他目录时立即重扫，
     * 移除最后一个目录时清空媒体列表与缩略图缓存，回到未扫描引导态。
     * 不删除任何用户文件。
     */
    suspend fun removeScanFolder(uriStr: String) {
        val current = settings.scanFolderUris.first()
        if (uriStr !in current) return
        val remaining = current - uriStr
        settings.setScanFolders(remaining)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uriStr.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (remaining.isEmpty()) {
            File(context.cacheDir, "video_thumbs").listFiles()?.forEach { it.delete() }
            _media.value = emptyList()
            _lastScan.value = null
        } else {
            scan()
        }
    }

    /**
     * E77：对外暴露观察者是否已注册，供插件禁用/`Kernel.shutdown()` 路径的
     * 集成测试断言"onStop 后 ContentObserver 确已注销"，不必依赖反射读私有字段。
     * E25 起**当前已注册**而非"进程存活期内注册过"——退到后台时会被暂时注销。
     */
    val isObserving: Boolean get() = contentObserver != null

    /**
     * 监听 MediaStore 视频与音频集合的变化（文件添加/删除），去抖后自动重扫。
     * 由插件 onStart 调用；onStop 调用 [stopObserving]。
     *
     * 音频集合必须单独注册：两个集合的 URI 不同，只监听视频会漏掉"新增一首歌"
     * 这类变更，音频要等下次手动重扫才出现。
     *
     * E25：注入了 [processLifecycle] 时，观察者跟随前台/后台——退后台即注销
     * （不再在进程存活期内无谓持有），回前台重挂并补发一次变更，把后台期间
     * 错过的文件增删扫进来（走同一 2s 去抖通道）。
     */
    fun startObserving() {
        if (contentObserver != null) return
        registerObservers()
        observerJob = observerScope.launch {
            mediaChanges.debounce(2_000).collect {
                if (it > 0) scan()
            }
        }
        val lifecycle = processLifecycle ?: return
        lifecycleJob = observerScope.launch {
            lifecycle.isForeground.collect { foreground ->
                if (foreground && contentObserver == null) {
                    registerObservers()
                    mediaChanges.value = System.currentTimeMillis()
                } else if (!foreground && contentObserver != null) {
                    unregisterObservers()
                }
            }
        }
    }

    fun stopObserving() {
        unregisterObservers()
        observerJob?.cancel()
        observerJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
    }

    private fun registerObservers() {
        if (contentObserver != null) return
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                mediaChanges.value = System.currentTimeMillis()
            }
        }
        context.contentResolver.registerContentObserver(videoCollection(), true, contentObserver!!)
        context.contentResolver.registerContentObserver(audioCollection(), true, contentObserver!!)
    }

    private fun unregisterObservers() {
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
    }

    /** MediaStore 视频集合（API 26–28 用常量 URI，29+ 用带卷名的getContentUri） */
    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** MediaStore 音频集合（与 [videoCollection] 同规则，id 前缀 "msa-" 与其区分） */
    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * 多目录查询：逐目录按授权范围过滤后按 id 去重合并（父/子目录重叠安全）。
     * 视频与音频合入同一个列表、统一按加入时间倒序——UI 自己按 `MediaItem.kind`
     * 分流，数据层不做分组，免得两处排序口径各说各话。
     */
    private fun queryMediaStore(scopes: List<ScanScope>): List<MediaItem> {
        val merged = LinkedHashMap<String, MediaItem>()
        scopes.forEach { scope ->
            queryScope(scope).forEach { merged[it.id] = it }
            queryAudioScope(scope).forEach { merged[it.id] = it }
        }
        return merged.values.sortedByDescending { it.addedAtMs }
    }

    /**
     * 授权范围 → MediaStore 过滤条件（E24/E66）。视频与音频共用同一套范围语义：
     * 两者都在同一个 `MediaStore.MediaColumns` 表结构下，只有集合 URI 与
     * `Video.*`/`Audio.*` 常量不同，逻辑抽一份可避免两个查询各写一遍、
     * 日后再改动时只改了一边（"任何分支都不得回退全量扫描"的不变量被悄悄破坏）。
     *
     * E24：按授权范围构建过滤；Unresolvable 不应到达此处，防御性返回空
     * E66 修复：relativePath/scope.name 来自 SAF tree docId 拆分，Android
     * 文件夹名允许包含 '%'/'_'——不转义会被 SQLite LIKE 解释成通配符，使匹配范围
     * 超出用户实际选择的目录，削弱"任何分支都不得回退全量扫描"的不变量。
     * 下面所有 LIKE 子句统一转义 + 追加 ESCAPE '\'。
     */
    private fun scopeSelection(scope: ScanScope, relativePathColumn: String, dataColumn: String): Pair<String?, Array<String>?> {
        val selection: String?
        val selectionArgs: Array<String>?
        when (scope) {
            is ScanScope.Unresolvable -> return null to null
            is ScanScope.Primary -> {
                val relativePath = scope.relativePath
                if (relativePath != null) {
                    val escaped = escapeLike(relativePath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        selection = "$relativePathColumn LIKE ? ESCAPE '\\'"
                        selectionArgs = arrayOf("$escaped%")
                    } else {
                        selection = "$dataColumn LIKE ? ESCAPE '\\'"
                        selectionArgs = arrayOf("%/$escaped/%")
                    }
                } else {
                    // 用户选的主存储根目录——全量扫描是明确意图
                    selection = null
                    selectionArgs = null
                }
            }
            is ScanScope.Volume -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 按卷精确过滤（SAF 卷名 == MediaStore VOLUME_NAME），绝不回退全量
                    val relativePath = scope.relativePath
                    selection = if (relativePath != null) {
                        "${MediaStore.MediaColumns.VOLUME_NAME} = ? AND " +
                            "$relativePathColumn LIKE ? ESCAPE '\\'"
                    } else {
                        "${MediaStore.MediaColumns.VOLUME_NAME} = ?"
                    }
                    // VOLUME_NAME 走等值比较（=），不是 LIKE，本身不需要转义；
                    // 只有 RELATIVE_PATH 的 LIKE 参数需要转义
                    selectionArgs = if (relativePath != null) {
                        arrayOf(scope.name, "${escapeLike(relativePath)}%")
                    } else {
                        arrayOf(scope.name)
                    }
                } else {
                    val root = "/storage/${escapeLike(scope.name)}"
                    selection = "$dataColumn LIKE ? ESCAPE '\\'"
                    selectionArgs = arrayOf(
                        if (scope.relativePath != null) {
                            "$root/${escapeLike(scope.relativePath)}/%"
                        } else {
                            "$root/%"
                        },
                    )
                }
            }
        }
        return selection to selectionArgs
    }

    private fun queryScope(scope: ScanScope): List<MediaItem> {
        val (selection, selectionArgs) = scopeSelection(
            scope,
            relativePathColumn = MediaStore.Video.Media.RELATIVE_PATH,
            dataColumn = MediaStore.Video.Media.DATA,
        )
        if (scope is ScanScope.Unresolvable) return emptyList()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA,
            // E79：分组（以及同文件夹连播队列）依赖"视频在哪个目录"。
            // 此前只读 DATA，而 DATA 在 Android 10+ 受分区存储限制、**经常返回 null**
            // ——整个媒体的 folder 于是全为 null，"按文件夹分组"与"同目录连播"双双静默失效
            // （播放本身正常，所以很难被发现）。RELATIVE_PATH 是 Q 起官方提供且始终可用的
            // 相对路径，优先用它；DATA 仅作旧版本兜底。
            MediaStore.Video.Media.RELATIVE_PATH,
            // 分辨率：卡片 meta 的「1080P / 4K」档位（设计稿 `v.res`）由此得出
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val hasRelativePath = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val primaryRelativePath = (scope as? ScanScope.Primary)?.relativePath
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            videoCollection(),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            // E59 修复配套：7 个投影列此前 6 个用 getColumnIndexOrThrow，任何一个列在
            // 异常 OEM MediaStore 实现下缺失都会直接抛异常、中止整次扫描。统一改为
            // getColumnIndex + 安全默认值；只有 _ID 缺失时该行确实无法构造合法 MediaItem，
            // 跳过这一行而不是中止整个游标遍历。
            val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val addedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val relativeCol = if (hasRelativePath) {
                cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                val item = MediaItem(
                    id = "ms-$id",
                    sourceId = sourceId,
                    title = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        .orEmpty().ifEmpty { "未命名视频" },
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "video/mp4",
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                    coverUri = thumbnailFor(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)),
                    folder = folderOf(
                        dataPath = dataPath,
                        relativePath = if (relativeCol >= 0) {
                            cursor.getString(relativeCol)
                        } else {
                            null
                        },
                    ),
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                )
                items += item
            }
        }
        return items
    }

    /**
     * 音频集合查询——与 [queryScope] 同构：同一套授权范围过滤、同一套列缺失防御。
     *
     * id 用 "msa-" 前缀与视频的 "ms-" 区分：两个集合的 `_ID` 是彼此独立的自增序号，
     * 不加前缀合并时同号记录会互相覆盖（一首歌被顶掉一个视频）。
     *
     * mimeType 必须是真实的音频 MIME（audio/ 开头）——[com.noneplayer.core.media.MediaItem.kind]
     * 正是按它分流音频/视频，这里若回退成视频 MIME，UI 会把歌当视频排进播放列表。
     */
    private fun queryAudioScope(scope: ScanScope): List<MediaItem> {
        val (selection, selectionArgs) = scopeSelection(
            scope,
            relativePathColumn = MediaStore.Audio.Media.RELATIVE_PATH,
            dataColumn = MediaStore.Audio.Media.DATA,
        )
        if (scope is ScanScope.Unresolvable) return emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            // 相册封面取 albumId（见 audioCoverUri）
            MediaStore.Audio.Media.ALBUM_ID,
            // ARTIST / IS_MUSIC 一并取出备查，但 MediaItem 本批不扩字段，且不按 IS_MUSIC
            // 过滤：铃声、闹钟、录音、语音备忘录同样落在音频集合里，按 IS_MUSIC=1 过滤会让
            // 它们从媒体库静默消失；"授权了哪些目录"已是用户的意图表达，不替他二次取舍。
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            audioCollection(),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            // 与视频一致：异常 OEM 实现下缺失的列不中止整次扫描，
            // 统一 getColumnIndex + 安全默认值；只有 _ID 缺失时该行跳过。
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relativeCol = if (hasRelativePath) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                // TITLE 才是"歌名"（DISPLAY_NAME 是文件名，常带艺人前缀与扩展名）；
                // 两者都空时才落占位文案。
                val title = (if (titleCol >= 0) cursor.getString(titleCol) else null)
                    .orEmpty()
                    .ifBlank { (if (nameCol >= 0) cursor.getString(nameCol) else null).orEmpty() }
                    .ifBlank { "未命名音频" }
                val item = MediaItem(
                    id = "msa-$id",
                    sourceId = sourceId,
                    title = title,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "audio/mpeg",
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                    coverUri = audioCoverUri(if (albumCol >= 0) cursor.getLong(albumCol) else 0L),
                    folder = folderOf(
                        dataPath = dataPath,
                        relativePath = if (relativeCol >= 0) {
                            cursor.getString(relativeCol)
                        } else {
                            null
                        },
                    ),
                )
                // `.cue` 分轨（§12.4/G4，批次 8）：同名 .cue 在场 → 一张专辑 N 轨
                items += cueTracksFor(item, dataPath)
            }
        }
        return items
    }

    /** E66：转义 SQLite LIKE 模式中的通配符，使文件夹名里的 '%'/'_' 不被误当通配符 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 从媒体记录提取展示用文件夹（如 "Movies"、"DCIM/Camera"）。
     *
     * E79：**优先 RELATIVE_PATH**。Android 10 起 [MediaStore.Video.Media.DATA] 受分区
     * 存储限制，取到 null 是常态；以前只看 DATA，导致 folder 全为 null，
     * "按文件夹分组"和"同文件夹连播"一起静默失效。RELATIVE_PATH 形如 "Movies/"，
     * 去掉尾部斜杠即为展示名；两者都拿不到时返回 null（调用方按"无目录"处理）。
     */
    private fun folderOf(dataPath: String, relativePath: String?): String? {
        relativePath
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (dataPath.isEmpty()) return null
        val dir = dataPath.substringBeforeLast('/')
            // E16：主存储前缀不硬编码——副存储/OTG 挂载点的根不是 /storage/emulated/0，
            // 固定前缀剥不掉时目录会显示成完整绝对路径
            .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
            .ifEmpty { "存储根目录" }
        return dir
    }

    /** 缩略图缓存：扫描时落盘为 JPEG，之后封面直接读文件 */
    private fun thumbnailFor(id: Long, videoUri: Uri): String? = runCatching {
        val dir = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(videoUri, Size(480, 270), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                ) ?: createFrameFallback(videoUri)
            }
            if (bitmap != null) {
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            }
        }
        if (file.exists() && file.length() > 0) file.absolutePath else null
    }.getOrNull()

    /**
     * 音频封面：Android 没有音频系统缩略图接口，只能走相册封面 URI。
     *
     * 这里**只给 URI、不落盘**——与视频缩略图不同，媒体库大多只有一张专辑图，
     * 扫描期为每首歌各存一份完全相同的 JPEG 纯属浪费空间。
     * albumId<=0（无专辑信息）时返回 null，由 UI 出占位图。
     */
    private fun audioCoverUri(albumId: Long): String? =
        if (albumId > 0) {
            ContentUris.withAppendedId(ALBUM_ART_URI.toUri(), albumId).toString()
        } else {
            null
        }

    /** 旧系统无系统缩略图时的兜底：MediaMetadataRetriever 抽首帧 */
    private fun createFrameFallback(videoUri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()

// ================= 批次 8｜容器嗅探与 .cue 分轨（§12.4） =================

/** 读文件头（上限 [maxBytes]）；任何失败返回 null——嗅探永远 fail-open */
private fun readHead(uri: android.net.Uri, maxBytes: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val buf = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            val n = stream.read(buf, read, maxBytes - read)
            if (n <= 0) break
            read += n
        }
        buf.copyOf(read)
    }
}.getOrNull()

/**
 * 容器嗅探校正（§12.4「错扩展名」）：MediaStore 的 mimeType 按扩展名推，扩展名错了
 * 它跟着错。文件头**确凿**认出容器且与原 mime 不同源时才覆盖；认不出一律保留原值
 * （嗅探是 fail-open 的信息增益，不是权威重写）。
 */
private fun sniffMimeCorrection(item: MediaItem): MediaItem {
    // .cue 分轨与原文件同 uri，逐轨重复嗅探纯属浪费 I/O
    if (item.isClipped) return item
    val head = readHead(android.net.Uri.parse(item.uri), ContainerSniffer.HEAD_BYTES) ?: return item
    val sniffed = ContainerSniffer.sniff(head) ?: return item
    if (sniffed.equals(item.mimeType, true)) return item
    if (sameContainerFamily(sniffed, item.mimeType)) return item
    return item.copy(mimeType = sniffed)
}

/** 同族容器视为一致（audio/x-wav ≡ audio/wav 这类别名），避免无意义的 mime 抖动 */
private fun sameContainerFamily(a: String, b: String): Boolean {
    val x = a.lowercase(); val y = b.lowercase()
    if (x == y) return true
    val wav = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    return x in wav && y in wav
}

/**
 * 拾取 MediaStore 不索引的无扩展名/未知类型文件（§12.4「扩展名缺失」）。
 *
 * 范围仍是授权目录（`MediaStore.Files` 集合 + `media_type = 0`，即非媒体行）；
 * 可见性需要"所有文件访问"（批次 6 的实测结论：只持 READ_MEDIA_* 时这些行
 * 根本不可见）——没授予就跳过，整条路径 fail-open。嗅探认不出容器的行不进库：
 * 没有任何 Extractor 认领的文件，塞进库只会变成"点开必失败"的垃圾条目。
 */
private fun sniffUnknownFiles(scopes: List<ScanScope>): List<MediaItem> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    if (!runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) return emptyList()
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val out = mutableListOf<MediaItem>()
    scopes.forEach { scope ->
        val (scopeSel, scopeArgs) = scopeSelection(
            scope,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        ) ?: return@forEach
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = 0" +
            (scopeSel?.let { " AND $it" } ?: "")
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                selection,
                scopeArgs,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    if (idCol < 0 || nameCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val head = readHead(android.net.Uri.parse(uri), ContainerSniffer.HEAD_BYTES)
                    val mime = head?.let { ContainerSniffer.sniff(it) } ?: continue
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val name = cursor.getString(nameCol).orEmpty()
                    out += MediaItem(
                        id = "msf-$id",
                        sourceId = sourceId,
                        title = name.substringBeforeLast('.').ifBlank { name },
                        uri = uri,
                        mimeType = mime,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                        folder = folderOf(
                            dataPath = dataPath,
                            relativePath = if (relCol >= 0) cursor.getString(relCol) else null,
                        ),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "无扩展名文件拾取失败（fail-open 跳过）", it) }
    }
    return out
}

/**
 * `.cue` 分轨展开（§12.4 / §9.7 G4）：同名 `<音频名>.cue` 在场时，把单文件项
 * 展开为"一张专辑 N 轨"——每轨指向同一音频文件，窗口由 `clipStartUs/clipEndUs`
 * 圈定（引擎挂 ClippingConfiguration，G4 ④：WAV/FLAC 可 seek，无需
 * `allowUnseekableMedia` 特批）。cue 缺失/坏表/无权限读取时**原样返回单文件项**
 * ——分轨是增益不是门槛，绝不因为展开失败让文件从库里消失。
 *
 * G4 前置核对：① 仅听音频（批次 3 ✅）② 队列/选集 UI（批次 1 ✅）
 * ③ cue 文本 GB18030 转码（复用 SubtitleCharset ✅）④ allowUnseekableMedia 取舍（本注释）。
 */
private fun cueTracksFor(file: MediaItem, dataPath: String): List<MediaItem> {
    if (file.kind != MediaKind.AUDIO || file.durationMs <= 0L || dataPath.isBlank()) {
        return listOf(file)
    }
    val dir = dataPath.substringBeforeLast('/')
    val stem = dataPath.substringAfterLast('/').substringBeforeLast('.')
    val cueFile = File(dir, "$stem.cue")
    if (!cueFile.isFile) return listOf(file)
    val text = runCatching {
        // CUE 是纯文本但 GB18030 编码极常见（G4 ③）——复用字幕字符集试探
        val bytes = cueFile.readBytes()
        if (bytes.size > 1 shl 20) return listOf(file)
        SubtitleCharset.decode(bytes).text
    }.getOrNull() ?: return listOf(file)
    val sheet = CueSheet.parse(text) ?: return listOf(file)
    val fileEndUs = file.durationMs * 1000L
    return sheet.tracks.mapIndexedNotNull { index, track ->
        val endUs = (sheet.tracks.getOrNull(index + 1)?.startUs ?: fileEndUs)
            .coerceAtLeast(track.startUs)
        if (track.startUs >= endUs) return@mapIndexedNotNull null
        val label = track.title?.takeIf { it.isNotBlank() } ?: "Track %02d".format(track.number)
        val numberText = "%02d".format(track.number)
        file.copy(
            id = "${file.id}#t${track.number}",
            title = label,
            displayTitle = "${file.title} · $numberText · $label",
            durationMs = (endUs - track.startUs) / 1000L,
            clipStartUs = track.startUs,
            // 最后一轨到文件尾：clipEndUs 归 0（契约里 0 = 不设终点）
            clipEndUs = if (endUs >= fileEndUs) 0L else endUs,
        )
    }.ifEmpty { listOf(file) }
}

}

private val JUNK_SUFFIXES = listOf(".part", ".tmp", ".crdownload", ".!qb", ".downloading")

private const val TAG = "LocalMediaSource"

/**
 * 相册封面集合 URI。系统未公开常量（`MediaStore.Audio.Albums.ALBUM_ART` 不是它），
 * 但这是 MediaProvider 长期稳定的公开约定地址，读相册图只能用它。
 */
private const val ALBUM_ART_URI = "content://media/external/audio/albumart"

/** E67：scan() 命中并发守卫时的最长等待时长与轮询间隔 */
private const val SCAN_WAIT_TIMEOUT_MS = 10_000L
private const val SCAN_WAIT_POLL_MS = 100L

/**
 * 用户授权目录（SAF tree）解析出的扫描范围（E24）。
 *
 * 关键约束：**任何分支都不得回退"全量扫描整机"**——
 * 非主存储（SD 卡 / OTG）按卷过滤；tree 无法解析时拒绝扫描（安全优先于可用性）。
 */
internal sealed interface ScanScope {
    /** 主存储（`primary:...`）；relativePath=null 表示用户选的就是存储根目录（全量是明确意图） */
    data class Primary(val relativePath: String?) : ScanScope

    /** 可移动卷（SD 卡 / OTG，如 `1234-5678:Movies`）；relativePath=null 表示卷根目录 */
    data class Volume(val name: String, val relativePath: String?) : ScanScope

    /** 无法解析（异常 / 非预期格式）→ 拒绝扫描 */
    data object Unresolvable : ScanScope
}

/** 纯函数：SAF tree docId → [ScanScope]（供单测） */
internal fun parseScanScope(docId: String): ScanScope {
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2 || parts[0].isBlank()) return ScanScope.Unresolvable
    val relativePath = parts[1].trim('/').ifEmpty { null }
    return if (parts[0] == "primary") {
        ScanScope.Primary(relativePath)
    } else {
        ScanScope.Volume(parts[0], relativePath)
    }
}

internal fun scanScopeFromTree(treeUriStr: String): ScanScope = try {
    parseScanScope(DocumentsContract.getTreeDocumentId(treeUriStr.toUri()))
} catch (_: Exception) {
    ScanScope.Unresolvable
}

/** 设置页展示用完整描述（如 "内部存储/Movies"）；无法解析时为 null */
internal fun describeTree(treeUriStr: String): String? = when (val scope = scanScopeFromTree(treeUriStr)) {
    is ScanScope.Primary -> scope.relativePath?.let { "内部存储/$it" } ?: "内部存储（根目录）"
    is ScanScope.Volume -> scope.relativePath?.let { "${scope.name}/$it" } ?: scope.name
    ScanScope.Unresolvable -> null
}
