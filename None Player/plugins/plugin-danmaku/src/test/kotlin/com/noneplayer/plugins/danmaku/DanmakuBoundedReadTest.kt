package com.noneplayer.plugins.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * E223：弹幕读取有界 + 内嵌 spawned 倒退重建。
 *
 * - 有界读：≤上限全量返回；>上限判 Oversize 且**不再读入**（大文件不进内存）；
 * - spawned 重建：正常追加保持时间升序；倒退 seek 整表清空；乱序插入被排回
 *   升序（不变量兜底）；
 * - 倒退判定：倒退超阈值才算，普通抖动/同位刷新不算。
 */
class DanmakuBoundedReadTest {

    // ---- 有界读 ----

    @Test
    fun `bounded read returns content under cap`() {
        val data = "弹幕文本".toByteArray(Charsets.UTF_8)
        val read = readBoundedBytes(ByteArrayInputStream(data), cap = 1024)
        assertTrue(read is BoundedRead.Content)
        assertEquals("弹幕文本", String((read as BoundedRead.Content).bytes, Charsets.UTF_8))
    }

    @Test
    fun `bounded read flags oversize and stops reading`() {
        // 1KB 上限 + 4KB 输入：判超限（读入量不超上限+一个缓冲块，而不是整段进内存）
        val data = ByteArray(4 * 1024) { 'x'.code.toByte() }
        val read = readBoundedBytes(ByteArrayInputStream(data), cap = 1024)
        assertTrue(read is BoundedRead.Oversize)
    }

    @Test
    fun `bounded read at exactly cap is content`() {
        val data = ByteArray(1024) { 'a'.code.toByte() }
        val read = readBoundedBytes(ByteArrayInputStream(data), cap = 1024)
        assertTrue(read is BoundedRead.Content)
        // 常量口径：16MB（顺手钉住，防止无意间调大 OOM 面/调小误伤正常文件）
        assertEquals(16L * 1024 * 1024, MAX_DANMAKU_BYTES)
    }

    // ---- spawned 重建 ----

    private fun dan(timeMs: Long, text: String = "弹幕") = Danmaku(
        timeMs = timeMs,
        mode = DanmakuMode.Scroll,
        fontScale = 1f,
        colorArgb = 0xFFFFFFFF.toInt(),
        text = text,
    )

    @Test
    fun `spawn appends and keeps time ascending`() {
        val current = listOf(dan(1_000), dan(2_000))
        val next = nextSpawnedOnCue(current, dan(3_000), backwardSeek = false)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), next.map { it.timeMs })
    }

    @Test
    fun `backward seek clears stale spawns and keeps the current cue`() {
        // 旧 spawned（60s/61s）显示位置在倒退后全部失效 → 清空；
        // 当前 cue 的 spawn 位置是对的 → 保留
        val current = listOf(dan(60_000), dan(61_000))
        val next = nextSpawnedOnCue(current, dan(10_000), backwardSeek = true)
        assertEquals(listOf(10_000L), next.map { it.timeMs })
    }

    @Test
    fun `out of order spawn is re-sorted to ascending`() {
        // 不变量兜底：乱序（如时钟回拨/边界抖动）也不破坏升序契约
        val current = listOf(dan(1_000), dan(5_000))
        val next = nextSpawnedOnCue(current, dan(3_000), backwardSeek = false)
        assertEquals(listOf(1_000L, 3_000L, 5_000L), next.map { it.timeMs })
    }

    // ---- 倒退判定 ----

    @Test
    fun `backward seek needs to exceed threshold`() {
        assertTrue(isBackwardSeek(newPositionMs = 10_000, lastPositionMs = 12_000))
        assertFalse(isBackwardSeek(newPositionMs = 11_500, lastPositionMs = 12_000))
        assertFalse(isBackwardSeek(newPositionMs = 12_000, lastPositionMs = 12_000))
        assertFalse(isBackwardSeek(newPositionMs = 13_000, lastPositionMs = 12_000))
        // 首帧（last=-1）不算倒退——调用方以 lastPositionMs >= 0 门控
        assertFalse(isBackwardSeek(newPositionMs = 0, lastPositionMs = -1))
    }
}
