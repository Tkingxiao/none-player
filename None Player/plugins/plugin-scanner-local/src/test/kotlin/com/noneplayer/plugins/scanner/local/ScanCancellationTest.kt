package com.noneplayer.plugins.scanner.local

import android.provider.DocumentsContract
import com.noneplayer.kernel.api.ScanPhase
import com.noneplayer.kernel.api.ScanProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 取消真正生效（用户抱怨③：「无法取消扫描」）。
 *
 * 旧实现的 walk 是同步递归、整段没有挂起点——`cancelScan()` 取消 job 之后要等整棵
 * 目录树走完才响应，用户看到的是"点了取消还在转"。现在 walk 是多车道 suspend 工作队列，
 * 每个目录、每一行游标前都有取消点，这里用**可注入延迟的** [FakeDocumentsProvider]
 * 把 walk 拉长到秒级，硬性断言取消后 300ms 内收敛。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCancellationTest {

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        DataStoreTestReset.reset()
        harness = ScanHarness()
    }

    private fun dir(docId: String, name: String) =
        FakeDocumentsProvider.DocEntry(docId, name, DocumentsContract.Document.MIME_TYPE_DIR)

    /** 造一条 [depth] 层目录链：每层一次 children 查询（带注入延迟），把 walk 拉长到秒级。 */
    private fun chainOfDirectories(depth: Int) {
        var parent = ScanTestTree.MAIN_TREE_DOC_ID
        for (index in 1..depth) {
            val child = "$parent/d$index"
            harness.docsProvider.setChildren(parent, listOf(dir(child, "d$index")))
            parent = child
        }
    }

    @Test
    fun `隐藏目录遍历中取消_300ms 内返回且不留失败摘要`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        chainOfDirectories(depth = 40)
        // 40 层 × 30ms ≈ 1.2s 的 walk：取消必须发生在这段进行中，才有验证意义
        harness.docsProvider.queryDelayMs = 30L

        // 先订阅再开扫：确认已经进入 HIDDEN_WALK 段
        val walkEntered = async(Dispatchers.Unconfined) {
            harness.source.observeScanProgress().first { it.phase == ScanPhase.HIDDEN_WALK }
        }
        val job = launch(Dispatchers.Default) { runCatching { harness.source.scan() } }
        withTimeout(5_000) { walkEntered.await() }

        val startedAt = System.nanoTime()
        harness.source.cancelScan()
        // 硬指标：300ms 内必须收敛
        withTimeout(300) { job.join() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("[取消实测] cancelScan → job.join 收敛耗时 = ${elapsedMs}ms")

        assertTrue("cancelScan 后应在 300ms 内返回，实际 ${elapsedMs}ms", elapsedMs < 300)
        assertEquals("取消不得被上报成失败摘要", "已取消", harness.lastScanNote())
        assertFalse("取消后 scanning 必须回 false", harness.source.scanning.value)
        assertEquals(
            "取消后进度必须复位成 idle",
            ScanProgress(),
            harness.source.observeScanProgress().first(),
        )
    }

    @Test
    fun `提交段取消_摘要记为已取消而不是扫描失败`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)

        val entered = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await() // 一直挂着，直到 cancelScan 取消整次扫描
        }

        val job = launch(Dispatchers.Default) { runCatching { harness.source.scan() } }
        withTimeout(5_000) { entered.await() }

        harness.source.cancelScan()
        withTimeout(2_000) { job.join() }
        harness.source.beforeCommitHook = null

        val note = harness.lastScanNote()
        assertEquals("已取消", note)
        assertFalse("取消不是失败：摘要里不该出现「扫描失败」", note.orEmpty().startsWith("扫描失败"))
        assertEquals(false, harness.source.scanning.value)
    }

    @Test
    fun `取消之后立刻重扫能正常跑完_取消哨兵已复位`() = runBlocking {
        harness.grantAllMediaPermissions()
        harness.insertDefaultLibrary()
        harness.setFolders(ScanTestTree.MAIN_TREE)
        harness.docsProvider.setChildren(ScanTestTree.MAIN_TREE_DOC_ID, emptyList())

        val entered = CompletableDeferred<Unit>()
        harness.source.beforeCommitHook = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val job = launch(Dispatchers.Default) { runCatching { harness.source.scan() } }
        withTimeout(5_000) { entered.await() }
        harness.source.cancelScan()
        withTimeout(2_000) { job.join() }
        harness.source.beforeCommitHook = null

        // 上一轮的 cancelRequested 必须随 finally 复位，否则这一轮会在第一个取消点就自杀
        harness.source.scan()

        assertTrue("取消后重扫应重新产出条目", harness.items().isNotEmpty())
        assertNull("正常扫描不该留 note", harness.lastScanNote())
    }
}
