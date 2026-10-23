package com.noneplayer.plugins.scanner.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * [ScanWalkIndex]：已存在文件夹的二次扫描增量索引。
 *
 * 核心回归点（全部对应用户抱怨「已存在文件夹也要重扫很久」）：
 * 1. 写读往返不丢字段（ID 流水线要靠 items 里的 docId 重建，丢一个字段就丢文件）；
 * 2. **只对 leaf 目录复用** —— 非叶目录的深层新增不会传播 mtime 到祖先，复用会漏文件；
 * 3. lmMs <= 0 / mtime 变化 / TTL 过期 / inheritedHidden 变化 / payload 被配额丢弃，一律不复用；
 * 4. 版本不符、文件损坏按空索引处理，绝不抛异常（索引不是真相来源）；
 * 5. 配额：先丢 items 留骨架，骨架仍超额再从最旧整条丢弃；落盘永不超过 MAX_PAYLOAD_BYTES。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanWalkIndexTest {

    private lateinit var root: File
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "walk-index-test-${UUID.randomUUID()}")
        assertTrue("临时目录创建失败", root.mkdirs())
        file = File(root, "scan_walk_index.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newIndex(ttlMs: Long = ScanWalkIndex.DEFAULT_TTL_MS) =
        ScanWalkIndex(file = file, ttlMs = ttlMs, ioDispatcher = Dispatchers.Unconfined)

    private fun indexed(i: Int, tag: String = "") = ScanWalkIndex.Indexed(
        docId = "doc-$i$tag",
        name = "视频 ${i}$tag.mp4",
        mime = "video/mp4",
        sizeBytes = 1024L * i,
        lastModifiedSec = 1_700_000_000L + i,
        hidden = i % 2 == 0,
    )

    private fun entry(
        docId: String,
        leaf: Boolean = true,
        lmMs: Long = LM,
        hidden: Boolean = false,
        items: List<ScanWalkIndex.Indexed> = emptyList(),
        walkedAtMs: Long = NOW,
        treeUri: String = TREE,
        payloadDropped: Boolean = false,
    ) = ScanWalkIndex.Entry(
        docId = docId,
        treeUri = treeUri,
        lastModifiedMs = lmMs,
        inheritedHidden = hidden,
        leaf = leaf,
        items = items,
        walkedAtMs = walkedAtMs,
        payloadDropped = payloadDropped,
    )

    // ---- 1) 写读往返 ----

    @Test
    fun `往返_叶子与非叶条目写入后读回完全一致`() = runTest {
        val index = newIndex()
        val leafEntry = entry("doc-leaf-1", items = (1..5).map { indexed(it) })
        val branchEntry = entry("doc-branch-1", leaf = false, lmMs = LM + 1, hidden = true)
        index.record(leafEntry)
        index.record(branchEntry)

        assertTrue("save 应成功", index.save())

        val reloaded = newIndex()
        reloaded.load()
        assertEquals(2, reloaded.size())
        assertEquals(leafEntry, reloaded.lookup(TREE, "doc-leaf-1"))
        assertEquals(branchEntry, reloaded.lookup(TREE, "doc-branch-1"))
        // items 的每个字段都要过一遍序列化
        assertEquals((1..5).map { indexed(it) }, reloaded.lookup(TREE, "doc-leaf-1")!!.items)
    }

    @Test
    fun `往返_不同树的同名docId互不覆盖`() = runTest {
        val index = newIndex()
        index.record(entry("same", treeUri = "content://tree/a", lmMs = 111L))
        index.record(entry("same", treeUri = "content://tree/b", lmMs = 222L))
        assertTrue(index.save())

        val reloaded = newIndex()
        reloaded.load()
        assertEquals(2, reloaded.size())
        assertEquals(111L, reloaded.lookup("content://tree/a", "same")!!.lastModifiedMs)
        assertEquals(222L, reloaded.lookup("content://tree/b", "same")!!.lastModifiedMs)
        assertNotEquals(ScanWalkIndex.keyOf("content://tree/a", "same"), ScanWalkIndex.keyOf("content://tree/b", "same"))
    }

    @Test
    fun `缺失文件_load当空索引不抛`() = runTest {
        val index = newIndex()
        index.record(entry("doc-1"))
        index.load() // 文件还不存在
        assertEquals(0, index.size())
        assertNull(index.lookup(TREE, "doc-1"))
        assertNull(index.reusable(TREE, "doc-1", LM, false, NOW))
    }

    // ---- 2) 版本不符 / 损坏 ----

    @Test
    fun `版本不符_整体忽略当空索引`() = runTest {
        file.writeText(
            JSONObject()
                .put("version", ScanWalkIndex.VERSION + 1)
                .put("entries", JSONArray().put(entryJson("doc-old", LM)))
                .toString(),
        )
        val index = newIndex()
        index.load()
        assertEquals(0, index.size())
        assertNull(index.lookup(TREE, "doc-old"))
    }

    @Test
    fun `文件损坏_不抛异常当空索引`() = runTest {
        file.writeText("{这不是合法 JSON")
        val index = newIndex()
        index.load()
        assertEquals(0, index.size())
    }

    @Test
    fun `条目缺items数组_视为payload已丢_不复用`() = runTest {
        // 手写一份只有骨架、没有 payloadDropped 标记的旧格式
        file.writeText(
            JSONObject().put("version", ScanWalkIndex.VERSION)
                .put(
                    "entries",
                    JSONArray().put(
                        JSONObject()
                            .put("docId", "doc-skeleton")
                            .put("treeUri", TREE)
                            .put("lastModifiedMs", LM)
                            .put("inheritedHidden", false)
                            .put("leaf", true)
                            .put("walkedAtMs", NOW),
                    ),
                )
                .toString(),
        )
        val index = newIndex()
        index.load()
        val e = index.lookup(TREE, "doc-skeleton")
        assertNotNull(e)
        assertTrue("没有 items 的旧条目必须按 payload 已丢处理", e!!.payloadDropped)
        assertNull("payload 已丢的骨架不得复用", index.reusable(TREE, "doc-skeleton", LM, false, NOW))
    }

    // ---- 3) 复用判据 ----

    @Test
    fun `叶子目录_mtime未变_未过TTL_可复用`() = runTest {
        val index = newIndex()
        index.record(entry("leaf", items = listOf(indexed(1), indexed(2))))

        val hit = index.reusable(TREE, "leaf", LM, false, NOW + 1_000)
        assertNotNull("lm 未变 + TTL 内应命中", hit)
        assertEquals(2, hit!!.items.size)
        assertEquals(ScanWalkIndex.keyOf(TREE, "leaf"), ScanWalkIndex.keyOf(hit.treeUri, hit.docId))
    }

    @Test
    fun `非叶目录_即使父目录mtime未变也不复用_否则漏文件`() = runTest {
        // 场景：Anc/sub/New.mp4 新增，只会改 sub 的 mtime，Anc 的 mtime 不变。
        // 若 Anc（leaf=false）被复用，就会整棵跳过 sub，漏掉 New.mp4。
        val index = newIndex()
        index.record(entry("branch", leaf = false, items = listOf(indexed(1))))

        assertNull(index.reusable(TREE, "branch", LM, false, NOW))
        // 对照：同样的 lm/TTL，只要标了 leaf=true 就能命中
        index.record(entry("leaf", leaf = true, items = listOf(indexed(1))))
        assertNotNull(index.reusable(TREE, "leaf", LM, false, NOW))
    }

    @Test
    fun `mtime变化_不复用`() = runTest {
        val index = newIndex()
        index.record(entry("leaf", lmMs = LM))
        assertNull("父目录列表看到新 mtime 必须重走", index.reusable(TREE, "leaf", LM + 1, false, NOW))
    }

    @Test
    fun `mtime为0_永不复用`() = runTest {
        val index = newIndex()
        index.record(entry("leaf", lmMs = 0L, items = listOf(indexed(1))))
        assertNull(index.reusable(TREE, "leaf", 0L, false, NOW))
    }

    @Test
    fun `TTL边界_恰好到期仍可复用_超1ms即失效`() = runTest {
        val ttl = ScanWalkIndex.DEFAULT_TTL_MS
        val index = newIndex(ttlMs = ttl)
        index.record(entry("leaf", walkedAtMs = NOW))

        assertNotNull("age == ttl 仍在有效期内", index.reusable(TREE, "leaf", LM, false, NOW + ttl))
        assertNull("age > ttl 必须重走", index.reusable(TREE, "leaf", LM, false, NOW + ttl + 1))
    }

    @Test
    fun `默认TTL为5分钟_而非30分钟`() = runTest {
        // 复用只能靠父列表里子目录的 mtime，而叶子目录内同名覆盖写不改叶子 mtime，父目录侧观察不到
        // → TTL 就是陈旧窗口的长度。默认保鲜期按用户拍板压到 5 分钟（只覆盖一次会话内的连续重扫）。
        assertEquals(5L * 60 * 1000, ScanWalkIndex.DEFAULT_TTL_MS)

        val index = newIndex() // 默认 TTL
        index.record(entry("leaf", walkedAtMs = NOW))
        assertNotNull("5 分钟内应复用", index.reusable(TREE, "leaf", LM, false, NOW + 5L * 60 * 1000))
        assertNull("超过 5 分钟必须重走", index.reusable(TREE, "leaf", LM, false, NOW + 5L * 60 * 1000 + 1))
    }

    @Test
    fun `时钟回拨_按过期处理不复用`() = runTest {
        val index = newIndex()
        index.record(entry("leaf", walkedAtMs = NOW))
        assertNull("nowMs < walkedAtMs 属可疑时钟，不接受复用", index.reusable(TREE, "leaf", LM, false, NOW - 1))
    }

    @Test
    fun `inheritedHidden不一致_不复用`() = runTest {
        val index = newIndex()
        index.record(entry("leaf", hidden = false))
        assertNull("本轮继承隐藏位变了必须重走", index.reusable(TREE, "leaf", LM, true, NOW))

        index.record(entry("hidden-leaf", hidden = true))
        assertNotNull(index.reusable(TREE, "hidden-leaf", LM, true, NOW))
        assertNull(index.reusable(TREE, "hidden-leaf", LM, false, NOW))
    }

    @Test
    fun `payload被丢弃的骨架_不复用`() = runTest {
        val index = newIndex()
        index.record(entry("skeleton", items = emptyList(), payloadDropped = true))
        assertNotNull("lookup 仍能拿到骨架", index.lookup(TREE, "skeleton"))
        assertNull("骨架无法重建内容，不得复用", index.reusable(TREE, "skeleton", LM, false, NOW))
    }

    @Test
    fun `空叶子目录_零条目仍是有效命中_0IPC`() = runTest {
        // 叶子目录里没有媒体文件是常态，复用命中必须返回空 items（而不是 null）
        val index = newIndex()
        index.record(entry("empty-leaf", items = emptyList()))
        val hit = index.reusable(TREE, "empty-leaf", LM, false, NOW)
        assertNotNull(hit)
        assertTrue(hit!!.items.isEmpty())
        assertFalse(hit.payloadDropped)
    }

    // ---- 4) retainOnly ----

    @Test
    fun `retainOnly_只保留本轮走到的目录`() = runTest {
        val index = newIndex()
        (1..5).forEach { index.record(entry("doc-$it")) }
        assertEquals(5, index.size())

        val seen = setOf(
            ScanWalkIndex.keyOf(TREE, "doc-2"),
            ScanWalkIndex.keyOf(TREE, "doc-4"),
        )
        index.retainOnly(seen)

        assertEquals(2, index.size())
        assertNotNull(index.lookup(TREE, "doc-2"))
        assertNotNull(index.lookup(TREE, "doc-4"))
        assertNull(index.lookup(TREE, "doc-1"))
        // 裁剪后落盘也只留 2 条
        assertTrue(index.save())
        val reloaded = newIndex()
        reloaded.load()
        assertEquals(2, reloaded.size())
    }

    @Test
    fun `retainOnly_空集合清空索引`() = runTest {
        val index = newIndex()
        index.record(entry("doc-1"))
        index.retainOnly(emptySet())
        assertEquals(0, index.size())
    }

    // ---- 5) 配额 ----

    @Test
    fun `超条目配额_只保留最新MAX_ENTRIES条_其余丢弃`() = runTest {
        val index = newIndex()
        val total = ScanWalkIndex.MAX_ENTRIES + 5
        for (i in 0 until total) {
            // walkedAtMs 递增：i 越大越新
            index.record(entry("doc-$i", walkedAtMs = NOW + i, items = listOf(indexed(1))))
        }

        assertTrue(index.save())
        assertTrue(
            "落盘不得超过字节上限（${file.length()} > ${ScanWalkIndex.MAX_PAYLOAD_BYTES}）",
            file.length() <= ScanWalkIndex.MAX_PAYLOAD_BYTES,
        )

        val reloaded = newIndex()
        reloaded.load()
        assertTrue("条数不得超过 MAX_ENTRIES（${reloaded.size()}）", reloaded.size() <= ScanWalkIndex.MAX_ENTRIES)
        assertTrue("裁剪后不能一条不剩", reloaded.size() > 0)
        // 最旧的 5 条必然落在被丢的一侧；最新的 1 条一定保留（同一时刻按 docId 排序，故用最新几条断言）
        for (i in 0 until 5) assertNull("最旧的 doc-$i 应被丢弃", reloaded.lookup(TREE, "doc-$i"))
        assertNotNull(
            "最新的 doc-${total - 1} 必须保留",
            reloaded.lookup(TREE, "doc-${total - 1}"),
        )
    }

    @Test
    fun `超字节配额_先丢items留骨架_骨架不复用但最新条目仍尽量复用`() = runTest {
        val index = newIndex()
        // 每条 500 项、项名较长，总量远超 2 MiB；walkedAtMs 递增，i 越大越新
        val entryCount = 80
        for (i in 0 until entryCount) {
            val items = (0 until 500).map { k -> indexed(k, tag = "-entry$i-padding-padding") }
            index.record(entry("doc-$i", walkedAtMs = NOW + i, items = items))
        }

        assertTrue("超配额应裁剪后落盘成功", index.save())
        assertTrue(
            "落盘不得超过字节上限（${file.length()}）",
            file.length() <= ScanWalkIndex.MAX_PAYLOAD_BYTES,
        )

        val reloaded = newIndex()
        reloaded.load()
        // 阶段一：只丢 payload，条数配额没超，所以 80 条骨架都在
        assertEquals(entryCount, reloaded.size())
        val newest = reloaded.lookup(TREE, "doc-${entryCount - 1}")!!
        assertFalse("最新条目应保留 payload", newest.payloadDropped)
        assertEquals(500, newest.items.size)
        assertNotNull("最新条目可复用", reloaded.reusable(TREE, "doc-${entryCount - 1}", LM, false, NOW + entryCount))

        val oldest = reloaded.lookup(TREE, "doc-0")!!
        assertTrue("最旧条目先被丢 payload", oldest.payloadDropped)
        assertTrue(oldest.items.isEmpty())
        assertNull("被丢 payload 的骨架不得复用", reloaded.reusable(TREE, "doc-0", LM, false, NOW + entryCount))
    }

    // ---- 6) 其它 ----

    @Test
    fun `clear_同时清内存与落盘文件`() = runTest {
        val index = newIndex()
        index.record(entry("doc-1"))
        assertTrue(index.save())
        assertTrue(file.isFile)

        index.clear()
        assertEquals(0, index.size())
        assertFalse("落盘文件应被删除", file.isFile)
    }

    @Test
    fun `并发record_各车道写入不丢条目`() = runTest {
        val index = newIndex()
        val lanes = 8
        val perLane = 50
        (0 until lanes).map { lane ->
            async(Dispatchers.Default) {
                for (i in 0 until perLane) {
                    index.record(entry("doc-$lane-$i", walkedAtMs = NOW + i))
                }
            }
        }.awaitAll()

        assertEquals(lanes * perLane, index.size())
        assertNotNull(index.lookup(TREE, "doc-7-49"))
    }

    @Test
    fun `重复record_同目录后写覆盖先写`() = runTest {
        val index = newIndex()
        index.record(entry("doc-1", lmMs = 100L))
        index.record(entry("doc-1", lmMs = 200L))
        assertEquals(1, index.size())
        assertEquals(200L, index.lookup(TREE, "doc-1")!!.lastModifiedMs)
    }

    @Test
    fun `空docId_被忽略`() = runTest {
        val index = newIndex()
        index.record(entry(""))
        assertEquals(0, index.size())
    }

    private fun entryJson(docId: String, lmMs: Long): JSONObject = JSONObject()
        .put("docId", docId)
        .put("treeUri", TREE)
        .put("lastModifiedMs", lmMs)
        .put("inheritedHidden", false)
        .put("leaf", true)
        .put("walkedAtMs", NOW)
        .put("payloadDropped", false)
        .put("items", JSONArray())

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        const val LM = 1_700_000_000_000L
        const val NOW = 1_700_000_500_000L
    }
}
