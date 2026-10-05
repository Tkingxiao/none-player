package com.noneplayer.plugins.danmaku

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuParsersTest {

    // ---- B 站 XML ----

    @Test
    fun `bilibili xml parses p fields`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <i>
              <chatserver>chat.bilibili.com</chatserver>
              <d p="12.345,1,25,16777215,0,0,abc,0">普通滚动</d>
              <d p="13.5,4,25,16711680,0,0,abc,0">底部弹幕</d>
              <d p="14.0,5,36,255,0,0,abc,0">顶部大字</d>
              <d p="15.0,7,25,16777215,0,0,abc,0">高级弹幕应丢弃</d>
              <d p="bad,1,25,16777215,0,0,abc,0">坏行应丢弃</d>
            </i>
        """.trimIndent()
        val list = DanmakuParsers.parseBilibiliXml(xml)
        assertEquals(3, list.size)
        assertEquals(12345L, list[0].timeMs)
        assertEquals(DanmakuMode.Scroll, list[0].mode)
        assertEquals(1f, list[0].fontScale)
        assertEquals(0xFFFFFFFFL, list[0].colorArgb.toLong() and 0xFFFFFFFFL)
        assertEquals(DanmakuMode.Bottom, list[1].mode)
        assertEquals(0xFFFF0000L, list[1].colorArgb.toLong() and 0xFFFFFFFFL)
        assertEquals(DanmakuMode.Top, list[2].mode)
        assertEquals(36f / 25f, list[2].fontScale)
        assertEquals("顶部大字", list[2].text)
    }

    @Test
    fun `bilibili xml unescapes entities and drops empty`() {
        val xml = """
            <i><d p="1.0,1,25,16777215,0,0,a,0">&lt;script&gt; &amp; &quot;q&quot;</d>
            <d p="2.0,1,25,16777215,0,0,a,0">   </d></i>
        """.trimIndent()
        val list = DanmakuParsers.parseBilibiliXml(xml)
        assertEquals(1, list.size)
        assertEquals("<script> & \"q\"", list[0].text)
    }

    @Test
    fun `garbage xml yields empty list not exception`() {
        assertTrue(DanmakuParsers.parseBilibiliXml("<i><d p=").isEmpty())
        assertTrue(DanmakuParsers.parseBilibiliXml("").isEmpty())
    }

    // ---- 转换器 JSON ----

    @Test
    fun `converter json array with tolerant keys`() {
        val json = """
            [
              {"time": 1.5, "mode": "scroll", "color": "#FF0000", "text": "滚动"},
              {"t": 2000, "type": "top", "c": "65280", "d": "毫秒口径+顶"},
              {"time": 3.0, "mode": "advanced", "text": "不支持的模式丢弃"},
              {"time": 4.0, "mode": "bottom", "text": "底部"}
            ]
        """.trimIndent()
        val list = DanmakuParsers.parseConverterJson(json)
        assertEquals(3, list.size)
        assertEquals(1500L, list[0].timeMs)
        assertEquals(DanmakuMode.Scroll, list[0].mode)
        assertEquals(0xFFFF0000L, list[0].colorArgb.toLong() and 0xFFFFFFFFL)
        // 时间一律按秒口径（类注）：t=2000 → 2_000_000ms
        assertEquals(2_000_000L, list[1].timeMs)
        assertEquals(DanmakuMode.Top, list[1].mode)
        assertEquals(0xFF00FF00L, list[1].colorArgb.toLong() and 0xFFFFFFFFL)
        assertEquals(DanmakuMode.Bottom, list[2].mode)
    }

    @Test
    fun `converter json wrapped object form`() {
        val json = """{"danmaku": [{"time": 0.5, "mode": 1, "color": "#FFF", "text": "缩写色"}]}"""
        val list = DanmakuParsers.parseConverterJson(json)
        assertEquals(1, list.size)
        assertEquals(500L, list[0].timeMs)
        assertEquals(0xFFFFFFFFL, list[0].colorArgb.toLong() and 0xFFFFFFFFL)
    }

    // ---- ASS ----

    @Test
    fun `ass danmaku parses dialogue with move and pos`() {
        val ass = """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour
            Format: R2L, syl, 48, &H00FFFFFF

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:06.00,R2L,,0,0,0,,{\move(1920,64,-320,64)}{\fs48}从右滚到左
            Dialogue: 0,0:00:07.00,0:00:10.00,TOP,,0,0,0,,{\pos(960,64)}{\c&HFF0000&}顶部蓝字
            Dialogue: 0,0:00:11.00,0:00:13.00,POP,,0,0,0,,{\pos(960,900)}底部
        """.trimIndent()
        val list = DanmakuParsers.parseAss(ass)
        assertEquals(3, list.size)
        assertEquals(1000L, list[0].timeMs)
        assertEquals(DanmakuMode.Scroll, list[0].mode)
        assertEquals(48f / 25f, list[0].fontScale)
        assertEquals("从右滚到左", list[0].text)
        // ASS 颜色 &HFF0000& 是 BGR → 蓝 (0x0000FF)
        assertEquals(DanmakuMode.Top, list[1].mode)
        assertEquals(0xFF0000FFL, list[1].colorArgb.toLong() and 0xFFFFFFFFL)
        assertEquals(DanmakuMode.Bottom, list[2].mode)
    }

    @Test
    fun `ass text containing commas stays intact`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,R2L,,0,0,0,,前半,后半
        """.trimIndent()
        val list = DanmakuParsers.parseAss(ass)
        assertEquals(1, list.size)
        assertEquals("前半,后半", list[0].text)
    }

    // ---- 统一入口 ----

    @Test
    fun `parse sorts by time across all formats`() {
        val list = DanmakuParsers.parse(
            """[{"time": 5.0, "mode": "scroll", "text": "晚"}, {"time": 1.0, "mode": "scroll", "text": "早"}]""",
            DanmakuFormat.ConverterJson,
        )
        assertEquals(2, list.size)
        assertTrue(abs(list[0].timeMs - 1000L) < 1)
        assertEquals("早", list[0].text)
    }
}

class DanmakuLocatorTest {

    @Test
    fun `sibling names follow extension priority`() {
        assertEquals(
            listOf("video.xml", "video.json", "video.ass"),
            DanmakuLocator.siblingNames("video.mp4"),
        )
        assertEquals(
            listOf("EP01.xml", "EP01.json", "EP01.ass"),
            DanmakuLocator.siblingNames("/movies/EP01.mkv"),
        )
    }

    @Test
    fun `no extension still yields candidates`() {
        assertEquals(
            listOf("video.xml", "video.json", "video.ass"),
            DanmakuLocator.siblingNames("video"),
        )
    }

    @Test
    fun `format from filename is case insensitive`() {
        assertEquals(DanmakuFormat.BilibiliXml, DanmakuFormat.fromFileName("A.XML"))
        assertEquals(DanmakuFormat.Ass, DanmakuFormat.fromFileName("b.Ass"))
        assertNull(DanmakuFormat.fromFileName("c.srt"))
    }
}
