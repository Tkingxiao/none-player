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
import android.os.SystemClock
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.noneplayer.core.common.DispatcherProvider
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.LOCAL_MEDIA_SOURCE_ID
import com.noneplayer.core.database.HistoryEntry
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
import com.noneplayer.kernel.api.ScanFolderController
import com.noneplayer.kernel.api.ScanResult
import com.noneplayer.kernel.api.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
) : MediaSourceProvider, CacheProvider, ScanFolderController {

    override val sourceId: String = LOCAL_MEDIA_SOURCE_ID
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

    /**
     * **扫描索引快照**：上次扫描结果的落盘副本（`filesDir/scan_index.json`）。
     *
     * 存在理由（两件事共用一份存储）：
     *  - 冷启动首帧：媒体列表此前只在内存里，进程一死就要等一整轮全量扫描
     *    （大库是分钟级）——"不加载好不进去"（用户实报）。快照让 UI 挂载即有内容，
     *    扫描在后台做增量刷新。
     *  - 增量扫描的对照组：`scanInternal` 以快照为基线做**按 id 对比**——
     *    未变的项直接复用（跳过富化/嗅探/封面生成），只对新增与变更项跑完整管线。
     *    `added/removed` 统计因此在跨进程的两次扫描之间也成立（旧实现每次冷启都为 0）。
     *
     * 写入时机：每次扫描完成、发布之后。失败（磁盘满/IO 错）不拖垮扫描——缓存不是真相。
     */
    private val snapshotFile get() = File(context.filesDir, "scan_index.json")

    /** 快照/本进程扫描是否已产出过一次数据——`mediaSnapshot()` 的判据（区分"还没扫"与"确实空"） */
    @Volatile
    private var hasSnapshot = false

    override fun mediaSnapshot(): List<MediaItem>? =
        if (hasSnapshot || _lastScan.value != null) _media.value else null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastScan = MutableStateFlow<LocalScanSummary?>(null)
    val lastScan: StateFlow<LocalScanSummary?> = _lastScan.asStateFlow()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaChanges = MutableStateFlow(0L)
    private var contentObserver: ContentObserver? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var lifecycleJob: kotlinx.coroutines.Job? = null

    /** 上一次扫描完成时刻（[SystemClock.elapsedRealtime] 基准，A5/P2-2）；0 = 还没扫过 */
    @Volatile
    private var lastScanFinishedAt = 0L

    /**
     * 扫描互斥闸门。
     *
     * `_scanning` 是给 UI 看的 StateFlow，**不能当锁用**：scan() 跑在
     * `dispatchers.io`（多线程），`if (_scanning.value) …` 与随后的 `_scanning.value = true`
     * 之间没有原子性。下拉刷新、选目录、`onStart`、ContentObserver 去抖四条路径
     * 可以同时通过那道判断，跑出两份全量 MediaStore 查询；后完成的那次还会用陈旧结果
     * 覆盖 `_media` 并广播一条假的 MediaScanned。判重只认这个 CAS 闸门。
     */
    private val scanGate = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun isAvailable(): Boolean = hasMediaPermission(context)

    override suspend fun scan(): ScanResult = withContext(dispatchers.io) {
        if (!acquireScanGate()) {
            return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
        }
        _scanning.value = true
        try {
            val folderUriStrs = settings.scanFolderUris.first()
            if (folderUriStrs.isEmpty()) {
                return@withContext ScanResult(sourceId, added = 0, updated = 0, removed = 0, durationMs = 0)
            }
            // E59 修复：scan() 本体现在整体包一层 runCatching——任何异常（如 OEM
            // MediaStore 列缺失、Provider 查询失败）都不再向上冒泡崩溃插件 onStart()
            // 乃至拖垮整个内核启动，而是落成一条带原因的失败摘要。
            runCatching { scanInternal(folderUriStrs) }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
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
            scanGate.set(false)
            // A5/P2-2：记下「这次扫描完成于何时」，回前台的补扫据此去重
            lastScanFinishedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 抢扫描闸门。拿到返回 true；有人正在扫则短轮询等它结束再抢一次——
     * E67 的语义要保留：ContentObserver 的 debounce 已经消费掉那次发射，
     * 这里的请求再被丢弃就等于永久漏掉这批文件增删。
     *
     * 等超时或抢不到（说明又有人开跑）时返回 false，交给持有方，避免 N 个调用者
     * 排队跑 N 次全量查询。
     */
    private suspend fun acquireScanGate(): Boolean {
        if (scanGate.compareAndSet(false, true)) return true
        var waited = 0L
        while (scanGate.get() && waited < SCAN_WAIT_TIMEOUT_MS) {
            delay(SCAN_WAIT_POLL_MS)
            waited += SCAN_WAIT_POLL_MS
        }
        return scanGate.compareAndSet(false, true)
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
        val previousList = _media.value
        // 增量基线：上次发布（或本进程从快照恢复）的列表。按 id 复用未变项——
        // 富化 / 容器嗅探 / 封面生成只对新增与变更项跑；未变项连无扩展名文件的
        // 文件头读取都跳过（那是逐文件 I/O，大库上是最贵的一段）。
        val baseline = previousList.associateBy { it.id }
        val previousIds = baseline.keys
        val rawStore = queryMediaStore(validScopes)
            // 垃圾文件过滤（§10 批次 4）：下载中的 .part/.tmp 等半成品不进库
            .filterNot { isJunkFile(it.title, it.sizeBytes) }
        val storeItems = rawStore.map { fresh ->
            // 富化（E34 的消费端）：插件按注册顺序串行富化，失败不拖垮扫描。
            // 未变项直接沿用基线实例（带上它的富化结果与封面地址）。
            val prev = baseline[fresh.id]
            if (prev != null && prev.unchangedFrom(fresh)) {
                prev
            } else {
                // 容器嗅探（§12.4，批次 8）：无扩展名/错扩展名按文件头判容器，
                // mime 改对才会被引擎的 Extractor 链正确认领
                sniffMimeCorrection(enrichAll(fresh))
            }
        }
        // 无扩展名文件拾取（Files 集合查询 + 文件头嗅探）——同样支持基线复用
        val unknownItems = sniffUnknownFiles(validScopes, baseline)
        // L14：MediaStore 跳过隐藏目录（`.` 前缀），这里用 SAF 遍历把其中的媒体补齐。
        // 只补 MediaStore 看不到的那部分——按「相对路径 + 文件名」与已收项去重，
        // 非隐藏文件仍以 MediaStore 为唯一来源，不重复采集。
        // 去重键剥扩展名：无扩展名拾取（`msf-`）的标题已剥过扩展名，隐藏项用的是
        // 全文件名——不统一口径时同一文件可以同时以两种 id 进库（双份文件的来源之一）。
        fun dedupKey(item: MediaItem): String? =
            item.folder?.let { "$it/${item.title.substringBeforeLast('.')}" }

        val storePaths = (storeItems + unknownItems).asSequence()
            .mapNotNull(::dedupKey)
            .toHashSet()
        val hiddenItems = queryHiddenDocuments(liveFolders, baseline)
            .filterNot { item ->
                val path = dedupKey(item)
                path != null && path in storePaths
            }
            // 富化与 MediaStore 项同链路（短标题/集号对隐藏目录里的剧集一样有用）；
            // 复用项是基线**同一实例**（=== 判定），跳过；新增/变更项才走富化
            .map { item -> if (item === baseline[item.id]) item else enrichAll(item) }
        // 封面并发补齐放在**发布之前**：UI 侧拿到的列表永远是"封面已就绪"的那一份，
        // 不需要两段式刷新，也不会因为某一项封面慢而整页停在占位图。
        val items = attachCovers((storeItems + unknownItems + hiddenItems).distinctBy { it.id })
        // [诊断] 发布前自检：同 id 重复会以 Lazy 重复 key 直接打崩 UI（实报闪退），
        // 这里留一行日志把缺陷暴露在数据层，而不是让它到界面上炸（正常应为零输出）
        run {
            val dupKeys = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupKeys.isNotEmpty()) {
                Log.e(TAG, "[诊断] 发布列表出现重复 id：${dupKeys.take(5)}（共 ${dupKeys.size} 个）")
            }
        }
        // §8.3/E11：首次扫描把 DataStore 的断点迁入 Room，随后清旧键
        migrateHistoryOnce(items)
        // B7：清理已删除视频对应的缩略图缓存（音频走相册封面、不落缓存）。
        // L14：SAF 拾取的隐藏项也有缩略图，键一并纳入白名单，否则每轮扫描都会被删掉重建
        val validThumbKeys = items.mapNotNull { thumbKeyOf(it.id) }.toSet()
        File(context.cacheDir, "video_thumbs")
            .listFiles()
            ?.forEach { f -> if (f.nameWithoutExtension !in validThumbKeys) f.delete() }
        _media.value = items
        // E30（B7 收尾）：按 id 集合差集统计，而非净值——
        // 同时增删时净值会互相抵消（新增 3 删 2 显示为 +1/0）
        val newIds = items.map { it.id }.toSet()
        val skippedCount = scopes.size - validScopes.size
        val notes = buildList {
            if (skippedCount > 0) add("$skippedCount 个目录无法解析，已跳过")
            if (revoked.isNotEmpty()) add("${revoked.size} 个目录授权已被系统撤销，请重新选择或移除")
        }
        // L16：孤儿清理——只有本次扫描**覆盖了全部授权目录**（notes 为空）时才做。
        // 任一目录被跳过/撤销时结果集不完整，此刻删记录会把整个目录的断点误清；
        // 这批死记录留到下一次完整扫描再清即可，不会误导计数太久。
        if (notes.isEmpty()) {
            val pruned = history.pruneOrphans(items.map { HistoryEntry.keyOf(it) }.toSet(), sourceId)
            if (pruned > 0) Log.i(TAG, "孤儿清理（L16）：删除 $pruned 条失效播放历史")
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
        // 落盘基线：内容有变化才写（列表相等是 O(n) 比较，比一次几 MB 的 JSON 写便宜得多）。
        // 放在发布之后——UI 的刷新不被磁盘 I/O 拖住。
        if (items != previousList) saveSnapshot(items)
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
     * 增量复用判据：文件身份字段全部一致才复用基线实例。
     *
     * `lastModifiedSec == 0`（来源没给这个字段，如 Files 集合查询）时跳过该项比较；
     * `folder` 参与比较——移动文件不改大小与文件名，只有路径变。
     */
    private fun MediaItem.unchangedFrom(fresh: MediaItem): Boolean =
        title == fresh.title &&
            sizeBytes == fresh.sizeBytes &&
            folder == fresh.folder &&
            addedAtMs == fresh.addedAtMs &&
            (fresh.lastModifiedSec == 0L || lastModifiedSec == fresh.lastModifiedSec)

    // ---- 扫描索引快照（落盘基线：冷启动首帧 + 增量扫描对照组） ----

    /** 恢复上次扫描的落盘快照。只在本进程还没扫过、内存还没有列表时生效（防与并发扫描打架）。 */
    suspend fun restoreSnapshot() = withContext(dispatchers.io) {
        if (hasSnapshot || _lastScan.value != null || _media.value.isNotEmpty()) return@withContext
        val items = runCatching { readSnapshot() }.getOrNull() ?: return@withContext
        if (_lastScan.value != null || _media.value.isNotEmpty()) return@withContext
        // [诊断] 快照自检（同"发布前自检"）：正常应为零输出
        run {
            val dupKeys = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupKeys.isNotEmpty()) {
                Log.e(TAG, "[诊断] 快照恢复列表出现重复 id：${dupKeys.take(5)}（共 ${dupKeys.size} 个）")
            }
        }
        _media.value = items
        hasSnapshot = true
        Log.i(TAG, "扫描索引快照已恢复：${items.size} 项（冷启动首帧不再等全量扫描）")
    }

    /** 把当前列表写为下次启动的基线；失败静默——快照是加速增益，不是真相来源 */
    private fun saveSnapshot(items: List<MediaItem>) {
        runCatching {
            val arr = org.json.JSONArray()
            items.forEach { item ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("id", item.id)
                        put("sourceId", item.sourceId)
                        put("title", item.title)
                        put("uri", item.uri)
                        put("mimeType", item.mimeType)
                        put("durationMs", item.durationMs)
                        put("sizeBytes", item.sizeBytes)
                        put("lastModifiedSec", item.lastModifiedSec)
                        put("addedAtMs", item.addedAtMs)
                        item.coverUri?.let { put("coverUri", it) }
                        item.folder?.let { put("folder", it) }
                        put("width", item.width)
                        put("height", item.height)
                        item.displayTitle?.let { put("displayTitle", it) }
                        put("seasonNumber", item.seasonNumber)
                        put("episodeNumber", item.episodeNumber)
                        item.videoCodec?.let { put("videoCodec", it) }
                        put("clipStartUs", item.clipStartUs)
                        put("clipEndUs", item.clipEndUs)
                    },
                )
            }
            val payload = org.json.JSONObject()
                .put("version", SNAPSHOT_VERSION)
                .put("items", arr)
                .toString()
            // 先写临时文件再替换：写一半被进程杀死时旧快照仍完整
            val tmp = File(snapshotFile.absolutePath + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(snapshotFile)) {
                snapshotFile.delete()
                tmp.renameTo(snapshotFile)
            }
        }.onFailure { Log.w(TAG, "扫描索引快照写入失败（忽略，不影响本次结果）", it) }
    }

    /** 读快照；文件缺失/损坏/版本不符返回 null（按"确实还没扫过"处理）。封面指向已删除的缓存文件时置空。 */
    private fun readSnapshot(): List<MediaItem>? {
        val file = snapshotFile
        if (!file.isFile) return null
        val root = org.json.JSONObject(file.readText())
        if (root.optInt("version") != SNAPSHOT_VERSION) return null
        val arr = root.optJSONArray("items") ?: return null
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val uri = o.optString("uri")
            if (id.isEmpty() || uri.isEmpty()) continue
            out += MediaItem(
                id = id,
                sourceId = o.optString("sourceId").ifEmpty { sourceId },
                title = o.optString("title"),
                uri = uri,
                mimeType = o.optString("mimeType").ifEmpty { "video/mp4" },
                durationMs = o.optLong("durationMs"),
                sizeBytes = o.optLong("sizeBytes"),
                lastModifiedSec = o.optLong("lastModifiedSec"),
                addedAtMs = o.optLong("addedAtMs"),
                coverUri = o.optString("coverUri")
                    .takeIf { it.isNotEmpty() && File(it).isFile },
                folder = o.optString("folder").takeIf { it.isNotEmpty() },
                width = o.optInt("width"),
                height = o.optInt("height"),
                displayTitle = o.optString("displayTitle").takeIf { it.isNotEmpty() },
                seasonNumber = o.optInt("seasonNumber"),
                episodeNumber = o.optInt("episodeNumber"),
                videoCodec = o.optString("videoCodec").takeIf { it.isNotEmpty() },
                clipStartUs = o.optLong("clipStartUs"),
                clipEndUs = o.optLong("clipEndUs"),
            )
        }
        return out
    }

    /**
     * 富化链（E34 的消费端）：按注册顺序**串行**执行 `MetadataEnricher`，
     * 后一个看到前一个的输出（契约约定：实现必须幂等）；单个失败按原样跳过。
     */
    private suspend fun enrichAll(item: MediaItem): MediaItem {
        var current = item
        registry.query<MetadataEnricher>().forEach { enricher ->
            current = try {
                enricher.enrich(current)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                current
            }
        }
        return current
    }

    /**
     * 缩略图并发生成（性能批次）。
     *
     * 此前 `thumbnailFor(...)` 直接写在游标遍历里，于是整库视频**串行**等
     * `ContentResolver.loadThumbnail` / `MediaMetadataRetriever`：单片 1080p 就要几十毫秒，
     * 几百部叠起来是分钟级的首扫时间。而这一步排在 `_media.value = items` **之前**，
     * 挡住的正是"主页什么时候能出封面"。
     *
     * 现在把它挪到发布前并发跑：缓存命中的项只是几次 `File.exists()`（几乎零成本），
     * 未命中的项最多 [THUMB_PARALLELISM] 个同时抽帧。语义不变——仍然在发布前把封面
     * 全部备好，UI 不需要任何两段式刷新。
     */
    private suspend fun attachCovers(items: List<MediaItem>): List<MediaItem> {
        // 音频（`msa-`，含 .cue 分轨）走相册封面 URI，没有可生成的帧缩略图；
        // 其余项（MediaStore 视频 `ms-` / 隐藏目录文档 `saf-`）缺封面、或封面的
        // 缓存文件已被清掉（清缓存/系统清 cacheDir）时才补——只信 `coverUri != null`
        // 会让指向已删文件的旧地址永远不重生成。
        val pending = items.filter {
            // 先落成本地 val 再判：直接写 `File(it.coverUri)` 时属性链上的可空性
            // 跨模块（:core:media）不进 Smart Cast，Java 构造器会收到 String? 告警
            val cover = it.coverUri
            !it.id.startsWith(AUDIO_ID_PREFIX) && (cover == null || !File(cover).isFile)
        }
        if (pending.isEmpty()) return items
        val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
        val gate = Semaphore(THUMB_PARALLELISM)
        coroutineScope {
            pending.map { item ->
                async(dispatchers.io) {
                    gate.withPermit { coverFor(item)?.let { covers[item.id] = it } }
                }
            }.awaitAll()
        }
        if (covers.isEmpty()) return items
        return items.map { item -> covers[item.id]?.let { item.copy(coverUri = it) } ?: item }
    }

    /**
     * 封面生产口径（与 id 前缀一一对应，保证 `video_thumbs/<key>.jpg` 的键与
     * `thumbKeyOf` 的清理白名单一致）：
     * `ms-<id>` → 系统缩略图（低版本兜底抽帧）；`saf-…` → 文档 URI 缩略图 + 抽帧兜底。
     */
    private fun coverFor(item: MediaItem): String? = when {
        item.id.startsWith(VIDEO_ID_PREFIX) ->
            item.id.removePrefix(VIDEO_ID_PREFIX).toLongOrNull()
                ?.let { thumbnailFor(it, item.uri.toUri()) }

        item.id.startsWith(HIDDEN_ID_PREFIX) -> thumbnailFor(item.id, item.uri.toUri())
        else -> null
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

    /** 添加扫描目录：已存在则直接重扫，否则持久化后立即扫描（[ScanFolderController] 契约） */
    override suspend fun addScanFolder(uri: String) {
        val current = settings.scanFolderUris.first()
        if (uri !in current) settings.setScanFolders(current + uri)
        scan()
    }

    /**
     * 移除一个扫描目录：释放其持久化授权；仍有其他目录时立即重扫，
     * 移除最后一个目录时清空媒体列表、缩略图缓存与落盘快照，回到未扫描引导态。
     * 不删除任何用户文件。
     */
    override suspend fun removeScanFolder(uri: String) {
        val current = settings.scanFolderUris.first()
        if (uri !in current) return
        val remaining = current - uri
        settings.setScanFolders(remaining)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (remaining.isEmpty()) {
            File(context.cacheDir, "video_thumbs").listFiles()?.forEach { it.delete() }
            _media.value = emptyList()
            _lastScan.value = null
            // 快照是"已扫描"的落盘证据——最后一个目录都被移除后它必须一起消失，
            // 否则下次冷启动会把已撤销目录的列表恢复出来
            hasSnapshot = false
            snapshotFile.delete()
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
    @OptIn(kotlinx.coroutines.FlowPreview::class)
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
                    // 回前台补扫是必要的：观察者注销期间 MediaStore 的事件收不到。
                    // 但快速往返（拉通知栏、切出去看一眼就回来）不该再来一遍全量查询——
                    // 距上次完成不足 MIN_RESCAN_INTERVAL_MS 时，中间根本没有「观察者没注册」的窗口。
                    if (SystemClock.elapsedRealtime() - lastScanFinishedAt >= MIN_RESCAN_INTERVAL_MS) {
                        mediaChanges.value = System.currentTimeMillis()
                    }
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
            MediaStore.Video.Media.DATE_MODIFIED,
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
            val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
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
                    lastModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L,
                    addedAtMs = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L,
                    // 封面不在这里生成：游标里逐项抽帧是**串行**的，几百部片子会把首扫
                    // 拖到分钟级。统一交给扫描末尾的 attachCovers 并发补齐（见该方法）。
                    coverUri = null,
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
            MediaStore.Audio.Media.DATE_MODIFIED,
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
            val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
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
                    lastModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L,
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

    /**
     * L14：隐藏目录（`.` 前缀）里的媒体拾取。
     *
     * MediaStore 的媒体集合**跳过隐藏目录**：模拟器实测把同一个 mp4 放进
     * `Movies/.nphidden/` 后，视频集合的 13 行里 0 行属于它——用户在手机上把片子
     * 收进 `.xxx/` 目录，库里便一条都看不见。而 SAF（`DocumentsContract`）的 children
     * 查询**不跳过隐藏项**（同一次实测里 `.nphidden` 与 `.thumbnails` 都照常返回）。
     *
     * 于是沿用户授权的每个 tree 递归：**目录一律下钻**（隐藏目录可能藏在非隐藏目录之下，
     * 如 `Movies/Series/.hidden`），**只收"路径中含 `.` 前缀分段"的文件**——非隐藏文件
     * 仍以 MediaStore 为唯一来源，绝不重复采集。返回项的 uri 是 document URI，引擎直接
     * 可播（media3 走 ContentResolver）；tree 的持久化读授权已覆盖其全部子孙。
     *
     * 遍历有 [HIDDEN_WALK_BUDGET] 硬预算：目录树里混进 `node_modules`/`.git` 这类巨型结构
     * 时，不把一次扫描拖成分钟级；超预算即停（fail-open，宁可少收也不卡死）。
     */
    private fun queryHiddenDocuments(treeUriStrs: List<String>, baseline: Map<String, MediaItem>): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        var budget = HIDDEN_WALK_BUDGET
        // 去重：tree 根重叠（父/子目录各自授权）时同一文档会被走到两次——
        // 此前无判重，同一文件以同一 `saf-` id 进列表两次（双份文件的来源之一）
        val seen = HashSet<String>()

        fun walk(treeUri: Uri, docId: String, inheritedHidden: Boolean) {
            if (budget <= 0) return
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            }.getOrNull() ?: return
            runCatching {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val lmCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        if (budget-- <= 0) return
                        if (idCol < 0) continue
                        val childDocId = cursor.getString(idCol) ?: continue
                        val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        if (name.isEmpty()) continue
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                        val hidden = inheritedHidden || name.startsWith(".")
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (name in HIDDEN_DENY_DIRS) continue
                            walk(treeUri, childDocId, hidden)
                        } else if (hidden) {
                            if (isJunkSuffix(name)) continue
                            val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            if (sizeCol >= 0 && size <= 0L) continue
                            val id = hiddenDocumentId(childDocId)
                            if (!seen.add(id)) continue
                            // 未变项复用基线：跳过文件头嗅探（逐文件 I/O）
                            val lmSec = if (lmCol >= 0) cursor.getLong(lmCol) / 1000 else 0L
                            val prev = baseline[id]
                            if (prev != null && prev.sizeBytes == size && prev.lastModifiedSec == lmSec && prev.title == name) {
                                out += prev
                                continue
                            }
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                            // mime 已是 video/*、audio/* 就用它；否则（application/octet-stream
                            // 之类）按文件头嗅探——认不出的直接跳过，不往库里塞"点开必失败"的项
                            val effectiveMime = if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                                mime
                            } else {
                                readHead(docUri, ContainerSniffer.HEAD_BYTES)
                                    ?.let { ContainerSniffer.sniff(it) }
                                    ?: continue
                            }
                            out += MediaItem(
                                id = id,
                                sourceId = sourceId,
                                title = name,
                                uri = docUri.toString(),
                                mimeType = effectiveMime,
                                sizeBytes = size,
                                // SAF 的 LAST_MODIFIED 是毫秒；断点指纹统一按秒（与 MediaStore 同单位）
                                lastModifiedSec = lmSec,
                                addedAtMs = if (lmCol >= 0) cursor.getLong(lmCol) else 0L,
                                // 同 queryScope：封面统一由 attachCovers 并发补齐
                                coverUri = null,
                                folder = folderOfDocument(childDocId),
                            )
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "隐藏目录遍历失败（fail-open 跳过）", it) }
        }

        treeUriStrs.forEach { treeStr ->
            if (budget <= 0) return@forEach
            val treeUri = runCatching { treeStr.toUri() }.getOrNull() ?: return@forEach
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@forEach
            // tree 根自身就是隐藏目录（用户直接选了 `.foo`）时，其子项整支都算隐藏
            val rootName = treeDocId.substringAfter(':', treeDocId).substringAfterLast('/')
            walk(treeUri, treeDocId, rootName.startsWith("."))
        }
        return out
    }

    /** 缩略图缓存：扫描时落盘为 JPEG，之后封面直接读文件 */
    private fun thumbnailFor(id: Long, videoUri: Uri): String? = runCatching {
        writeThumbnail("$id") {
            // Q+ 的系统缩略图并非对所有容器都有（部分 OEM 的 AVI/MKV 会抛异常或给 null）——
            // 此前只兜低版本，失败即永久占位图；这里把抽帧兜底提到两条路共用
            val bySystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(videoUri, Size(480, 270), null) }
                    .getOrNull()
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
            bySystem ?: createFrameFallback(videoUri)
        }
    }.getOrNull()

    /**
     * SAF/文档 URI 的封面（L14）：隐藏项没有 MediaStore 长整型 id，走不了旧缩略图接口。
     * Q+ 用 `loadThumbnail`，更低版本直接抽首帧；键用项 id（`saf-…`），与 MediaStore 的
     * 数字键天然分开，不会互相覆盖。
     */
    private fun thumbnailFor(key: String, docUri: Uri): String? = runCatching {
        writeThumbnail(key) {
            // 文档 URI 的 loadThumbnail 在部分 provider/版本上不可用（抛异常或返回 null），
            // 兜一句 MediaMetadataRetriever 抽首帧，避免隐藏目录里的项永远只有占位图
            val bySystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(docUri, Size(480, 270), null) }
                    .getOrNull()
            } else {
                null
            }
            bySystem ?: createFrameFallback(docUri)
        }
    }.getOrNull()

    /** 缩略图落盘：文件名命中即复用（0 字节视为坏图），否则用 [produce] 抽帧写 JPEG */
    private fun writeThumbnail(key: String, produce: () -> Bitmap?): String? {
        val dir = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val file = File(dir, "$key.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bitmap = produce() ?: return null
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        }
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

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
    val head = readHead(item.uri.toUri(), ContainerSniffer.HEAD_BYTES) ?: return item
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
private fun sniffUnknownFiles(scopes: List<ScanScope>, baseline: Map<String, MediaItem>): List<MediaItem> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    if (!runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) return emptyList()
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val out = mutableListOf<MediaItem>()
    // 去重：目录范围父子重叠（同时授权父目录与子目录）时，同一行会被逐 scope 查两次——
    // 此前 `out +=` 无判重，同一文件以同一 id 进列表两次（用户实报"扫描会出现双份文件"）
    val seen = HashSet<String>()
    scopes.forEach { scope ->
        // Unresolvable 一律跳过（与 [queryScope] 同一道闸）：scopeSelection 对它返回
        // (null, null)，而 (null, null) 与"用户选的就是主存储根目录＝全量扫描"同形，
        // 拼进 selection 就只剩 `MEDIA_TYPE = 0` —— 那条查询会越过授权目录扫遍整机
        // 非媒体行，正违反 scopeSelection 注释里"任何分支都不得回退全量扫描"的不变量。
        if (scope is ScanScope.Unresolvable) return@forEach
        val (scopeSel, scopeArgs) = scopeSelection(
            scope,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
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
                    val id = "msf-${cursor.getLong(idCol)}"
                    if (!seen.add(id)) continue
                    val name = cursor.getString(nameCol).orEmpty()
                    val title = name.substringBeforeLast('.').ifBlank { name }
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val addedAt = if (addedCol >= 0) cursor.getLong(addedCol) * 1000 else 0L
                    // 未变项（改名/改大小/改加入时间之外都不变）复用基线：跳过文件头读取
                    val prev = baseline[id]
                    if (prev != null && prev.title == title && prev.sizeBytes == size && prev.addedAtMs == addedAt) {
                        out += prev
                        continue
                    }
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString()
                    val head = readHead(uri.toUri(), ContainerSniffer.HEAD_BYTES)
                    val mime = head?.let { ContainerSniffer.sniff(it) } ?: continue
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    out += MediaItem(
                        id = id,
                        sourceId = sourceId,
                        title = title,
                        uri = uri,
                        mimeType = mime,
                        sizeBytes = size,
                        addedAtMs = addedAt,
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

/** L14 遍历预算：单次扫描最多访问的文档数上限（防巨型目录把扫描拖成分钟级） */
private const val HIDDEN_WALK_BUDGET = 20_000

/** L14 下钻黑名单：这些隐藏目录只装系统缓存 / 回收站，不可能有用户媒体 */
private val HIDDEN_DENY_DIRS = setOf(
    ".thumbnails", ".trash", ".Trash", ".recycle", ".trashbin", ".cache", ".temp",
)

/** 半成品后缀（与 `isJunkFile` 共用 [JUNK_SUFFIXES]）：隐藏目录里的 .part 之类不该进库 */
private fun isJunkSuffix(name: String): Boolean {
    val lower = name.lowercase()
    return JUNK_SUFFIXES.any { lower.endsWith(it) }
}

/** 条目 id 的三种前缀：MediaStore 视频 / MediaStore 音频（含 .cue 分轨）/ SAF 隐藏目录文档 */
private const val VIDEO_ID_PREFIX = "ms-"
private const val AUDIO_ID_PREFIX = "msa-"
private const val HIDDEN_ID_PREFIX = "saf-"

/** 首次扫描时同时抽帧的缩略图数量上限：再高只会在低端机上抢 I/O 与解码器，不会更快 */
private const val THUMB_PARALLELISM = 4

/** 缩略图文件名键：MediaStore 项剥 `ms-`（沿用既有缓存名），SAF 项直接用整 id */
private fun thumbKeyOf(id: String): String? = when {
    id.startsWith(VIDEO_ID_PREFIX) -> id.removePrefix(VIDEO_ID_PREFIX)
    id.startsWith(HIDDEN_ID_PREFIX) -> id
    else -> null
}

/**
 * L14：SAF docId → 展示用相对目录（如 "Movies/.nphidden"）；卷根目录返回 null。
 *
 * `primary:Movies/.nphidden/secret.mp4` → "Movies/.nphidden"，与 MediaStore 的
 * RELATIVE_PATH 同口径（相对媒体根、不带首尾斜杠），文件夹树与同目录队列直接可用。
 */
internal fun folderOfDocument(docId: String): String? =
    docId.substringAfter(':', docId)
        .trim('/')
        .substringBeforeLast('/', "")
        .trim('/')
        .ifEmpty { null }

/**
 * L14：SAF docId → 稳定短 id（`saf-` + SHA-1 截断 16 位十六进制）。
 *
 * 断点/收藏/缩略图都认这个 id，所以必须**跨扫描稳定**（docId 不变则 id 不变），
 * 且是文件系统安全的短名（既进 Room 键，也进 `video_thumbs/<key>.jpg`）。
 */
internal fun hiddenDocumentId(docId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(docId.toByteArray())
    return HIDDEN_ID_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(16)
}

private const val TAG = "LocalMediaSource"

/**
 * 相册封面集合 URI。系统未公开常量（`MediaStore.Audio.Albums.ALBUM_ART` 不是它），
 * 但这是 MediaProvider 长期稳定的公开约定地址，读相册图只能用它。
 */
private const val ALBUM_ART_URI = "content://media/external/audio/albumart"

/** E67：scan() 命中并发守卫时的最长等待时长与轮询间隔 */
private const val SCAN_WAIT_TIMEOUT_MS = 10_000L
private const val SCAN_WAIT_POLL_MS = 100L

/** A5/P2-2：回前台补扫的最小间隔——比它更短的往返不值得再跑一次全量 MediaStore 查询 */
private const val MIN_RESCAN_INTERVAL_MS = 30_000L

/** 扫描索引快照的格式版本：字段增删时递增，旧快照按"没有"处理（下轮扫描重建） */
private const val SNAPSHOT_VERSION = 1

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
