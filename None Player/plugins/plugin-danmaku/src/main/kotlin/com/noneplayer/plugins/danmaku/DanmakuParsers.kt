package com.noneplayer.plugins.danmaku

import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 三格式弹幕解析（批次 6 任务①）。全部是**纯文本 → List<[Danmaku]>**，
 * 无 Android 依赖（XmlPullParser 在设备上用系统实现，单测用 kxml2），
 * 结果按时间升序、丢弃坏行——解析器永不抛异常，坏文件得到空列表。
 *
 * - **B 站 XML**：`<d p="时间秒,模式,字号,颜色(十进制RGB),池,UID,行hash,dbid">文本</d>`，
 *   模式 1–3 滚动 / 4 底部 / 5 顶部，6–9 丢弃（见 [DanmakuMode] 注）；
 * - **转换器 JSON**：各弹幕转换工具导出的数组格式，字段名不统一，这里做
 *   **宽容解析**（时间 `time`/`t`/`progress`，模式 `mode`/`type`/`m`，
 *   颜色 `color`/`c`（`#RRGGBB` 或十进制），文本 `text`/`content`/`d`/`message`）；
 *   时间**一律按秒口径**（含小数点与否均可）——毫秒/秒无法从单个数值可靠区分，
 *   主流转换器导出的都是秒；极端超前的毫秒口径条目会因时间越界不显示，不出错。
 * - **ASS**（danmaku2ass 一系）：取 `[Events]` 的 `Dialogue` 行，
 *   样式/位移标签判模式（`\move` → 滚动，`\pos` 按 y 与 `PlayResY` 中线比 → 顶/底），
 *   `\c&HBBGGRR&` 取颜色（注意 ASS 是 BGR 序），其余覆盖标签剥掉。
 */
object DanmakuParsers {

    /** B 站标准字号；来源字号全部归一到它的比例 */
    private const val BASE_FONT_SIZE = 25f

    fun parse(content: String, format: DanmakuFormat): List<Danmaku> = when (format) {
        DanmakuFormat.BilibiliXml -> parseBilibiliXml(content)
        DanmakuFormat.ConverterJson -> parseConverterJson(content)
        DanmakuFormat.Ass -> parseAss(content)
    }.sortedBy { it.timeMs }

    // ---- B 站 XML ----

    fun parseBilibiliXml(content: String): List<Danmaku> = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(content))
        val result = mutableListOf<Danmaku>()
        var pAttr: String? = null
        var text = StringBuilder()
        var inD = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name.equals("d", ignoreCase = true)) {
                    inD = true
                    pAttr = parser.getAttributeValue(null, "p")
                    text = StringBuilder()
                }
                XmlPullParser.TEXT -> if (inD) text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name.equals("d", ignoreCase = true) && inD) {
                    inD = false
                    parseBilibiliP(pAttr, text.toString())?.let(result::add)
                }
            }
            parser.next()
        }
        result
    }.getOrDefault(emptyList())

    /** 解析一条 `<d p>`；p 缺字段/模式不支持返回 null */
    private fun parseBilibiliP(p: String?, text: String): Danmaku? {
        if (p.isNullOrBlank()) return null
        val fields = p.split(',')
        if (fields.size < 4) return null
        val timeSec = fields[0].toFloatOrNull() ?: return null
        val mode = when (fields[1].trim().toIntOrNull()) {
            1, 2, 3 -> DanmakuMode.Scroll
            4 -> DanmakuMode.Bottom
            5 -> DanmakuMode.Top
            else -> return null
        }
        val size = fields[2].toFloatOrNull()?.takeIf { it > 0 } ?: BASE_FONT_SIZE
        // 颜色是十进制 RGB（如 16777215 = 白）；异常值回白而不是丢弹幕
        val color = fields[3].trim().toLongOrNull()?.toInt() ?: 0xFFFFFF
        val clean = text.unescape().trim()
        if (clean.isEmpty()) return null
        return Danmaku(
            timeMs = (timeSec * 1000).roundToLong(),
            mode = mode,
            fontScale = size / BASE_FONT_SIZE,
            colorArgb = 0xFF000000.toInt() or (color and 0xFFFFFF),
            text = clean,
        )
    }

    // ---- 转换器 JSON ----

    fun parseConverterJson(content: String): List<Danmaku> = runCatching {
        val array = when {
            content.trimStart().startsWith("[") -> JSONArray(content)
            else -> JSONObject(content).getJSONArray("danmaku")
        }
        buildList {
            for (i in 0 until array.length()) {
                parseConverterEntry(array.optJSONObject(i) ?: continue)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun parseConverterEntry(o: JSONObject): Danmaku? {
        val text = o.pickText("text", "content", "d", "message")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val timeMs = o.pickNumber("time", "t", "progress")?.let { toMillis(it) } ?: return null
        val mode = parseMode(
            o.pickText("mode", "type") ?: o.opt("m")?.toString() ?: "scroll",
        ) ?: return null
        val colorRaw = o.pickText("color", "c") ?: "FFFFFF"
        val color = parseColor(colorRaw) ?: 0xFFFFFF
        return Danmaku(
            timeMs = timeMs,
            mode = mode,
            fontScale = 1f,
            colorArgb = 0xFF000000.toInt() or (color and 0xFFFFFF),
            text = text,
        )
    }

    /** `scroll/ltr/rtl/1/2/3` → 滚动，`top/5` → 顶，`bottom/4` → 底；其余 null（丢弃） */
    private fun parseMode(raw: String): DanmakuMode? = when (raw.trim().lowercase()) {
        "scroll", "ltr", "rtl", "1", "2", "3" -> DanmakuMode.Scroll
        "top", "5" -> DanmakuMode.Top
        "bottom", "4" -> DanmakuMode.Bottom
        else -> null
    }

    /** `#RGB` / `#RRGGBB` / 十进制串 / 裸数字 → RGB int */
    private fun parseColor(raw: String): Int? {
        val v = raw.removePrefix("#").trim()
        return when {
            v.matches(Regex("[0-9a-fA-F]{6}")) -> v.toInt(16)
            v.matches(Regex("[0-9a-fA-F]{3}")) -> v.map { c -> "$c$c" }.joinToString("").toInt(16)
            else -> v.toLongOrNull()?.toInt()
        }
    }

    /** 时间统一按秒口径（见类注）；换算毫秒 */
    private fun toMillis(raw: Double): Long = (raw * 1000).roundToLong()

    private fun JSONObject.pickText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { optString(it).takeUnless(String::isEmpty) }

    private fun JSONObject.pickNumber(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { if (has(it)) optDouble(it).takeUnless(Double::isNaN) else null }

    // ---- ASS ----

    fun parseAss(content: String): List<Danmaku> {
        var playResY = 0f
        var formatFields: List<String> = emptyList()
        val result = mutableListOf<Danmaku>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("PlayResY:", ignoreCase = true) ->
                    playResY = line.substringAfter(':').trim().toFloatOrNull() ?: 0f
                line.startsWith("Format:", ignoreCase = true) &&
                    line.contains("Start", ignoreCase = true) &&
                    line.contains("Text", ignoreCase = true) ->
                    formatFields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", ignoreCase = true) ->
                    parseAssDialogue(line.substringAfter(':'), formatFields, playResY)?.let(result::add)
            }
        }
        return result
    }

    /**
     * 一条 `Dialogue:` 行。`Format:` 行给出的字段里 Text 恒为最后一个且本身可含逗号，
     * 所以按字段总数做**限量切分**（limit = 字段数），最后一段自然就是完整 Text；
     * 没有 Format 行或字段对不上则整行丢弃。样式/标签都判不出模式时按滚动处理
     * （danmaku2ass 的主流形态）。
     */
    private fun parseAssDialogue(body: String, fields: List<String>, playResY: Float): Danmaku? {
        if (fields.size < 2) return null
        val parts = body.split(',', limit = fields.size)
        if (parts.size != fields.size) return null
        val startSec = assTimestamp(parts[fields.indexOf("start")]) ?: return null
        val style = parts.getOrNull(fields.indexOf("style"))?.trim().orEmpty()
        val textRaw = parts.last()
        val overrides = Regex("\\{[^}]*}").findAll(textRaw).joinToString("") { it.value }
        val text = textRaw.replace(Regex("\\{[^}]*}"), "")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .trim()
        if (text.isEmpty()) return null

        val colorBgr = Regex("\\\\1?c&H([0-9a-fA-F]{2,8})&").find(overrides)?.groupValues?.get(1)
        val color = colorBgr?.takeIf { it.length >= 6 }
            ?.let { bgrToRgb(Integer.parseInt(it.takeLast(6), 16)) }
            ?: 0xFFFFFF
        val fontSize = Regex("\\\\fs([0-9.]+)").find(overrides)?.groupValues?.get(1)?.toFloatOrNull()

        return Danmaku(
            timeMs = (startSec * 1000).roundToLong(),
            mode = assMode(overrides, style, playResY),
            fontScale = fontSize?.takeIf { it > 0 }?.div(BASE_FONT_SIZE) ?: 1f,
            colorArgb = 0xFF000000.toInt() or (color and 0xFFFFFF),
            text = text,
        )
    }

    /** ASS 覆盖标签的颜色是 `&H BB GG RR &`（BGR 序），翻成 RGB */
    private fun bgrToRgb(v: Int): Int =
        ((v and 0xFF) shl 16) or (v and 0x00FF00) or ((v shr 16) and 0xFF)

    private fun assMode(overrides: String, style: String, playResY: Float): DanmakuMode {
        // danmaku2ass 的样式名是权威信号；没有样式名再看 \pos 的纵向位置
        val s = style.uppercase()
        when {
            s.contains("R2L") || s.contains("L2R") -> return DanmakuMode.Scroll
            s.contains("TOP") -> return DanmakuMode.Top
            s.contains("POP") || s.contains("BTM") || s.contains("BOT") -> return DanmakuMode.Bottom
        }
        val pos = Regex("\\\\pos\\(([0-9.-]+),([0-9.-]+)").find(overrides)
        if (pos != null && playResY > 0) {
            return if (pos.groupValues[2].toFloat() < playResY / 2f) DanmakuMode.Top else DanmakuMode.Bottom
        }
        return DanmakuMode.Scroll
    }

    /** `0:01:02.34` → 秒；非法返回 null */
    private fun assTimestamp(raw: String): Float? {
        val m = Regex("(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(raw.trim()) ?: return null
        return m.groupValues[1].toFloat() * 3600f +
            m.groupValues[2].toFloat() * 60f +
            m.groupValues[3].toFloat()
    }

    /** 最小 XML 实体还原（B 站 XML 只会用这几个；数字实体一并处理） */
    private fun String.unescape(): String {
        if (!contains('&')) return this
        var s = replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        // 数字实体（如 &#x1F600; / &#128512;）——放在 &amp; 之后处理避免二次还原
        Regex("&#(\\d+);").findAll(s).forEach { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.let { s = s.replace(m.value, it.toString()) }
        }
        return s
    }
}
