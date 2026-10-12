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
        "(?i)(?<![a-z0-9])(hevc|h\\.?265|x265|avc|h\\.?264|x264|av1|vp9|vc-?1|divx|xvid|mpeg-?2|wmv3)(?![a-z0-9])",
    )
    private val SEPARATORS = Regex("[._]+")
    private val TECHNICAL_DETAIL = Regex("(?i)(\\d{3,4}x\\d{3,4}|[0-9a-f]{8}|[12567]\\.\\d)")
    /** 声道布局 `5.1` / `AAC5.1`：必须在把 `.` 拍成空格之前删掉，否则尾数会变成集号 1。 */
    private val CHANNEL_LAYOUT = Regex("(?i)(?<![0-9])[1-8]\\.\\d(?![0-9])")
    /** `OPUSx2` / `ASSx4` / `HEVC-10bit` 这种粘在一起的技术标签。 */
    private val COMPOUND_TECHNICAL = Regex(
        "(?i)^(hevc|avc|h\\.?26[45]|x26[45]|av1|vp9|opus|aac|flac|ac3|eac3|dts|ass|ssa|srt|pgs)" +
            "([._-]?\\d{0,2}bit|x\\d{1,2})?$",
    )
    private val MEDIA_EXTENSION = Regex("(?i)\\.(mkv|mp4|avi|mov|webm|m4v|ts|m2ts|mp3|flac|wav|m4a|ogg)$")
    /** 整段方括号是发布组（`[SAIO-Raws]` / `[字幕组]`），不是标题。 */
    private val RELEASE_GROUP = Regex(
        "(?i).*(字幕组|raws|subs|vcb[- ]studio|airota|subsplease|erai[- ]raws).*|^(?i)group$",
    )

    /** 折叠多余空白：每个条目的富化都要走两次，提到常量（E121） */
    private val COLLAPSE_WS = Regex("\\s{2,}")

    /** 标题里要丢掉的"标签词"（分辨率/片源/编码/位深/语言标记/版本号） */
    private val TITLE_NOISE = Regex(
        "(?i)^(\\d{3,4}[pi]|4k|8k|hd|fhd|uhd|web-?dl|webrip|web|blu-?ray|bd(rip)?|hdtv|dvdrip|" +
            "remux|x26[45]|h\\.?26[45]|hevc|avc|av1|vp9|aac|ac3|eac3|dts(-hd)?|flac|mp3|" +
            "(ma)?10p|hi10p|hi444p|10bit|8bit|hdr|sdr|proper|repack|v\\d+|cn|chs|cht|双语|简繁|中字|" +
            "简体|繁体|简中|繁中|gb|big5|jpsc|end|mp4|mkv|avi)$",
    )

    fun parse(rawName: String): Parsed? {
        val base = rawName.replace(MEDIA_EXTENSION, "").trim()
        if (base.isEmpty()) return null
        val prepared = CHANNEL_LAYOUT.replace(base, " ")

        // 拍平串：括号/分隔符 → 空格（组内文本保留，`[02]` 的编号才看得见）
        val bracketClean = BRACKET_GROUP.replace(prepared) { match ->
            val label = match.value.trim('[', ']')
            if (RELEASE_GROUP.matches(label) || isTechnicalTag(label)) " " else " $label "
        }
        val flat = CODEC.replace(bracketClean, " ")
            .replace(ANY_BRACKET_OR_PAREN, " ")
            .replace(SEPARATORS, " ")
            .split(' ')
            .filterNot { TITLE_NOISE.matches(it) }
            .joinToString(" ")
            .replace(COLLAPSE_WS, " ")
            .trim()

        // 编码先从原始串取（`[HEVC]` 就在方括号里）
        val codec = CODEC.find(prepared)?.value?.let(::canonicalCodec)

        var season = 0
        var episode = 0
        val matchedRanges = mutableListOf<IntRange>()
        SXXEXX.find(flat)?.let {
            season = it.groupValues[1].toIntOrNull() ?: 0
            episode = it.groupValues[2].toIntOrNull() ?: 0
            matchedRanges += it.range
        }
        if (season == 0) {
            SEASON_CJK.find(flat)?.let {
                season = parseNumber(it.groupValues[1]) ?: 0
                matchedRanges += it.range
            }
        }
        if (episode == 0) {
            EPISODE_CJK.find(flat)?.let {
                episode = parseNumber(it.groupValues[1]) ?: 0
                matchedRanges += it.range
            }
        }
        if (episode == 0) {
            EP_MARK.find(flat)?.let {
                episode = it.groupValues[1].toIntOrNull() ?: 0
                matchedRanges += it.range
            }
        }
        if (episode == 0) {
            // 末尾独立编号（"标题 - 01" / "标题 05" / "第二季 03"）：1–3 位，避开 1080/2024。
            // 必须取**最后一个**命中——"Rock 2 04" 里只有末尾那个才是集号。
            // 季号已经命中时仍要扫：`终物语 第二季 03` 不能把 03 留在标题里。
            // Recap/NCOP 前面的数字不是正片集号。
            TAIL_NUMBER.findAll(flat).lastOrNull()
                ?.takeIf { flat.substring(it.range.last + 1).trim(' ', '-', '_', '.').isEmpty() }
                ?.takeUnless { isNonEpisodeTail(flat, it.range.first) }
                ?.let {
                episode = it.groupValues[1].toIntOrNull() ?: 0
                matchedRanges += it.range
            }
        }

        // 只有编码、没有集号/季号：不要改写标题。否则 `[01]` 被后面的 Hi10p/OPUSx2
        // 挡住时会把整条技术串当成标题，卡片一行省略后名字显示不全、集数也丢了。
        if (episode == 0 && season == 0) return null

        // Remove exactly the matched spans: replacing "2" globally can corrupt the title itself.
        var source = flat
        for (range in matchedRanges.sortedByDescending { it.first }) {
            source = source.replaceRange(range, " ")
        }
        val title = buildTitle(source)
        return Parsed(
            // 什么都拼不出来（整个文件名就是 `[01]`）时保留原名，别造空标题
            title = title.ifBlank { base },
            season = season,
            episode = episode,
            codec = codec,
        )
    }

    private val TAG_SPLIT = Regex("[\\s_,._-]+")
    private val NON_EPISODE_WORD = Regex("(?i)(recap|ncop|nced|menu|pv|cm|trailer|preview|ova|oad|sp)")

    /** 尾号紧挨着 Recap/NCOP 时不是正片集号（否则总集篇会和第 1 集抢同一个角标）。 */
    private fun isNonEpisodeTail(flat: String, numberStart: Int): Boolean {
        val before = flat.substring(0, numberStart).trim()
        val word = before.substringAfterLast(' ')
        return NON_EPISODE_WORD.matches(word)
    }

    private fun isTechnicalToken(token: String): Boolean =
        TITLE_NOISE.matches(token) || TECHNICAL_DETAIL.matches(token) || COMPOUND_TECHNICAL.matches(token)

    private fun isTechnicalTag(label: String): Boolean =
        label.split(TAG_SPLIT).filter { it.isNotBlank() }
            .let { parts -> parts.isNotEmpty() && parts.all(::isTechnicalToken) }

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
