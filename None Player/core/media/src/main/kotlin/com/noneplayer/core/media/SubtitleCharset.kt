package com.noneplayer.core.media

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 外挂字幕字符集探测（E40）——纯 Kotlin，无 Android 依赖，可直接单测。
 *
 * media3 的 SubRip/VTT 解析只按 BOM 判定 UTF-8/UTF-16、默认 UTF-8，
 * GBK/GB18030/Big5 的中文字幕会整片乱码。挂载前用本工具探测并转成 UTF-8。
 *
 * 判定顺序（见审核文档 §12.4）：
 * 1. BOM（UTF-8 / UTF-16LE / UTF-16BE）直接采信；
 * 2. UTF-8 严格解码（遇非法字节即失败）——通过则原样使用，零成本；
 * 3. 否则在 GB18030 / Big5 间试探：严格解码 + 常用汉字命中打分，择优；
 * 4. 全部失败时按 UTF-8 宽容解码兜底（保证不崩、不空白）。
 */
object SubtitleCharset {

    /** 探测结果；[needsRewrite] 为 true 时需要把 [text] 落成 UTF-8 副本再交给播放器 */
    data class Result(
        val text: String,
        val charsetName: String,
        val needsRewrite: Boolean,
    )

    private const val REPLACEMENT = '\uFFFD'

    /** 判定中文编码是否可信的最低分（误判为中文编码时通常远低于此） */
    private const val MIN_SCORE = 6

    /**
     * E75 修复：此前 decode() 完全依赖调用方（SubtitleFile.kt）先行限流，自身没有
     * 大小护栏——作为公共可复用 object，一旦未来新增调用方忘记先限流，这里就是
     * 唯一的最后防线。32MB 是比 SubtitleFile 的 8MB 更宽松的硬上限（留给非字幕场景
     * 的余量），超限直接拒绝而不是进入多轮严格解码 + 逐字符打分（本身是比较重的操作）。
     */
    private const val MAX_DECODE_BYTES = 32 * 1024 * 1024

    fun decode(bytes: ByteArray): Result {
        require(bytes.size <= MAX_DECODE_BYTES) {
            "字幕内容过大（${bytes.size} 字节 > $MAX_DECODE_BYTES 字节上限），调用方应先行限流"
        }
        // 1) BOM 优先
        if (bytes.startsWith(0xEF, 0xBB, 0xBF)) {
            return Result(
                text = decodeStrict(bytes, 3, Charsets.UTF_8) ?: decodeLenient(bytes, 3, Charsets.UTF_8),
                charsetName = "UTF-8(BOM)",
                needsRewrite = true,
            )
        }
        if (bytes.startsWith(0xFF, 0xFE)) {
            return Result(
                text = decodeStrict(bytes, 2, Charsets.UTF_16LE) ?: decodeLenient(bytes, 2, Charsets.UTF_16LE),
                charsetName = "UTF-16LE",
                needsRewrite = true,
            )
        }
        if (bytes.startsWith(0xFE, 0xFF)) {
            return Result(
                text = decodeStrict(bytes, 2, Charsets.UTF_16BE) ?: decodeLenient(bytes, 2, Charsets.UTF_16BE),
                charsetName = "UTF-16BE",
                needsRewrite = true,
            )
        }

        // 2) 无 BOM 的 UTF-16：字节流里 NUL 密集且奇偶分明。
        //    必须在严格 UTF-8 之前判——UTF-16 字节流恰好也是"合法 UTF-8"（NUL 合法），
        //    否则会被误当成含 NUL 的 UTF-8 而漏转
        detectUtf16WithoutBom(bytes)?.let { (name, text) ->
            return Result(text, name, needsRewrite = true)
        }

        // 3) 严格 UTF-8：通过即无需任何转码
        decodeStrict(bytes, 0, Charsets.UTF_8)?.let {
            return Result(it, "UTF-8", needsRewrite = false)
        }

        // 4) 中文编码试探：GB18030 覆盖 GBK/GB2312，Big5 覆盖繁体
        val best = CHINESE_CANDIDATES
            .mapNotNull { (name, charset) ->
                decodeStrict(bytes, 0, charset)?.let { text -> Triple(name, text, score(text)) }
            }
            .maxByOrNull { it.third }
        if (best != null && best.third >= MIN_SCORE) {
            return Result(best.second, best.first, needsRewrite = true)
        }

        // 5) 兜底：UTF-8 宽容解码
        return Result(decodeLenient(bytes, 0, Charsets.UTF_8), "UTF-8(宽容)", needsRewrite = true)
    }


    /**
     * 无 BOM 的 UTF-16 启发式：抽样统计奇/偶位置上的 NUL 占比。
     * 拉丁/中文的 UTF-16 文本会出现"每隔一字节一个 0x00"的规律分布，
     * 而真正的 UTF-8 文本实务上不含 NUL。
     */
    private fun detectUtf16WithoutBom(bytes: ByteArray): Pair<String, String>? {
        val sampleSize = minOf(bytes.size, 4096)
        if (sampleSize < 16) return null
        var evenZero = 0
        var oddZero = 0
        var evenTotal = 0
        var oddTotal = 0
        for (i in 0 until sampleSize) {
            if (bytes[i].toInt() == 0) {
                if (i % 2 == 0) evenZero++ else oddZero++
            }
            if (i % 2 == 0) evenTotal++ else oddTotal++
        }
        val evenRatio = evenZero.toFloat() / evenTotal
        val oddRatio = oddZero.toFloat() / oddTotal
        return when {
            oddRatio > 0.3f && evenRatio < 0.05f ->
                "UTF-16LE(无BOM)" to decodeLenient(bytes, 0, Charsets.UTF_16LE)
            evenRatio > 0.3f && oddRatio < 0.05f ->
                "UTF-16BE(无BOM)" to decodeLenient(bytes, 0, Charsets.UTF_16BE)
            else -> null
        }
    }

    private val CHINESE_CANDIDATES: List<Pair<String, Charset>> = listOf(
        "GB18030" to Charset.forName("GB18030"),
        "Big5" to Charset.forName("Big5"),
    )

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeLenient(bytes: ByteArray, offset: Int, charset: Charset): String =
        String(bytes, offset, bytes.size - offset, charset)

    /**
     * 文本可信度打分：常用汉字加分；替换符/控制字符/私用区字符（误判的典型信号）扣分。
     * 语义：让"真中文"远高于"把 UTF-8 字节按 GB18030 读出的噪声"。
     */
    private fun score(text: String): Int {
        var score = 0
        for (ch in text) {
            when {
                ch == REPLACEMENT -> score -= 20
                ch in COMMON_CJK_RANGE || ch in COMMON_SIMPLIFIED -> score += 2
                ch.code in 0xE000..0xF8FF -> score -= 10 // 私用区
                ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 8
            }
        }
        return score
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.withIndex().all { (i, b) -> this[i].toInt() and 0xFF == b }
    }
}

/** CJK 统一表意文字基本区（含扩展 A 起点的常见范围） */
private val COMMON_CJK_RANGE = '\u4E00'..'\u9FFF'

/** 高频简体常用字（约 150 字），用于区分"真中文"与"误判噪声" */
private const val COMMON_SIMPLIFIED_TEXT =
    "的一是不了在人有我他这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会" +
        "家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总" +
        "从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世" +
        "什两次使身者被高已亲其进此话常与活正感"

private val COMMON_SIMPLIFIED: Set<Char> = COMMON_SIMPLIFIED_TEXT.toSet()
