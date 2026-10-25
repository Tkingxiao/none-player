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

    /** ASS 覆盖标签 `{\...}`（E98：闭合花括号必须转义，ICU 正则不容忍裸 `}`） */
    private val OVERRIDE_TAG = Regex("\\{[^}]*\\}")

    // 下面七条按**每条弹幕**执行（ASS 的覆盖标签解析在 cue 循环里、时间戳/颜色每条一次），
    // 原先写成内联 `Regex(...)` 等于每 cue 重编译一次 Pattern——十万条的文件就是几十万次
    // 编译。提到 object 常量，与 OVERRIDE_TAG 同构（E121）。
    private val HEX_COLOR_6 = Regex("[0-9a-fA-F]{6}")
    private val HEX_COLOR_3 = Regex("[0-9a-fA-F]{3}")
    private val ASS_COLOR_TAG = Regex("\\\\1?c&H([0-9a-fA-F]{2,8})&")
    private val ASS_FONT_SIZE_TAG = Regex("\\\\fs([0-9.]+)")
    private val ASS_POS_TAG = Regex("\\\\pos\\(([0-9.-]+),([0-9.-]+)")
    private val ASS_MOVE_TAG = Regex("\\\\move\\(([0-9.-]+),([0-9.-]+)")
    private val ASS_TIMESTAMP = Regex("(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
    private val HTML_NUMERIC_ENTITY = Regex("&#(\\d+);")

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
            v.matches(HEX_COLOR_6) -> v.toInt(16)
            v.matches(HEX_COLOR_3) -> v.map { c -> "$c$c" }.joinToString("").toInt(16)
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

    fun parseAss(content: String): List<Danmaku> = parseAssDetailed(content).items

    /**
     * E221：带统计的 ASS 解析。返回条目 + 丢弃统计（「空结果给原因」的读数）。
     *
     * 加固三件（任务 §5）：
     * - **Format/Dialogue 只认 `[Events]` 节**——按节头行切换当前节；此前任何
     *   位置的 `Format:`/`Dialogue:` 都会被认（样式节里恰好带 Start/Text 字样
     *   的 Format 行会污染字段序）；
     * - **`Comment:` 事件行显式跳过并计数**（此前静默落空，统计里看不见）；
     * - **`\move(x1,y1,…)` 的起始 y 参与 顶/底 判定**（与 `\pos` 同口径；
     *   此前 \move 行一律兜底滚动，顶部飘过的移动弹幕挂错车道）。
     * 「段数不一致丢整行」既有行为由用例钉住（[parseAssDialogue]）。
     */
    fun parseAssDetailed(content: String): AssParseResult {
        var playResY = 0f
        var formatFields: List<String> = emptyList()
        var currentSection = ""
        val result = mutableListOf<Danmaku>()
        var dialogues = 0
        var comments = 0
        var droppedTimestamp = 0
        var droppedFieldMismatch = 0
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.lowercase()
                return@forEach
            }
            when {
                line.startsWith("PlayResY:", ignoreCase = true) ->
                    playResY = line.substringAfter(':').trim().toFloatOrNull() ?: 0f
                currentSection == "[events]" && line.startsWith("Format:", ignoreCase = true) &&
                    line.contains("Start", ignoreCase = true) &&
                    line.contains("Text", ignoreCase = true) ->
                    formatFields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                currentSection == "[events]" && line.startsWith("Comment:", ignoreCase = true) ->
                    comments++
                currentSection == "[events]" && line.startsWith("Dialogue:", ignoreCase = true) -> {
                    dialogues++
                    when (val outcome = parseAssDialogue(line.substringAfter(':'), formatFields, playResY)) {
                        is AssDialogueOutcome.Kept -> result += outcome.danmaku
                        AssDialogueOutcome.DroppedTimestamp -> droppedTimestamp++
                        AssDialogueOutcome.DroppedFieldMismatch -> droppedFieldMismatch++
                        AssDialogueOutcome.DroppedEmptyText -> Unit
                    }
                }
            }
        }
        val reason = if (result.isEmpty() && dialogues > 0) {
            buildList {
                if (droppedTimestamp > 0) add("$droppedTimestamp 条时间戳无效")
                if (droppedFieldMismatch > 0) add("$droppedFieldMismatch 条与 Format 段数不一致")
                if (comments > 0) add("另有 $comments 条注释行")
            }.joinToString(";").ifBlank { "原因未归类" }
        } else {
            null
        }
        return AssParseResult(
            items = result,
            dialogues = dialogues,
            comments = comments,
            droppedTimestamp = droppedTimestamp,
            droppedFieldMismatch = droppedFieldMismatch,
            emptyReason = reason,
        )
    }

    /** E221：ASS 解析统计（[parseAssDetailed] 的返回）。 */
    data class AssParseResult(
        val items: List<Danmaku>,
        /** [Events] 里的 Dialogue 行总数（含被丢的） */
        val dialogues: Int,
        /** Comment: 注释行数 */
        val comments: Int,
        /** 时间戳无效被丢的条数 */
        val droppedTimestamp: Int,
        /** 字段段数与 Format 不一致被丢的条数 */
        val droppedFieldMismatch: Int,
        /** items 为空且有过 Dialogue 时的原因描述；其余为 null */
        val emptyReason: String?,
    )

    /**
     * 一条 `Dialogue:` 行。`Format:` 行给出的字段里 Text 恒为最后一个且本身可含逗号，
     * 所以按字段总数做**限量切分**（limit = 字段数），最后一段自然就是完整 Text；
     * 没有 Format 行或字段对不上则整行丢弃。样式/标签都判不出模式时按滚动处理
     * （danmaku2ass 的主流形态）。
     */
    /** E221：单条 Dialogue 的解析结局（统计要分清「为什么被丢」） */
    private sealed interface AssDialogueOutcome {
        data class Kept(val danmaku: Danmaku) : AssDialogueOutcome
        object DroppedTimestamp : AssDialogueOutcome
        object DroppedFieldMismatch : AssDialogueOutcome
        object DroppedEmptyText : AssDialogueOutcome
    }

    private fun parseAssDialogue(body: String, fields: List<String>, playResY: Float): AssDialogueOutcome {
        if (fields.size < 2) return AssDialogueOutcome.DroppedFieldMismatch
        val parts = body.split(',', limit = fields.size)
        if (parts.size != fields.size) return AssDialogueOutcome.DroppedFieldMismatch
        val startSec = assTimestamp(parts[fields.indexOf("start")])
            ?: return AssDialogueOutcome.DroppedTimestamp
        val style = parts.getOrNull(fields.indexOf("style"))?.trim().orEmpty()
        val textRaw = parts.last()
        // E98：闭合 `}` 必须转义——JVM 正则容忍裸 `}`，Android 的 ICU 实现
        // 直接 PatternSyntaxException（`\{[^}]*}` 在真机上编译即崩，
        // 同名 .ass 弹幕一挂载就闪退）。`[^}]*` 里的 `}` 在字符类内不受影响。
        val overrides = OVERRIDE_TAG.findAll(textRaw).joinToString("") { it.value }
        val text = textRaw.replace(OVERRIDE_TAG, "")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .trim()
        if (text.isEmpty()) return AssDialogueOutcome.DroppedEmptyText

        val colorBgr = ASS_COLOR_TAG.find(overrides)?.groupValues?.get(1)
        val color = colorBgr?.takeIf { it.length >= 6 }
            ?.let { bgrToRgb(Integer.parseInt(it.takeLast(6), 16)) }
            ?: 0xFFFFFF
        val fontSize = ASS_FONT_SIZE_TAG.find(overrides)?.groupValues?.get(1)?.toFloatOrNull()

        return AssDialogueOutcome.Kept(
            Danmaku(
                timeMs = (startSec * 1000).roundToLong(),
                mode = assMode(overrides, style, playResY),
                fontScale = fontSize?.takeIf { it > 0 }?.div(BASE_FONT_SIZE) ?: 1f,
                colorArgb = 0xFF000000.toInt() or (color and 0xFFFFFF),
                text = text,
            ),
        )
    }

    /** ASS 覆盖标签的颜色是 `&H BB GG RR &`（BGR 序），翻成 RGB */
    private fun bgrToRgb(v: Int): Int =
        ((v and 0xFF) shl 16) or (v and 0x00FF00) or ((v shr 16) and 0xFF)

    private fun assMode(overrides: String, style: String, playResY: Float): DanmakuMode {
        // danmaku2ass 的样式名是权威信号；没有样式名再看 \pos / \move 的纵向位置
        val s = style.uppercase()
        when {
            s.contains("R2L") || s.contains("L2R") -> return DanmakuMode.Scroll
            s.contains("TOP") -> return DanmakuMode.Top
            s.contains("POP") || s.contains("BTM") || s.contains("BOT") -> return DanmakuMode.Bottom
        }
        // \pos 与 \move 同口径：拿纵坐标与 PlayResY 中线比（E221：\move 此前
        // 一律兜底滚动，顶部飘过的移动弹幕会被挂错车道）
        val y = ASS_POS_TAG.find(overrides)?.groupValues?.get(2)
            ?: ASS_MOVE_TAG.find(overrides)?.groupValues?.get(2)
        if (y != null && playResY > 0) {
            return if (y.toFloat() < playResY / 2f) DanmakuMode.Top else DanmakuMode.Bottom
        }
        return DanmakuMode.Scroll
    }

    /** `0:01:02.34` → 秒；非法返回 null */
    private fun assTimestamp(raw: String): Float? {
        val m = ASS_TIMESTAMP.find(raw.trim()) ?: return null
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
        HTML_NUMERIC_ENTITY.findAll(s).forEach { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.let { s = s.replace(m.value, it.toString()) }
        }
        return s
    }
}
