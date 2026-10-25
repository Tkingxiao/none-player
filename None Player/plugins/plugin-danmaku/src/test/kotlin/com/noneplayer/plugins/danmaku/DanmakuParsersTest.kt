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


/**
 * E221：ASS 解析加固——节限定、Comment 统计、\move 起始 y 判模式、
 * 段数不一致丢整行（既有行为钉住）、空结果给原因。
 */
class DanmakuAssHardeningTest {

    /** 最小 ASS 骨架：样式节里故意放一行带 Start/Text 字样的 Format（限节测试用） */
    private fun ass(body: String): String = """
        [Script Info]
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, Start, Text
        Style: 陷阱行,Arial,25,&HFFFFFF,,0,0

        [Events]
        Format: Layer, Start, End, Style, Text
        $body
    """.trimIndent()

    @Test
    fun `style 节的 format 行不污染 events 字段序`() {
        // 样式节里那行 Format 恰好带 Start/Text——若不限节会污染字段序，
        // Dialogue 按它切分就全错（本用例同时钉住：正确的 Events Format 仍生效）
        val result = DanmakuParsers.parseAssDetailed(
            ass("Dialogue: 0,0:00:01.00,0:00:05.00,R2L,滚动弹幕"),
        )
        assertEquals(1, result.items.size)
        assertEquals(DanmakuMode.Scroll, result.items.single().mode)
    }

    @Test
    fun `comment 行跳过并计数`() {
        val result = DanmakuParsers.parseAssDetailed(
            ass("Comment: 0,0:00:00.00,0:00:01.00,R2L,这是注释") + "\n" +
                "Dialogue: 0,0:00:02.00,0:00:05.00,R2L,真弹幕",
        )
        assertEquals(1, result.items.size)
        assertEquals(1, result.comments)
    }

    @Test
    fun `move 的起始 y 参与顶底判定`() {
        // 无样式名信号，\move 起始 y=100 < PlayResY/2(540) → 顶
        val top = DanmakuParsers.parseAssDetailed(
            ass("Dialogue: 0,0:00:01.00,0:00:05.00,,{\\move(200,100,400,100)}顶部移动"),
        )
        assertEquals(DanmakuMode.Top, top.items.single().mode)
        // y=800 > 540 → 底
        val bottom = DanmakuParsers.parseAssDetailed(
            ass("Dialogue: 0,0:00:01.00,0:00:05.00,,{\\move(200,800,400,800)}底部移动"),
        )
        assertEquals(DanmakuMode.Bottom, bottom.items.single().mode)
    }

    @Test
    fun `段数不一致丢整行`() {
        // Dialogue 字段数比 Format 少一段（少 Style）→ 整行丢
        val result = DanmakuParsers.parseAssDetailed(
            ass("Dialogue: 0,0:00:01.00,0:00:05.00,缺一段"),
        )
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.droppedFieldMismatch)
    }

    @Test
    fun `空结果给出原因`() {
        // 时间戳全坏 → 0 条；统计里给出原因
        val result = DanmakuParsers.parseAssDetailed(
            ass("Dialogue: 0,bad,0:00:05.00,R2L,坏时间戳") + "\n" +
                "Dialogue: 0,0:00:xx,0:00:05.00,R2L,坏时间戳二",
        )
        assertTrue(result.items.isEmpty())
        assertEquals(2, result.dialogues)
        assertEquals(2, result.droppedTimestamp)
        assertTrue(result.emptyReason!!.contains("时间戳无效"))
    }
}
