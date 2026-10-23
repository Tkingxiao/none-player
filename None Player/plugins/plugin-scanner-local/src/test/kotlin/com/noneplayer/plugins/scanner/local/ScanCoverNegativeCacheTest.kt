package com.noneplayer.plugins.scanner.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * [ScanCoverNegativeCache]：封面否定缓存。
 *
 * 时间和节流时钟都可注入，所以 TTL 边界、节流窗口都是精确断言。
 *
 * 回归点：
 * 1. 指纹 key（id|size|mtime）变了就必须重试 —— 否则替换过的文件会被永久拒绝；
 * 2. 7 天边界：恰好到期仍命中、超 1ms 失效并顺手清掉；
 * 3. 上限 2000 条按最旧淘汰，落盘也遵守（写超限文件会让下次 load 直接空表，等于全量失效）；
 * 4. 坏 JSON / 超大文件 / 版本不符一律当空表，且之后还能正常写；
 * 5. save() 节流 ≥1s，flush() 忽略节流；落盘不留 .tmp。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCoverNegativeCacheTest {

    private lateinit var root: File
    private lateinit var file: File
    private val logs = ArrayList<String>()
    private var now = 0L

    private val ttl = ScanCoverNegativeCache.DEFAULT_TTL_MS
    private val key = ScanCoverNegativeCache.keyOf("doc-1", 1024L, 1_700_000_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "cover-negative-test-" + UUID.randomUUID())
        assertTrue("临时目录创建失败", root.mkdirs())
        file = File(root, ScanCoverNegativeCache.FILE_NAME)
        now = 1_000_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun cache(
        maxEntries: Int = ScanCoverNegativeCache.MAX_ENTRIES,
        minSaveIntervalMs: Long = ScanCoverNegativeCache.MIN_SAVE_INTERVAL_MS,
    ) = ScanCoverNegativeCache(
        file = file,
        maxEntries = maxEntries,
        minSaveIntervalMs = minSaveIntervalMs,
        nowMs = { now },
        log = { logs.add(it) },
    )

    private fun storedEntries(): JSONObject? {
        if (!file.isFile) return null
        return JSONObject(String(file.readBytes(), Charsets.UTF_8)).optJSONObject("entries")
    }

    // ---------------- 基本命中 / 幂等 ----------------

    @Test
    fun `缺文件时全部未失败_日志记0条`() {
        val c = cache()
        assertFalse(c.isFailed(key, now))
        assertEquals(0, c.failedCount())
        assertTrue("缺少 loaded 日志：" + logs, logs.any { it == "cover negative loaded entries=0" })
    }

    @Test
    fun `标记失败后命中_并有saved日志`() {
        val c = cache()
        assertTrue(c.markFailed(key, now))
        assertTrue(c.isFailed(key, now))
        assertEquals(1, c.failedCount())
        assertTrue("缺少 saved 日志：" + logs, logs.any { it == "cover negative saved entries=1" })
    }

    @Test
    fun `重复标记同一指纹_幂等返回false`() {
        val c = cache()
        assertTrue(c.markFailed(key, now))
        assertFalse("已有未过期结论，不该重复写", c.markFailed(key, now + 1000L))
        assertEquals(1, c.failedCount())
    }

    @Test
    fun `落盘后新实例能读回结论`() {
        cache().markFailed(key, now)
        val reopened = cache()
        assertTrue(reopened.isFailed(key, now + 1L))
        assertEquals(1, reopened.failedCount())
        assertTrue("缺少 loaded 日志：" + logs, logs.any { it == "cover negative loaded entries=1" })
    }

    // ---------------- TTL ----------------

    @Test
    fun `TTL边界_恰好7天仍命中_超1毫秒失效并清除`() {
        val c = cache()
        c.markFailed(key, now)
        assertTrue("age == ttl 仍有效", c.isFailed(key, now + ttl))
        assertFalse("age > ttl 必须重试", c.isFailed(key, now + ttl + 1))
        assertEquals("过期项应被读到即淘汰", 0, c.failedCount())
    }

    @Test
    fun `过期后重新标记_返回true`() {
        val c = cache()
        c.markFailed(key, now)
        assertTrue("过期条目允许重新记录", c.markFailed(key, now + ttl + 1))
        assertTrue(c.isFailed(key, now + ttl + 1))
    }

    @Test
    fun `未来时间戳_按过期处理_不永久拒绝`() {
        val c = cache()
        c.markFailed(key, now + 5000L) // 记录时间晚于「现在」：时钟被回拨的迹象
        assertFalse("可疑时间戳不能当成有效结论", c.isFailed(key, now))
    }

    // ---------------- 上限淘汰 ----------------

    @Test
    fun `超过上限_按最旧淘汰`() {
        val c = cache(maxEntries = 3)
        val k1 = ScanCoverNegativeCache.keyOf("d1", 1L, 1L)
        val k2 = ScanCoverNegativeCache.keyOf("d2", 1L, 1L)
        val k3 = ScanCoverNegativeCache.keyOf("d3", 1L, 1L)
        val k4 = ScanCoverNegativeCache.keyOf("d4", 1L, 1L)
        c.markFailed(k1, now)
        c.markFailed(k2, now + 1)
        c.markFailed(k3, now + 2)
        c.markFailed(k4, now + 3)
        assertEquals(3, c.failedCount())
        assertFalse("最旧的那条应被淘汰", c.isFailed(k1, now + 3))
        assertTrue(c.isFailed(k4, now + 3))
    }

    @Test
    fun `落盘条目数也遵守上限`() {
        val c = cache(maxEntries = 3)
        repeat(5) { i -> c.markFailed(ScanCoverNegativeCache.keyOf("d" + i, 1L, 1L), now + i) }
        c.flush()
        val stored = storedEntries()
        assertTrue("必须落盘", stored != null)
        assertEquals(3, stored!!.length())
        assertNull("最旧的 d0 已淘汰", stored.opt("d0|1|1"))
    }

    @Test
    fun `读取侧也执行上限_只留最新的`() {
        val stored = JSONObject()
        stored.put(ScanCoverNegativeCache.keyOf("old", 1L, 1L), now - 5_000L)
        stored.put(ScanCoverNegativeCache.keyOf("mid", 1L, 1L), now - 1_000L)
        stored.put(ScanCoverNegativeCache.keyOf("new", 1L, 1L), now - 10L)
        file.writeText(JSONObject().put("version", 1).put("entries", stored).toString())

        val c = cache(maxEntries = 2)
        assertEquals(2, c.failedCount())
        assertFalse(c.isFailed(ScanCoverNegativeCache.keyOf("old", 1L, 1L), now))
        assertTrue(c.isFailed(ScanCoverNegativeCache.keyOf("new", 1L, 1L), now))
    }

    // ---------------- 容错 ----------------

    @Test
    fun `坏JSON_当空表且之后仍能写`() {
        file.writeText("not json at all")
        val c = cache()
        assertEquals(0, c.failedCount())
        assertTrue(c.markFailed(key, now))
        c.flush()
        assertEquals(1, storedEntries()!!.length())
    }

    @Test
    fun `超过256KB的文件_当空表`() {
        // 300KB 的垃圾内容：读进来也不能当真相（可能是别的版本/被破坏的文件）。
        file.writeBytes(ByteArray(300 * 1024) { 'x'.code.toByte() })
        val c = cache()
        assertEquals(0, c.failedCount())
        assertFalse(c.isFailed(key, now))
        assertTrue("应留下 oversized 日志：" + logs, logs.any { it.contains("oversized") })
    }

    @Test
    fun `版本不符_当空表`() {
        file.writeText(JSONObject().put("version", 2).put("entries", JSONObject().put(key, now)).toString())
        assertEquals(0, cache().failedCount())
    }

    @Test
    fun `落盘超配额时丢最旧_文件不超256KB`() {
        // 长 key 撑爆配额：必须丢最旧的条目，而不是写一个下次 load 会当空表的超大文件。
        val c = cache(maxEntries = 2_000, minSaveIntervalMs = 600_000L)
        val longId = "d".repeat(300)
        repeat(2_000) { i -> c.markFailed(ScanCoverNegativeCache.keyOf(longId + i, 1L, 1L), now + i) }
        c.flush()

        assertTrue("文件大小必须 <= 上限，实际 " + file.length(), file.length() <= ScanCoverNegativeCache.MAX_PAYLOAD_BYTES)
        val stored = storedEntries()!!
        assertTrue("应保留至少一条", stored.length() >= 1)
        assertTrue("最旧的必须被丢掉，实际 " + stored.length(), stored.length() < 2_000)
    }

    // ---------------- 节流 ----------------

    @Test
    fun `save节流_1秒内不重复写盘_超过后写`() {
        val c = cache()
        val k2 = ScanCoverNegativeCache.keyOf("doc-2", 1L, 1L)
        c.markFailed(key, now) // 首次写盘（last 为 null，不受节流）
        assertEquals(1, storedEntries()!!.length())

        c.markFailed(k2, now) // 内部 save 被节流跳过
        assertFalse("1 秒内不该写盘", c.save())
        assertEquals("落盘仍是旧内容", 1, storedEntries()!!.length())

        now += ScanCoverNegativeCache.MIN_SAVE_INTERVAL_MS
        assertTrue("够 1 秒应写盘", c.save())
        assertEquals(2, storedEntries()!!.length())
    }

    @Test
    fun `flush忽略节流_立即写盘`() {
        val c = cache()
        c.markFailed(key, now)
        val k2 = ScanCoverNegativeCache.keyOf("doc-2", 1L, 1L)
        c.markFailed(k2, now) // 节流跳过
        assertTrue("flush 必须无视节流", c.flush())
        assertEquals(2, storedEntries()!!.length())
    }

    @Test
    fun `无变更时save不写`() {
        assertFalse(cache().save())
    }

    @Test
    fun `落盘不留tmp文件`() {
        val c = cache()
        c.markFailed(key, now)
        c.flush()
        assertTrue(file.isFile)
        assertFalse("原子替换后不许留 .tmp", File(file.path + ".tmp").exists())
    }

    // ---------------- clear / 指纹 ----------------

    @Test
    fun `clear清空条目并删除文件`() {
        val c = cache()
        c.markFailed(key, now)
        c.flush()
        c.clear()
        assertEquals(0, c.failedCount())
        assertFalse("落盘文件应被删除", file.exists())
        assertTrue("清空后还能重新记录", c.markFailed(key, now + 10L))
    }

    @Test
    fun `文件指纹变化即重试`() {
        val c = cache()
        c.markFailed(ScanCoverNegativeCache.keyOf("doc-1", 1024L, 1_700_000_000L), now)
        assertTrue(c.isFailed(ScanCoverNegativeCache.keyOf("doc-1", 1024L, 1_700_000_000L), now))
        assertFalse("大小变了要重试", c.isFailed(ScanCoverNegativeCache.keyOf("doc-1", 2048L, 1_700_000_000L), now))
        assertFalse("mtime 变了要重试", c.isFailed(ScanCoverNegativeCache.keyOf("doc-1", 1024L, 1_700_000_001L), now))
        assertFalse("id 变了要重试", c.isFailed(ScanCoverNegativeCache.keyOf("doc-2", 1024L, 1_700_000_000L), now))
    }

    @Test
    fun `keyOf格式固定为id竖线大小竖线mtime`() {
        assertEquals(
            "primary:Foo/a.mp4|1024|1700000000",
            ScanCoverNegativeCache.keyOf("primary:Foo/a.mp4", 1024L, 1_700_000_000L),
        )
    }
}
