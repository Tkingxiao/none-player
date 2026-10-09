package com.noneplayer.plugins.enricher.filename

/**
 * 文件名解析（§12.4 / E42）：`[字幕组] 标题 - 01 [1080p][HEVC]`
 * → 短标题「标题」、集号 1、编码 HEVC。
 *
 * 纯函数、无 Android 依赖（可单测）。**只在真的解析出东西时才产出结果**——
 * 解析不出就返回 null，调用方原样放行（不猜、不改写普通文件名）。
 *
 * 覆盖形态：
 * - `[字幕组] 标题 - 01 [1080p][HEVC].mkv`（方括号标签 + 破折号编号）
 * - `[Airota][Title][02][1080p]`（标题/编号都在方括号里）
 * - `Show.S01E02.1080p.WEB-DL.mkv`（SxxExx）
 * - `剧集 第三集.mp4` / `第10话`（中文数字与「第N集/话/回」）
 * - `Title EP05.mkv` / `Title 05.mp4`（EP 标记 / 末尾独立编号）
 *
 * 实现要点：**编号匹配在"拍平串"上做**（方括号/圆括号/分隔符都换成空格、内容保留），
 * 于是 `[02]` 里的编号也能被编号正则看见；标题从"扣掉开头组名与命中编号"的净文本
 * 里生成，并滤掉分辨率/片源/编码一类标签词。
 */
internal object FilenameParser {

    data class Parsed(
        val title: String,
        val season: Int,
        val episode: Int,
        val codec: String?,
    )

    private val BRACKET_GROUP = Regex("\\[[^\\]]*]")
    private val ANY_BRACKET_OR_PAREN = Regex("[\\[\\]()]")
    private val SXXEXX = Regex("(?i)s(\\d{1,2})[\\s._-]*e(\\d{1,3})")
    private val SEASON_CJK = Regex("第\\s*([0-9]+|[〇零一二三四五六七八九十百千两]+)\\s*季")
    private val EPISODE_CJK = Regex("第\\s*([0-9]+|[〇零一二三四五六七八九十百千两]+)\\s*[集话回]")
    private val EP_MARK = Regex("(?i)(?:^|[\\s._-])(?:ep|e)\\.?[\\s._-]?(\\d{1,3})(?=$|[\\s._-])")
    private val TAIL_NUMBER = Regex("(?:^|[\\s._-]+)(\\d{1,3})(?=$|[\\s._-])")
    private val CODEC = Regex(
        "(?i)\\b(hevc|h\\.?265|x265|avc|h\\.?264|x264|av1|vp9|vc-?1|divx|xvid|mpeg-?2|wmv3)\\b",
    )
    private val SEPARATORS = Regex("[._]+")

    /** 折叠多余空白：每个条目的富化都要走两次，提到常量（E121） */
    private val COLLAPSE_WS = Regex("\\s{2,}")

    /** 标题里要丢掉的"标签词"（分辨率/片源/编码/位深/语言标记/版本号） */
    private val TITLE_NOISE = Regex(
        "(?i)^(\\d{3,4}[pi]|4k|8k|hd|fhd|uhd|web-?dl|webrip|web|blu-?ray|bd(rip)?|hdtv|dvdrip|" +
            "remux|x26[45]|h\\.?26[45]|hevc|avc|av1|vp9|aac|ac3|eac3|dts(-hd)?|flac|mp3|" +
            "10bit|8bit|hdr|sdr|proper|repack|v\\d+|cn|chs|cht|双语|简繁|中字)$",
    )

    fun parse(rawName: String): Parsed? {
        val base = rawName.substringBeforeLast('.', rawName).trim()
        if (base.isEmpty()) return null

        // 拍平串：括号/分隔符 → 空格（组内文本保留，`[02]` 的编号才看得见）
        val flat = base.replace(ANY_BRACKET_OR_PAREN, " ")
            .replace(SEPARATORS, " ")
            .replace(COLLAPSE_WS, " ")
            .trim()

        // 编码先从原始串取（`[HEVC]` 就在方括号里）
        val codec = CODEC.find(base)?.value?.let(::canonicalCodec)

        var season = 0
        var episode = 0
        var matchedText: String? = null
        SXXEXX.find(flat)?.let {
            season = it.groupValues[1].toIntOrNull() ?: 0
            episode = it.groupValues[2].toIntOrNull() ?: 0
            matchedText = it.value
        }
        if (season == 0) {
            SEASON_CJK.find(flat)?.let {
                season = parseNumber(it.groupValues[1]) ?: 0
                matchedText = it.value
            }
        }
        if (episode == 0) {
            EPISODE_CJK.find(flat)?.let {
                episode = parseNumber(it.groupValues[1]) ?: 0
                matchedText = it.value
            }
        }
        if (episode == 0) {
            EP_MARK.find(flat)?.let {
                episode = it.groupValues[1].toIntOrNull() ?: 0
                matchedText = it.value
            }
        }
        if (episode == 0 && season == 0) {
            // 末尾独立编号（"标题 - 01" / "标题 05"）：1–3 位，避开 1080/2024 这类四位数字。
            // 必须取**最后一个**命中——"Rock 2 04" 里 2 与 04 都是合法的独立数字段，
            // 只有末尾那个才是集号（取第一个会把年份/序号中段当成集号）。
            TAIL_NUMBER.findAll(flat).lastOrNull()?.let {
                episode = it.groupValues[1].toIntOrNull() ?: 0
                matchedText = it.value
            }
        }

        if (episode == 0 && season == 0 && codec == null) return null

        // 标题主路径：去掉开头的方括号组（字幕组命名的惯例位置）与其余方括号组，
        // 再扣掉命中的编号，最后滤标签词
        var source = dropLeadingBracketGroup(base).replace(BRACKET_GROUP, " ")
        val hit = matchedText?.trim()?.trim('-', '—', '·', '_', '.')
        if (!hit.isNullOrBlank()) source = source.replaceFirst(hit, " ")
        var title = buildTitle(source)

        // 回退：文件名几乎全是方括号组 → 用组内文本（丢掉第一个组、命中的编号与标签词）
        if (title.isBlank()) {
            val groups = BRACKET_GROUP.findAll(base).map { it.value.trim('[', ']') }.toList()
            title = buildTitle(
                groups.drop(1).filterNot { it == hit }.joinToString(" "),
            )
        }
        return Parsed(
            // 什么都拼不出来（整个文件名就是 `[01]`）时保留原名，别造空标题
            title = title.ifBlank { base },
            season = season,
            episode = episode,
            codec = codec,
        )
    }

    /** 名字以 `[` 开头时丢掉第一对方括号（惯例上那里是字幕组名，不是标题） */
    private fun dropLeadingBracketGroup(s: String): String =
        if (s.startsWith('[')) s.replaceFirst(BRACKET_GROUP, " ") else s

    /** 归一空白 + 去掉标签词 + 修剪残留分隔符 */
    private fun buildTitle(raw: String): String = raw
        .replace(ANY_BRACKET_OR_PAREN, " ")
        .replace(SEPARATORS, " ")
        .replace(COLLAPSE_WS, " ")
        .trim()
        .split(' ')
        .filter { it.isNotBlank() && !TITLE_NOISE.matches(it) }
        .joinToString(" ")
        .trim('-', '—', '·', '_', ' ')

    /** 数字段：纯数字直接取值，中文数字走 [com.noneplayer.core.media.NaturalOrder] 的简版解析 */
    private fun parseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val chinese = com.noneplayer.core.media.NaturalOrder.parseChineseNumber(raw) ?: return null
        return if (chinese in 0..999) chinese.toInt() else null
    }

    /** 编码归一并统一大写（h.265/x265 → HEVC；h.264/x264 → AVC） */
    private fun canonicalCodec(raw: String): String = when (raw.lowercase().replace(".", "")) {
        "h265", "x265" -> "HEVC"
        "h264", "x264" -> "AVC"
        "vc1" -> "VC-1"
        "mpeg2" -> "MPEG-2"
        else -> raw.uppercase()
    }
}
