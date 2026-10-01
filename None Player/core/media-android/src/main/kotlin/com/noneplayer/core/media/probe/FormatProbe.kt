package com.noneplayer.core.media.probe

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat as PlatformMediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import android.net.Uri
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.PlaybackFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 起播前格式预探测（E39，§12.2 ①）——首批覆盖两类"必然失败"：
 *
 * 1. **容器无解封装支持**：任何 media3 Extractor 都无法 sniff（RMVB / WMV 等）；
 * 2. **视频编码无解**：容器可读但没有平台解码器（WMV3 / VC-1 / 无损旧编码等）。
 *
 * 探测用与播放相同的 media3 Extractor 链 + 平台 `MediaCodecList`，
 * 结论只用于"提前给出可读失败原因"，避免 3–8s 白转圈；
 * 拿不准（远程流 / 读取异常 / 超预算）一律 [Result.Unknown] 放行给引擎。
 */
@OptIn(UnstableApi::class)
object FormatProbe {

    sealed interface Result {
        /** 已确定无法播放，带完整三行失败信息 */
        data class Unplayable(val failure: PlaybackFailure) : Result

        /** 探测通过（可带上轨道编码名供失败兜底文案使用） */
        data class Playable(
            val container: String?,
            val videoCodec: String?,
            val audioCodec: String?,
        ) : Result

        /** 无法给出结论——照常交给播放引擎 */
        data object Unknown : Result
    }

    /** 在 IO 线程执行；绝不抛出，异常按 [Result.Unknown] 放行 */
    suspend fun probe(context: Context, uriString: String): Result =
        withContext(Dispatchers.IO) {
            runCatching { probeLocal(context.applicationContext, uriString) }
                .getOrDefault(Result.Unknown)
        }

    // ---- 内部实现 ----

    private val PROBE_SCHEMES = setOf("file", "content")
    private const val MAX_READS = 400
    private const val LENGTH_HINT = 8L * 1024 * 1024

    private fun probeLocal(context: Context, uriString: String): Result {
        val uri = Uri.parse(uriString)
        if (uri.scheme?.lowercase() !in PROBE_SCHEMES) return Result.Unknown

        val containerName = MediaNames.containerName(uriString)
        val totalLength = localLength(context, uri)
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        try {
            dataSource.open(DataSpec(uri))
            var input = DefaultExtractorInput(dataSource, 0, totalLength ?: LENGTH_HINT)

            val extractors = DefaultExtractorsFactory().createExtractors(uri, emptyMap())
            var matched: Extractor? = null
            var sniffThrew = false
            for (extractor in extractors) {
                val sniffed = try {
                    extractor.sniff(input)
                } catch (_: Throwable) {
                    sniffThrew = true
                    false
                }
                if (sniffed) {
                    matched = extractor
                    // 关键：sniff 只做 peek；不重置会让 Extractor 自己的首个 read
                    // （如 AviExtractor 会再 sniff 一次）从错误的 peek 位置开始
                    input.resetPeekPosition()
                    break
                }
                input.resetPeekPosition()
            }
            if (matched == null) {
                return Result.Unplayable(
                    if (sniffThrew) PlaybackFailure.unreadable(containerName)
                    else PlaybackFailure.containerUnsupported(containerName),
                )
            }

            val output = ProbeOutput()
            try {
                matched.init(output)
            } catch (_: Throwable) {
                return Result.Unplayable(PlaybackFailure.unreadable(containerName))
            }
            // MediaStore 的 content:// 地址没有扩展名：容器名以"谁认领了它"为准
            val effectiveContainer = containerFromExtractor(matched) ?: containerName

            val holder = PositionHolder()
            var reads = 0
            while (reads < MAX_READS) {
                reads++
                val result = try {
                    matched.read(input, holder)
                } catch (_: Throwable) {
                    // 解复用中途失败（含部分编码未映射的容器）：轨道信息可能只拿到一半，
                    // 有视频轨照常判定，没有则按"编码无法识别"给原因
                    break
                }
                if (result == Extractor.RESULT_SEEK) {
                    val position = holder.position
                    if (position < 0 || (totalLength != null && position >= totalLength)) break
                    // 重开取随机访问，避免 skip 扫全文件（moov 在尾部的 MP4）
                    dataSource.close()
                    dataSource.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
                    input = DefaultExtractorInput(
                        dataSource,
                        position,
                        (totalLength ?: (position + LENGTH_HINT)) - position,
                    )
                } else if (result == Extractor.RESULT_END_OF_INPUT) {
                    break
                }
                // hdrl/moov/tracks 解析完成后轨道表已确定，不再读样本数据
                if (output.tracksEnded) break
            }
            matched.release()
            return output.decide(effectiveContainer)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /** 认领文件的是哪个解封装器 → 容器显示名（补 MediaStore 地址无扩展名的缺口） */
    private fun containerFromExtractor(extractor: Extractor): String? =
        when (extractor.javaClass.simpleName) {
            "AviExtractor" -> "AVI"
            "MatroskaExtractor" -> "Matroska (MKV)"
            "Mp4Extractor", "FragmentedMp4Extractor" -> "MP4"
            "TsExtractor" -> "MPEG-TS"
            "PsExtractor" -> "MPEG-PS"
            "FlvExtractor" -> "FLV"
            "OggExtractor" -> "Ogg"
            "WavExtractor" -> "WAV"
            "Mp3Extractor" -> "MP3"
            "AdtsExtractor" -> "AAC (ADTS)"
            "FlacExtractor" -> "FLAC"
            "AmrExtractor" -> "AMR"
            "Ac3Extractor" -> "AC-3"
            "Ac4Extractor" -> "AC-4"
            else -> null
        }

    /** 本地文件字节长度；拿不到返回 null（用探测预算兜底）。不抛异常。 */
    private fun localLength(context: Context, uri: android.net.Uri): Long? = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { path -> java.io.File(path).takeIf { it.isFile }?.length() }
            "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                fd.length.takeIf { it >= 0 }
            }
            else -> null
        }
    }.getOrNull()

    private val Format.isVideoTrack: Boolean
        get() = sampleMimeType?.startsWith("video/") == true

    private class ProbeOutput : ExtractorOutput {
        val formats = mutableListOf<Format>()
        var tracksEnded = false
            private set

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val discard = DiscardingTrackOutput()

            override fun format(format: Format) {
                formats.add(format)
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int,
            ): Int = discard.sampleData(input, length, allowEndOfInput, sampleDataPart)

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
                discard.sampleData(data, length, sampleDataPart)

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) = discard.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit

        /** 探测结论：容器/编码名 + 平台解码器查询 */
        fun decide(containerName: String?): Result {
            val video = formats.firstOrNull { it.isVideoTrack }
            val audio = formats.firstOrNull { it.sampleMimeType?.startsWith("audio/") == true }
            val videoName = MediaNames.videoCodecName(video?.sampleMimeType)
            val audioName = MediaNames.audioCodecName(audio?.sampleMimeType)

            if (video == null) {
                return Result.Unplayable(PlaybackFailure.unknownVideoCodec(containerName, audioName))
            }
            if (!hasPlatformDecoder(video)) {
                return Result.Unplayable(
                    PlaybackFailure.videoCodecUnsupported(containerName, videoName, audioName),
                )
            }
            return Result.Playable(containerName, videoName, audioName)
        }
    }

    /** 平台解码器查询：与系统同源（MediaCodecList），尺寸缺失时按常见档位查询 */
    private fun hasPlatformDecoder(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return true
        return runCatching {
            val platform = PlatformMediaFormat.createVideoFormat(
                mime,
                max(format.width, 1),
                max(format.height, 1),
            )
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(platform) != null
        }.getOrDefault(true)
    }
}
