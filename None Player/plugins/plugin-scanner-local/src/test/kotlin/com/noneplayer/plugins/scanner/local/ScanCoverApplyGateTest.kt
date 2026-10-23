package com.noneplayer.plugins.scanner.local

import com.noneplayer.core.media.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E205：封面回写与扫描提交段的 lost-update 回归。
 *
 * 场景钉死：泵「读列表之后、写回之前」被扫描提交段插队（列表被替换成新扫描结果），
 * 旧的无锁直写会把旧列表整份覆盖回去——本轮的增删全部回退。代际门必须：
 * 拒掉基于旧列表的写回、对着新列表重试合并——封面落到新列表上、新条目不丢。
 */
class ScanCoverApplyGateTest {

    private fun item(id: String, cover: String? = null) = MediaItem(
        id = id,
        sourceId = "local",
        title = id,
        uri = "file:///tmp/$id.mp4",
        coverUri = cover,
    )

    @Test
    fun `提交插队时拒掉旧列表写回，对新列表重试合并`() = runBlocking {
        val gate = ScanCommitGate()
        val oldList = listOf(item("a"), item("b"))
        val newList = listOf(item("a"), item("fresh")) // 提交段落地：b 已删、fresh 新增
        var reads = 0
        val published = mutableListOf<List<MediaItem>>()

        val ok = applyCoversUnderCommitGate(
            gate,
            covers = mapOf("a" to "file:///covers/a.jpg"),
            currentItems = {
                reads++
                if (reads == 1) {
                    // 提交段插在这记读取之后：代际已变，但本次读到的仍是旧列表
                    gate.invalidate()
                    oldList
                } else {
                    // 重试读到的是提交后的新列表
                    newList
                }
            },
            publish = { published.add(it) },
        )

        assertTrue(ok)
        assertEquals("只允许一次成功发布", 1, published.size)
        val merged = published.single()
        // 合并基于新列表：fresh 还在（旧列表直写会把它覆盖掉）、b 不复活
        assertEquals(listOf("a", "fresh"), merged.map { it.id })
        // 封面确实落上了
        assertEquals("file:///covers/a.jpg", merged.first { it.id == "a" }.coverUri)
    }

    @Test
    fun `无变化不写回，正常合并一次写回`() = runBlocking {
        val gate = ScanCommitGate()
        var publishes = 0

        // 封面已存在 → merged == current → 不写
        val unchanged = listOf(item("a", cover = "file:///covers/a.jpg"))
        assertTrue(
            applyCoversUnderCommitGate(
                gate,
                mapOf("a" to "file:///covers/a.jpg"),
                { unchanged },
                { publishes++ },
            ),
        )
        assertEquals(0, publishes)

        // 正常路径：一次写回
        assertTrue(
            applyCoversUnderCommitGate(
                gate,
                mapOf("a" to "file:///covers/new.jpg"),
                { listOf(item("a")) },
                { publishes++ },
            ),
        )
        assertEquals(1, publishes)
    }
}
