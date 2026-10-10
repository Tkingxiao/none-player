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
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import android.net.Uri
import androidx.media3.extractor.TrackOutput
import com.homesoft.exo.extractor.AviExtractorsFactory
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.ProbeMemo
import com.noneplayer.core.media.PlaybackFailure
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 起播前格式预探测（E39，§12.2 ①）——首批覆盖两类"必然失败"：
 *
 * 1. **容器无解封装支持**：任何 media3 Extractor 都无法 sniff（RMVB / WMV 等）；
 * 2. **视频编码无解**：容器可读，但**平台 MediaCodec、内置 FFmpeg 软解与 AVI MJPEG 位图软解
 *    都不认这个编码**（WMV2 / VC-1 / RealVideo 等旧编码）。
 *
 * 探测用与播放相同的 media3 Extractor 链，解码器查询则同时看三条链：
 * 平台 `MediaCodecList`（硬解/平台软解）+ NextLib 的 `FfmpegLibrary`（内置 FFmpeg 软解）
 * + AVI MJPEG 的 `BitmapFactoryVideoRenderer`（位图软解）。
 * 只查平台会把"平台没有、FFmpeg 能解"的编码（AV1 / MPEG-1/2 等）与"平台没有、MJPEG
 * 位图渲染器能解"的 AVI 误判为不可播——播放侧明明是 `EXTENSION_RENDERER_MODE_ON`
 * （平台不支持就落 FFmpeg），且末端还挂着 MJPEG 位图渲染器，两边必须一致。
 *
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
            /** HDR 概况（容器声明了 colorTransfer 时）："HDR10" / "HLG"；SDR/未知为 null */
            val hdr: String? = null,
            /** 音频声道布局展示名（"2.0" / "5.1"）；未知为 null */
            val audioChannels: String? = null,
            /**
             * 该媒体只有**软件解码器**可用（§C21 软解前置提示）。
             * 软件解码可以播，但高分辨率/高码率下会掉帧、发热、耗电——
             * UI 据此在起播前给一条 warn 胶囊，而不是让用户自己发现"为什么卡"。
             */
            val softwareVideoOnly: Boolean = false,
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
     *
     * [sizeBytes]/[lastModifiedSec] 是调用方手里现成的文件指纹（E111 断点校验同一约定）：
     * 给了才读写缓存，不给就照旧现场探。为什么要有这份缓存——E80 规定每条装载路径都必须
     * 过探测，而一次探测实测 0.5–1.5s，全压在用户等的那段时间里（手机基线：选集切换
     * 1255–1861ms 中 probe 占 507–953ms）。
     */
    suspend fun probe(
        context: Context,
        uriString: String,
        expectedMime: String? = null,
        sizeBytes: Long = 0L,
        lastModifiedSec: Long = 0L,
        /** 预探测传 false：`ProbeMemo` 是"最近**起播**的那一条"的排障入口，不该被预热写脏 */
        recordMemo: Boolean = true,
    ): Result {
        val cacheable = sizeBytes > 0L && uriString.isNotEmpty()
        if (cacheable) {
            resultCache.get(uriString)?.takeIf {
                it.sizeBytes == sizeBytes && it.lastModifiedSec == lastModifiedSec
            }?.let { hit ->
                // 命中要留声：切集耗时里"probe 段变短"必须能区分是命中还是碰巧读得快
                android.util.Log.d("FormatProbe", "命中缓存(${hit.result.javaClass.simpleName}) $uriString")
                return hit.result
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                probeLocal(context.applicationContext, uriString, expectedMime)
                    .also { if (recordMemo) recordMemo(uriString, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.Unknown
            }
        }.also {
            // Unknown（远程流 / 读取异常 / 超预算）没有结论，不缓存——放行不等于验证过
            if (cacheable && it != Result.Unknown) {
                resultCache.put(uriString, Memoized(sizeBytes, lastModifiedSec, it))
            }
        }
    }

    /** 探测结果缓存条目：结论 + 它成立时所依据的文件指纹 */
    private data class Memoized(val sizeBytes: Long, val lastModifiedSec: Long, val result: Result)

    /**
     * uri → 结论。上限按"一个文件夹一季"量级给 64 条，结论本身是几个字符串，不心疼。
     *
     * `LruCache` 自身方法同步，够这里用（并发预探测最多几条，撞上了也就是多探一次）。
     */
    private val resultCache = android.util.LruCache<String, Memoized>(64)

    /**
     * 最近探测摘要（E39 余项 + §12.3 解码诊断，批次 8）：设置页「解码诊断」的
     * 「最近探测」行来自这里。展示名取地址末段；Unknown 不记录（没有可读结论）。
     */
    private fun recordMemo(uriString: String, result: Result) {
        val displayName = Uri.parse(uriString).lastPathSegment?.substringAfterLast('/') ?: uriString
        val entry = when (result) {
            is Result.Playable -> ProbeMemo.Entry(
                displayName = displayName,
                container = result.container,
                videoCodec = result.videoCodec,
                audioCodec = result.audioCodec,
                hdr = result.hdr,
                audioChannels = result.audioChannels,
                playable = true,
                reason = null,
                atMs = System.currentTimeMillis(),
            )
            is Result.Unplayable -> ProbeMemo.Entry(
                displayName = displayName,
                container = result.failure.container,
                videoCodec = result.failure.videoCodec,
                audioCodec = result.failure.audioCodec,
                hdr = null,
                audioChannels = null,
                playable = false,
                reason = result.failure.reason,
                atMs = System.currentTimeMillis(),
            )
            Result.Unknown -> return
        }
        runCatching { ProbeMemo.record(entry) }
    }

    // ---- 内部实现 ----

    private val PROBE_SCHEMES = setOf("file", "content")
    private const val MAX_READS = 400

    /** tracksEnded 之后仍无任何 format 时的宽限读轮数（Wav 型：格式在下一轮 read 才来） */
    private const val GRACE_READS = 8
    private const val LENGTH_HINT = 8L * 1024 * 1024
    private const val MIME_MP4V = "video/mp4v-es"
    private const val MIME_MJPEG = "video/mjpeg"

    /**
     * 探测用的 Extractor 链——**必须与播放链同源**（E39 的一致性前提）。
     *
     * AVI 交给 Media3Avi 的增强解封装器（OpenDML/AVIX + indx/ix 索引、MP42/MP43、
     * MJPEG、更多 H.264 fourCC），其余容器仍是 media3 默认链；这与播放侧的
     * `DefaultMediaSourceFactory(context, AviExtractorsFactory())` 是同一套。
     * 两边不同源就会出现自相矛盾的结论：探测说"没有视频轨"而播放能出画，
     * 或探测放行而播放黑屏。
     *
     * 工厂本身无状态，每次 `createExtractors()` 产出全新的 Extractor 实例
     * （Extractor 有内部游标，不能跨文件复用），因此可以常驻单例。
     */
    private val AVI_EXTRACTORS: ExtractorsFactory = AviExtractorsFactory()

    /** profile 判读只需头部字节；截留上限（VOS/VOL/GOV 头远小于此） */
    private const val HEAD_CAP = 4 * 1024

    /**
     * suspend + 循环内 `ensureActive()`：探测跑在 IO 线程上、一读最多 400 轮，
     * 不可取消的话，"已经被用户切集取代的那次预热探测"会把磁盘通道占到底——
     * 平板实测撞上时同一次真探测从 589ms 涨到 1799ms。取消必须能在两轮读之间落地。
     */
    private suspend fun probeLocal(context: Context, uriString: String, expectedMime: String?): Result {
        // 预期类型决定"没有视频轨"怎么判读（批次 8，音频文件实播暴露）：
        //  - 音频文件（mime audio/*）：只有音频轨是**正常**，不是失败；
        //  - 视频文件：缺视频轨照旧拦截（E80——wmv2-clip.avi 的"音频播完无声失败"）；
        //  - 未知（外部调用没给 mime）：维持旧语义，拦截并给原因（fail-closed 到可读失败）
        val expectVideo: Boolean? = expectedMime?.let {
            if (it.startsWith("audio/", true)) false else if (it.startsWith("video/", true)) true else null
        }
        val uri = Uri.parse(uriString)
        if (uri.scheme?.lowercase() !in PROBE_SCHEMES) return Result.Unknown

        val containerName = MediaNames.containerName(uriString)
        val totalLength = localLength(context, uri)
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        try {
            dataSource.open(DataSpec(uri))
            var input = DefaultExtractorInput(dataSource, 0, totalLength ?: LENGTH_HINT)

            val extractors = AVI_EXTRACTORS.createExtractors(uri, emptyMap())
            var matched: Extractor? = null
            var sniffThrew = false
            for (extractor in extractors) {
                coroutineContext.ensureActive()
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
                coroutineContext.ensureActive()
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
                // 轨道表确定后通常即可收尾；但有两类例外要**多读几轮**：
                // ① MPEG-4 ASP 判读（§12.9.4）需要 codec 头字节——XVID/DivX 的 AVI 常把
                //    VOS/VOL 头内联在首帧（initializationData 为空）；
                // ② WavExtractor 等"先 endTracks、下一次 read 才吐 format"的解封装器
                //    （批次 8 实测：endTracks 即 break 会拿到 0 条格式，音频分支全灭）。
                // 收尾条件因此改为：轨道已结束 **且**（有格式 或 宽限轮次用尽）且 mp4v 判读就绪。
                if (output.tracksEnded) output.graceReads++
                if (output.tracksEnded &&
                    (output.formats.isNotEmpty() || output.graceReads > GRACE_READS) &&
                    output.hasEnoughForProfileCheck()
                ) break
            }
            matched.release()
            android.util.Log.d(
                "FormatProbe",
                "matched=${matched.javaClass.simpleName} expectedMime=$expectedMime expectVideo=$expectVideo " +
                    "formats=${output.formats.size} mimes=${output.formats.map { it.sampleMimeType }}",
            )
            return output.decide(effectiveContainer, expectVideo)
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

    /**
     * 平台音频解码器查询（批次 8）：与视频版 [hasPlatformDecoder] 同源，
     * 采样率/声道缺失时按常见档位查询；查询失败 fail-open（视为支持）。
     */
    private fun hasPlatformAudioDecoder(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return true
        return runCatching {
            val platform = PlatformMediaFormat.createAudioFormat(
                mime,
                if (format.sampleRate != null && format.sampleRate > 0) format.sampleRate else 48_000,
                if (format.channelCount != null && format.channelCount > 0) format.channelCount else 2,
            )
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(platform) != null
        }.getOrDefault(true)
    }

    /** 声道数 → 常见布局名（§12.3 面板展示用；非标准声道如实给数字） */
    private fun audioLayoutName(channels: Int): String = when (channels) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$channels 声道"
    }

    private val Format.isVideoTrack: Boolean
        get() = sampleMimeType?.startsWith("video/") == true

    private class ProbeOutput : ExtractorOutput {
        val formats = mutableListOf<Format>()
        var tracksEnded = false
            private set

        /** endTracks 之后的宽限读轮次（格式迟到型解封装器，见 read 循环注释） */
        var graceReads = 0

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
        fun decide(containerName: String?, expectVideo: Boolean?): Result {
            val video = formats.firstOrNull { it.isVideoTrack }
            val audio = formats.firstOrNull { it.sampleMimeType?.startsWith("audio/") == true }
            val videoName = MediaNames.videoCodecName(video?.sampleMimeType)
            val audioName = MediaNames.audioCodecName(audio?.sampleMimeType)

            if (video == null) {
                // 纯音频文件（audio/* 预期）：音频轨在、且本机解得动 → 可播
                if (expectVideo == false && audio != null) {
                    val audioDecodable = hasPlatformAudioDecoder(audio) || ffmpegSupports(audio.sampleMimeType)
                    if (audioDecodable) {
                        return Result.Playable(
                            container = containerName,
                            videoCodec = null,
                            audioCodec = audioName,
                            audioChannels = audio.channelCount?.takeIf { it > 0 }?.let(::audioLayoutName),
                        )
                    }
                    return Result.Unplayable(
                        PlaybackFailure.audioCodecUnsupported(containerName, audioName),
                    )
                }
                return Result.Unplayable(PlaybackFailure.unknownVideoCodec(containerName, audioName))
            }
            // 解码能力 = 平台解码器 或 内置 FFmpeg 软解 或 AVI MJPEG 位图软解
            // （播放链是"硬解优先 + 扩展兜底 + MJPEG 位图兜底"，三段的顺序与来源见
            //  plugin-player-exo 的 NpRenderersFactory）
            // E39 余项：HDR 概况与音频声道——来自容器声明的轨道格式（MKV/MP4 的
            // 色彩盒、声道数），探测期白拿；拿不到就是 null，不编造
            val hdr = when (video.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
            val audioLayout = audio?.channelCount?.takeIf { it > 0 }?.let(::audioLayoutName)
            val platformVideo = hasPlatformDecoder(video)
            val ffmpegVideo = ffmpegSupports(video.sampleMimeType)
            val bitmapVideo = bitmapRendererSupports(video.sampleMimeType)
            if (!platformVideo && !ffmpegVideo && !bitmapVideo) {
                return Result.Unplayable(
                    PlaybackFailure.videoCodecUnsupported(containerName, videoName, audioName),
                )
            }
            // §12.9.4：有解码器，但档次可能不被设备解码器支持（mp4v ASP vs Simple-only）
            profileMismatch(video, containerName, videoName, audioName)?.let { return it }
            return Result.Playable(
                container = containerName,
                videoCodec = videoName,
                audioCodec = audioName,
                hdr = hdr,
                audioChannels = audioLayout,
                // 平台没有该编码的解码器、只能靠 FFmpeg 或 MJPEG 位图出画时，同样是"仅软解可用"，
                // 也要给 C21 前置提示（否则用户只会觉得"莫名其妙很卡/很烫"）
                softwareVideoOnly = onlySoftwareDecoder(video) ||
                    (!platformVideo && (ffmpegVideo || bitmapVideo)),
            )
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

    /**
     * 是否**只有软件解码器**可用（C21）：默认解码器被系统标记为非硬件加速时为真。
     *
     * 保守取"默认解码器"的判断而不是枚举全部：`findDecoderForFormat` 返回的就是
     * 播放器实际会用的那一个；枚举全表再取"有没有硬解"会与真实选择脱节
     * （有硬解但默认选了软解的设备确实存在）。
     * `isHardwareAccelerated` 需要 API 29；更低版本返回 false（不给提示）。
     */
    private fun onlySoftwareDecoder(format: Format): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val mime = format.sampleMimeType ?: return false
        return runCatching {
            val platform = PlatformMediaFormat.createVideoFormat(
                mime,
                max(format.width, 1),
                max(format.height, 1),
            )
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoderName = list.findDecoderForFormat(platform) ?: return@runCatching false
            val info = list.codecInfos.firstOrNull { it.name == decoderName } ?: return@runCatching false
            !info.isHardwareAccelerated
        }.getOrDefault(false)
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

    /**
     * 内置 FFmpeg（NextLib）能否软解该编码。
     *
     * 覆盖范围以库内的编解码表为准（视频 h264 / hevc / av1 / mpeg1 / mpeg2 / vp8 / vp9，
     * 音频 aac / mp3 / ac3 / eac3 / dts / truehd / flac / alac / opus / vorbis / amr / pcm）。
     * **不含** mpeg4(Xvid/DivX)、wmv2/vc1、mjpeg——所以 ASP 拦截走 [profileMismatch]，
     * AVI 的 MJPEG 走 [bitmapRendererSupports]，都不受这里影响。
     *
     * 首次调用会触发 `System.loadLibrary("media3ext")`；ABI 缺失或加载失败时
     * [FfmpegLibrary.isAvailable] 为 false、本方法返回 false（fail-open 到平台判定）。
     */
    private fun ffmpegSupports(mime: String?): Boolean {
        if (mime == null) return false
        return runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
    }

    /**
     * AVI 内嵌 MJPEG 能否由播放链解出。
     *
     * MJPEG 既不在平台 `MediaCodec` 表里，也不在 NextLib 的 FFmpeg 编解码表里，
     * 但播放链末端挂了 `BitmapFactoryVideoRenderer`（见 plugin-player-exo 的
     * `NpRenderersFactory`），它认领 `video/mjpeg`、用 BitmapFactory 逐帧软解。
     * 探测若不认这条链，会把**实际能播**的 MJPEG AVI 误判为"没有可用解码器"。
     *
     * 只认 `video/mjpeg` 这一个 mime——渲染器的认领范围以 Media3Avi 的
     * `BitmapFactoryVideoRenderer` 为准（其内部仅匹配该 mime）。
     */
    private fun bitmapRendererSupports(mime: String?): Boolean = mime == MIME_MJPEG
}
