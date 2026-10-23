package com.noneplayer.plugins.scanner.local

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.noneplayer.core.media.MediaItem
import com.noneplayer.kernel.api.ScanPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 目录级增量索引（ScanWalkIndex）接线：只对「叶子目录 + 父列表 mtime 未变」复用。
 *
 * 验的是**取舍边界**而不是"索引能存能读"：
 * - 该省的 IPC 必须真省（二扫只剩根目录一次查询）；
 * - 不该复用的一条都不能复用（mtime 变了、TTL 过期、payload 被配额丢弃）；
 * - 复用命中后结果必须与首扫**逐字段一致**（id/folder/addedAtMs 尤其容易错）；
 * - 取消/不完整绝不落盘（残缺索引会让下一轮把残缺当完整结果复用，直接漏文件）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanWalkIndexWiringTest {

    private lateinit var harness: ScanHarness

    /** 索引文件名序号：同一用例内可能开多个 harness，各自独立文件避免串味。 */
    private var indexSeq = 0

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    // ---- 素材 ----

    companion object {
        private const val T0 = 1_700_000_000_500L // 毫秒，故意带 500ms 尾巴：验秒级索引的精度损失
    }

    private fun dir(docId: String, name: String, lastModified: Long = T0) =
        FakeDocumentsProvider.DocEntry(
            docId = docId,
            displayName = name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            size = 0L,
            lastModified = lastModified,
        )

    private fun file(docId: String, name: String, size: Long = 1_024L, lastModified: Long = T0) =
        FakeDocumentsProvider.DocEntry(
            docId = docId,
            displayName = name,
            mimeType = "video/mp4",
            size = size,
            lastModified = lastModified,
        )

    private fun newIndexFile(): File =
        File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "walk_index_${indexSeq++}.json",
        )

    private fun harnessWithIndex(ttlMs: Long = ScanWalkIndex.DEFAULT_TTL_MS): ScanHarness =
        ScanHarness(
            walkIndexEnabled = true,
            walkIndexTtlMs = ttlMs,
            walkIndexFile = newIndexFile(),
        )

    /** 「Movies/Series 下两个隐藏视频」的标准语料：Series 无子目录 → 叶子目录，可被复用。 */
    private fun seedLeafTree(target: ScanHarness = harness) {
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        target.docsProvider.setChildren(root, listOf(dir("$root/Series", "Series")))
        target.docsProvider.setChildren(
            "$root/Series",
            listOf(
                file("$root/Series/.a.mp4", ".a.mp4", size = 1_024L),
                file("$root/Series/.b.mp4", ".b.mp4", size = 2_048L),
            ),
        )
    }

    /** 逐字段指纹：id/folder/addedAtMs 这三项是复用最易出错的地方，必须单独盯。 */
    private fun fingerprint(item: MediaItem): String = listOf(
        item.id,
        item.title,
        item.uri,
        item.mimeType,
        item.sizeBytes.toString(),
        item.lastModifiedSec.toString(),
        item.addedAtMs.toString(),
        item.folder.orEmpty(),
        item.coverUri.orEmpty(),
    ).joinToString("|")

    private suspend fun scanOnce(target: ScanHarness = harness): List<String> {
        target.scan()
        return target.items().map { fingerprint(it) }
    }

    // ------------------------------------------------------------------
    // 复用命中：二扫零 IPC
    // ------------------------------------------------------------------

    @Test
    fun `叶子目录二扫命中索引_只剩根目录一次查询且结果逐字段一致`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()

        val firstItems = scanOnce()
        val queriesAfterFirst = harness.docsProvider.queryCount
        assertTrue("首扫完整走完必须落盘索引", harness.walkIndexSaved())
        assertEquals("首扫：根 + Series 两个目录", 2, queriesAfterFirst)

        val secondItems = scanOnce()
        val secondQueries = harness.docsProvider.queryCount - queriesAfterFirst

        assertEquals("二扫只该查根目录（Series 命中索引，一次 IPC 都不发）", 1, secondQueries)
        assertEquals("复用命中后结果必须与首扫逐字段一致", firstItems, secondItems)
        assertEquals(2, secondItems.size)
    }

    @Test
    fun `子目录 mtime 变化后必须重走_不能拿旧缓存顶替`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()
        scanOnce()
        val queriesAfterFirst = harness.docsProvider.queryCount

        // 父列表里看到 Series 的 mtime 变了（目录内容动过）→ 必须重新查
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        harness.docsProvider.setChildren(root, listOf(dir("$root/Series", "Series", lastModified = T0 + 60_000L)))
        scanOnce()

        assertEquals(
            "mtime 变了仍要走目录（根 + Series）",
            2,
            harness.docsProvider.queryCount - queriesAfterFirst,
        )
    }

    @Test
    fun `TTL 过期后不复用`() = runBlocking {
        // ttl = -1ms：任何时刻都算过期（reusable 要求 0 <= age <= ttlMs）
        harness = harnessWithIndex(ttlMs = -1L)
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()
        scanOnce()
        val queriesAfterFirst = harness.docsProvider.queryCount

        scanOnce()

        assertEquals("TTL 过期必须重走目录", 2, harness.docsProvider.queryCount - queriesAfterFirst)
    }

    @Test
    fun `payload 被配额丢弃的条目不复用`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()
        scanOnce()

        // 手工把索引改写成「骨架还在、payload 已被配额丢弃」的状态（真实触发要造 >2MiB 的 payload）
        val indexFile = harness.walkIndexFileOnDisk()
        indexFile.writeText(
            "{\"version\":1,\"entries\":[{\"docId\":\"primary:Movies/Series\"," +
                "\"treeUri\":\"${ScanTestTree.MAIN_TREE}\"," +
                "\"lastModifiedMs\":$T0,\"inheritedHidden\":false,\"leaf\":true," +
                "\"walkedAtMs\":${System.currentTimeMillis()},\"payloadDropped\":true,\"items\":[]}]}",
        )
        val queriesBeforeSecond = harness.docsProvider.queryCount

        val items = scanOnce()

        assertEquals("payload 被丢的目录必须重走", 2, harness.docsProvider.queryCount - queriesBeforeSecond)
        assertEquals("重走后结果照旧", 2, items.size)
    }

    // ------------------------------------------------------------------
    // 不落盘：取消 / 预算耗尽
    // ------------------------------------------------------------------

    @Test
    fun `walk 中取消不落盘索引`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        var parent = ScanTestTree.MAIN_TREE_DOC_ID
        for (index in 1..20) {
            val child = "$parent/d$index"
            harness.docsProvider.setChildren(parent, listOf(dir(child, "d$index")))
            parent = child
        }
        harness.docsProvider.queryDelayMs = 30L

        val walkEntered = async(Dispatchers.Unconfined) {
            harness.source.observeScanProgress().first { it.phase == ScanPhase.HIDDEN_WALK }
        }
        val job = launch(Dispatchers.Default) { runCatching { harness.source.scan() } }
        withTimeout(5_000) { walkEntered.await() }
        harness.source.cancelScan()
        withTimeout(2_000) { job.join() }

        assertFalse("中途取消不得落盘索引（残缺索引会让下轮漏文件）", harness.walkIndexSaved())
    }

    @Test
    fun `预算耗尽不落盘索引`() = runBlocking {
        harness = ScanHarness(walkIndexEnabled = true, hiddenWalkBudget = 2, walkIndexFile = newIndexFile())
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        harness.docsProvider.setChildren(
            ScanTestTree.MAIN_TREE_DOC_ID,
            (1..5).map { index -> file("${ScanTestTree.MAIN_TREE_DOC_ID}/.hidden-$index.mp4", ".hidden-$index.mp4") },
        )

        harness.scan()

        val note = harness.lastScanNote()
        assertNotNull("预算耗尽必须留不完整摘要", note)
        assertTrue("摘要应说明不完整（实际：$note）", note!!.contains("不完整"))
        assertFalse("不完整的一轮不得落盘索引", harness.walkIndexSaved())
    }

    // ------------------------------------------------------------------
    // 语义不变：E198
    // ------------------------------------------------------------------

    @Test
    fun `复用不改变 E198 判定_未显式选择的隐藏目录始终不进入索引`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        harness.docsProvider.setChildren(root, listOf(dir("$root/.nphidden", ".nphidden")))
        harness.docsProvider.setChildren(
            "$root/.nphidden",
            listOf(file("$root/.nphidden/secret.mp4", "secret.mp4")),
        )

        scanOnce()
        val second = scanOnce()

        assertTrue("普通根下的隐藏目录一律不下钻（首扫如此，复用后也必须如此）", second.isEmpty())
        val saved = harness.walkIndexFileOnDisk().readText()
        assertFalse("索引里不该出现被 E198 拒掉的目录内容", saved.contains("secret.mp4"))
    }

    // ------------------------------------------------------------------
    // addedAtMs 精度：空基线复用的兜底
    // ------------------------------------------------------------------

    @Test
    fun `空基线复用项_id 与 folder 一致_毫秒精度按秒兜底`() = runBlocking {
        val indexFile = newIndexFile()
        val first = ScanHarness(walkIndexEnabled = true, walkIndexFile = indexFile)
        first.grantAllMediaPermissions()
        first.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree(first)
        first.scan()
        val firstItems = first.items()
        assertEquals(2, firstItems.size)
        assertTrue("首扫的 addedAtMs 应是 SAF 原毫秒值", firstItems.all { it.addedAtMs == T0 })

        // 第二个 harness = 全新进程内状态（_media 为空）→ 复用命中但没有基线可兜底
        val second = ScanHarness(walkIndexEnabled = true, walkIndexFile = indexFile)
        second.grantAllMediaPermissions()
        second.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree(second)
        second.scan()
        val reused = second.items()

        assertEquals(2, reused.size)
        assertEquals(
            "id 必须跨轮稳定（封面/断点/收藏都认它）",
            firstItems.map { it.id }.sorted(),
            reused.map { it.id }.sorted(),
        )
        assertEquals(
            "folder 必须一致",
            firstItems.map { it.folder.orEmpty() }.sorted(),
            reused.map { it.folder.orEmpty() }.sorted(),
        )
        assertEquals(
            "索引只存秒级 mtime，空基线时 addedAtMs 只能按秒兜底（已知精度损失）",
            (T0 / 1000L) * 1000L,
            reused.first().addedAtMs,
        )
    }

    // ------------------------------------------------------------------
    // 开关关闭 / 索引损坏：行为必须与「没有索引」完全一致
    // ------------------------------------------------------------------

    @Test
    fun `索引关闭时不建文件且每轮都完整走`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()

        val firstItems = scanOnce()
        val queriesAfterFirst = harness.docsProvider.queryCount
        val secondItems = scanOnce()

        assertEquals("关索引 → 每轮都完整走", 2, harness.docsProvider.queryCount - queriesAfterFirst)
        assertEquals(firstItems, secondItems)
        assertFalse("关索引不该产生任何索引文件", harness.walkIndexFileOnDisk().exists())
    }

    @Test
    fun `索引文件损坏时按空索引处理_结果照旧`() = runBlocking {
        harness = harnessWithIndex()
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()
        harness.walkIndexFileOnDisk().writeText("{ 这不是 JSON")

        val items = scanOnce()

        assertEquals("损坏索引不影响正确性", 2, items.size)
    }

    // ------------------------------------------------------------------
    // 目录去重 / 深度上限
    // ------------------------------------------------------------------

    @Test
    fun `父子 tree 重叠授权时同一个目录只走一遍`() = runBlocking {
        harness.grantAllMediaPermissions()
        // 同时选 primary:Movies 与 primary:Movies/Series：后者的根就是前者的子目录
        harness.setFolders(
            ScanTestTree.MAIN_TREE,
            "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FSeries",
        )
        seedLeafTree()

        val items = scanOnce()

        assertEquals("重叠授权不得把同一批文件收两遍", 2, items.size)
        assertEquals("同一文件只应有一个 id", items.distinct().size, items.size)
    }

    @Test
    fun `provider 把自身当 child 返回时不成环`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        harness.docsProvider.setChildren(root, listOf(dir(root, "Movies")))

        harness.scan()

        assertTrue("自引用目录不得产出条目", harness.items().isEmpty())
        assertTrue(
            "自引用必须被目录去重挡掉，而不是靠预算耗尽（实际查询 ${harness.docsProvider.queryCount} 次）",
            harness.docsProvider.queryCount <= 2,
        )
    }

    @Test
    fun `无底洞目录链被深度上限截断`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        harness.docsProvider.setChildren(root, listOf(dir("$root/d", "d")))
        // 每一层再长出一层：只有 MAX_WALK_DEPTH 能截住
        harness.docsProvider.dynamicChildren = { docId ->
            if (docId.startsWith("$root/d")) listOf(dir("$docId/d", "d")) else null
        }

        harness.scan()

        val note = harness.lastScanNote()
        assertTrue(
            "深度上限必须挡住无底洞（实际查询 ${harness.docsProvider.queryCount} 次）",
            harness.docsProvider.queryCount <= 70,
        )
        assertFalse("深度截断不该被当成预算耗尽（实际：$note）", note.orEmpty().contains("预算耗尽"))
    }

    // ------------------------------------------------------------------
    // trace 落盘 / 快照顺序
    // ------------------------------------------------------------------

    @Test
    fun `trace 关闭时不建文件`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        seedLeafTree()
        harness.scanTraceFile().delete()
        ScanTrace.enabled = false
        try {
            harness.scan()
            assertFalse("ScanTrace.enabled=false 时不得建 trace 文件", harness.scanTraceFile().exists())
        } finally {
            ScanTrace.enabled = true
        }
    }

    @Test
    fun `连扫两次第二次不重写快照`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        harness.docsProvider.setChildren(
            root,
            listOf(dir("$root/Series", "Series"), dir("$root/Docs", "Docs")),
        )
        harness.docsProvider.setChildren("$root/Series", listOf(file("$root/Series/.a.mp4", ".a.mp4")))
        harness.docsProvider.setChildren(
            "$root/Docs",
            listOf(file("$root/Docs/.c.mp4", ".c.mp4"), file("$root/Docs/.d.mp4", ".d.mp4")),
        )

        harness.scan()
        val snapshotFile = harness.snapshotFile()
        val firstBytes = snapshotFile.readBytes()
        val firstModified = snapshotFile.lastModified()

        harness.scan()

        assertEquals("语料没变，快照内容不该变", firstBytes.toList(), snapshotFile.readBytes().toList())
        assertEquals(
            "快照比较是保序的：walk 结果必须稳定排序，否则每轮都判定「变了」而重写几 MB",
            firstModified,
            snapshotFile.lastModified(),
        )

        // 车道调参取证（真机上靠这几行 logcat / trace 读「路数 → 吞吐」曲线，这里只验接线到位）：
        // prepare 行带拓扑与两条车道的起跑值/上限；walk 收尾带 lane tune 与 perf 汇总。
        val trace = harness.scanTraceFile().readText()
        assertTrue("prepare 行必须带 cores/big/soc/io/decode/walkCap", trace.contains("scan prepare cores="))
        assertTrue("walk 段必须打 lane tune 收尾行", trace.contains("lane tune kind=WALK"))
        assertTrue("walk 段必须打 perf 汇总行", trace.contains("phase=WALK perf util="))
        assertTrue(
            "成功路径必须落盘车道 presets",
            File(harness.context.filesDir, ScanLanePresets.FILE_NAME).exists(),
        )
    }
}
