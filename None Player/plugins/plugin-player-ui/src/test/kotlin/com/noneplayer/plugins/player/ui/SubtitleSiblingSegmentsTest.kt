package com.noneplayer.plugins.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E144：同名外挂字幕的 SAF 目录下钻判据。
 *
 * 真机缺陷：授权 `Movies` 根后，子目录里的视频（SAIO 两层嵌套的 MKV）永远匹配不到
 * 同名 `.ass`——旧实现要求视频相对路径**等于**授权树相对路径。这里把「等于或子孙」
 * 的放宽判据逐条钉住。
 */
class SubtitleSiblingSegmentsTest {

    @Test
    fun `文件直接位于授权树根时无需下钻`() {
        assertEquals(emptyList<String>(), subtitleSiblingDirectorySegments("Movies", "Movies"))
        // MediaStore 的 relative_path 恒带尾斜杠
        assertEquals(emptyList<String>(), subtitleSiblingDirectorySegments("Movies", "Movies/"))
        assertEquals(emptyList<String>(), subtitleSiblingDirectorySegments("/Movies/", "/Movies/"))
    }

    @Test
    fun `子目录视频返回剩余目录段`() {
        val video =
            "Movies/[SAIO-Raws] Monogatari Series [BD 1920x1080 HEVC-10bit OPUSx2]/" +
                "04.物语系列第二季合集/04.猫物语(白)/"
        assertEquals(
            listOf(
                "[SAIO-Raws] Monogatari Series [BD 1920x1080 HEVC-10bit OPUSx2]",
                "04.物语系列第二季合集",
                "04.猫物语(白)",
            ),
            subtitleSiblingDirectorySegments("Movies", video),
        )
        assertEquals(
            listOf("剧集", "第一季"),
            subtitleSiblingDirectorySegments("Movies", "Movies/剧集/第一季/"),
        )
    }

    @Test
    fun `授权存储根时整条相对路径都是下钻段`() {
        assertEquals(
            listOf("Movies", "a"),
            subtitleSiblingDirectorySegments("", "Movies/a/"),
        )
        // 空根 + 空路径：存储根本身，零段
        assertEquals(emptyList<String>(), subtitleSiblingDirectorySegments("", ""))
    }

    @Test
    fun `无关路径不归这棵树管`() {
        assertNull(subtitleSiblingDirectorySegments("Movies", "Music/a/"))
        // 前缀相近但不是子目录（MoviesX 不是 Movies 的子孙）
        assertNull(subtitleSiblingDirectorySegments("Movies", "MoviesX/a/"))
        assertNull(subtitleSiblingDirectorySegments("Movies/[A]", "Movies/[B]/"))
        assertNull(subtitleSiblingDirectorySegments("Movies", "/"))
    }

    @Test
    fun `多余斜杠与空段被归一化`() {
        assertEquals(
            listOf("a", "b"),
            subtitleSiblingDirectorySegments("Movies/", "/Movies//a/b/"),
        )
    }
}
