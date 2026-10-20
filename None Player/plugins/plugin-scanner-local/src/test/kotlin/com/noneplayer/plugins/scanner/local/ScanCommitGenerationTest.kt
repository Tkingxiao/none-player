package com.noneplayer.plugins.scanner.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * R0 / REV-03 提交代际验收（B 组）。
 *
 * 旧问题：扫描在最后一步直接 `_media.value = items` + 写快照，而 `removeScanFolder`
 * 是另一条路径；扫描期间用户移除目录，慢扫描恢复后会把已移除目录的旧结果重新发布、
 * 甚至按旧结果清理历史。
 *
 * 契约：目录集变化递增"代际"，扫描开始时捕获代际，提交区（发布列表 / 清孤儿历史 /
 * 删缩略图 / 写快照）复核代际——不一致就整份丢弃，且 `removeScanFolder` 不得等待在途扫描。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCommitGenerationTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    /** 完整扫描基线：主卷 4 项 + 历史/收藏。 */
    private suspend fun runBaseline(folders: Array<String> = arrayOf(ScanTestTree.MAIN_TREE)) {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(*folders)
        harness.scan()
        val items = harness.items()
        harness.seedHistory(*items.toTypedArray())
        assertTrue(items.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // B1：移除目录让在途扫描作废
    // ------------------------------------------------------------------

    @Test
    fun `B1 removing folder while scan is in flight discards stale publish`() = runBlocking {
        runBaseline()
        val historyBefore = harness.historyCount()

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            release.await()
        }

        val staleJob = launch(Dispatchers.Default) {
            runCatching { harness.source.scan() }
        }
        withTimeout(5_000) { entered.await() }

        // 唯一目录被移除：目录集变化 + 代际递增；remaining 为空走同步清理分支
        harness.source.removeScanFolder(ScanTestTree.MAIN_TREE)
        release.complete(Unit)
        staleJob.join()
        harness.source.beforeCommitHook = null

        assertEquals("旧扫描不得把已移除目录的列表重新发布", 0, harness.items().size)
        assertFalse("旧扫描不得重建快照", harness.snapshotFile().exists())
        assertEquals("旧扫描不得按旧结果清理历史", historyBefore, harness.historyCount())
    }

    // ------------------------------------------------------------------
    // B2：扫描期间新增目录 → 旧结果丢弃
    // ------------------------------------------------------------------

    @Test
    fun `B2 adding folder while scan is in flight discards stale publish`() = runBlocking {
        runBaseline()
        // 用户在系统选择器里授权第二卷目录（此时还没加进扫描目录设置）
        harness.grantPersistedUriPermission(ScanTestTree.SECOND_TREE)
        // 第二卷（可移动卷）里再放一个视频
        harness.mediaProvider.insertVideo(
            id = 3,
            displayName = "second.mp4",
            volumeName = "1234-ABCD",
        )

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            release.await()
        }

        var staleResult: com.noneplayer.kernel.api.ScanResult? = null
        val staleJob = launch(Dispatchers.Default) {
            staleResult = runCatching { harness.source.scan() }.getOrNull()
        }
        withTimeout(5_000) { entered.await() }

        val addJob = launch(Dispatchers.Default) {
            harness.source.addScanFolder(ScanTestTree.SECOND_TREE)
        }
        // 等 addScanFolder 完成"递增代际 + 写目录设置"，之后它才去排队等扫描闸门
        withTimeout(5_000) {
            while (harness.settings.scanFolderUris.first().size < 2) delay(10)
        }
        release.complete(Unit)
        staleJob.join()
        addJob.join()
        harness.source.beforeCommitHook = null

        assertEquals(
            "旧扫描结果必须整份丢弃（不应发布任何新增）",
            0,
            staleResult?.added ?: 0,
        )
        val items = harness.items()
        assertEquals("新扫描应覆盖两个目录：主卷 4 项 + 第二卷 1 项", 5, items.size)
        assertTrue(
            "第二卷目录项应来自新扫描（实际 ${items.map { it.id }}）",
            items.any { it.id == "ms-3" },
        )
    }

    // ------------------------------------------------------------------
    // B3：removeScanFolder 不得等待在途扫描
    // ------------------------------------------------------------------

    @Test
    fun `B3 removeScanFolder returns promptly while scan is in flight`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.mediaProvider.insertVideo(
            id = 3,
            displayName = "second.mp4",
            volumeName = "1234-ABCD",
        )
        harness.setFolders(ScanTestTree.MAIN_TREE, ScanTestTree.SECOND_TREE)
        harness.scan()
        assertEquals(5, harness.items().size)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            release.await()
        }
        val staleJob = launch(Dispatchers.Default) {
            runCatching { harness.source.scan() }
        }
        withTimeout(5_000) { entered.await() }

        val startedAtNanos = System.nanoTime()
        val removed = runCatching {
            withTimeout(500) { harness.source.removeScanFolder(ScanTestTree.MAIN_TREE) }
        }
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000

        release.complete(Unit)
        staleJob.join()
        harness.source.beforeCommitHook = null

        assertTrue(
            "removeScanFolder 不应等待在途扫描（耗时 ${elapsedMs}ms，异常：${removed.exceptionOrNull()}）",
            removed.isSuccess && elapsedMs < 500,
        )
        assertFalse(
            "目录设置应已移除主卷",
            harness.settings.scanFolderUris.first().contains(ScanTestTree.MAIN_TREE),
        )
    }
}
