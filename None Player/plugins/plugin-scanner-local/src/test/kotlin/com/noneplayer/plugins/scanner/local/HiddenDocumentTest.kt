package com.noneplayer.plugins.scanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L14：隐藏目录（`.` 前缀）拾取的纯函数部分。
 *
 * MediaStore 不收隐藏目录里的媒体，改用 SAF（`DocumentsContract`）遍历补齐，
 * 这里只测"docId → 文件夹 / 稳定 id"两段不依赖 Android 运行时的映射。
 */
class HiddenDocumentTest {

    @Test
    fun `docId 目录部分与 MediaStore 的 relative_path 同口径`() {
        // 隐藏目录里的文件：folder 带 `.nphidden`，文件夹树里就是 Movies > .nphidden
        assertEquals("Movies/.nphidden", folderOfDocument("primary:Movies/.nphidden/secret.mp4"))
        // 非隐藏子目录照常，根目录文件没有 folder
        assertEquals("Movies/Series", folderOfDocument("primary:Movies/Series/EP01.mp4"))
        assertNull(folderOfDocument("primary:secret.mp4"))
        // 可移动卷同样按卷内相对路径
        assertEquals("Music/.hidden", folderOfDocument("1234-5678:Music/.hidden/a.mp3"))
    }

    @Test
    fun `稳定 id 同输入同输出且带 saf 前缀`() {
        val key = hiddenDocumentId("primary:Movies/.nphidden/secret.mp4")
        assertTrue(key.startsWith("saf-"))
        // 断点/收藏/缩略图都认它，必须跨扫描可复现
        assertEquals(key, hiddenDocumentId("primary:Movies/.nphidden/secret.mp4"))
    }

    @Test
    fun `不同 docId 得到不同 id`() {
        assertNotEquals(
            hiddenDocumentId("primary:Movies/.nphidden/a.mp4"),
            hiddenDocumentId("primary:Movies/.nphidden/b.mp4"),
        )
    }

    @Test
    fun `显式选择的隐藏根判定 tree 路径含隐藏分段`() {
        assertFalse(treeRootEntersHiddenPath("primary:Movies"))
        assertTrue(treeRootEntersHiddenPath("primary:Movies/.nphidden"))
        assertTrue(treeRootEntersHiddenPath("primary:Movies/.nphidden/series"))
        assertTrue(treeRootEntersHiddenPath("primary:.secret"))
        assertFalse(treeRootEntersHiddenPath("1234-5678:Movies/Anime"))
        assertFalse(treeRootEntersHiddenPath("primary:"))
    }

    @Test
    fun `仅显式选中的隐藏分支才下钻隐藏子目录`() {
        val deny = setOf(".thumbnails", ".cache")
        // 非隐藏目录照常下钻
        assertTrue(shouldDescendDirectory("Series", false, deny))
        // 普通授权根下的隐藏目录：不进（数据保护 E198）
        assertFalse(shouldDescendDirectory(".nphidden", false, deny))
        // 显式选中的隐藏根下：可继续进
        assertTrue(shouldDescendDirectory(".nphidden", true, deny))
        // 黑名单优先，即便在显式隐藏根下也不进
        assertFalse(shouldDescendDirectory(".thumbnails", true, deny))
        assertFalse(shouldDescendDirectory(".cache", false, deny))
    }
}
