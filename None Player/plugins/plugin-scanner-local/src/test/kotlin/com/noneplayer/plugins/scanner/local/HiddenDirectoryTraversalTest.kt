package com.noneplayer.plugins.scanner.local

import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据保护（E198）：隐藏目录仅在用户显式选择时遍历。
 *
 * 端到端验两向：普通授权根（`Movies`）下的隐藏子目录不越权下钻；
 * 用户把 tree 直接选到隐藏目录（`Movies/.nphidden`）时其内媒体可被遍历。
 * 非隐藏子目录里的隐藏**文件**仍以 SAF 拾取（MediaStore 会跳过它们）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenDirectoryTraversalTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        // 本类用索引开着的 harness：固化行为（空叶是否被记进索引）只有索引启用才可判；
        // 类内既有用例只断言标题集合，不受复用影响。
        harness = ScanHarness(walkIndexEnabled = true)
    }

    private fun dir(docId: String, name: String) =
        FakeDocumentsProvider.DocEntry(docId, name, DocumentsContract.Document.MIME_TYPE_DIR)

    private fun file(docId: String, name: String) =
        FakeDocumentsProvider.DocEntry(docId, name, "video/mp4")

    @Test
    fun `普通授权根下的隐藏子目录不越权遍历，非隐藏子目录里的隐藏文件仍拾取`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID // primary:Movies（非隐藏根）
        harness.docsProvider.setChildren(
            root,
            listOf(dir("$root/.nphidden", ".nphidden"), dir("$root/Series", "Series")),
        )
        harness.docsProvider.setChildren("$root/.nphidden", listOf(file("$root/.nphidden/secret.mp4", "secret.mp4")))
        harness.docsProvider.setChildren("$root/Series", listOf(file("$root/Series/.loose.mp4", ".loose.mp4")))

        harness.scan()
        val titles = harness.items().map { it.title }

        assertFalse("未显式选择的隐藏子目录 .nphidden 不应被下钻（secret.mp4 不该出现）", "secret.mp4" in titles)
        assertTrue("非隐藏子目录 Series 下的隐藏文件 .loose.mp4 仍应被拾取", ".loose.mp4" in titles)
    }

    @Test
    fun `用户显式选到隐藏目录时其内媒体可被遍历`() = runBlocking {
        harness.grantAllMediaPermissions()
        val hiddenTree = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2F.nphidden"
        val hiddenRoot = "primary:Movies/.nphidden"
        harness.setFolders(hiddenTree)
        harness.docsProvider.setChildren(hiddenRoot, listOf(file("$hiddenRoot/secret.mp4", "secret.mp4")))

        harness.scan()
        val titles = harness.items().map { it.title }

        assertTrue("显式选择的隐藏根 .nphidden 下的 secret.mp4 应被扫到", "secret.mp4" in titles)
    }

    /**
     * E205：children 游标返回 null（provider 契约允许）不得被当成「空目录」——
     * 空叶固化进 ScanWalkIndex + complete 误判触发孤儿清理，会把该目录文件的
     * 播放历史/收藏当孤儿删掉。三段式：正常基线（写历史）→ 注入 null（判不完整、
     * 不清孤儿）→ 解除注入（索引未固化空叶，重走后条目与历史都回来）。
     */
    @Test
    fun `children 游标返回 null 按不完整处理：不固化空叶、不清孤儿、下轮可恢复`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val root = ScanTestTree.MAIN_TREE_DOC_ID
        val seriesDir = "$root/Series"
        harness.docsProvider.setChildren(root, listOf(dir(seriesDir, "Series")))
        // SAF 隐藏遍历只拾取点前缀文件（非隐藏文件归 MediaStore 管），条目必须用隐藏名
        harness.docsProvider.setChildren(seriesDir, listOf(file("$seriesDir/.ep1.mp4", ".ep1.mp4")))

        // 基线：正常一轮，ep1 进库并写历史/收藏
        harness.scan()
        val ep1 = harness.items().single { it.title == ".ep1.mp4" }
        harness.seedHistory(ep1)

        // 注入 null 游标：对根目录注入（根不走叶子复用、每轮必查；对叶子注入会被
        // 增量索引的复用命中短路掉，children 查询根本不会发出）
        harness.docsProvider.returnNullForDocId = root
        harness.scan()
        assertTrue(
            "null 游标必须判为本次扫描不完整（不能当空目录）",
            harness.lastScanNote().orEmpty().contains("不完整"),
        )
        assertEquals("读不出来的目录，其历史不得被孤儿清理", 1, harness.historyCount())

        // 解除注入重扫：目录没有被固化成空叶子（索引未记该目录），ep1 回来、历史仍在
        harness.docsProvider.returnNullForDocId = null
        harness.scan()
        assertTrue("解除注入后 ep1 应重新出现（索引未固化空叶）", harness.items().any { it.title == ".ep1.mp4" })
        assertEquals(1, harness.historyCount())
    }
}
