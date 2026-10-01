package com.noneplayer.plugins.scanner.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.KernelEvent
import com.noneplayer.kernel.api.MediaSourceProvider
import com.noneplayer.kernel.api.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * 本地媒体来源——基于 MediaStore 的设备视频扫描。
 *
 * 门控策略：仅在用户通过 SAF 授权了扫描目录后才扫描（未选择目录时
 * scan 为空操作），目录列表由 [AppSettings.scanFolderUris] 持久化。
 *
 * 缩略图：扫描时把系统缩略图缓存到 cacheDir（已存在则复用），
 * 媒体卡直接读文件，避免大视频现场抽帧卡顿。
 *
 * 增删同步：注册 MediaStore ContentObserver，文件变化去抖 2s 后自动重扫。
 */
class LocalMediaSource(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val eventBus: EventBus,
    val settings: AppSettings,
) : MediaSourceProvider {

    override val sourceId: String = "local"
    override val displayName: String = "本地视频"

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

    override suspend fun isAvailable(): Boolean = hasMediaPermission(context)

    override suspend fun scan(): ScanResult = withContext(dispatchers.io) {
        if (_scanning.value) {
            return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }
        val folderUriStrs = settings.scanFolderUris.first()
        if (folderUriStrs.isEmpty()) {
            return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }

        _scanning.value = true
        try {
            val startedAt = System.currentTimeMillis()
            // E24：无法解析的目录绝不回退"全量扫描整机"；全部无法解析时拒绝扫描
            val scopes = folderUriStrs.map { scanScopeFromTree(it) }
            val validScopes = scopes.filterNot { it is ScanScope.Unresolvable }
            if (validScopes.isEmpty()) {
                Log.w(TAG, "授权目录均无法解析，已拒绝扫描: $folderUriStrs")
                _lastScan.value = LocalScanSummary(
                    added = 0,
                    removed = 0,
                    total = _media.value.size,
                    durationMs = 0,
                    note = "授权目录无法解析，已拒绝扫描（不会扫描其他位置）",
                )
                return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
            }
            val previousIds = _media.value.map { it.id }.toSet()
            val items = queryMediaStore(validScopes)
            // B7：清理已删除视频对应的缩略图缓存
            val validIds = items.map { it.id.removePrefix("ms-") }.toSet()
            File(context.cacheDir, "video_thumbs")
                .listFiles()
                ?.forEach { f -> if (f.nameWithoutExtension !in validIds) f.delete() }
            _media.value = items
            // E30（B7 收尾）：按 id 集合差集统计，而非净值——
            // 同时增删时净值会互相抵消（新增 3 删 2 显示为 +1/0）
            val newIds = items.map { it.id }.toSet()
            val skippedCount = scopes.size - validScopes.size
            val summary = LocalScanSummary(
                added = (newIds - previousIds).size,
                removed = (previousIds - newIds).size,
                total = items.size,
                durationMs = System.currentTimeMillis() - startedAt,
                note = if (skippedCount > 0) "$skippedCount 个目录无法解析，已跳过" else null,
            )
            _lastScan.value = summary
            eventBus.publish(KernelEvent.MediaScanned(sourceId = sourceId, count = items.size))
            ScanResult(
                sourceId = sourceId,
                added = summary.added,
                updated = 0,
                removed = summary.removed,
                durationMs = summary.durationMs,
            )
        } finally {
            _scanning.value = false
        }
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
     * 监听 MediaStore 视频集合的变化（文件添加/删除），去抖后自动重扫。
     * 由插件 onStart 调用；onStop 调用 [stopObserving]。
     */
    fun startObserving() {
        if (contentObserver != null) return
        observerJob = observerScope.launch {
            mediaChanges.debounce(2_000).collect {
                if (it > 0) scan()
            }
        }
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                mediaChanges.value = System.currentTimeMillis()
            }
        }
        context.contentResolver.registerContentObserver(videoCollection(), true, contentObserver!!)
    }

    fun stopObserving() {
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        observerJob?.cancel()
        observerJob = null
    }

    /** MediaStore 视频集合（API 26–28 用常量 URI，29+ 用带卷名的getContentUri） */
    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** 多目录查询：逐目录按授权范围过滤后按 id 去重合并（父/子目录重叠安全） */
    private fun queryMediaStore(scopes: List<ScanScope>): List<MediaItem> {
        val merged = LinkedHashMap<String, MediaItem>()
        scopes.forEach { scope -> queryScope(scope).forEach { merged[it.id] = it } }
        return merged.values.sortedByDescending { it.addedAtMs }
    }

    private fun queryScope(scope: ScanScope): List<MediaItem> {
        // E24：按授权范围构建过滤；Unresolvable 不应到达此处，防御性返回空
        val selection: String?
        val selectionArgs: Array<String>?
        when (scope) {
            is ScanScope.Unresolvable -> return emptyList()
            is ScanScope.Primary -> {
                val relativePath = scope.relativePath
                if (relativePath != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                        selectionArgs = arrayOf("$relativePath%")
                    } else {
                        selection = "${MediaStore.Video.Media.DATA} LIKE ?"
                        selectionArgs = arrayOf("%/$relativePath/%")
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
                        "${MediaStore.MediaColumns.VOLUME_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    } else {
                        "${MediaStore.MediaColumns.VOLUME_NAME} = ?"
                    }
                    selectionArgs = if (relativePath != null) {
                        arrayOf(scope.name, "$relativePath%")
                    } else {
                        arrayOf(scope.name)
                    }
                } else {
                    val root = "/storage/${scope.name}"
                    selection = "${MediaStore.Video.Media.DATA} LIKE ?"
                    selectionArgs = arrayOf(
                        if (scope.relativePath != null) "$root/${scope.relativePath}/%" else "$root/%",
                    )
                }
            }
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA,
        )
        val primaryRelativePath = (scope as? ScanScope.Primary)?.relativePath
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            videoCollection(),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dataPath = cursor.getString(dataCol).orEmpty()
                val item = MediaItem(
                    id = "ms-$id",
                    sourceId = sourceId,
                    title = cursor.getString(nameCol).orEmpty().ifEmpty { "未命名视频" },
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mimeType = cursor.getString(mimeCol) ?: "video/mp4",
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = cursor.getLong(sizeCol),
                    addedAtMs = cursor.getLong(addedCol) * 1000,
                    coverUri = thumbnailFor(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)),
                    folder = folderOf(dataPath, primaryRelativePath),
                )
                items += item
            }
        }
        return items
    }

    /** 从完整路径提取展示用文件夹（去掉主存储前缀，如 "DCIM/Camera"） */
    private fun folderOf(dataPath: String, relativePath: String?): String? {
        if (dataPath.isEmpty()) return null
        val dir = dataPath.substringBeforeLast('/')
            .removePrefix("/storage/emulated/0/")
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
}

private const val TAG = "LocalMediaSource"

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
