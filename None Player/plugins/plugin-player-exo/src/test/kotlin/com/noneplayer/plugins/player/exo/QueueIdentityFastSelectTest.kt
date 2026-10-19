package com.noneplayer.plugins.player.exo

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A15：切集快路径必须靠**队列身份**放行，不能只看"项数一样"。
 *
 * 真实故障：探测还在走（0.5–1.5s）时切到另一份**等长**文件夹的选集，
 * 旧判据 `mediaItemCount == items.size` 会把它当成同一条队列直接
 * `seekToDefaultPosition`，于是播出无关文件。
 */
class QueueIdentityFastSelectTest {

    private fun item(id: String, uri: String = "file:///folderA/$id.mkv") =
        MediaItem(id = id, sourceId = "local", title = id, uri = uri)

    private fun fast(
        applied: List<String>,
        requested: List<String>,
        engineItemCount: Int = requested.size,
        loadPending: Boolean = false,
        terminalFailure: Boolean = false,
    ) = canFastSelectQueueIndex(applied, requested, engineItemCount, loadPending, terminalFailure)

    @Test
    fun `fast path allowed for the same queue in the same order`() {
        val ids = listOf("a1", "a2", "a3")
        assertTrue(fast(ids, ids))
    }

    @Test
    fun `same item count from another folder must not fast seek`() {
        // 两份文件夹恰好等长：项数一样，但 id 序列对不上
        assertFalse(fast(listOf("a1", "a2", "a3"), listOf("b1", "b2", "b3")))
    }

    @Test
    fun `same ids in a different order must not fast seek`() {
        assertFalse(fast(listOf("a1", "a2", "a3"), listOf("a3", "a2", "a1")))
    }

    @Test
    fun `pending load must not fast seek`() {
        val ids = listOf("a1", "a2")
        assertFalse(fast(ids, ids, loadPending = true))
    }

    @Test
    fun `terminal failure must not fast seek`() {
        val ids = listOf("a1", "a2")
        assertFalse(fast(ids, ids, terminalFailure = true))
    }

    @Test
    fun `engine item count mismatch must not fast seek`() {
        val ids = listOf("a1", "a2", "a3")
        assertFalse(fast(ids, ids, engineItemCount = 2))
    }

    @Test
    fun `empty queue or missing applied identity must not fast seek`() {
        assertFalse(fast(emptyList(), emptyList()))
        assertFalse(fast(emptyList(), listOf("a1")))
    }

    @Test
    fun `queue identity uses ids and falls back to uri for blank ids`() {
        val items = listOf(
            item("a1"),
            MediaItem(id = "", sourceId = "local", title = "route", uri = "file:///deep/link.mkv"),
        )
        assertEquals(listOf("a1", "file:///deep/link.mkv"), queueIdentity(items))
    }
}
