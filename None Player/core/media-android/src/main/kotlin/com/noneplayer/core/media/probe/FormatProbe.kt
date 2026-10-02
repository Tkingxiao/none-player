package com.noneplayer.core.media.probe

import android.content.Context
import android.media.MediaCodecInfo
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
import java.io.ByteArrayOutputStream
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

    /**
     * 在 IO 线程执行；非取消异常按 [Result.Unknown] 放行。
     *
     * E58 修复：此前用 `runCatching { }.getOrDefault(...)` 兜底，但 `runCatching`
     * 捕获 `Throwable`——`CancellationException` 是其子类，会被一并吞掉，
     * 使结构化并发的取消信号失效：调用方 `prepareJob?.cancel()` 后，旧探测仍可能
     * "被取消"却跑完并返回正常结果，静默覆盖新一次 prepare() 的媒体。
     * 显式区分取消异常并继续向上抛出，是修复的关键一步。
     */
    suspend fun probe(context: Context, uriString: String): Result =
        withContext(Dispatchers.IO) {
            try {
                probeLocal(context.applicationContext, uriString)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.Unknown
            }
        }

    // ---- 内部实现 ----

    private val PROBE_SCHEMES = setOf("file", "content")
    private const val MAX_READS = 400
    private const val LENGTH_HINT = 8L * 1024 * 1024
    private const val MIME_MP4V = "video/mp4v-es"

    /** profile 判读只需头部字节；截留上限（VOS/VOL/GOV 头远小于此） */
    private const val HEAD_CAP = 4 * 1024

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
                // 轨道表确定后通常即可收尾；但 MPEG-4 ASP 判读（§12.9.4）需要 codec 头字节，
                // 而 XVID/DivX 的 AVI 常把 VOS/VOL 头内联在首帧（initializationData 为空）。
                // 这种情况下继续读到抓住首个视频样本的前导字节为止（仍受 MAX_READS 封顶）。
                if (output.tracksEnded && output.hasEnoughForProfileCheck()) break
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

        /** 首个视频样本的前导字节（上限 [HEAD_CAP]）——mp4v 无 csd 时的 profile 判读来源 */
        var firstVideoSampleHead: ByteArray? = null
            private set

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val discard = DiscardingTrackOutput()
            private val isVideo = type == C.TRACK_TYPE_VIDEO
            private val head: ByteArrayOutputStream? = if (isVideo) ByteArrayOutputStream() else null
            private var sampleCaptured = false

            override fun format(format: Format) {
                formats.add(format)
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int,
            ): Int {
                // 非视频轨 / 已抓够：维持原丢弃行为（零额外内存）
                if (head == null || firstVideoSampleHead != null) {
                    return discard.sampleData(input, length, allowEndOfInput, sampleDataPart)
                }
                // 视频轨首样本：自读以截留前导字节，读完即丢（不改变 input 消费语义）
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n == -1) {
                        if (allowEndOfInput) break
                        error("unexpected EOF in sampleData read=$read/$length")
                    }
                    read += n
                }
                captureHead(head, buf, 0, read)
                return read
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                if (head == null || firstVideoSampleHead != null) {
                    discard.sampleData(data, length, sampleDataPart)
                    return
                }
                captureHead(head, data.data, data.position, length)
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                // 视频轨：首个样本边界到达 → 固化前导字节
                if (head != null && !sampleCaptured && head.size() > 0 && firstVideoSampleHead == null) {
                    firstVideoSampleHead = head.toByteArray()
                    sampleCaptured = true
                }
                if (head == null) {
                    discard.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                }
            }

            private fun captureHead(sink: ByteArrayOutputStream, src: ByteArray, from: Int, len: Int) {
                if (sink.size() >= HEAD_CAP || len <= 0) return
                val take = minOf(len, HEAD_CAP - sink.size())
                sink.write(src, from, take)
            }
        }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit

        /**
         * 读到可以收尾了吗？—— 只在"首个视频轨是 mp4v 且 initializationData 为空"时
         * 需要额外抓首帧前导字节；其余格式轨道表齐了就够。
         */
        fun hasEnoughForProfileCheck(): Boolean {
            val video = formats.firstOrNull { it.isVideoTrack } ?: return true
            val needsHead = video.sampleMimeType == MIME_MP4V &&
                video.initializationData.isNullOrEmpty()
            return !needsHead || firstVideoSampleHead != null
        }

        /** 探测结论：容器/编码名 + 平台解码器查询（含 §12.9.4 profile 求交） */
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
            // §12.9.4：有解码器，但档次可能不被设备解码器支持（mp4v ASP vs Simple-only）
            profileMismatch(video, containerName, videoName, audioName)?.let { return it }
            return Result.Playable(containerName, videoName, audioName)
        }

        /**
         * MPEG-4 ASP 档次拦截（§12.9.4）：判读码流需要的 profile，与设备 mp4v 解码器声明的
         * `profileLevels` 求交。仅当"码流确需 Advanced Simple 且无任何解码器声明 ASP"时
         * 返回 [Result.Unplayable]；拿不准一律返回 null（fail-open 放行给引擎）。
         */
        private fun profileMismatch(
            video: Format,
            containerName: String?,
            videoName: String?,
            audioName: String?,
        ): Result? {
            if (video.sampleMimeType != MIME_MP4V) return null
            val header = video.initializationData?.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: firstVideoSampleHead
                ?: return null
            val verdict = Mpeg4ProfileParser.parse(header)
            if (verdict.required != Mpeg4ProfileParser.RequiredProfile.ADVANCED_SIMPLE) return null
            // 设备上所有 mp4v 解码器声明的 profile 集合
            val deviceProfiles = mp4vDeviceProfiles()
            val supportsAsp = deviceProfiles.any {
                it == MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple
            }
            if (supportsAsp) return null // 设备确实声明了 ASP：放行
            if (deviceProfiles.isEmpty()) return null // 查不到声明：fail-open，交给引擎
            return Result.Unplayable(
                PlaybackFailure.videoProfileUnsupported(
                    container = containerName,
                    videoCodec = videoName,
                    audioCodec = audioName,
                    requiredProfile = "Advanced Simple Profile",
                    deviceProfiles = deviceProfiles
                        .map { mpeg4ProfileName(it) }
                        .distinct()
                        .joinToString(" / "),
                ),
            )
        }
    }

    /** 枚举设备上所有支持 mp4v 的解码器声明的 profile（去不去重由调用方定）。 */
    private fun mp4vDeviceProfiles(): List<Int> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { info ->
                runCatching { info.supportedTypes.any { it.equals(MIME_MP4V, true) } }
                    .getOrDefault(false)
            }
            .flatMap { info ->
                runCatching {
                    info.getCapabilitiesForType(MIME_MP4V).profileLevels?.map { it.profile }
                        ?: emptyList()
                }.getOrDefault(emptyList()).asSequence()
            }
            .toList()
    }.getOrDefault(emptyList())

    private fun mpeg4ProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileSimple -> "Simple Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileSimpleScalable -> "Simple Scalable Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileCore -> "Core Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileMain -> "Main Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple -> "Advanced Simple Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedCoding -> "Advanced Coding Profile"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedCore -> "Advanced Core Profile"
        else -> "0x${Integer.toHexString(profile)}"
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
