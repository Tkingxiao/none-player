package com.noneplayer.plugins.scanner.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * 隐藏目录并行遍历的**性能向**回归（粗粒度 bench，不作精确基准）。
 *
 * 用户抱怨③：带点号的隐藏文件夹搜索极慢。慢的根因不是 CPU，而是 SAF 的
 * `queryChildDocuments` **每个目录一次 binder IPC**，串行递归几千个目录就是几千次
 * 往返叠加。因此这里把「每次 children 查询」注入 5ms 延迟（模拟一次真实 IPC），
 * 造 400 个目录，再断言一次 `scan()` 的总耗时**明显低于串行下界**。
 *
 * 断言口径（刻意宽松，抗 CI 抖动）：
 * - **净耗时 = 400 目录扫描耗时 − 空树扫描耗时**：先扫一次空树量出与遍历无关的固定开销
 *   （MediaStore 查询、快照落盘、提交闸门），再减掉，避免固定开销冲淡并行收益；
 * - **串行下界 = 目录数 × 单次延迟**（400 × 5ms = 2000ms）；另用 [measureSerialBaselineMs]
 *   实测一遍串行 400 次往返，只打日志不参与断言（Robolectric 下单次影子调用比真机慢，
 *   拿它当基线会把阈值放得过松）；
 * - 要求净耗时 < 串行下界 × 0.75。车道数最少是 4（[ScanParallelism.ioWorkers] 下限），
 *   理论上限约 0.25，留三倍余量。
 *
 * 同时断言条目数 = 目录数：确认遍历**真的走完了所有目录**——否则"很快"可能只是漏扫。
 *
 * 为什么不直接用 [ScanTestSupport] 里的 [FakeDocumentsProvider]：它没有任何延迟注入点，
 * 且是 internal 非 open 无法继承；本用例在 harness 构造后**用同 authority 覆盖注册**一个
 * 带延迟的 Provider（`ShadowContentResolver.registerProviderInternal` 后者生效），
 * 生产代码与测试基建都不用改。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanWalkParallelismBenchTest {

    private lateinit var harness: ScanHarness
    private lateinit var docs: DelayingDocsProvider

    /** 目录数量：够大才能让 IPC 延迟主导总耗时，又不至于让单测跑成分钟级。 */
    private val dirCount = 400

    /** 每次 children 查询注入的延迟（毫秒），约等于一次真实 binder 往返的量级。 */
    private val perQueryDelayMs = 5L

    /** 全 IO 调度器：见 setUp 里对 Unconfined 事件循环陷阱的说明 */
    private val BenchDispatchers = object : com.noneplayer.core.common.DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.IO
        override val io: CoroutineDispatcher = Dispatchers.IO
    }

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        // E220：基准用全 IO 调度器——缺省的 Unconfined 续体链偶发把扫描收尾的
        // 最后一跳排进停泊 worker 的 ThreadLocalEventLoop（批跑下两次死锁，
        // 单跑恒过），全 IO 无此队列陷阱；线程模型对计时断言无害（更贴生产）。
        harness = ScanHarness(dispatchers = BenchDispatchers)
        docs = DelayingDocsProvider(perQueryDelayMs)
        // harness init 里注册的 FakeDocumentsProvider 被这里的同 authority 覆盖
        ShadowContentResolver.registerProviderInternal(ScanTestTree.DOCS_AUTHORITY, docs)
    }

    @Test
    fun `400 个目录各注入 5ms IPC 延迟时并行遍历明显快于串行下界`() = runBlocking {
        // E220 观察项修根：批跑下本用例偶发挂死（今日两次，单跑恒过）——
        // runBlocking 把 Robolectric 主线程占成阻塞事件循环，扫描编排的续体
        // 偶发排进"要等主线程空转才能服务"的队列即成死锁。把测试体整体挪到
        // Dispatchers.IO 上跑，主线程事件循环保持空闲，死锁类随之消除；
        // 影子 ContentResolver 与车道计时对线程没有约束（扫描本就跑在 IO 上）。
        withContext(Dispatchers.IO) {
            bench()
        }
    }

    private suspend fun bench() {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        // 预热：吞掉首轮 JIT / Robolectric 影子类加载 / DataStore 首次写入等一次性开销
        queryChildrenOnce("warmup")

        // ① 空树基线扫描：量出「与目录遍历无关的固定开销」（MediaStore 查询、快照落盘、提交闸门等），
        //    后面从总耗时里减掉，避免固定开销把并行收益冲淡成误判
        val baseStartedAt = System.nanoTime()
        harness.scan()
        val baseMs = (System.nanoTime() - baseStartedAt) / 1_000_000.0

        // ② 实测串行基线：逐目录查一遍 children，往返次数与串行实现完全一致（机器慢时基线同步变大）
        val serialMeasuredMs = measureSerialBaselineMs()
        // ③ 理论串行下界：目录数 × 单次延迟
        val serialLowerBoundMs = dirCount * perQueryDelayMs

        // ④ 正式计时：400 个目录的隐藏文件拾取
        seedDirsOnFakeTree()
        val startedAt = System.nanoTime()
        harness.scan()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val walkMs = (elapsedMs - baseMs).coerceAtLeast(0.0)

        val titles = harness.items().map { it.title }
        // 取证：加 -i 跑时这行会带出实际耗时对比
        println(
            "bench hidden-walk: dirs=$dirCount delay=${perQueryDelayMs}ms " +
                "scan=${elapsedMs.toLong()}ms base=${baseMs.toLong()}ms walk=${walkMs.toLong()}ms " +
                "serialMeasured=${serialMeasuredMs}ms serialBound=$serialLowerBoundMs" +
                "ms lanes=${ScanParallelism.ioWorkers()}",
        )
        assertEquals(
            "遍历必须走完全部目录（少了就是漏扫，不是变快）",
            dirCount,
            titles.size,
        )
        assertTrue(
            "并行遍历净耗时 ${walkMs.toLong()}ms 应明显低于串行下界 ${serialLowerBoundMs}ms" +
                "（目录=$dirCount 单次延迟=${perQueryDelayMs}ms 空树基线=${baseMs.toLong()}ms " +
                "实测串行=${serialMeasuredMs}ms 车道=${ScanParallelism.ioWorkers()}）",
            walkMs < serialLowerBoundMs * 0.75,
        )
    }

    // ---- 素材 ----

    /** 根目录下挂 [dirCount] 个普通子目录，每个子目录里一个隐藏音频文件（走 SAF 拾取，不触发封面解码）。 */
    private fun seedDirsOnFakeTree() {
        val root = ScanTestTree.MAIN_TREE_DOC_ID // primary:Movies（非隐藏根，普通子目录一律下钻）
        val dirs = (0 until dirCount).map { index ->
            DelayingDocsProvider.Row(
                docId = "$root/d$index",
                displayName = "d$index",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                // 目录 mtime 给个正常值：留作将来接目录索引时的判据
                lastModified = 1_700_000_000_000L + index,
            )
        }
        docs.setChildren(root, dirs)
        (0 until dirCount).forEach { index ->
            docs.setChildren(
                "$root/d$index",
                listOf(
                    DelayingDocsProvider.Row(
                        docId = "$root/d$index/.track-$index.mp3",
                        displayName = ".track-$index.mp3",
                        mimeType = "audio/mpeg",
                        size = 1_024L,
                        lastModified = 1_700_000_000_000L,
                    ),
                ),
            )
        }
    }

    /**
     * 实测串行基线：在主线程上**逐目录**查一遍 children（与串行实现同样的往返次数）。
     *
     * 用来抵消机器快慢差异：CI 再慢，这条基线也会跟着慢下去。
     */
    private fun measureSerialBaselineMs(): Long {
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        val startedAt = System.nanoTime()
        queryChildrenOnce(root)
        (0 until dirCount).forEach { queryChildrenOnce("$root/d$it") }
        return (System.nanoTime() - startedAt) / 1_000_000L
    }

    private fun queryChildrenOnce(docId: String) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(
            Uri.parse(ScanTestTree.MAIN_TREE),
            docId,
        )
        harness.context.contentResolver.query(uri, null, null, null, null)?.close()
    }

    /**
     * 带延迟的假 SAF Provider：**只**给 children 查询注入延迟，其他 URI 一律返回空游标。
     *
     * 与 [FakeDocumentsProvider] 的差别仅两点：可配置每次查询延迟、可外部预置子项。
     */
    private class DelayingDocsProvider(private val perQueryDelayMs: Long) : ContentProvider() {

        data class Row(
            val docId: String,
            val displayName: String,
            val mimeType: String = "video/mp4",
            val size: Long = 1_024L,
            val lastModified: Long = 1_700_000_000_000L,
        )

        private val childrenByDocId = LinkedHashMap<String, List<Row>>()

        fun setChildren(treeDocId: String, children: List<Row>) {
            childrenByDocId[treeDocId] = children
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
            // 模拟一次 binder IPC 的固定开销：串行实现是"延迟 × 目录数"，并行实现是"延迟 × 目录数 / 车道数"
            if (perQueryDelayMs > 0) Thread.sleep(perQueryDelayMs)
            val columns = projection?.takeIf { it.isNotEmpty() }?.map { it }
                ?: DEFAULT_PROJECTION.toList()
            val docId = parentDocId(uri) ?: return MatrixCursor(columns.toTypedArray())
            val cursor = MatrixCursor(columns.toTypedArray())
            childrenByDocId[docId].orEmpty().forEach { row ->
                cursor.addRow(columns.map { columnValue(it, row) })
            }
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? = null

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
            val documentIndex = (treeIndex + 1 until segments.size)
                .firstOrNull { segments[it] == "document" } ?: return null
            return segments.getOrNull(documentIndex + 1)
        }

        private fun columnValue(column: String, row: Row): Any? = when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.docId
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.displayName
            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mimeType
            DocumentsContract.Document.COLUMN_SIZE -> row.size
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.lastModified
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
}
