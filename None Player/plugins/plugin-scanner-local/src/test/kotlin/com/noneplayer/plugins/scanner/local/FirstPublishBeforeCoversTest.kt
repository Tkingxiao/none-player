package com.noneplayer.plugins.scanner.local

import com.noneplayer.kernel.api.ScanProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A25：首扫先发布基础列表，封面/缩略图在发布之后补齐。
 *
 * 没有快照的首次扫描，旧实现要等全库抽帧结束才 `_media.value = items`；
 * 大库时媒体库页一直停在骨架。这里用 [LocalMediaSource.beforeCoversHook] 暂停
 * 在“基础列表已发布、封面尚未补齐”的时点，断言 UI 已经能看到条目。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstPublishBeforeCoversTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    @Test
    fun `首扫在封面阶段前先发布基础列表`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.source.beforeCoversHook = {
            entered.complete(Unit)
            release.await()
        }

        val job = launch(Dispatchers.Default) { harness.source.scan() }
        withTimeout(5_000) { entered.await() }

        val early = harness.items()
        assertTrue("首扫应在封面补齐前已有内容", early.isNotEmpty())
        assertTrue("此刻扫描仍在进行", harness.source.scanning.value)

        release.complete(Unit)
        job.join()
        harness.source.beforeCoversHook = null

        assertEquals("最终列表不得少于基础列表", early.size, harness.items().size)
    }

    @Test
    fun `取消扫描后进度回到 idle`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)

        val entered = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await() // 挂住，直到 cancelScan 取消整个扫描
        }

        val job = launch(Dispatchers.Default) { runCatching { harness.source.scan() } }
        withTimeout(5_000) { entered.await() }
        assertTrue(harness.source.scanning.value)

        harness.source.cancelScan()
        job.join()
        harness.source.beforeCommitHook = null

        assertEquals(false, harness.source.scanning.value)
        assertEquals(ScanProgress(), harness.source.observeScanProgress().first())
    }
}
