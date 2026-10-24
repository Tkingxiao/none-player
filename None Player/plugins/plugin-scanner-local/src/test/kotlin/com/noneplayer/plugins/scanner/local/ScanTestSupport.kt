package com.noneplayer.plugins.scanner.local

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.ExtensionRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import kotlin.reflect.KClass

/**
 * R0 扫描级回归的测试基建（REV-01 / REV-03 / REV-12 / REV-13）。
 *
 * 三块能力：
 * 1. [FakeMediaStore] / [FakeDocumentsProvider]：用**真实 SQLite 过滤**的假
 *    ContentProvider 顶替系统 MediaStore 与 SAF 文档提供者，可注入"查询抛异常 /
 *    返回 null / 旧 Provider 拒绝未知列 / 子目录遍历失败 / 预算不足"等故障；
 * 2. [ScanHarness]：把 [LocalMediaSource] 装进 Robolectric 环境（DataStore、Room、
 *    持久化 URI 授权、权限授予/收回），用例直接 `harness.source.scan()` 驱动；
 * 3. 若干断言辅助：快照字节、缩略图目录、扫描摘要 note。
 *
 * 这些类都只用于测试，不进入生产代码。
 */
internal object ScanTestTree {
    const val DOCS_AUTHORITY = "com.android.externalstorage.documents"

    /** 主卷 Movies：`getTreeDocumentId` → `primary:Movies` → [ScanScope.Primary]("Movies")。 */
    const val MAIN_TREE = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
    const val MAIN_TREE_DOC_ID = "primary:Movies"

    /** 可移动卷（模拟 SD 卡）Movies：→ [ScanScope.Volume]("1234-ABCD", "Movies")。 */
    const val SECOND_TREE =
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMovies"
    const val SECOND_TREE_DOC_ID = "1234-ABCD:Movies"

    /** 主存储根（Robolectric 下由 [Environment.getExternalStorageDirectory] 决定）。 */
    fun externalRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /** 主卷 Movies 里的文件绝对路径（pre-Q 的 DATA 列用）。 */
    fun mainVolumeDataPath(displayName: String): String =
        "${externalRootPath()}/Movies/$displayName"

    /** 可移动卷里的文件绝对路径（pre-Q 的 DATA 列用）。 */
    fun secondVolumeDataPath(displayName: String): String =
        "/storage/1234-ABCD/Movies/$displayName"
}

// ---------------------------------------------------------------------------
// 假 MediaStore Provider
// ---------------------------------------------------------------------------

/**
 * 假 MediaStore（authority = `media`）。
 *
 * 用内存 SQLite 建 video / audio / files 三张表，查询时把 selection / selectionArgs
 * 原样交给 `rawQuery`——也就是说 [LocalMediaSource] 里 `ScanScopeQuery` 生成的
 * 目录边界 SQL 会被**真实执行**（REV-02 的越界问题能被这类用例抓到）。
 *
 * 故障注入（`query` 抛异常 / 返回 null / 旧 Provider 拒绝 RELATIVE_PATH）在测试里
 * 逐条打开，模拟"子查询失败但扫描仍要继续"的现实场景。
 */
internal class FakeMediaStore(
    val authority: String = "media",
) : ContentProvider() {

    companion object {
        const val VIDEO_TABLE = "video"
        const val AUDIO_TABLE = "audio"
        const val FILES_TABLE = "files"
        const val PRIMARY_VOLUME = "external_primary"
        const val DEFAULT_DATE_ADDED = 1_700_000_000L
        const val DEFAULT_DATE_MODIFIED = 1_699_999_000L

        /** MediaStore 列名（与 `MediaStore.*.Media` 常量一致），供建表与默认投影用。 */
        val COLUMNS = listOf(
            "_id",
            "_data",
            "relative_path",
            "volume_name",
            "_display_name",
            "duration",
            "_size",
            "date_modified",
            "date_added",
            "mime_type",
            "width",
            "height",
            "album_id",
            "artist",
            "title",
            "is_music",
            "media_type",
        )
    }

    private val db: SQLiteDatabase = SQLiteDatabase.create(null)

    /** 视频集合查询抛异常（模拟 Provider 崩溃 / 权限被系统拒绝）。 */
    var failVideoQuery: Boolean = false

    /** 音频集合查询抛异常。 */
    var failAudioQuery: Boolean = false

    /** files 集合（无扩展名文件拾取）查询抛异常。 */
    var failUnknownQuery: Boolean = false

    /** 视频集合查询返回 null（模拟旧 Provider 的 null cursor）。 */
    var returnNullVideoQuery: Boolean = false

    /** 音频集合查询返回 null。 */
    var returnNullAudioQuery: Boolean = false

    /**
     * 旧 Provider 行为：投影里出现 `relative_path` / `volume_name` 时直接抛
     * IllegalArgumentException（模拟 API 26–28 上没有这两列的 Provider）。
     */
    var rejectUnknownColumns: Boolean = false

    var videoQueryCount: Int = 0
        private set
    var audioQueryCount: Int = 0
        private set
    var unknownQueryCount: Int = 0
        private set

    /** 最近一次视频查询的投影（断言 REV-12 的按 SDK 裁剪）。 */
    var lastVideoProjection: List<String>? = null
        private set
    var lastAudioProjection: List<String>? = null
        private set

    init {
        listOf(VIDEO_TABLE, AUDIO_TABLE, FILES_TABLE).forEach { db.execSQL(createTableSql(it)) }
    }

    // ---- 数据写入便捷方法 ----

    fun insertVideo(
        id: Long,
        displayName: String,
        relativePath: String? = "Movies/",
        data: String? = null,
        mimeType: String = "video/mp4",
        size: Long = 1_000_000L,
        durationMs: Long = 60_000L,
        dateAdded: Long = DEFAULT_DATE_ADDED,
        dateModified: Long = DEFAULT_DATE_MODIFIED,
        volumeName: String = PRIMARY_VOLUME,
        width: Int = 1920,
        height: Int = 1080,
    ) {
        insert(
            table = VIDEO_TABLE,
            values = contentValues(
                id = id,
                displayName = displayName,
                relativePath = relativePath,
                data = data ?: defaultDataPath(displayName, relativePath, volumeName),
                mimeType = mimeType,
                size = size,
                durationMs = durationMs,
                dateAdded = dateAdded,
                dateModified = dateModified,
                volumeName = volumeName,
                width = width,
                height = height,
            ),
        )
    }

    fun insertAudio(
        id: Long,
        displayName: String,
        relativePath: String? = "Movies/",
        data: String? = null,
        mimeType: String = "audio/mpeg",
        size: Long = 500_000L,
        durationMs: Long = 30_000L,
        dateAdded: Long = DEFAULT_DATE_ADDED,
        dateModified: Long = DEFAULT_DATE_MODIFIED,
        volumeName: String = PRIMARY_VOLUME,
        albumId: Long = 0L,
        artist: String? = null,
        title: String? = null,
        isMusic: Boolean = true,
    ) {
        insert(
            table = AUDIO_TABLE,
            values = contentValues(
                id = id,
                displayName = displayName,
                relativePath = relativePath,
                data = data ?: defaultDataPath(displayName, relativePath, volumeName),
                mimeType = mimeType,
                size = size,
                durationMs = durationMs,
                dateAdded = dateAdded,
                dateModified = dateModified,
                volumeName = volumeName,
                albumId = albumId,
                artist = artist,
                title = title,
                isMusic = isMusic,
            ),
        )
    }

    /** 清空三张表（"空但完整"的扫描基线）。 */
    fun clearAll() {
        listOf(VIDEO_TABLE, AUDIO_TABLE, FILES_TABLE).forEach { db.delete(it, null, null) }
    }

    fun clearVideo() = db.delete(VIDEO_TABLE, null, null)

    fun clearAudio() = db.delete(AUDIO_TABLE, null, null)

    fun resetCounters() {
        videoQueryCount = 0
        audioQueryCount = 0
        unknownQueryCount = 0
        lastVideoProjection = null
        lastAudioProjection = null
    }

    // ---- ContentProvider ----

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val table = tableOf(uri) ?: return emptyCursor(projection)
        val columns = projection?.takeIf { it.isNotEmpty() }?.map { it } ?: COLUMNS
        when (table) {
            VIDEO_TABLE -> {
                videoQueryCount++
                lastVideoProjection = columns
                if (failVideoQuery) throw IllegalStateException("注入故障：视频查询失败")
                if (returnNullVideoQuery) return null
            }
            AUDIO_TABLE -> {
                audioQueryCount++
                lastAudioProjection = columns
                if (failAudioQuery) throw IllegalStateException("注入故障：音频查询失败")
                if (returnNullAudioQuery) return null
            }
            FILES_TABLE -> {
                unknownQueryCount++
                if (failUnknownQuery) throw IllegalStateException("注入故障：files 查询失败")
            }
        }
        if (rejectUnknownColumns) {
            val unknown = columns.firstOrNull { it == "relative_path" || it == "volume_name" }
            if (unknown != null) {
                throw IllegalArgumentException("no such column: $unknown (旧 Provider)")
            }
        }
        val sql = buildString {
            append("SELECT ")
            append(columns.joinToString(", "))
            append(" FROM ")
            append(table)
            if (!selection.isNullOrBlank()) {
                append(" WHERE ")
                append(selection)
            }
            if (!sortOrder.isNullOrBlank()) {
                append(" ORDER BY ")
                append(sortOrder)
            }
        }
        return db.rawQuery(sql, selectionArgs?.toList()?.toTypedArray())
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("测试里请用 insertVideo/insertAudio")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // ---- 内部 ----

    private fun insert(table: String, values: ContentValues) {
        db.insertOrThrow(table, null, values)
    }

    private fun contentValues(
        id: Long,
        displayName: String,
        relativePath: String?,
        data: String?,
        mimeType: String,
        size: Long,
        durationMs: Long,
        dateAdded: Long,
        dateModified: Long,
        volumeName: String,
        width: Int = 0,
        height: Int = 0,
        albumId: Long = 0L,
        artist: String? = null,
        title: String? = null,
        isMusic: Boolean = false,
    ): ContentValues = ContentValues().apply {
        put("_id", id)
        put("_data", data)
        put("relative_path", relativePath)
        put("volume_name", volumeName)
        put("_display_name", displayName)
        put("duration", durationMs)
        put("_size", size)
        put("date_modified", dateModified)
        put("date_added", dateAdded)
        put("mime_type", mimeType)
        put("width", width)
        put("height", height)
        put("album_id", albumId)
        put("artist", artist)
        put("title", title)
        put("is_music", if (isMusic) 1 else 0)
        put("media_type", 0)
    }

    private fun defaultDataPath(
        displayName: String,
        relativePath: String?,
        volumeName: String,
    ): String {
        val root = if (volumeName == PRIMARY_VOLUME) {
            ScanTestTree.externalRootPath()
        } else {
            "/storage/$volumeName"
        }
        val rel = relativePath?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (rel == null) "$root/$displayName" else "$root/$rel/$displayName"
    }

    private fun tableOf(uri: Uri): String? {
        val segments = uri.pathSegments
        return when {
            segments.contains("video") -> VIDEO_TABLE
            segments.contains("audio") -> AUDIO_TABLE
            segments.contains("file") -> FILES_TABLE
            else -> null
        }
    }

    private fun emptyCursor(projection: Array<out String>?): Cursor =
        MatrixCursor(projection?.map { it }?.toTypedArray() ?: arrayOf("_id"))

    private fun createTableSql(table: String): String = """
        CREATE TABLE $table (
            _id INTEGER PRIMARY KEY,
            _data TEXT,
            relative_path TEXT,
            volume_name TEXT,
            _display_name TEXT,
            duration INTEGER DEFAULT 0,
            _size INTEGER DEFAULT 0,
            date_modified INTEGER DEFAULT 0,
            date_added INTEGER DEFAULT 0,
            mime_type TEXT,
            width INTEGER DEFAULT 0,
            height INTEGER DEFAULT 0,
            album_id INTEGER DEFAULT 0,
            artist TEXT,
            title TEXT,
            is_music INTEGER DEFAULT 0,
            media_type INTEGER DEFAULT 0
        )
    """.trimIndent()
}

// ---------------------------------------------------------------------------
// 假 DocumentsProvider（SAF）
// ---------------------------------------------------------------------------

/**
 * 假 SAF 文档提供者（authority = `com.android.externalstorage.documents`）。
 *
 * 只实现 `query`：处理 `DocumentsContract.buildChildDocumentsUriUsingTree(...)` 生成的
 * children URI，返回 `DOCUMENT_ID / DISPLAY_NAME / MIME_TYPE / SIZE / LAST_MODIFIED`
 * 五列。隐藏目录遍历（[LocalMediaSource.queryHiddenDocuments]）就是靠它喂数据。
 *
 * 故障注入：`failForDocId` 让某个目录的 children 查询抛异常；`maxChildren` 只返回
 * 前 N 个（模拟预算不足/部分结果）。
 */
internal class FakeDocumentsProvider(
    val authority: String = ScanTestTree.DOCS_AUTHORITY,
) : ContentProvider() {

    data class DocEntry(
        val docId: String,
        val displayName: String,
        val mimeType: String = "video/mp4",
        val size: Long = 1_024L,
        val lastModified: Long = 1_700_000_000_000L,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private val childrenByDocId = LinkedHashMap<String, List<DocEntry>>()

    /** 命中该 docId 的 children 查询直接抛异常（模拟遍历失败）。 */
    var failForDocId: String? = null

    /** 命中该 docId 的 children 查询返回 null（E205：provider 契约允许，不得当空目录）。 */
    var returnNullForDocId: String? = null

    /** 只返回前 N 个子项（模拟预算耗尽/部分结果）；null = 全部返回。 */
    var maxChildren: Int? = null

    /**
     * 每次 children 查询前的注入延迟（毫秒）。
     *
     * 取消用例靠它把「隐藏目录遍历」拉长到可观测的量级：walk 改成多车道后每条车道都在
     * 真实 I/O 里，没有延迟就无法在 walk 进行中按下取消。
     */
    var queryDelayMs: Long = 0

    /**
     * 动态子项（优先于 [setChildren] 的静态表）：按 docId 现场生成子项。
     *
     * 用来造「无底洞」目录链——静态表只能铺有限层，验不了 MAX_WALK_DEPTH（64）上限；
     * 返回 null 表示该 docId 交给静态表处理。
     */
    var dynamicChildren: ((treeDocId: String) -> List<DocEntry>?)? = null

    var queryCount: Int = 0
        private set

    fun setChildren(treeDocId: String, children: List<DocEntry>) {
        childrenByDocId[treeDocId] = children
    }

    fun addChildren(treeDocId: String, vararg children: DocEntry) {
        childrenByDocId[treeDocId] = childrenByDocId[treeDocId].orEmpty() + children
    }

    fun clearChildren() {
        childrenByDocId.clear()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queryCount++
        if (queryDelayMs > 0L) Thread.sleep(queryDelayMs)
        val columns = projection?.takeIf { it.isNotEmpty() }?.map { it } ?: DEFAULT_PROJECTION.toList()
        val docId = parentDocId(uri) ?: return MatrixCursor(columns.toTypedArray())
        if (docId == failForDocId) {
            throw IllegalStateException("注入故障：SAF children 查询失败（$docId）")
        }
        if (docId == returnNullForDocId) {
            // E205：provider 对 children 查询返回 null（契约允许）——不是空目录
            return null
        }
        val all = dynamicChildren?.invoke(docId) ?: childrenByDocId[docId].orEmpty()
        val limited = maxChildren?.let { all.take(it) } ?: all
        val cursor = MatrixCursor(columns.toTypedArray())
        limited.forEach { entry ->
            cursor.addRow(columns.map { columnValue(it, entry) })
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        throw FileNotFoundException("假 SAF Provider 不提供文件内容：$uri")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun parentDocId(uri: Uri): String? {
        val segments = uri.pathSegments
        val treeIndex = segments.indexOf("tree")
        if (treeIndex < 0) return null
        var documentIndex = -1
        for (index in treeIndex + 1 until segments.size) {
            if (segments[index] == "document") {
                documentIndex = index
                break
            }
        }
        if (documentIndex < 0 || documentIndex + 1 >= segments.size) return null
        return segments[documentIndex + 1]
    }

    private fun columnValue(column: String, entry: DocEntry): Any? = when (column) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> entry.docId
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.displayName
        DocumentsContract.Document.COLUMN_MIME_TYPE -> entry.mimeType
        DocumentsContract.Document.COLUMN_SIZE -> entry.size
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> entry.lastModified
        else -> null
    }

    private companion object {
        val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

// ---------------------------------------------------------------------------
// 简单替身：EventBus / ExtensionRegistry / DispatcherProvider
// ---------------------------------------------------------------------------

internal class RecordingEventBus : EventBus {
    val published: MutableList<Any> = mutableListOf()

    override fun publish(event: Any) {
        published += event
    }

    override fun <T : Any> subscribe(clazz: KClass<T>): Flow<T> = flowOf()
}

internal class InMemoryExtensionRegistry : ExtensionRegistry {
    private val byType = LinkedHashMap<Class<*>, MutableList<Any>>()

    override fun <T : ExtensionPoint> register(clazz: Class<T>, extension: T) {
        byType.getOrPut(clazz) { mutableListOf() } += extension
    }

    override fun <T : ExtensionPoint> unregister(clazz: Class<T>, extension: T) {
        byType[clazz]?.remove(extension)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ExtensionPoint> query(clazz: Class<T>): List<T> =
        byType[clazz].orEmpty().toList() as List<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : ExtensionPoint> observe(clazz: Class<T>): Flow<List<T>> =
        flowOf(query(clazz))
}

/**
 * 测试调度器：`io` 用真实 [Dispatchers.IO]（扫描会真的并发跑在后台线程上，
 * 这样 `beforeCommitHook` 的挂起/取消语义才和线上一致）；main/default 用 Unconfined，
 * 避免 Robolectric 主线程 Looper 未驱动导致的挂起。
 */
internal object TestDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.IO
}

// ---------------------------------------------------------------------------
// DataStore 单例复位
// ---------------------------------------------------------------------------

/**
 * 复位 `AppSettings` 的 DataStore 单例。
 *
 * `preferencesDataStore` 的委托把 DataStore 实例缓存在**静态字段**里（首次访问时
 * 用当时的 `applicationContext.filesDir` 决定文件路径）。Robolectric 每个测试方法
 * 都会换一个全新的临时目录，但静态单例还指着上一个已删除的目录——第二个用例开始
 * 的写入就会 FileNotFoundException。这里在 `@Before` 把缓存的 DataStore 清掉，
 * 让下一个用例重新按新目录创建。
 *
 * 注意：**不能** cancel 委托的 `scope` 字段——委托只持有一个 scope 并复用于之后
 * 创建的每个 DataStore 实例；cancel 它会让后续所有 DataStore 写入立刻抛
 * JobCancellationException（表现为整批用例失败）。只清 INSTANCE 即可。
 */
internal object DataStoreTestReset {
    fun reset() {
        // E211 起 AppSettings 有两份 DataStore（主设置 + SAF 目录）；只清主设置会让
        // saf_folders 单例继续指向上一个测试的临时目录，跨用例复用扫描目录。
        listOf("dataStore\$delegate", "safFoldersStore\$delegate").forEach { fieldName ->
            runCatching {
                val fileClass = Class.forName("com.noneplayer.core.data.AppSettingsKt")
                val delegateField = fileClass.getDeclaredField(fieldName)
                delegateField.isAccessible = true
                val delegate = delegateField.get(null) ?: return@forEach
                val instanceField = delegate.javaClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                instanceField.set(delegate, null)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 扫描门面
// ---------------------------------------------------------------------------

/**
 * [LocalMediaSource] 的 Robolectric 测试门面。
 *
 * 一个 harness 对应一个用例：注册两个假 Provider、按需授予媒体权限、写扫描目录
 * 设置与持久化 URI 授权，然后 `source.scan()` 直接驱动扫描（不依赖 ContentObserver）。
 */
internal class ScanHarness(
    val context: Context = ApplicationProvider.getApplicationContext<Context>(),
    val settings: AppSettings = AppSettings(context),
    val history: HistoryRepository = HistoryRepository(context),
    val mediaProvider: FakeMediaStore = FakeMediaStore(),
    val docsProvider: FakeDocumentsProvider = FakeDocumentsProvider(),
    sdkInt: Int = 34,
    // LocalMediaSource.HIDDEN_WALK_BUDGET 是文件私有常量，测试侧用同值字面量（20_000）。
    hiddenWalkBudget: Int = 20_000,
    // 目录级增量索引默认**关闭**：既有用例断言的是「每轮都完整走一遍」的 IPC 计数与结果，
    // 索引默认开会把那些断言从「IPC 次数」维度上打乱。接线用例显式传 true 并给独立临时文件。
    val walkIndexEnabled: Boolean = false,
    val walkIndexTtlMs: Long = ScanWalkIndex.DEFAULT_TTL_MS,
    // 默认文件名与生产一致（LocalMediaSource.walkIndexFile / scan-contract §3）：scan_walk_index.json。
    val walkIndexFile: File = File(context.filesDir, "scan_walk_index.json"),
) {

    val eventBus = RecordingEventBus()
    val registry = InMemoryExtensionRegistry()

    val source: LocalMediaSource = LocalMediaSource(
        context = context,
        dispatchers = TestDispatcherProvider,
        eventBus = eventBus,
        settings = settings,
        registry = registry,
        history = history,
        processLifecycle = null,
        sdkInt = sdkInt,
        hiddenWalkBudget = hiddenWalkBudget,
        walkIndexEnabled = walkIndexEnabled,
        walkIndexFile = walkIndexFile,
        walkIndexTtlMs = walkIndexTtlMs,
    )

    init {
        ShadowContentResolver.registerProviderInternal(mediaProvider.authority, mediaProvider)
        ShadowContentResolver.registerProviderInternal(docsProvider.authority, docsProvider)
    }

    // ---- 权限 ----

    fun grantVideo() = grant(Manifest.permission.READ_MEDIA_VIDEO)

    fun grantAudio() = grant(Manifest.permission.READ_MEDIA_AUDIO)

    /** 旧系统（API ≤ 32）唯一的存储权限。 */
    fun grantLegacyStorage() = grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun grantAllMediaPermissions() {
        grantVideo()
        grantAudio()
    }

    fun revokeVideo() = deny(Manifest.permission.READ_MEDIA_VIDEO)

    fun revokeAudio() = deny(Manifest.permission.READ_MEDIA_AUDIO)

    fun revokeAllMediaPermissions() {
        revokeVideo()
        revokeAudio()
    }

    private fun grant(permission: String) {
        Shadows.shadowOf(context.applicationContext as Application).grantPermissions(permission)
    }

    private fun deny(permission: String) {
        Shadows.shadowOf(context.applicationContext as Application).denyPermissions(permission)
    }

    // ---- 扫描目录 ----

    /** 只申请持久化 URI 授权，不写目录设置（模拟用户在系统选择器里点"允许"）。 */
    fun grantPersistedUriPermission(treeUriStr: String) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(treeUriStr),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** 写扫描目录设置并申请持久化 URI 授权（两者缺一，目录都会被判定为"已撤销"）。 */
    suspend fun setFolders(vararg treeUriStrs: String) {
        settings.setScanFolders(treeUriStrs.toList())
        treeUriStrs.forEach { tree -> grantPersistedUriPermission(tree) }
    }

    // ---- 历史 / 收藏 ----

    /** 直接把历史写进 Room（`markPlayed`），并默认加收藏。 */
    suspend fun seedHistory(vararg items: MediaItem, favorite: Boolean = true) {
        items.forEach { item ->
            history.markPlayed(item)
            if (favorite) history.toggleFavorite(item)
        }
    }

    // ---- 扫描 ----

    /** 同步跑一次扫描（suspend，测试里在 runBlocking 中调用）。 */
    suspend fun scan() = source.scan()

    suspend fun items(): List<MediaItem> = source.observeMedia().first()

    suspend fun historyCount(): Int = history.count.first()

    suspend fun isFavorite(item: MediaItem): Boolean = history.isFavorite(historyKeyOf(item)).first()

    fun historyKeyOf(item: MediaItem): String = "${item.sourceId}:${item.id}"

    fun lastScanNote(): String? = source.lastScan.value?.note

    // ---- 快照 / 缩略图 ----

    fun snapshotFile(): File = File(context.filesDir, "scan_index.json")

    fun snapshotBytes(): ByteArray? = snapshotFile().takeIf { it.isFile }?.readBytes()

    /** 快照里的条目数；快照不存在返回 null。 */
    fun snapshotItemCount(): Int? {
        val file = snapshotFile()
        if (!file.isFile) return null
        return runCatching {
            org.json.JSONObject(file.readText()).getJSONArray("items").length()
        }.getOrNull()
    }

    /** 真机/单测共用的扫描时间线文件（ScanTrace 落盘于 filesDir）。 */
    fun scanTraceFile(): File = File(context.filesDir, "scan_trace.log")

    /** 目录遍历索引文件（用例里应给独立临时路径，避免跨用例串味）。 */
    fun walkIndexFileOnDisk(): File = walkIndexFile

    /** 索引文件是否已落盘（取消/预算耗尽时应当为 false）。 */
    fun walkIndexSaved(): Boolean = walkIndexFile.isFile && walkIndexFile.length() > 0

    fun thumbsDir(): File = File(context.cacheDir, "video_thumbs")

    fun writeThumbs(vararg names: String) {
        val dir = thumbsDir()
        dir.mkdirs()
        names.forEach { File(dir, "$it.jpg").writeBytes(byteArrayOf(1, 2, 3)) }
    }

    fun thumbFileNames(): List<String> =
        thumbsDir().listFiles()?.map { it.name }?.sorted().orEmpty()

    // ---- 便捷断言素材 ----

    /** 主卷 Movies 的两个视频 + 两个音频（id: ms-1 / ms-2 / msa-11 / msa-12）。 */
    fun insertDefaultLibrary() {
        mediaProvider.insertVideo(1, "movie-a.mp4")
        mediaProvider.insertVideo(2, "movie-b.mp4")
        mediaProvider.insertAudio(11, "song-a.mp3")
        mediaProvider.insertAudio(12, "song-b.mp3")
    }

    fun videoItem(id: Long, displayName: String): MediaItem = MediaItem(
        id = "ms-$id",
        sourceId = "local",
        title = displayName.substringBeforeLast('.'),
        uri = "content://media/external/video/media/$id",
    )

    fun audioItem(id: Long, displayName: String): MediaItem = MediaItem(
        id = "msa-$id",
        sourceId = "local",
        title = displayName,
        uri = "content://media/external/audio/media/$id",
        mimeType = "audio/mpeg",
    )
}
