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
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.ProbeMemo
import com.noneplayer.core.media.PlaybackFailure
import com.noneplayer.core.media.extract.NpExtractorsFactory
import com.noneplayer.core.media.legacy.LegacyDecoderLibrary
import com.noneplayer.core.media.ffmpeg.NpFfmpegCodecs
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
 * 探测用与播放相同的 media3 Extractor 链，解码器查询则同时看四条链：
 * 平台 `MediaCodecList`（硬解/平台软解）+ NextLib 的 `FfmpegLibrary`（内置 FFmpeg 软解）
 * + AVI MJPEG 的 `BitmapFactoryVideoRenderer`（位图软解）
 * + `libnplegacy`（只认 WMV2 与专用 ASP mime，加载失败视为不支持）。
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
            /**
             * 视频可解、但音轨本机不可解（A11）：平台与内置 FFmpeg 都不认这个音频编码。
             * 播放继续（静音），UI 必须明确提示，不能像旧实现那样静默丢轨。
             */
            val audioUnsupported: Boolean = false,
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
        /**
         * REV-04/A17：错误面板「重试」必须绕过缓存。
         *
         * 之前只有 `cacheable` 一道门：缓存里躺着一条"容器不支持"，用户点重试还是同一条，
         * 永远回到同一个错误。传 true 强制现场重探（配合 [invalidate] 清掉旧结论）。
         */
        bypassCache: Boolean = false,
    ): Result {
        val cacheable = sizeBytes > 0L && uriString.isNotEmpty()
        if (cacheable && !bypassCache) {
            resultCache.get(uriString)?.takeIf {
                it.sizeBytes == sizeBytes && it.lastModifiedSec == lastModifiedSec
            }?.let { hit ->
                // 命中要留声：切集耗时里"probe 段变短"必须能区分是命中还是碰巧读得快
                android.util.Log.d(
                    "FormatProbe",
                    "命中缓存(${hit.outcome.result.javaClass.simpleName}) $uriString",
                )
                return hit.outcome.result
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                probeLocal(context.applicationContext, uriString, expectedMime)
                    .also { outcome ->
                        if (recordMemo) recordMemo(uriString, outcome.result)
                        // REV-04/A17：只有确定性结论进缓存；Unknown / unreadable / 查询异常
                        // 都是瞬态，重探一次可能就通了，不能把它们固化成永久判决
                        if (cacheable && outcome.cacheable) {
                            resultCache.put(
                                uriString,
                                ProbeCacheEntry(sizeBytes, lastModifiedSec, outcome),
                            )
                        }
                    }
                    .result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 查询/读取异常 → Unknown（诊断放行），不冒充确定结论、不缓存
                Result.Unknown
            }
        }
    }

    /**
     * REV-04/A17：错误面板「重试」前清掉该 uri 的缓存结论。
     *
     * 与 `probe(bypassCache = true)` 互补：一个清旧账，一个强制现场重探。
     */
    fun invalidate(uriString: String) {
        resultCache.invalidate(uriString)
    }

    /**
     * uri → 确定性结论。上限按"一个文件夹一季"量级给 64 条，结论本身是几个字符串，不心疼。
     *
     * 纯 Kotlin 的 [ProbeResultCache]（不是 `android.util.LruCache`）：缓存策略因此能在
     * 纯 JVM 单测里直接验证；并发预探测最多几条，撞上了也就是多探一次。
     */
    private val resultCache = ProbeResultCache()

    /**
     * 最近探测摘要（E39 余项 + §12.3 解码诊断，批次 8）：设置页「解码诊断」的
     * 「最近探测」行来自这里。展示名取地址末段；Unknown 不记录（没有可读结论）。
     */
    private fun recordMemo(uriString: String, result: Result) {
        val displayName = uriString.toUri().lastPathSegment?.substringAfterLast('/') ?: uriString
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

    /**
     * 随机访问探测的读轮上限：SEEK 免费后，成本只剩逐块读头（每轮 8 字节 + 一次定位）。
     * 真正封顶的是下面的墙钟预算——两小时正片十几万块在预算内走不走完都由 [PROBE_BUDGET_NS] 决定。
     */
    private const val MAX_READS = 1_000_000

    /** 随机访问探测的墙钟预算：只对"逐块扫 movi"这种重探测生效，常规探测远够不着。 */
    private const val PROBE_BUDGET_NS = 4L * 1_000_000_000

    /** 墙钟检查的轮间隔（System.nanoTime 也有成本，不必每轮查） */
    private const val BUDGET_CHECK_STRIDE = 64

    /** 回落路径（拿不到 seekable channel）的读轮上限：保持旧值，每轮 SEEK 都要重开 fd。 */
    private const val FALLBACK_MAX_READS = 400

    /** tracksEnded 之后仍无任何 format 时的宽限读轮数（Wav 型：格式在下一轮 read 才来） */
    private const val GRACE_READS = 8
    private const val LENGTH_HINT = 8L * 1024 * 1024
    private const val MIME_MP4V = "video/mp4v-es"
    private const val MIME_MJPEG = "video/mjpeg"

    /**
     * 探测用的 Extractor 链——**必须与播放链同源**（E39 的一致性前提）。
     *
     * AVI 交给 [NpExtractorsFactory]：非 WMV2 仍是 Media3Avi，WMV2 由补轨提取器认领，
     * 确需软解的 MPEG-4 ASP 在 Format 出手前改成专用 mime。这与播放侧的
     * `DefaultMediaSourceFactory(context, NpExtractorsFactory())` 是同一套。
     * 两边不同源就会出现自相矛盾的结论：探测说"没有视频轨"而播放能出画，
     * 或探测放行而播放黑屏。
     *
     * 工厂本身无状态，每次 `createExtractors()` 产出全新的 Extractor 实例
     * （Extractor 有内部游标，不能跨文件复用），因此可以常驻单例。
     */
    private val AVI_EXTRACTORS: ExtractorsFactory = NpExtractorsFactory()

    /** profile 判读只需头部字节；截留上限（VOS/VOL/GOV 头远小于此） */
    private const val HEAD_CAP = 4 * 1024

    /**
     * REV-05：读取样本时的固定 scratch（每次 read 请求上限）。
     * 不再按样本 `length` 分配整段数组——超大样本（可达数十 MB）会直接 OOM；
     * 分块读完后只截留 head 需要的前缀，其余块读完即丢。
     */
    private const val SAMPLE_SCRATCH_BYTES = 64 * 1024

    /**
     * suspend + 循环内 `ensureActive()`：探测跑在 IO 线程上、一读最多 400 轮，
     * 不可取消的话，"已经被用户切集取代的那次预热探测"会把磁盘通道占到底——
     * 平板实测撞上时同一次真探测从 589ms 涨到 1799ms。取消必须能在两轮读之间落地。
     */
    private suspend fun probeLocal(context: Context, uriString: String, expectedMime: String?): ProbeOutcome {
        // 预期类型决定"没有视频轨"怎么判读（批次 8，音频文件实播暴露）：
        //  - 音频文件（mime audio/*）：只有音频轨是**正常**，不是失败；
        //  - 视频文件：缺视频轨照旧拦截（E80——wmv2-clip.avi 的"音频播完无声失败"）；
        //  - 未知（外部调用没给 mime）：维持旧语义，拦截并给原因（fail-closed 到可读失败）
        val expectVideo: Boolean? = expectedMime?.let {
            if (it.startsWith("audio/", true)) false else if (it.startsWith("video/", true)) true else null
        }
        val uri = uriString.toUri()
        if (uri.scheme?.lowercase() !in PROBE_SCHEMES) return ProbeOutcome.transient(Result.Unknown)

        val containerName = MediaNames.containerName(uriString)

        // A1 收口：fd 只开一次、SEEK 变 channel.position()。
        // 旧路径每次 RESULT_SEEK 都 dataSource.close()+open()——content:// 上一次 open
        // 是一次 openAssetFileDescriptor（binder + dup fd），无 idx1 的 AVI 扫描每块一次
        // SEEK，两小时正片十几万块就是十几万次 binder 往返（真机探测因此从亚秒涨到数秒）。
        // FileChannel 天然可定位：本地 file/content 都能拿到一个 seekable channel。
        val channel = openSeekableChannel(context, uri)
            ?: return probeViaDataSource(context, uri, containerName, expectedMime, expectVideo)
        try {
            return probeOnChannel(channel, uriString, containerName, expectedMime, expectVideo)
        } finally {
            runCatching { channel.close() }
        }
    }

    /**
     * file/content → 一个可定位的只读 [FileChannel]；拿不到（远端、SAF 撤权、
     * 流式 provider）返回 null，调用方回落到旧的 DataSource 探测路径。
     */
    private fun openSeekableChannel(context: Context, uri: Uri): FileChannel? = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { path ->
                java.io.RandomAccessFile(path, "r").channel
            }
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
            }
            else -> null
        }
    }.getOrNull()

    /**
     * 随机访问探测：单 fd + channel 定位，SEEK 零成本（A1 收口的兑现）。
     */
    private suspend fun probeOnChannel(
        channel: FileChannel,
        uriString: String,
        containerName: String?,
        expectedMime: String?,
        expectVideo: Boolean?,
    ): ProbeOutcome {
        val totalLength = runCatching { channel.size() }.getOrDefault(0L)
        val reader = ChannelReader(channel)
        var input = DefaultExtractorInput(reader, 0, if (totalLength > 0) totalLength else LENGTH_HINT)

        val uri = uriString.toUri()
        val extractors = AVI_EXTRACTORS.createExtractors(uri, emptyMap())
        var matched: Extractor? = null
        var sniffThrew = false
        for (extractor in extractors) {
            currentCoroutineContext().ensureActive()
            val sniffed = try {
                extractor.sniff(input)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            // sniff 抛异常 = 瞬态读失败，不缓存；没有解封装器认领才是确定结论
            return if (sniffThrew) {
                ProbeOutcome.transient(
                    Result.Unplayable(PlaybackFailure.unreadable(containerName)),
                )
            } else {
                ProbeOutcome(
                    Result.Unplayable(PlaybackFailure.containerUnsupported(containerName)),
                    ProbeVerdict.CONTAINER_UNSUPPORTED,
                )
            }
        }

        val output = ProbeOutput()
        try {
            try {
                matched.init(output)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 解封装器初始化失败（I/O、SAF 失效…）：瞬态，不缓存
                return ProbeOutcome.transient(
                    Result.Unplayable(PlaybackFailure.unreadable(containerName)),
                )
            }
        // MediaStore 的 content:// 地址没有扩展名：容器名以"谁认领了它"为准
        val effectiveContainer = containerFromExtractor(matched) ?: containerName

        val holder = PositionHolder()
        var reads = 0
        var readThrew = false
        var budgetExceeded = false
        val deadlineNs = System.nanoTime() + PROBE_BUDGET_NS
        while (reads < MAX_READS) {
            reads++
            currentCoroutineContext().ensureActive()
            if (reads % BUDGET_CHECK_STRIDE == 0 && System.nanoTime() > deadlineNs) {
                // 预算按墙钟收（SEEK 已经免费，剩下的成本是逐块读头本身）；
                // 超预算与旧读数耗尽同判：有格式照常定论，没格式按拿不准放行
                budgetExceeded = true
                break
            }
            val result = try {
                matched.read(input, holder)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 解复用中途失败（含部分编码未映射的容器）：轨道信息可能只拿到一半，
                // 有视频轨照常判定，没有则按"编码无法识别"给原因
                readThrew = true
                break
            }
            if (result == Extractor.RESULT_SEEK) {
                val position = holder.position
                // E227：`== totalLength` 不能截断——无 idx1 的 OpenDML AVI 的 movi 就是
                // 最后一个 chunk，提取器要去 movi 尾**读一次 EOF** 才能确定「没有 idx1」
                // 并回落超索引/扫描（真机实锤：守卫截断后 0 格式 → 每次打开都弹
                // 「没有解析到受支持的视频编码」失败面板，画面却在面板后面正常播）。
                // 只拦 `> totalLength` 的越界寻位；恰在文件尾时读一步立刻 EOF，无成本。
                if (position < 0 || (totalLength > 0 && position > totalLength)) break
                // A1 收口后 SEEK 只是 channel.position()，不再 close+open
                runCatching { channel.position(position) }
                input = DefaultExtractorInput(
                    reader,
                    position,
                    if (totalLength > 0) totalLength - position else LENGTH_HINT,
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
        android.util.Log.d(
            "FormatProbe",
            "matched=${matched.javaClass.simpleName} ch=seekable expectedMime=$expectedMime expectVideo=$expectVideo " +
                "reads=$reads formats=${output.formats.size} mimes=${output.formats.map { it.sampleMimeType }}",
        )
        // A1：预算耗尽或读取抛异常，而**一条格式都没拿到** = 拿不准，不是"编码不支持"。
        // 判 Unplayable 会被写进缓存（缓存条件只排除 Unknown），于是能播的文件被永久
        // 记成"没有受支持的视频编码"。类注释承诺的"拿不准放行"在这里兑现。
        if (output.formats.isEmpty() &&
            (readThrew || budgetExceeded || (!output.tracksEnded && reads >= MAX_READS))
        ) {
            return ProbeOutcome.transient(Result.Unknown)
        }
        val outcome = output.decide(effectiveContainer, expectVideo)
        // 解复用中途失败过：即便算出了结论，也按瞬态处理（不写缓存，下次重探）
        return if (readThrew) {
            ProbeOutcome(outcome.result, ProbeVerdict.NON_DETERMINISTIC)
        } else {
            outcome
        }
        } finally {
            matched?.let { extractor -> runCatching { extractor.release() } }
        }
    }

    /**
     * 拿不到 seekable channel 时的回落路径：旧 DataSource + 每次 SEEK 重开 fd。
     * 只服务远端/流式/SAF 异常态，预算保持旧读数，不做放宽。
     */
    private suspend fun probeViaDataSource(
        context: Context,
        uri: Uri,
        containerName: String?,
        expectedMime: String?,
        expectVideo: Boolean?,
    ): ProbeOutcome {
        val totalLength = localLength(context, uri)
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        try {
            dataSource.open(DataSpec(uri))
            var input = DefaultExtractorInput(dataSource, 0, totalLength ?: LENGTH_HINT)

            val extractors = AVI_EXTRACTORS.createExtractors(uri, emptyMap())
            var matched: Extractor? = null
            var sniffThrew = false
            for (extractor in extractors) {
                currentCoroutineContext().ensureActive()
                val sniffed = try {
                    extractor.sniff(input)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
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
                // sniff 抛异常 = 瞬态读失败，不缓存；没有解封装器认领才是确定结论
                return if (sniffThrew) {
                    ProbeOutcome.transient(
                        Result.Unplayable(PlaybackFailure.unreadable(containerName)),
                    )
                } else {
                    ProbeOutcome(
                        Result.Unplayable(PlaybackFailure.containerUnsupported(containerName)),
                        ProbeVerdict.CONTAINER_UNSUPPORTED,
                    )
                }
            }

            val output = ProbeOutput()
            try {
                try {
                    matched.init(output)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 解封装器初始化失败（I/O、SAF 失效…）：瞬态，不缓存
                    return ProbeOutcome.transient(
                        Result.Unplayable(PlaybackFailure.unreadable(containerName)),
                    )
                }
            // MediaStore 的 content:// 地址没有扩展名：容器名以"谁认领了它"为准
            val effectiveContainer = containerFromExtractor(matched) ?: containerName

            val holder = PositionHolder()
            var reads = 0
            var readThrew = false
            while (reads < FALLBACK_MAX_READS) {
                reads++
                currentCoroutineContext().ensureActive()
                val result = try {
                    matched.read(input, holder)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 解复用中途失败（含部分编码未映射的容器）：轨道信息可能只拿到一半，
                    // 有视频轨照常判定，没有则按"编码无法识别"给原因
                    readThrew = true
                    break
                }
                if (result == Extractor.RESULT_SEEK) {
                    val position = holder.position
                    // 与 probeOnChannel 同口径：恰在文件尾的 SEEK 放行（E227，ODML 无
                    // idx1 的 EOF 探测需要它），只拦越界。
                    if (position < 0 || (totalLength != null && position > totalLength)) break
                    // 重开取随机访问，避免 skip 扫全文件（moov 在尾部的 MP4）。
                    // A1 记一笔做不到的事：`DataSource` 接口面上**没有** `seek`/`canSeekAt`
                    // （那两个只在 `BaseDataSource` 上，而 `DefaultDataSource.Factory()
                    // .createDataSource()` 的静态类型是 `DataSource`），`ExtractorInput` 同样
                    // 没有 skip/seek——所以"每块一次 SEEK"在 content:// 上仍是一次
                    // openAssetFileDescriptor，就地推进只能把 payload 真读出来（几 GB 白读）。
                    // 要断掉这条风暴得给探测换一个可随机访问的输入，见《任务文档》A1 订正。
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
            android.util.Log.d(
                "FormatProbe",
                "matched=${matched.javaClass.simpleName} expectedMime=$expectedMime expectVideo=$expectVideo " +
                    "formats=${output.formats.size} mimes=${output.formats.map { it.sampleMimeType }}",
            )
            // A1：预算耗尽或读取抛异常，而**一条格式都没拿到** = 拿不准，不是"编码不支持"。
            // 判 Unplayable 会被写进缓存（缓存条件只排除 Unknown），于是能播的文件被永久
            // 记成"没有受支持的视频编码"——无 idx1 的 AVI 扫描每块一次 SEEK，400 轮根本
            // 走不到建索引，正是这条。类注释承诺的"拿不准放行"在这里兑现。
            if (output.formats.isEmpty() &&
                (readThrew || (!output.tracksEnded && reads >= FALLBACK_MAX_READS))
            ) {
                return ProbeOutcome.transient(Result.Unknown)
            }
            val outcome = output.decide(effectiveContainer, expectVideo)
            // 解复用中途失败过：即便算出了结论，也按瞬态处理（不写缓存，下次重探）
            return if (readThrew) {
                ProbeOutcome(outcome.result, ProbeVerdict.NON_DETERMINISTIC)
            } else {
                outcome
            }
            } finally {
                matched?.let { extractor -> runCatching { extractor.release() } }
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /** 认领文件的是哪个解封装器 → 容器显示名（补 MediaStore 地址无扩展名的缺口） */
    private fun containerFromExtractor(extractor: Extractor): String? {
        if (extractor is com.noneplayer.core.media.extract.NpFfmpegExtractor) {
            return extractor.containerDisplayName
        }
        return when (extractor.javaClass.simpleName) {
            "AviExtractor", "Wmv2AviExtractor", "Mp4vAspRewritingExtractor" -> "AVI"
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
    }

    /** 本地文件字节长度；拿不到返回 null（用探测预算兜底）。不抛异常。 */
    private fun localLength(context: Context, uri: Uri): Long? = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { path -> java.io.File(path).takeIf { it.isFile }?.length() }
            "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                fd.length.takeIf { it >= 0 }
            }
            else -> null
        }
    }.getOrNull()

    /**
     * 平台音频解码器三态查询（批次 8 + REV-04/A17）：null=查询异常，不得冒充确定支持/不支持。
     * 采样率/声道缺失时按常见档位查询（与视频查询同源，见 [PlatformDecoderSupport.platformDecoderQuery]）。
     */
    private fun platformAudioDecoderQuery(format: Format): Boolean? =
        PlatformDecoderSupport.platformDecoderQuery(format)

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

    // R3-11/REV-05：internal 仅为同模块纯 JVM 测试可见（零行为变化；可见性偏差已报 Lead）
    internal class ProbeOutput : ExtractorOutput {
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
            // 视频轨专用的固定读取 scratch（惰性分配；REV-05：不随样本 length 增长）
            private var scratch: ByteArray? = null

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
                // 视频轨首样本：固定 scratch 分块自读，只截留前导字节，其余读完即丢。
                // REV-05：禁止 ByteArray(length) 整段分配（大样本会 OOM）；分块只改变
                // 读取粒度，不改变 input 消费语义与返回字节数（EOF 处理与旧实现一致）。
                val buffer = scratch ?: ByteArray(SAMPLE_SCRATCH_BYTES).also { scratch = it }
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, 0, minOf(buffer.size, length - read))
                    if (n == -1) {
                        if (allowEndOfInput) break
                        error("unexpected EOF in sampleData read=$read/$length")
                    }
                    captureHead(head, buffer, 0, n)
                    read += n
                }
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

        /**
         * 探测结论（REV-04/A17 起带确定性分类）：容器/编码名 + 解码器查询（含 §12.9.4 profile 求交）。
         *
         * 关键区分：查询异常 ≠ 确定不支持。以前查询失败按 fail-open 返回「支持」，
         * 后续播放失败又被缓存“定罪”；现在异常一律走 [ProbeVerdict.NON_DETERMINISTIC]，
         * 结果照常放行给引擎，但不写缓存。
         */
        fun decide(containerName: String?, expectVideo: Boolean?): ProbeOutcome {
            val video = formats.firstOrNull { it.isVideoTrack }
            val audio = formats.firstOrNull { it.sampleMimeType?.startsWith("audio/") == true }
            val videoName = MediaNames.videoCodecName(video?.sampleMimeType)
            val audioName = MediaNames.audioCodecName(audio?.sampleMimeType)

            if (video == null) {
                // E231（错扩展名，mp3 改名 .mp4）：expectVideo 只是**扩展名 mime** 的推断
                // （MediaStore 按后缀给 video/mp4），而"认领文件的解封装器已 endTracks、
                // 轨道表里只有音频轨"是内容层事实——两者打架时以内容为准，按音频文件判读，
                // 不再误拦"没有受支持的视频编码"（真机实锤：探测日志 formats=[audio/mpeg]
                // 却因 expectVideo=true 落进下面的瞬态拦截）。tracksEnded 未到（读被截断）
                // 不在此列：那种轨道表可能不全，维持旧语义拿不准。
                // release 构建的 extractor 类名被混淆，不能按认领者身份判——内容证据可靠。
                val contentAudioOnly = tracksEnded && audio != null
                if ((expectVideo == false || contentAudioOnly) && audio != null) {
                    // 纯音频内容（audio/* 预期，或 E231 的内容判定）：音频轨在、且本机解得动 → 可播
                    // A17：平台查询三态——查询异常不能当「支持」，也不能当「不支持」
                    val audioQuery = platformAudioDecoderQuery(audio)
                    val audioDecodable = audioQuery == true ||
                        ffmpegSupports(audio.sampleMimeType) ||
                        npFfmpegAudioSupports(audio)
                    if (audioDecodable) {
                        return ProbeOutcome(
                            Result.Playable(
                                container = containerName,
                                videoCodec = null,
                                audioCodec = audioName,
                                audioChannels = audio.channelCount.takeIf { it > 0 }?.let(::audioLayoutName),
                            ),
                            verdict = if (audioQuery != null) {
                                ProbeVerdict.PLAYABLE
                            } else {
                                ProbeVerdict.NON_DETERMINISTIC
                            },
                        )
                    }
                    // audioCodecUnsupported 不在可缓存的三类结论里：按瞬态处理，下次重探
                    return ProbeOutcome.transient(
                        Result.Unplayable(
                            PlaybackFailure.audioCodecUnsupported(containerName, audioName),
                        ),
                    )
                }
                // 有音频轨却没视频轨：可能是读取被截断导致的轨道表不全，拿不准
                return ProbeOutcome.transient(
                    Result.Unplayable(PlaybackFailure.unknownVideoCodec(containerName, audioName)),
                )
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
            val platformVideoQuery = platformVideoDecoderQuery(video)
            // E242：平台 mime 查询只说明“有候选”，还要对当前 Format 的 profile/level/
            // 位深重新求交。UNKNOWN 放行但不缓存；只有明确 UNSUPPORTED 才摘掉平台链。
            val platformCapability = if (platformVideoQuery == true) {
                runCatching { CodecCapabilityProbe.decoderDecision(video) }.getOrNull()
            } else {
                null
            }
            val platformCapabilityUnsupported = platformCapability?.verdict == CapabilityVerdict.UNSUPPORTED
            val platformVideo = platformVideoQuery == true && !platformCapabilityUnsupported
            val ffmpegVideo = ffmpegSupports(video.sampleMimeType)
            val bitmapVideo = bitmapRendererSupports(video.sampleMimeType)
            val legacyVideo = legacySupports(video.sampleMimeType)
            val npFfmpegVideo = npFfmpegVideoSupports(video.sampleMimeType)
            // A11：有视频轨时也要查音频解码器。H.264 + WMA/DTS/TrueHD 这类组合
            // 以前一律判 Playable，起播后无声或音频渲染器报错，用户拿不到原因。
            // A17：只有平台**明确**说没有时才敢声明 audioUnsupported（查询异常不冒充）。
            val audioQuery = audio?.let { platformAudioDecoderQuery(it) }
            val audioUnsupported = audio != null &&
                audioQuery == false &&
                !ffmpegSupports(audio.sampleMimeType) &&
                !npFfmpegAudioSupports(audio)
            android.util.Log.d(
                "FormatProbe",
                "decide platform=$platformVideo ffmpeg=$ffmpegVideo bitmap=$bitmapVideo " +
                    "legacy=$legacyVideo npff=$npFfmpegVideo",
            )
            if (!platformVideo && !ffmpegVideo && !bitmapVideo && !legacyVideo && !npFfmpegVideo) {
                // A17：平台查询异常时「没有解码器」可能只是没查到，不能冒充确定不支持
                return if (platformVideoQuery == null) {
                    ProbeOutcome.transient(Result.Unknown)
                } else if (platformCapabilityUnsupported) {
                    ProbeOutcome(
                        Result.Unplayable(
                            PlaybackFailure.videoCapabilityUnsupported(
                                container = containerName,
                                videoCodec = videoName,
                                audioCodec = audioName,
                                detail = platformCapability?.reasons?.joinToString("; "),
                            ),
                        ),
                        ProbeVerdict.VIDEO_CODEC_UNSUPPORTED,
                    )
                } else {
                    ProbeOutcome(
                        Result.Unplayable(
                            PlaybackFailure.videoCodecUnsupported(containerName, videoName, audioName),
                        ),
                        ProbeVerdict.VIDEO_CODEC_UNSUPPORTED,
                    )
                }
            }
            // §12.9.4：有解码器，但档次可能不被设备解码器支持（mp4v ASP vs Simple-only）
            profileMismatch(video, containerName, videoName, audioName)?.let { return it }
            val queryFailed = platformVideoQuery == null ||
                platformCapability?.verdict == CapabilityVerdict.UNKNOWN ||
                (audio != null && audioQuery == null)
            return ProbeOutcome(
                Result.Playable(
                    container = containerName,
                    videoCodec = videoName,
                    audioCodec = audioName,
                    hdr = hdr,
                    audioChannels = audioLayout,
                    // 平台没有该编码的解码器、只能靠 FFmpeg 或 MJPEG 位图出画时，同样是"仅软解可用"，
                    // 也要给 C21 前置提示（否则用户只会觉得"莫名其妙很卡/很烫"）。
                    // 不用再判 `ffmpegVideo || bitmapVideo`：上面 unsupported 的早退已保证三者至少
                    // 其一成立，走到这里 `!platformVideo` 就必然是两条软解之一出画。
                    softwareVideoOnly = onlySoftwareDecoder(video) || !platformVideo,
                    audioUnsupported = audioUnsupported,
                ),
                verdict = if (queryFailed) ProbeVerdict.NON_DETERMINISTIC else ProbeVerdict.PLAYABLE,
            )
        }

        /**
         * MPEG-4 ASP 档次拦截（§12.9.4 + A17）：判读码流需要的 profile，与**将被选中的**
         * 那个 mp4v 解码器声明的 `profileLevels` 求交；全设备并集只在拿不到选中信息时兜底。
         *
         * 三态：false → 放行平台链；null → 查询异常，放行（fail-open）但结论不缓存；
         * true → 返回确定的「档次不支持」，可缓存。
         */
        private fun profileMismatch(
            video: Format,
            containerName: String?,
            videoName: String?,
            audioName: String?,
        ): ProbeOutcome? {
            if (video.sampleMimeType != MIME_MP4V) return null
            val header = video.initializationData.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: firstVideoSampleHead
                ?: return null
            return when (Mp4vAspGate.needsDedicatedDecoder(header, video)) {
                false -> null
                // 设备查询异常：不冒充「设备不支持 ASP」，也不缓存这条结论
                null -> ProbeOutcome.transient(Result.Unknown)
                true -> {
                    // 诊断展示用：设备上所有 mp4v 解码器声明的 profile 集合
                    val deviceProfiles = Mp4vAspGate.deviceProfiles()
                    ProbeOutcome(
                        Result.Unplayable(
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
                        ),
                        ProbeVerdict.VIDEO_CODEC_UNSUPPORTED,
                    )
                }
            }
        }
    }

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
     * 是否**只有软件解码器**可用（C21）。
     *
     * A10：判定下沉到 [PlatformDecoderSupport.hasHardwareDecoder]——与播放侧兼容档的
     * MediaCodecSelector 用同一规则（按 `isHardwareAccelerated` 过滤），避免
     * 「探测说硬解可用、兼容档却把平台解码器摘掉」的分叉。API 29 以下保持旧语义
     * （有平台解码器就不提示"仅软解"）。
     */
    private fun onlySoftwareDecoder(format: Format): Boolean =
        !PlatformDecoderSupport.hasHardwareDecoder(format)

    /** 平台视频解码器三态查询（REV-04/A17）：null=查询异常，不得冒充确定支持/不支持。 */
    private fun platformVideoDecoderQuery(format: Format): Boolean? =
        PlatformDecoderSupport.platformDecoderQuery(format)

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

    /**
     * libnplegacy 是否认这个 mime。加载失败是 false，不能像
     * [PlatformDecoderSupport.hasPlatformDecoder] 那样 fail-open。表外 mime 不触发加载。
     */
    private fun legacySupports(mime: String?): Boolean =
        runCatching { LegacyDecoderLibrary.supports(mime) }.getOrDefault(false)

    /**
     * E204 白名单批：libnpffmpeg（NextLib 共享 libavcodec 后端）能否解这个 mime。
     * 与播放链同源——NpFfmpegVideoRenderer/NpFfmpegAudioRenderer 认领的就是这张表，
     * 探测与播放不会分叉。加载失败时表查询返回 null（fail-closed，不冒充可解）。
     */
    private fun npFfmpegVideoSupports(mime: String?): Boolean =
        mime != null && NpFfmpegCodecs.videoCodecName(mime) != null

    private fun npFfmpegAudioSupports(format: androidx.media3.common.Format?): Boolean {
        val mime = format?.sampleMimeType ?: return false
        // 与播放链同一道闸：WMA 族没有完整 WAVEFORMATEX 就取不到 blockAlign，解码器
        // 无法 open（Media3Avi 只发 codec data 的路径就在这里诚实判「音频不支持」）。
        if (NpFfmpegCodecs.isWmaFamily(mime) &&
            !NpFfmpegCodecs.hasWaveFormatEx(format.initializationData)
        ) {
            return false
        }
        return NpFfmpegCodecs.audioCodecName(mime, format.initializationData) != null
    }
}

// ---------------------------------------------------------------------------
// R3-14 / REV-04 / A17：探测结论的确定性分类与缓存
// ---------------------------------------------------------------------------

/**
 * seekable [FileChannel] 之上的只读 [DataReader]：`DefaultExtractorInput` 只会向前
 * read（外加 peek 窗口），定位由探测循环直接 `channel.position()`——探测侧因此
 * 不需要 DataSource 的 open/close 语义（A1 收口）。
 */
@OptIn(UnstableApi::class)
private class ChannelReader(private val channel: FileChannel) : DataReader {
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = channel.read(java.nio.ByteBuffer.wrap(target, offset, length))
        return if (n < 0) C.RESULT_END_OF_INPUT else n
    }
}

/**
 * 结论的确定性分类：决定它能否被缓存。
 *
 * [FormatProbe.Result] 本身分不出「明确的结论」与「查询异常下算出的结论」——
 * `PlaybackFailure.reason` 是自由文本，不能当分类依据。A17 的「同一份错误结论二次定罪」
 * 就是缺了这层分类：只缓存 Playable / 容器不支持 / 视频编码（含档次）不支持。
 */
internal enum class ProbeVerdict {
    PLAYABLE,
    CONTAINER_UNSUPPORTED,
    VIDEO_CODEC_UNSUPPORTED,

    /** 拿不准：Unknown、瞬态 I/O、读取/sniff 抛异常、设备查询异常 —— 一律不缓存。 */
    NON_DETERMINISTIC,
}

/** 一次探测的结论 + 确定性分类（缓存的唯一入场券）。 */
internal data class ProbeOutcome(
    val result: FormatProbe.Result,
    val verdict: ProbeVerdict,
) {
    val cacheable: Boolean get() = verdict != ProbeVerdict.NON_DETERMINISTIC

    companion object {
        /** 拿不准的结论：放行给引擎，但绝不进缓存。 */
        fun transient(result: FormatProbe.Result): ProbeOutcome =
            ProbeOutcome(result, ProbeVerdict.NON_DETERMINISTIC)
    }
}

/** 缓存条目：确定性结论 + 它成立时所依据的文件指纹。 */
internal data class ProbeCacheEntry(
    val sizeBytes: Long,
    val lastModifiedSec: Long,
    val outcome: ProbeOutcome,
)

/**
 * uri → 探测结论的 LRU 缓存（纯 Kotlin，可在 JVM 单测里验证；不依赖 android.util.LruCache）。
 *
 * 只收 [ProbeOutcome.cacheable] 的条目：`put` 对瞬态结论直接拒绝并返回 false。
 * 同一条目并发读写走 `@Synchronized`；缓存本身只是省一次 0.5–1.5s 的探测，不值得无锁化。
 */
internal class ProbeResultCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = object : LinkedHashMap<String, ProbeCacheEntry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ProbeCacheEntry>,
        ): Boolean = size > capacity
    }

    @Synchronized
    fun get(uri: String): ProbeCacheEntry? = entries[uri]

    /** 返回是否真的写入（瞬态结论会被门拒绝）。 */
    @Synchronized
    fun put(uri: String, entry: ProbeCacheEntry): Boolean {
        if (!entry.outcome.cacheable) return false
        entries[uri] = entry
        return true
    }

    @Synchronized
    fun invalidate(uri: String) {
        entries.remove(uri)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** 上限按"一个文件夹一季"量级给 64 条；结论本身是几个字符串，不心疼。 */
        const val DEFAULT_CAPACITY = 64
    }
}
