package com.noneplayer.plugins.scanner.local

import android.provider.DocumentsContract
import com.noneplayer.kernel.api.ScanPhase
import com.noneplayer.kernel.api.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分阶段进度（用户抱怨③：「没有扫描进度」）。
 *
 * 六段 [ScanPhase] 必须各至少上报一次，UI 才能从"未上报"变成"正在做哪一段"；
 * HIDDEN_WALK 段 total 恒 0、processed 报「已发现条目数」（目录树深度未知，无法给总数）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanProgressPhaseTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    @Test
    fun `六段阶段各至少上报一次`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)

        val snapshots = java.util.Collections.synchronizedList(mutableListOf<ScanProgress>())
        // Unconfined 收集器：每次 `_scanProgress.value = ...` 都会在**发射线程上同步**跑到
        // collect，不会被 StateFlow 的合并语义吞掉中间的短段（PREPARING/MEDIA_STORE 默认必丢）
        val recorder = launch(Dispatchers.Unconfined) {
            harness.source.observeScanProgress().collect { snapshots.add(it) }
        }
        harness.scan()
        recorder.cancel()

        val expected = listOf(
            ScanPhase.PREPARING,
            ScanPhase.MEDIA_STORE,
            ScanPhase.HIDDEN_WALK,
            ScanPhase.COVERS,
            ScanPhase.DURATION,
            ScanPhase.COMMIT,
        )
        val seen = snapshots.mapNotNull { it.phase }
        val missing = expected.filterNot { it in seen }
        assertTrue("以下阶段一次都没上报：$missing（实际序列 $seen）", missing.isEmpty())
        // 末值必须是 idle：UI 的进度条靠它收尾
        assertEquals(ScanProgress(), snapshots.last())
    }

    @Test
    fun `隐藏目录遍历期间 total 为 0 且 processed 是已发现条目数`() = runBlocking {
        harness.grantAllMediaPermissions()
        // 显式选到隐藏目录（E198 允许整支下钻），其内 3 个文件会被发现
        val hiddenTree = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2F.nphidden"
        val hiddenRoot = "primary:Movies/.nphidden"
        harness.setFolders(hiddenTree)
        harness.docsProvider.setChildren(
            hiddenRoot,
            (1..3).map { index ->
                FakeDocumentsProvider.DocEntry(
                    docId = "$hiddenRoot/v$index.mp4",
                    displayName = "v$index.mp4",
                    mimeType = "video/mp4",
                )
            },
        )
        // 故意不加任何注入延迟：只有 1 个目录的小隐藏树既走不到 16 个目录的节流点、也过不了 100ms
        // 时间窗，必须靠「首次发现条目必报一次」把真实 processed 报出来（否则界面观感还是「一直 0」）
        harness.docsProvider.queryDelayMs = 0L

        val snapshots = java.util.Collections.synchronizedList(mutableListOf<ScanProgress>())
        val recorder = launch(Dispatchers.Unconfined) {
            harness.source.observeScanProgress().collect { snapshots.add(it) }
        }
        harness.scan()
        recorder.cancel()

        val walkFrames = snapshots.filter { it.phase == ScanPhase.HIDDEN_WALK }
        assertTrue("HIDDEN_WALK 段应上报过（实际 $snapshots）", walkFrames.isNotEmpty())
        assertTrue("HIDDEN_WALK 期间 total 必须为 0", walkFrames.all { it.total == 0 })
        assertTrue(
            "walk 期间应报出「已发现 3 条」的进度（实际 $walkFrames）",
            walkFrames.any { it.processed == 3 },
        )
        assertEquals("walk 结束后条目应进库", 3, harness.items().size)
    }
}
