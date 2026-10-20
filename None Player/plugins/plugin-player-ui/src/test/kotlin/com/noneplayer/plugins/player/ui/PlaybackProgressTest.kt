package com.noneplayer.plugins.player.ui

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REV-04「进度落盘身份核对」的纯函数单测。
 *
 * 覆盖的是一类**静默写坏历史**的 bug：会话下标是引擎异步回写的，切集窗口里
 * "当前项"与"引擎里正在放的那条"会短暂错位，旧实现直接拿两者拼一条进度落盘，
 * 于是 A 的进度进了 B 的历史键（点开 B 直接跳到 A 看到的位置）。这里把判定
 * 逻辑钉在纯函数上：身份由 `sourceId + mediaId + uri + clipStartUs + clipEndUs`
 * 五段共同决定，位置/时长优先取引擎"已生效媒体"的值，legacy 引擎退回旧行为。
 *
 * 不依赖 Android/Robolectric：本文件只跑 JVM 纯逻辑。
 */
class PlaybackProgressTest {

    private fun item(
        id: String = "ms-1",
        sourceId: String = "local",
        uri: String = "file:///m/a.mkv",
        clipStartUs: Long = 0L,
        clipEndUs: Long = 0L,
    ): MediaItem = MediaItem(
        id = id,
        sourceId = sourceId,
        title = "测试媒体",
        uri = uri,
        clipStartUs = clipStartUs,
        clipEndUs = clipEndUs,
    )

    /** CUE 分轨：同一音频文件、不同裁剪窗口（扫描器就是 file.copy(id = "…#tN")） */
    private fun cueTrack(track: Int, startUs: Long, endUs: Long): MediaItem = item(
        id = "ms-1#t$track",
        uri = "file:///m/album.flac",
        clipStartUs = startUs,
        clipEndUs = endUs,
    )

    @Test
    fun `历史键与 Room 的 media_key 同一约定`() {
        assertEquals("local:ms-1", keyOf(item()))
        assertEquals("local:ms-1", keyOf(sourceId = "local", mediaId = "ms-1"))
    }

    @Test
    fun `快照原样复制身份与位置信息`() {
        val snapshot = snapshotOf(item(), positionMs = 12_000L, durationMs = 600_000L, generation = 3L)

        assertEquals("local", snapshot.sourceId)
        assertEquals("ms-1", snapshot.mediaId)
        assertEquals("file:///m/a.mkv", snapshot.uri)
        assertEquals(12_000L, snapshot.positionMs)
        assertEquals(600_000L, snapshot.durationMs)
        assertEquals(3L, snapshot.generation)
        assertEquals("local:ms-1", snapshot.mediaKey)
    }

    @Test
    fun `同一媒体的两份快照是同一目标（与位置和代际无关）`() {
        val a = snapshotOf(item(), positionMs = 1_000L, durationMs = 600_000L, generation = 1L)
        val b = snapshotOf(item(), positionMs = 500_000L, durationMs = 600_000L, generation = 9L)

        assertTrue(isSameTarget(a, b))
    }

    @Test
    fun `不同条目不是同一目标`() {
        val a = snapshotOf(item(id = "ms-1"), positionMs = 0L, durationMs = 600_000L, generation = 0L)
        val b = snapshotOf(item(id = "ms-2"), positionMs = 0L, durationMs = 600_000L, generation = 0L)

        assertFalse(isSameTarget(a, b))
    }

    @Test
    fun `CUE 分轨共享同一 URI，但裁剪窗口不同就是不同目标`() {
        val track2 = snapshotOf(cueTrack(2, 60_000_000L, 120_000_000L), 0L, 60_000L, 0L)
        val track5 = snapshotOf(cueTrack(5, 240_000_000L, 300_000_000L), 0L, 60_000L, 0L)

        // 只比 uri 的实现会在这里把两轨串成一条：专辑第 2 轨的进度被写进第 5 轨
        assertFalse(isSameTarget(track2, track5))
        assertFalse(isSameTarget(track2, cueTrack(2, 60_000_000L, 120_000_000L + 1L)))
    }

    @Test
    fun `URI 变了（重扫换路径）就不是同一目标`() {
        val before = snapshotOf(item(uri = "file:///m/a.mkv"), 0L, 600_000L, 0L)
        val after = snapshotOf(item(uri = "file:///m/a (1).mkv"), 0L, 600_000L, 0L)

        assertFalse(isSameTarget(before, after))
    }

    @Test
    fun `canPersist 只在身份完全一致时放行`() {
        val target = item()
        val snapshot = snapshotOf(target, positionMs = 42_000L, durationMs = 600_000L, generation = 2L)

        assertTrue(canPersist(snapshot, target))
        // 快照属于另一条媒体：写进去就是"B 的键、A 的位置"
        assertFalse(canPersist(snapshot, item(id = "ms-2")))
        assertFalse(canPersist(snapshot, item(uri = "file:///m/other.mkv")))
    }

    @Test
    fun `canPersist 拒绝空快照与无 id 条目`() {
        assertFalse(canPersist(null, item()))
        assertFalse(canPersist(snapshotOf(item(), 0L, 0L, 0L), null))
        // 无 id 的条目没有历史键，写进去只会生成孤儿行
        assertFalse(canPersist(snapshotOf(item(id = ""), 5_000L, 600_000L, 0L), item(id = "")))
    }

    @Test
    fun `引擎身份匹配时优先采信已生效媒体的位置与时长`() {
        val snapshot = resolvePersistSnapshot(
            target = item(),
            appliedMediaKey = "local:ms-1",
            appliedPositionMs = 42_000L,
            appliedDurationMs = 600_000L,
            // 控制器值还在旧媒体上（切集窗口内会这样），不能采信
            controllerPositionMs = 1_000L,
            controllerDurationMs = 0L,
            generation = 7L,
        ) ?: error("身份匹配时必须给出可落盘快照")

        assertEquals("local:ms-1", snapshot.mediaKey)
        assertEquals(42_000L, snapshot.positionMs)
        assertEquals(600_000L, snapshot.durationMs)
        assertEquals(7L, snapshot.generation)
    }

    @Test
    fun `引擎未回报位置或时长时逐项退回控制器值`() {
        val snapshot = resolvePersistSnapshot(
            target = item(),
            appliedMediaKey = "local:ms-1",
            appliedPositionMs = null,
            appliedDurationMs = 0L,
            controllerPositionMs = 5_000L,
            controllerDurationMs = 600_000L,
            generation = 1L,
        ) ?: error("身份匹配时必须给出可落盘快照")

        assertEquals(5_000L, snapshot.positionMs)
        assertEquals(600_000L, snapshot.durationMs)
    }

    @Test
    fun `引擎已生效媒体与会话当前项不一致时拒绝落盘`() {
        // 典型窗口：用户点了下一集，会话下标已指向 ms-2，引擎还停在 ms-1
        assertNull(
            resolvePersistSnapshot(
                target = item(id = "ms-2"),
                appliedMediaKey = "local:ms-1",
                appliedPositionMs = 500_000L,
                appliedDurationMs = 600_000L,
                controllerPositionMs = 500_000L,
                controllerDurationMs = 600_000L,
                generation = 4L,
            ),
        )
    }

    @Test
    fun `CUE 分轨的引擎身份不符时同样拒绝落盘`() {
        assertNull(
            resolvePersistSnapshot(
                target = cueTrack(2, 60_000_000L, 120_000_000L),
                appliedMediaKey = "local:ms-1#t5",
                appliedPositionMs = 10_000L,
                appliedDurationMs = 60_000L,
                controllerPositionMs = 10_000L,
                controllerDurationMs = 60_000L,
                generation = 2L,
            ),
        )
    }

    @Test
    fun `legacy 引擎没有身份时退回改造前的落盘行为`() {
        val snapshot = resolvePersistSnapshot(
            target = item(),
            appliedMediaKey = null,
            appliedPositionMs = null,
            appliedDurationMs = 0L,
            controllerPositionMs = 8_000L,
            controllerDurationMs = 600_000L,
            generation = 0L,
        ) ?: error("legacy 引擎必须保持旧行为，不能丢断点")

        assertEquals(8_000L, snapshot.positionMs)
        assertEquals(600_000L, snapshot.durationMs)
        assertEquals("local:ms-1", snapshot.mediaKey)
    }

    @Test
    fun `引擎回报空身份等同于没有已生效媒体`() {
        // 部分引擎用空串表示"还没装载"，语义与 null 一致
        val snapshot = resolvePersistSnapshot(
            target = item(),
            appliedMediaKey = "",
            appliedPositionMs = null,
            appliedDurationMs = 0L,
            controllerPositionMs = 8_000L,
            controllerDurationMs = 600_000L,
            generation = 0L,
        ) ?: error("空身份应走 legacy 分支")

        assertEquals(8_000L, snapshot.positionMs)
    }
}
