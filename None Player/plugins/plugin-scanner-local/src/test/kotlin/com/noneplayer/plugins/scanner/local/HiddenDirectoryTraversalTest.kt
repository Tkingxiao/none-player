package com.noneplayer.plugins.scanner.local

import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
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
        harness = ScanHarness()
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
}
