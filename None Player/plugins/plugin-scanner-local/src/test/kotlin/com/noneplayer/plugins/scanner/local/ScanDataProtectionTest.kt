package com.noneplayer.plugins.scanner.local

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R0 / REV-01 扫描级数据保护验收（A 组）。
 *
 * 原话（docs/项目审查与优化路线-2026-10-20.md §6 R0）：
 * 「假 Provider 注入子查询异常/null、遍历超预算、权限收缩、空但完整扫描；
 *   前三类不删历史/收藏/旧快照，最后一类才允许确认删除；取消异常仍原样传播」。
 *
 * 基线：主卷 Movies 下 2 视频 + 2 音频 → 完整扫描得到 4 项 → 种 4 条历史/收藏、
 * 写 2 张缩略图、记录快照字节。之后注入故障再扫一次，断言"删除类提交"没有发生。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanDataProtectionTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    // ------------------------------------------------------------------
    // 基线
    // ------------------------------------------------------------------

    /** 完整扫描基线：4 项 + 历史/收藏 + 缩略图 + 快照。返回 4 个媒体项。 */
    private suspend fun runBaseline(): List<MediaItem> {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        val result = harness.scan()
        assertEquals("首次完整扫描应新增 4 项", 4, result.added)
        val items = harness.items()
        assertEquals(4, items.size)
        harness.seedHistory(*items.toTypedArray())
        harness.writeThumbs("ms-1", "ms-2")
        assertEquals(4, harness.historyCount())
        assertNotNull("完整扫描应写出快照", harness.snapshotBytes())
        assertEquals(4, harness.snapshotItemCount())
        return items
    }

    /**
     * 断言"受保护数据"没有被动过：历史条数、收藏、旧快照字节、缩略图都保持基线，
     * 且扫描摘要明确标记"不完整"。
     *
     * [expectNote] 为 false 时只校验数据保护、不要求摘要——取消场景下扫描在提交前
     * 就被取消，不会产生任何摘要。
     */
    private suspend fun assertDataProtected(
        items: List<MediaItem>,
        snapshot: ByteArray,
        expectNote: Boolean = true,
    ) {
        assertEquals("历史条数不应变化", 4, harness.historyCount())
        items.forEach { item ->
            assertTrue("收藏 ${item.id} 不应被删除", harness.isFavorite(item))
        }
        assertArrayEquals("旧快照字节不应被覆盖", snapshot, harness.snapshotBytes())
        val thumbs = harness.thumbFileNames()
        assertTrue("缩略图 ms-1.jpg 应保留（实际 $thumbs）", "ms-1.jpg" in thumbs)
        assertTrue("缩略图 ms-2.jpg 应保留（实际 $thumbs）", "ms-2.jpg" in thumbs)
        if (expectNote) {
            val note = harness.lastScanNote()
            assertNotNull("不完整扫描应有摘要 note", note)
            assertTrue("摘要应标记不完整（实际：$note）", note!!.contains("不完整"))
        }
    }

    private suspend fun protectedSnapshot(items: List<MediaItem>): ByteArray {
        assertEquals(4, items.size)
        return harness.snapshotBytes()!!
    }

    // ------------------------------------------------------------------
    // A1 / A2：MediaStore 子查询失败 / 返回 null
    // ------------------------------------------------------------------

    @Test
    fun `A1 video query failure must not delete history favorites snapshot or thumbs`() = runBlocking {
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        harness.mediaProvider.failVideoQuery = true
        harness.scan()

        assertDataProtected(items, snapshot)
    }

    @Test
    fun `A2 video query returning null must not delete history favorites snapshot or thumbs`() = runBlocking {
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        harness.mediaProvider.returnNullVideoQuery = true
        harness.scan()

        assertDataProtected(items, snapshot)
    }

    // ------------------------------------------------------------------
    // A3 / A4：SAF 隐藏目录遍历失败 / 预算耗尽
    // ------------------------------------------------------------------

    @Test
    fun `A3 hidden walk failure must not delete history favorites snapshot or thumbs`() = runBlocking {
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        harness.docsProvider.failForDocId = ScanTestTree.MAIN_TREE_DOC_ID
        harness.scan()

        assertDataProtected(items, snapshot)
    }

    @Test
    fun `A4 hidden walk budget exhaustion must not delete history favorites snapshot or thumbs`() = runBlocking {
        // 预算只能构造时指定：新开一个预算为 2 的 harness，基线扫描时还没有隐藏文件
        harness = ScanHarness(hiddenWalkBudget = 2)
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        // 5 个隐藏视频文件：预算 2 走不完 → 必须判为不完整
        harness.docsProvider.setChildren(
            ScanTestTree.MAIN_TREE_DOC_ID,
            (1..5).map { index ->
                FakeDocumentsProvider.DocEntry(
                    docId = "${ScanTestTree.MAIN_TREE_DOC_ID}/.hidden-$index.mp4",
                    displayName = ".hidden-$index.mp4",
                    mimeType = "video/mp4",
                )
            },
        )
        harness.scan()

        assertDataProtected(items, snapshot)
    }

    // ------------------------------------------------------------------
    // A5：权限收缩（音频被收回）
    // ------------------------------------------------------------------

    @Test
    fun `A5 audio permission shrink keeps audio history and still scans video`() = runBlocking {
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        harness.revokeAudio()
        harness.scan()

        // 视频项仍能扫到（不能因为音频被拒就整库不可用）
        val visible = harness.items()
        assertTrue("视频项应仍可见（实际 ${visible.map { it.id }}）", visible.isNotEmpty())
        assertTrue("收缩后不应出现音频项", visible.none { it.mimeType.startsWith("audio/") })

        val note = harness.lastScanNote()
        assertNotNull(note)
        assertTrue(
            "摘要应说明音频未覆盖/不完整（实际：$note）",
            note!!.contains("音频") || note.contains("不完整"),
        )
        // 历史/收藏/旧快照/缩略图全部保留
        assertDataProtected(items, snapshot)
    }

    // ------------------------------------------------------------------
    // A6：取消异常原样传播
    // ------------------------------------------------------------------

    @Test
    fun `A6 cancellation propagates as CancellationException and commits nothing`() = runBlocking {
        val items = runBaseline()
        val snapshot = protectedSnapshot(items)

        val entered = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            awaitCancellation()
        }
        var failure: Throwable? = null
        val job = launch(Dispatchers.Default) {
            failure = runCatching { harness.source.scan() }.exceptionOrNull()
        }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        harness.source.beforeCommitHook = null

        assertTrue(
            "取消必须原样以 CancellationException 传播（实际：$failure）",
            failure is java.util.concurrent.CancellationException,
        )
        assertFalse("取消后不应仍标记扫描中", harness.source.scanning.value)
        assertDataProtected(items, snapshot, expectNote = false)
    }

    // ------------------------------------------------------------------
    // A7：空但完整 → 允许确认删除
    // ------------------------------------------------------------------

    @Test
    fun `A7 empty but complete scan prunes history favorites snapshot and thumbs`() = runBlocking {
        val items = runBaseline()
        assertEquals(4, items.size)

        harness.mediaProvider.clearAll()
        harness.docsProvider.clearChildren()
        harness.scan()

        assertEquals("空但完整应清空媒体列表", 0, harness.items().size)
        assertEquals("空但完整应清理历史", 0, harness.historyCount())
        items.forEach { item ->
            assertFalse("收藏 ${item.id} 应被清理", harness.isFavorite(item))
        }
        assertNotNull("空但完整也应写出（空）快照", harness.snapshotBytes())
        assertEquals(0, harness.snapshotItemCount())
        assertEquals("缩略图应被清理", emptyList<String>(), harness.thumbFileNames())
        assertNull("完整扫描不应标记不完整（实际：${harness.lastScanNote()}）", harness.lastScanNote())
    }

    // ------------------------------------------------------------------
    // A8：空但完整 + 仅音频可见（视频权限被收回）
    // ------------------------------------------------------------------

    @Test
    fun `A8 empty but complete visible category still prunes its history`() = runBlocking {
        // 基线：只有音频项（视频类别"可见但为空"，因此完整）
        harness.grantAllMediaPermissions()
        harness.mediaProvider.insertAudio(11, "song-a.mp3")
        harness.mediaProvider.insertAudio(12, "song-b.mp3")
        harness.setFolders(ScanTestTree.MAIN_TREE)
        harness.scan()
        val audioItems = harness.items()
        assertEquals(2, audioItems.size)
        harness.seedHistory(*audioItems.toTypedArray())
        assertEquals(2, harness.historyCount())

        // 权限收缩为"仅音频可见"，同时音频库也清空 → 可见类别被完整覆盖为空
        harness.revokeVideo()
        harness.mediaProvider.clearAudio()
        harness.scan()

        assertEquals("可见类别（音频）完整覆盖为空时，音频历史应被清理", 0, harness.historyCount())
    }
}
