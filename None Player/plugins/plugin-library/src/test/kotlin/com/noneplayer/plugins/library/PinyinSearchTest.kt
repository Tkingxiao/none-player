package com.noneplayer.plugins.library

import com.noneplayer.core.media.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 8：拼音搜索的**匹配逻辑**单测。转写（android.icu）在 JVM 单测环境不可用
 * （`Build.VERSION.SDK_INT == 0` → PinyinSearch 走 null 拼音退化路径），
 * 因此拼音命中路径用 [PinyinSearch.hitsQuery] 直接注入拼音串验证，
 * [matches] 只验证退化路径与匹配域收口。
 */
class PinyinSearchTest {

    private fun item(title: String, folder: String? = "Movies") = MediaItem(
        id = title,
        sourceId = "local",
        title = title,
        uri = "file:///$title",
        folder = folder,
    )

    @Test
    fun `全拼前缀与首字母都命中`() {
        assertTrue(PinyinSearch.hitsQuery("爱情", "ai qing", "ai"))
        assertTrue(PinyinSearch.hitsQuery("爱情", "ai qing", "qing"))
        assertTrue(PinyinSearch.hitsQuery("爱情", "ai qing", "aq"))
        assertFalse(PinyinSearch.hitsQuery("爱情", "ai qing", "abc"))
    }

    @Test
    fun `拼音为 null 时退回普通包含`() {
        assertTrue(PinyinSearch.hitsQuery("爱情", null, "爱"))
        assertTrue(PinyinSearch.hitsQuery("爱情", null, "情"))
        assertFalse(PinyinSearch.hitsQuery("爱情", null, "ai"))
    }

    @Test
    fun `matches 退化路径覆盖标题_短标题_文件夹域`() {
        assertTrue(PinyinSearch.matches(item("爱情神话.mp4"), "神话"))
        assertTrue(PinyinSearch.matches(item("movie.mp4", folder = "纪录片"), "纪录"))
        assertFalse(PinyinSearch.matches(item("爱情神话.mp4"), "xyz"))
    }

    /**
     * 审查批次 A1：`."` 曾因 `contains("")` 恒真而命中**全部**媒体。
     * 这两个断言就是那次缺陷的回归闸。
     */
    @Test
    fun `matchesAll 不会因单个点命中所有媒体`() {
        assertFalse(PinyinSearch.matchesAll(item("爱情神话.mp4"), "."))
        assertFalse(PinyinSearch.matchesAll(item("movie.mkv"), "."))
        assertFalse(PinyinSearch.matchesAll(item("没有任何扩展名"), "."))
    }

    @Test
    fun `matchesAll 扩展名匹配仍然有效`() {
        assertFalse(PinyinSearch.matchesAll(item("爱情神话.mp4"), ".mkv"))
        assertTrue(PinyinSearch.matchesAll(item("movie.mkv"), ".mkv"))
        assertTrue(PinyinSearch.matchesAll(item("movie.mkv"), "mkv"))
        assertTrue(PinyinSearch.matchesAll(item("MOVIE.MKV"), ".mkv"))
        // 标题本身含点但扩展名不匹配：不应命中
        assertFalse(PinyinSearch.matchesAll(item("a.b.mkv"), ".mp4"))
    }

    @Test
    fun `isSearchable 拒绝空白与纯点串`() {
        assertFalse(PinyinSearch.isSearchable(""))
        assertFalse(PinyinSearch.isSearchable("   "))
        assertFalse(PinyinSearch.isSearchable("."))
        assertFalse(PinyinSearch.isSearchable(".."))
        assertFalse(PinyinSearch.isSearchable(" . "))
        assertTrue(PinyinSearch.isSearchable("mkv"))
        assertTrue(PinyinSearch.isSearchable(".mkv"))
        assertTrue(PinyinSearch.isSearchable("爱"))
    }

    @Test
    fun `matchesAll 保留标题与文件夹域`() {
        assertTrue(PinyinSearch.matchesAll(item("电影.mkv"), "电影"))
        assertTrue(PinyinSearch.matchesAll(item("movie.mkv", folder = "纪录片"), "纪录"))
        assertFalse(PinyinSearch.matchesAll(item("movie.mkv"), "不存在的词"))
        // 空查询不参与匹配
        assertFalse(PinyinSearch.matchesAll(item("movie.mkv"), ""))
        assertFalse(PinyinSearch.matchesAll(item("movie.mkv"), "   "))
    }
}
