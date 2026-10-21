package com.noneplayer.plugins.library

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private fun item(folder: String?, id: String) = MediaItem(
        id = id,
        sourceId = "local",
        title = id,
        uri = "file:///$id",
        folder = folder,
    )

    @Test
    fun `自动补齐中间层并按名称排序`() {
        val root = buildFolderTree(
            listOf(
                item("Movies/B", "b1"),
                item("Movies/A", "a1"),
                item("Movies/A/Deep", "d1"),
                item(null, "none"),
                item("", "top"),
            ),
        )

        assertEquals("", root.path)
        assertEquals(listOf("top"), root.directVideos.map { it.id })
        assertEquals(listOf("Movies"), root.children.map { it.name })

        val movies = root.children.single()
        assertTrue(movies.directVideos.isEmpty())
        assertEquals(listOf("A", "B"), movies.children.map { it.name })
        assertEquals(listOf("a1"), movies.children.first().directVideos.map { it.id })
        assertEquals(listOf("Deep"), movies.children.first().children.map { it.name })

        // null 文件夹不进树；空路径归根。
        assertEquals(4, root.totalCount)
        assertEquals(4, root.videoCount)
    }

    /**
     * A24 大库基准：一万条、一万个叶子目录。旧实现对每个节点都 `nodes.values.filter`
     * 扫全表（O(节点²)，这里约一亿次比较）；新实现一次 groupBy + 自底向上递归。
     * 阈值放宽到 5s，只拦“复杂度退回平方”的回归，不当微基准。
     */
    @Test
    fun `一万条一万目录建树保持线性且计数完整`() {
        val items = buildList {
            repeat(10_000) { i ->
                add(item("Movies/G${i / 100}/F$i", "v$i"))
            }
        }

        val start = System.nanoTime()
        val root = buildFolderTree(items)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("FolderTree 10000 items / 10101 nodes: ${elapsedMs}ms")

        assertEquals(10_000, root.totalCount)
        assertEquals(10_000, root.videoCount)
        val movies = root.children.single()
        assertEquals(100, movies.children.size)
        assertEquals(100, movies.children.first().children.size)
        assertTrue("建树耗时 ${elapsedMs}ms 超出线性预期", elapsedMs < 5_000)
    }
}
