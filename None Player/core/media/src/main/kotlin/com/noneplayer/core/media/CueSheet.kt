package com.noneplayer.core.media

/**
 * CUE 分轨表解析（§12.4，批次 8）——WAV/FLAC + 同名 `.cue` 展开成"一张专辑 N 轨"。
 *
 * 解析范围刻意收窄到市面 `.cue` 的公共交集：
 * - `TITLE / PERFORMER / FILE / TRACK nn AUDIO / INDEX nn mm:ss:ff`；
 * - 轨起点只认 **`INDEX 01`**（市面约定：`INDEX 00` 是 pregap，非音频起点；
 *   单独的 `INDEX 00` 不产生轨）；
 * - 时间是 **1/75 秒制**（`mm:ss:ff`，ff ∈ 0..74）。换算成**绝对微秒**，
 *   不做逐轨累加——G4 提醒的 ±13ms/轨累积误差正是累加 `Ms` 造成的；
 *   绝对坐标 + `Us` 精度 setter 一并解决（`MediaItem.clipStartUs/clipEndUs`）。
 * - `REM`/`SONGWRITER`/未知行一律跳过；解析永不抛异常（坏表返回 null，退回单文件整轨）。
 *
 * 不支持（明确不假装支持）：多 `FILE` 行（整轨镜像/分段镜像）、非 AUDIO 轨
 * （MODE1/2352 数据轨）、`POSTGAP/PREGAP` 微调——遇到即按现有轨产出或返回 null。
 */
data class CueSheet(
    /** `FILE "xxx.wav" WAVE` 的文件名；与音频文件同名配对由扫描侧裁决 */
    val fileRef: String?,
    val albumTitle: String?,
    val albumPerformer: String?,
    val tracks: List<CueTrack>,
) {

    /** 一轨：序号（1 起）、标题（轨内 TITLE，可空）、起点绝对微秒（INDEX 01） */
    data class CueTrack(
        val number: Int,
        val title: String?,
        val startUs: Long,
    )

    companion object {

        /**
         * 解析 CUE 文本；无 TRACK 或无任何 INDEX 01 时返回 null（不算分轨表）。
         * 输入字符集由调用方处理（复用 [SubtitleCharset] 的 GB18030 试探，见扫描侧）。
         */
        fun parse(text: String): CueSheet? {
            var fileRef: String? = null
            var albumTitle: String? = null
            var albumPerformer: String? = null
            var currentTrackNumber = 0
            var currentTrackTitle: String? = null
            // 当前 TRACK 是否被接受为音频轨：INDEX 行只归属**本轨**——
            // 数据轨（MODE1/2352）之后的 INDEX 01 不能误挂到上一个 AUDIO 轨上
            var currentTrackIsAudio = false
            val tracks = mutableListOf<CueTrack>()

            text.lineSequence().forEach { raw ->
                when (val line = normalizeLine(raw) ?: return@forEach) {
                    is Line.File -> if (fileRef == null) fileRef = line.name
                    is Line.Title -> {
                        if (currentTrackNumber == 0) albumTitle = line.text
                        else currentTrackTitle = line.text
                    }
                    is Line.Performer -> if (currentTrackNumber == 0) albumPerformer = line.text
                    is Line.Track -> {
                        currentTrackNumber = line.number
                        currentTrackTitle = null
                        currentTrackIsAudio = line.isAudio
                    }
                    is Line.Index01 -> if (currentTrackNumber > 0 && currentTrackIsAudio) {
                        tracks += CueTrack(currentTrackNumber, currentTrackTitle, line.us)
                    }
                    is Line.Other -> Unit
                }
            }
            if (tracks.isEmpty()) return null
            // CUE 规范轨号升序；个别坏表乱序时按起点排序，保证分轨单调
            return CueSheet(fileRef, albumTitle, albumPerformer, tracks.sortedBy { it.startUs })
        }

        /** 解析 `mm:ss:ff`（ff 为 1/75 秒帧）为绝对微秒；格式坏返回 null */
        fun timeToUs(mmssff: String): Long? {
            val parts = mmssff.trim().split(':')
            if (parts.size != 3) return null
            val mm = parts[0].toLongOrNull() ?: return null
            val ss = parts[1].toLongOrNull() ?: return null
            val ff = parts[2].toLongOrNull() ?: return null
            if (mm < 0 || ss !in 0..59 || ff !in 0..74) return null
            return (mm * 60 + ss) * 75L * 1_000_000L / 75L + ff * 1_000_000L / 75L
        }

        private sealed interface Line {
            data class File(val name: String) : Line
            data class Title(val text: String) : Line
            data class Performer(val text: String) : Line
            data class Track(val number: Int, val isAudio: Boolean) : Line
            data class Index01(val us: Long) : Line
            data object Other : Line
        }

        /** 逐行归一：去 BOM/空白、取命令词；不可判读返回 null（跳过） */
        private fun normalizeLine(raw: String): Line? {
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("REM", ignoreCase = true)) return null
            val space = line.indexOfFirst { it == ' ' || it == '\t' }
            if (space <= 0) return Line.Other
            val keyword = line.take(space).uppercase()
            val rest = line.substring(space + 1).trim()
            return when (keyword) {
                "FILE" -> Line.File(quoted(rest) ?: rest.substringBeforeLast(' ').trim())
                "TITLE" -> quoted(rest)?.let { Line.Title(it) } ?: Line.Other
                "PERFORMER" -> quoted(rest)?.let { Line.Performer(it) } ?: Line.Other
                "TRACK" -> {
                    val num = rest.substringBefore(' ').trim().toIntOrNull()
                    // 只认音频轨；MODE1/2352 数据轨不是音乐轨
                    if (num != null) Line.Track(num, rest.uppercase().contains("AUDIO")) else Line.Other
                }
                "INDEX" -> {
                    val idx = rest.substringBefore(' ').trim()
                    if (idx == "01") {
                        Line.Index01(timeToUs(rest.substringAfter(' ').trim()) ?: return Line.Other)
                    } else {
                        Line.Other // INDEX 00（pregap）与 02+ 不产生轨起点
                    }
                }
                else -> Line.Other
            }
        }

        /** 取首个引号包裹段（CUE 字符串字段的标准形态） */
        private fun quoted(s: String): String? {
            val start = s.indexOf('"')
            if (start < 0) return null
            val end = s.indexOf('"', start + 1)
            return if (end > start) s.substring(start + 1, end) else null
        }
    }
}
