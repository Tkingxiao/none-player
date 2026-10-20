package com.noneplayer.core.media.probe

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * R3 独立验收（REV-05 有界读）测试基建。
 *
 * 全部替身都是纯 JVM 的：不触碰 android.media / android.util，因此可以直接跑在
 * `testDebugUnitTest` 里（与既有 [com.noneplayer.core.media.extract.Wmv2AudioMappingTest] 同口径）。
 */

/** 确定性字节源：第 i 字节 = ((i * 37 + 11) % 251)，非法下标除外。 */
internal object PatternBytes {

    fun byteAt(index: Long): Byte = ((index * 37L + 11L) % 251L).toByte()

    fun slice(from: Int, count: Int): ByteArray =
        ByteArray(count) { byteAt(from.toLong() + it.toLong()) }
}

/**
 * 可编程 [DataReader]：记录**每一次** read 请求的长度与总消费字节，用于验收
 * 「有没有 length 级的大请求 / 大分配」。
 *
 * - [chunkLimit]：单次最多吐多少字节（模拟慢分段到达）；
 * - [chunkSizes]：循环使用的分段尺寸（1..N 字节），优先于 [chunkLimit]；
 * - [source]：非空时按该数组吐字节（短样本字节等价断言用），否则用 [PatternBytes]。
 */
@OptIn(UnstableApi::class)
internal class RecordingDataReader(
    private val availableBytes: Long,
    private val chunkLimit: Int = Int.MAX_VALUE,
    private val chunkSizes: IntArray? = null,
    private val source: ByteArray? = null,
) : DataReader {

    /** 每次 read 的请求长度（按调用顺序） */
    val requests: MutableList<Int> = mutableListOf()

    /** 实际吐出的总字节数（= 输入被消费的字节数） */
    var consumedBytes: Long = 0L
        private set

    /** 单次 read 请求过的最大长度——旧实现会等于样本 length，有界实现必须远小于它 */
    var maxRequested: Int = 0
        private set

    var readCalls: Int = 0
        private set

    private var cycle = 0

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        readCalls++
        require(length > 0) { "read 请求长度必须为正，实际 $length" }
        requests += length
        maxRequested = maxOf(maxRequested, length)
        if (consumedBytes >= availableBytes) return C.RESULT_END_OF_INPUT
        var serve = minOf(length.toLong(), availableBytes - consumedBytes).toInt()
        val sizes = chunkSizes
        if (sizes != null && sizes.isNotEmpty()) {
            serve = minOf(serve, sizes[cycle % sizes.size])
            cycle++
        } else {
            serve = minOf(serve, chunkLimit)
        }
        for (i in 0 until serve) {
            target[offset + i] = byteAt(consumedBytes + i.toLong())
        }
        consumedBytes += serve
        return serve
    }

    private fun byteAt(index: Long): Byte =
        source?.let { array ->
            require(index < array.size) { "按 source 读取越界：$index >= ${array.size}" }
            array[index.toInt()]
        } ?: PatternBytes.byteAt(index)
}

/**
 * 反射访问 [FormatProbe] 内部的首个视频样本前导字节。
 *
 * 生产实现目前把 `ProbeOutput` 设为 `private`，本任务约束「不改生产代码」，因此验收先用反射取证；
 * 待 REV-05 实现落地（实现方通常会把它改成 internal 供测试使用）后，本类仍按类名+结构自适应，
 * 值不变。若彻底找不到该类，说明实现缺口仍未落地，直接以清晰失败信息报红。
 */
@OptIn(UnstableApi::class)
internal class ProbeOutputHarness private constructor(
    private val output: ExtractorOutput,
    private val outputClass: Class<*>,
) {

    fun videoTrack(): TrackOutput = output.track(0, C.TRACK_TYPE_VIDEO)

    /** 首个视频样本的前导字节（HEAD_CAP 上限）；未固化时为 null */
    fun firstVideoSampleHead(): ByteArray? {
        val getter = outputClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType == ByteArray::class.java
        } ?: throw AssertionError(
            "REV-05 实现缺口：ProbeOutput 未提供首个视频样本前导字节（firstVideoSampleHead）",
        )
        getter.isAccessible = true
        return getter.invoke(output) as ByteArray?
    }

    companion object {

        private const val PROBE_OUTPUT_CLASS = "com.noneplayer.core.media.probe.FormatProbe\$ProbeOutput"

        fun create(): ProbeOutputHarness {
            val loader = ProbeOutputHarness::class.java.classLoader
            // initialize=false：避免触发 object FormatProbe 的静态初始化（LruCache 等 Android 类在 JVM 单测不可用）
            val direct = runCatching { Class.forName(PROBE_OUTPUT_CLASS, false, loader) }.getOrNull()
            val classToUse = direct ?: runCatching {
                Class.forName("com.noneplayer.core.media.probe.FormatProbe", false, loader)
                    .declaredClasses
                    .firstOrNull { it.simpleName == "ProbeOutput" }
            }.getOrNull() ?: throw AssertionError(
                "REV-05 实现缺口：找不到 FormatProbe 内部的 ProbeOutput 输出壳，无法验收有界读",
            )
            val ctor = classToUse.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                ?: throw AssertionError("REV-05 实现缺口：ProbeOutput 没有无参构造")
            ctor.isAccessible = true
            return ProbeOutputHarness(ctor.newInstance() as ExtractorOutput, classToUse)
        }
    }
}

/** 改写器验收用的下游 [ExtractorOutput]：记录视频轨拿到的全部下发内容。 */
@OptIn(UnstableApi::class)
internal class RecordingExtractorOutput : ExtractorOutput {

    val tracks: MutableList<RecordingTrackOutput> = mutableListOf()
    var lastVideoTrack: RecordingTrackOutput? = null
        private set
    var endTracksCount: Int = 0
        private set

    override fun track(id: Int, type: Int): TrackOutput {
        val track = RecordingTrackOutput()
        tracks += track
        if (type == C.TRACK_TYPE_VIDEO) lastVideoTrack = track
        return track
    }

    override fun endTracks() {
        endTracksCount++
    }

    override fun seekMap(seekMap: SeekMap) = Unit
}

/** 下游 [TrackOutput] 替身：按调用顺序累计所有 sampleData 字节，并记录分块大小/元数据。 */
@OptIn(UnstableApi::class)
internal class RecordingTrackOutput : TrackOutput {

    data class SampleCall(val requestedSize: Int, val sampleDataPart: Int, val bytes: ByteArray)

    data class MetadataCall(
        val timeUs: Long,
        val flags: Int,
        val size: Int,
        val offset: Int,
        val cryptoData: TrackOutput.CryptoData?,
    )

    val formats: MutableList<Format> = mutableListOf()
    val sampleCalls: MutableList<SampleCall> = mutableListOf()
    val metadataCalls: MutableList<MetadataCall> = mutableListOf()
    val forwardedBytes: ByteArrayOutputStream = ByteArrayOutputStream()

    /** 单次下发（含 DataReader 透传请求）的最大长度——用于验收「剩余分块转发」 */
    var maxSampleRequestLength: Int = 0
        private set

    override fun format(format: Format) {
        formats += format
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        maxSampleRequestLength = maxOf(maxSampleRequestLength, length)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) break
                throw java.io.EOFException("下游透传读取意外 EOF")
            }
            read += n
        }
        sampleCalls += SampleCall(length, sampleDataPart, buffer.copyOf(read))
        forwardedBytes.write(buffer, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        maxSampleRequestLength = maxOf(maxSampleRequestLength, length)
        // 不能用 ParsableByteArray.readBytes(...)：media3 1.11 的 legacy 方法内部会做
        // “是否跑在测试里”的断言探测，纯 JVM 单测下查不到系统属性会抛 NPE（环境问题）。
        // 直接按当前 position 取底层字节，语义等价且不触发断言。
        val start = data.position
        val bytes = data.data.copyOfRange(start, start + length)
        sampleCalls += SampleCall(length, sampleDataPart, bytes)
        forwardedBytes.write(bytes, 0, length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        metadataCalls += MetadataCall(timeUs, flags, size, offset, cryptoData)
    }
}

/**
 * 包装器验收用的「内层 Extractor」：只捕获 [init] 收到的下游输出壳
 * （即 `Mp4vAspRewritingExtractor` 内部创建的 `RewritingOutput`），不做任何读取。
 */
@OptIn(UnstableApi::class)
internal class CapturingInnerExtractor : Extractor {

    var output: ExtractorOutput? = null
        private set

    override fun sniff(input: ExtractorInput): Boolean = false

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        Extractor.RESULT_END_OF_INPUT

    override fun seek(position: Long, timeUs: Long) = Unit

    override fun release() = Unit
}
