package com.noneplayer.core.media.extract

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.noneplayer.core.media.AviChunk
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.ffmpeg.NpFfmpegCodecs
import java.io.EOFException
import java.io.IOException
import kotlin.math.abs

/**
 * 只认「内层 AviExtractor 会丢掉的 WMV 视频家族轨」（WMV2/WMV1/WMV3/VC-1，白名单见
 * [wmvVideoMimeForFourcc]）。
 *
 * 白名单外 fourcc 的 AVI 在 [sniff] 返回 false，仍由 [com.homesoft.exo.extractor.AviExtractorsFactory] 解。
 * 时间戳用 strh 的 dwScale/dwRate。索引优先级：idx1 → **OpenDML 超级索引**（strl 里的
 * `indx` 指到 movi 内/外的 `ix##` 块，条目 DELTAFRAME 位给真关键帧——E204 的「可拖待超级
 * 索引」）→ 全量扫描（关键帧是猜的，不可拖）。
 */
@OptIn(UnstableApi::class)
class Wmv2AviExtractor : Extractor {

    private enum class Phase { HEADER, INDEX, VERIFY, SUPER, BUILD, EMIT, SAMPLE }

    /** 超级索引路径的子步：读一个 ix## 块 → 验基址（读首条目标 chunk 的 fourcc 对账） */
    private enum class SuperStep { READ_IX, VERIFY }

    /**
     * strl 里的 `indx`（AVI_INDEX_OF_INDEXES）：每条 entry 16 字节里的前 8 字节是一个
     * `ix##` 块的**绝对文件偏移**。[chunkId] 是本流的媒体 chunk id（如 `'00dc'`），
     * 读 ix## 块时用来对账。
     */
    private class SuperIndexRef(val chunkId: Int, val offsets: LongArray)

    /**
     * 超级索引路径上单条流的条目收集器：`ix##` 块逐块读入、按块落进这里，最后按绝对
     * 偏移归并进样本表。[delta] 是 OpenDML 的 AVISTDINDEX_DELTAFRAME 位（置位 = 差分帧，
     * 清零 = 关键帧——与 idx1 的 0x10「置位才是关键帧」方向相反）。
     */
    private class SuperCollector {
        var offset = LongArray(1024)
            private set
        var size = IntArray(1024)
            private set
        var delta = BooleanArray(1024)
            private set
        var count = 0
            private set

        fun add(off: Long, sz: Int, d: Boolean) {
            if (count == offset.size) {
                offset = offset.copyOf(count * 2)
                size = size.copyOf(count * 2)
                delta = delta.copyOf(count * 2)
            }
            offset[count] = off
            size[count] = sz
            delta[count] = d
            count++
        }
    }

    private class StreamInfo(
        val index: Int,
        /** 本提取器认领的视频 mime（WMV 家族 fourcc）；null = 不认领（fourcc 不在白名单） */
        val videoMime: String?,
        val audioMime: String?,
        val scale: Long,
        val rate: Long,
        val length: Long,
        val width: Int,
        val height: Int,
        val extra: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        /** WAVEFORMATEX.nBlockAlign：PCM 一帧的字节数，也是 WMA 超帧的最大输入尺寸 */
        val blockAlign: Int,
        /** PCM 位深（WAVEFORMATEX.wBitsPerSample），非 PCM 为 0 */
        /** E204：非视频流的整段 WAVEFORMATEX——WMA 族解码器 open 必需的 extradata */
        val audioExtra: ByteArray?,
        val bitsPerSample: Int,
        /** OpenDML 超级索引（strl 的 indx）；null = 该流没有或不可用 */
        val superIndex: SuperIndexRef? = null,
    )

    /**
     * chunk 索引的平铺存储（idx1 与扫描中间表共用）。[ckid] 存 fourcc 的**打包值**而不是
     * 字符串——扫描一条两小时视频是十几万次，每次 `String(b, o, 4)` 就是十几万个短命对象
     * （A1）。行式对象（每块一个实例 + List 引用）换成四个原生数组：十几万条不再逐条装箱。
     */
    private class IdxTable(capacity: Int = 1024) {
        var ckid = IntArray(capacity)
            private set
        var flags = LongArray(capacity)
            private set
        var offset = LongArray(capacity)
            private set
        var size = IntArray(capacity)
            private set
        var count = 0
            private set

        fun add(ck: Int, fl: Long, off: Long, sz: Int) {
            if (count == ckid.size) {
                ckid = ckid.copyOf(count * 2)
                flags = flags.copyOf(count * 2)
                offset = offset.copyOf(count * 2)
                size = size.copyOf(count * 2)
            }
            ckid[count] = ck
            flags[count] = fl
            offset[count] = off
            size[count] = sz
            count++
        }

        fun clear() {
            count = 0
        }
    }

    /**
     * materialize 之后的样本表：五列平铺（流号/偏移/大小/时间/关键帧），播放期常驻，
     * seek 的二分直接在列上进行。
     */
    private class SampleTable(capacity: Int = 1024) {
        var stream = IntArray(capacity)
            private set
        var offset = LongArray(capacity)
            private set
        var size = IntArray(capacity)
            private set
        var timeUs = LongArray(capacity)
            private set
        var keyframe = BooleanArray(capacity)
            private set
        var count = 0
            private set

        fun add(s: Int, off: Long, sz: Int, ts: Long, key: Boolean) {
            if (count == stream.size) {
                stream = stream.copyOf(count * 2)
                offset = offset.copyOf(count * 2)
                size = size.copyOf(count * 2)
                timeUs = timeUs.copyOf(count * 2)
                keyframe = keyframe.copyOf(count * 2)
            }
            stream[count] = s
            offset[count] = off
            size[count] = sz
            timeUs[count] = ts
            keyframe[count] = key
            count++
        }

        fun clear() {
            count = 0
        }
    }

    private var output: ExtractorOutput? = null
    private var phase = Phase.HEADER
    private var riffChecked = false
    private val streams = mutableListOf<StreamInfo>()
    private var moviFccPos = -1L
    private var moviDataStart = -1L
    private var moviEnd = -1L
    private var inputLengthCache = AviChunk.UNKNOWN_LENGTH
    private val rawIndex = IdxTable()
    private var baseTry = 0
    private var scanCursor = -1L
    private val scanned = IdxTable()

    /** 扫描路径上"这条流见过没有"——用集合代替 `scanned.none { }` 的 O(n²) 线性回看。 */
    private val scannedStreams = mutableSetOf<Int>()
    private val samples = SampleTable()

    /** emit 时预构建的关键帧寻点表（时间升序），getSeekPoints 在它上面二分 */
    private var seekTimes = LongArray(0)
    private var seekOffsets = LongArray(0)

    /** 关键帧信息是否可信：只有 idx1 的 flags 带真位；扫描路径的"首帧即关键帧"是猜的。 */
    private var keyframesReliable = false

    /** 样本按文件偏移是否单调（idx1 是解码顺序，不保证单调；不单调就不能二分）。 */
    private var samplesOffsetSorted = true

    /** 关键帧寻点表里是否真有关键帧位（扫描路径"首帧即关键帧"是猜的，不据此宣称可拖） */
    private var hasKeyframes = false

    /** idx1 的 16 字节记录位（流式解析复用，不整段分配 payload） */
    private val idxRecord = ByteArray(16)

    // ---- OpenDML 超级索引路径（indx → ix##）----
    /** 带可用超级索引的流（streams 下标），恒按流序推进 */
    private val superOrder = mutableListOf<Int>()
    /** 每流一个收集器，key = streams 下标 */
    private val superData = mutableMapOf<Int, SuperCollector>()
    private var superStreamPos = 0
    private var superIxPos = 0
    private var superStep = SuperStep.READ_IX
    /** 基址是否已对过账（首个 ix 块的候选基址命中 dwChunkId 之后恒用「写值+增量」） */
    private var superVerified = false
    /** 工作基址 - ix 头写的 qwBaseOffset（对账时定下，后续 ix 块沿用） */
    private var superBaseDelta = 0L
    private var superTry = 0
    private var ixReadAt = -1L
    private var superWrittenBase = 0L
    private var superChunkId = 0
    private var superEntryCount = 0
    /** 当前 ix 块的条目临时区（验证通过才落进 SuperCollector，复用不逐块分配） */
    private var superTempOff = LongArray(0)
    private var superTempSize = IntArray(0)
    /** ix 头读取位与单条 entry 位（每块复用） */
    private val ixHeader20 = ByteArray(20)
    private val ixEntry8 = ByteArray(8)
    private val four4 = ByteArray(4)

    /** chunk 头读取位（SAMPLE 阶段每帧复用） */
    private val header8 = ByteArray(8)

    /** 样本载荷 scratch：按需倍增，不每帧分配 */
    private var sampleScratch = ByteArray(64 * 1024)

    /** 扫描阶段的 payload 跳读 scratch（固定 64KB，循环用） */
    private val buildSkipScratch = ByteArray(64 * 1024)
    private val tracks = mutableMapOf<Int, TrackOutput>()
    private var sampleCursor = 0
    private var emitted = false

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            peekIsWmvFamily(input)
        } catch (_: Throwable) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return when (phase) {
            Phase.HEADER -> readHeader(input, seekPosition)
            Phase.INDEX -> readIndex(input, seekPosition)
            Phase.VERIFY -> verifyBase(input, seekPosition)
            Phase.SUPER -> superIndexStep(input, seekPosition)
            Phase.BUILD -> buildByScan(input, seekPosition)
            Phase.EMIT -> emit(seekPosition)
            Phase.SAMPLE -> readSample(input, seekPosition)
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (samples.count == 0) {
            resetParse()
            return
        }
        // 拖动是一次 seek 一拍，旧写法 `indexOfFirst` 在播放线程上对十几万条做线性扫。
        // idx1 是解码顺序、不保证偏移单调，所以只有确认单调时才二分（平铺列上直接二分）。
        sampleCursor = if (samplesOffsetSorted) {
            var lo = 0
            var hi = samples.count
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (samples.offset[mid] < position) lo = mid + 1 else hi = mid
            }
            lo
        } else {
            var at = 0
            while (at < samples.count && samples.offset[at] < position) at++
            at
        }
    }

    override fun release() = Unit

    private fun resetParse() {
        phase = Phase.HEADER
        riffChecked = false
        streams.clear()
        moviFccPos = -1L
        moviDataStart = -1L
        moviEnd = -1L
        inputLengthCache = AviChunk.UNKNOWN_LENGTH
        rawIndex.clear()
        baseTry = 0
        scanCursor = -1L
        scanned.clear()
        scannedStreams.clear()
        samples.clear()
        seekTimes = LongArray(0)
        seekOffsets = LongArray(0)
        keyframesReliable = false
        hasKeyframes = false
        samplesOffsetSorted = true
        superOrder.clear()
        superData.clear()
        superStreamPos = 0
        superIxPos = 0
        superStep = SuperStep.READ_IX
        superVerified = false
        superBaseDelta = 0L
        superTry = 0
        ixReadAt = -1L
        tracks.clear()
        sampleCursor = 0
        emitted = false
    }

    private fun readHeader(input: ExtractorInput, holder: PositionHolder): Int {
        if (!riffChecked) {
            val head = ByteArray(12)
            readFully(input, head)
            if (fcc(head, 0) != "RIFF" || fcc(head, 8) != "AVI ") {
                return Extractor.RESULT_END_OF_INPUT
            }
            riffChecked = true
        }
        val hdr = ByteArray(8)
        readFully(input, hdr)
        val chunkStart = input.position - 8
        val id = fcc(hdr, 0)
        val size = u32(hdr, 4)
        if (id == "LIST") {
            // A1：LIST 是**容器**不是叶子，不照声明 size 拒绝。正片 `movi` 的 size 等于
            // 4 加整段媒体数据，OpenDML 更直接写 0xFFFFFFFF——旧写法在这里就抛，探测侧
            // 拿到 0 条 format 判 Unplayable，播放侧报"封装格式暂无解封装支持"。
            // 上限只留给真正按 size 分配内存的地方（hdrl payload、idx1、样本）。
            val type = ByteArray(4)
            readFully(input, type)
            val listType = fcc(type, 0)
            if (size < 4) throw IOException("AVI list too small")
            when (listType) {
                "hdrl" -> {
                    if (size > MAX_CHUNK) throw IOException("AVI hdrl too large: $size")
                    val payloadLen = (size - 4).toInt()
                    val payload = ByteArray(payloadLen)
                    if (payloadLen > 0) readFully(input, payload)
                    streams.clear()
                    streams.addAll(parseHdrl(payload))
                    skipPad(input, size)
                }
                "movi" -> {
                    moviFccPos = input.position - 4
                    moviDataStart = input.position
                    val length = inputLength(input)
                    moviEnd = AviChunk.listEnd(chunkStart, size, length)
                    if (streams.none { it.videoMime != null }) return Extractor.RESULT_END_OF_INPUT
                    if (length != AviChunk.UNKNOWN_LENGTH && length > 0L && moviEnd >= length) {
                        // movi 已经是文件尾：idx1（在 movi 之后）不可能存在，不再 seek 到
                        // moviEnd 白读一次 EOF——E227 真机实锤探测驱动的 SEEK 守卫把「恰好
                        // 到文件尾」当终点，0 格式 → 每次打开都误弹「编码不支持」面板。
                        // 已知长度时直接进超索引/扫描；长度未知才保留 EOF 探测。
                        return startSuperIndexOrScan(holder)
                    }
                    phase = Phase.INDEX
                    holder.position = moviEnd
                    return Extractor.RESULT_SEEK
                }
                else -> {
                    // 已读过 4 字节的 list type：跳过**剩余** payload
                    // （E140 真机采出：先前写成 `size + (size - 4)`，seek 直接越过 movi，
                    //  整片零格式——WMV2 全灭。算术提进 AviChunk 并有单测钉着。）
                    val at = AviChunk.nextAfterUnknownList(input.position, size)
                    if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
                    holder.position = at
                    return Extractor.RESULT_SEEK
                }
            }
        } else {
            // 叶子 chunk 在这里只是**跳过**、不按 size 分配，所以同样不照声明值拒绝
            // （正片里一个巨大的 JUNK/对齐块是合法形态；idx1 走 readIndex，那里才要上限）
            val at = nextChunkPos(input.position, size)
            if (at <= input.position) return Extractor.RESULT_END_OF_INPUT
            holder.position = at
            return Extractor.RESULT_SEEK
        }
        return Extractor.RESULT_CONTINUE
    }

    /** 跳过一段 payload 后的位置：夹到文件长度，未知长度时保持算术值。 */
    private fun nextChunkPos(position: Long, payloadSize: Long): Long {
        val length = inputLengthCache
        return (position + payloadSize + pad(payloadSize)).let { raw ->
            if (length == AviChunk.UNKNOWN_LENGTH || length <= 0L) raw else raw.coerceAtMost(length)
        }
    }

    private fun inputLength(input: ExtractorInput): Long {
        val length = runCatching { input.length.toLong() }.getOrDefault(C.LENGTH_UNSET.toLong())
        inputLengthCache = if (length == C.LENGTH_UNSET.toLong()) AviChunk.UNKNOWN_LENGTH else length
        return inputLengthCache
    }

    private fun readIndex(input: ExtractorInput, holder: PositionHolder): Int {
        val hdr = try {
            val bytes = ByteArray(8)
            readFully(input, bytes)
            bytes
        } catch (_: EOFException) {
            return startSuperIndexOrScan(holder)
        }
        val id = fcc(hdr, 0)
        val size = u32(hdr, 4)
        if (id == "idx1" && size in 16 until MAX_CHUNK) {
            // idx1 直接流式解析进平铺表：两小时正片的 idx1 是 3MB 级，旧写法整段
            // payload + 逐条 RawIdx 对象（十几万个）两份驻留
            rawIndex.clear()
            var remaining = size
            while (remaining >= 16) {
                readFully(input, idxRecord)
                rawIndex.add(
                    AviChunk.fourcc(idxRecord, 0),
                    u32(idxRecord, 4),
                    u32(idxRecord, 8),
                    u32(idxRecord, 12).toInt(),
                )
                remaining -= 16
            }
            if (rawIndex.count == 0) return startSuperIndexOrScan(holder)
            baseTry = 0
            phase = Phase.VERIFY
            return Extractor.RESULT_CONTINUE
        }
        return startSuperIndexOrScan(holder)
    }

    /**
     * 无 idx1（或 idx1 对不上账）时的第二级索引：hdrl 各流 strl 里的 `indx` 超级索引
     * 指向 `ix##` 块，条目带真关键帧位。任一流带可用 indx 就走这条路；一个都没有才
     * 回全量扫描。走法：逐流逐块 seek 到 indx 记的绝对偏移读 ix##，基址对账后条目落
     * 进每流收集器，全部读完按偏移归并（materializeSuper）。
     */
    private fun startSuperIndexOrScan(holder: PositionHolder): Int {
        superOrder.clear()
        superData.clear()
        streams.forEachIndexed { i, s ->
            if (s.superIndex != null && s.superIndex.offsets.isNotEmpty()) superOrder.add(i)
        }
        if (superOrder.isEmpty()) return startScan(holder)
        superStreamPos = 0
        superIxPos = 0
        superStep = SuperStep.READ_IX
        superVerified = false
        superBaseDelta = 0L
        superTry = 0
        ixReadAt = streams[superOrder[0]].superIndex!!.offsets[0]
        phase = Phase.SUPER
        holder.position = ixReadAt
        return Extractor.RESULT_SEEK
    }

    private fun startScan(holder: PositionHolder): Int {
        scanned.clear()
        scannedStreams.clear()
        scanCursor = moviDataStart
        phase = Phase.BUILD
        holder.position = moviDataStart
        return Extractor.RESULT_SEEK
    }

    private fun verifyBase(input: ExtractorInput, holder: PositionHolder): Int {
        val bases = longArrayOf(moviFccPos, moviDataStart, 0L)
        if (baseTry >= bases.size) return startSuperIndexOrScan(holder)
        val at = bases[baseTry] + rawIndex.offset[0]
        if (at < 0) {
            baseTry++
            return Extractor.RESULT_CONTINUE
        }
        if (input.position != at) {
            holder.position = at
            return Extractor.RESULT_SEEK
        }
        val id = ByteArray(4)
        readFully(input, id)
        if (AviChunk.fourcc(id, 0) == rawIndex.ckid[0]) {
            // idx1 的 flags 带真关键帧位，这条路径上 SeekMap 才敢说"可拖"
            materialize(bases[baseTry], rawIndex, fromIndex = true)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        baseTry++
        return Extractor.RESULT_CONTINUE
    }

    // ---- OpenDML 超级索引（Phase.SUPER）----

    private fun superIndexStep(input: ExtractorInput, holder: PositionHolder): Int = when (superStep) {
        SuperStep.READ_IX -> readSuperIxChunk(input, holder)
        SuperStep.VERIFY -> verifySuperBase(input, holder)
    }

    /**
     * 读一个 `ix##` 块：8 字节块头 + 20 字节 ix 头（wLongsPerEntry=2、
     * bIndexType=AVI_INDEX_OF_CHUNKS、nEntriesInUse、dwChunkId、qwBaseOffset、保留位）
     * + n 条 8 字节 entry（相对偏移 + 大小|DELTAFRAME 位）进临时区。任何不合规或读半截
     * 都放弃整条超索引路径回扫描——超索引只是「省一次全扫」的加速器，不值得为它猜。
     */
    private fun readSuperIxChunk(input: ExtractorInput, holder: PositionHolder): Int {
        if (input.position != ixReadAt) {
            holder.position = ixReadAt
            return Extractor.RESULT_SEEK
        }
        val streamIx = superOrder[superStreamPos]
        try {
            readFully(input, header8)
            readFully(input, ixHeader20)
        } catch (_: EOFException) {
            return superAbandon(holder, "ix header eof")
        }
        val longsPer = u16(ixHeader20, 0)
        val indexType = ixHeader20[3].toInt() and 0xFF
        val inUse = u32(ixHeader20, 4).toInt()
        val chunkId = u32(ixHeader20, 8)
        superWrittenBase = u32(ixHeader20, 12)
        val declared = u32(header8, 4)
        if (!isIxFourcc(AviChunk.fourcc(header8, 0)) ||
            longsPer != IX_LONGS_PER_ENTRY ||
            indexType != INDEX_OF_CHUNKS ||
            inUse <= 0 ||
            inUse > MAX_IX_ENTRIES ||
            AviChunk.streamIndex(chunkId.toInt()) != streamIx ||
            declared < IX_HEADER_BYTES + inUse * IX_ENTRY_BYTES
        ) {
            return superAbandon(holder, "ix header invalid fourcc=${fcc(header8, 0)} n=$inUse")
        }
        if (superTempOff.size < inUse) {
            val grown = maxOf(inUse, superTempOff.size * 2)
            superTempOff = LongArray(grown)
            superTempSize = IntArray(grown)
        }
        try {
            for (i in 0 until inUse) {
                readFully(input, ixEntry8)
                superTempOff[i] = u32(ixEntry8, 0)
                superTempSize[i] = u32(ixEntry8, 4).toInt()
            }
        } catch (_: EOFException) {
            return superAbandon(holder, "ix entries eof")
        }
        superChunkId = chunkId.toInt()
        superEntryCount = inUse
        superTry = 0
        superStep = SuperStep.VERIFY
        return Extractor.RESULT_CONTINUE
    }

    /**
     * 基址对账：ix 条目偏移是相对量，muxer 写的 qwBaseOffset 有几种口径（相对 movi
     * fourcc / 相对 movi 数据起点 / 绝对 0）。与 idx1 的 verifyBase 同一政策——读到首条
     * entry 指向的 chunk fourcc，与 ix 头的 dwChunkId 对上才算数；对不上换下一个候选，
     * 全灭就回扫描。对过一次账后（muxer 全文件口径一致）后续 ix 块只试「写值+增量」。
     */
    private fun verifySuperBase(input: ExtractorInput, holder: PositionHolder): Int {
        val base = superBaseCandidate(superTry)
        if (base == null) return superAbandon(holder, "base verify exhausted")
        val abs = base + superTempOff[0]
        if (input.position != abs) {
            holder.position = abs
            return Extractor.RESULT_SEEK
        }
        try {
            readFully(input, four4)
        } catch (_: EOFException) {
            superTry++
            return Extractor.RESULT_CONTINUE
        }
        if (AviChunk.fourcc(four4, 0) == superChunkId) {
            superBaseDelta = base - superWrittenBase
            superVerified = true
            flushSuperTemp()
            return advanceSuper(holder)
        }
        superTry++
        return Extractor.RESULT_CONTINUE
    }

    private fun superBaseCandidate(tryIdx: Int): Long? = if (superVerified) {
        if (tryIdx == 0) superWrittenBase + superBaseDelta else null
    } else {
        when (tryIdx) {
            0 -> superWrittenBase
            1 -> moviFccPos
            2 -> moviDataStart
            3 -> 0L
            else -> null
        }
    }

    /** 临时区条目按对好的基址换算绝对偏移，落进当前流收集器 */
    private fun flushSuperTemp() {
        val streamIx = superOrder[superStreamPos]
        val collector = superData.getOrPut(streamIx) { SuperCollector() }
        val abs = superWrittenBase + superBaseDelta
        for (i in 0 until superEntryCount) {
            val flags = superTempSize[i]
            collector.add(
                abs + superTempOff[i],
                flags and DELTAFRAME_BIT.inv(),
                flags and DELTAFRAME_BIT != 0,
            )
        }
    }

    /** 当前 ix 块收尾：推进块游标/流游标；全部读完就归并进 EMIT */
    private fun advanceSuper(holder: PositionHolder): Int {
        superIxPos++
        if (superIxPos >= streams[superOrder[superStreamPos]].superIndex!!.offsets.size) {
            superStreamPos++
            superIxPos = 0
        }
        if (superStreamPos >= superOrder.size) {
            materializeSuper()
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        ixReadAt = streams[superOrder[superStreamPos]].superIndex!!.offsets[superIxPos]
        superStep = SuperStep.READ_IX
        holder.position = ixReadAt
        return Extractor.RESULT_SEEK
    }

    private fun superAbandon(holder: PositionHolder, why: String): Int {
        Log.d(TAG, "wmv2 superidx unusable: $why → scan")
        superOrder.clear()
        superData.clear()
        return startScan(holder)
    }

    /**
     * 超级索引归并：每流时间戳按流序算好（视频 ordinal × scale/rate、音频按累计字节
     * 折块——与 materialize 同口径），再按绝对偏移把所有流归并成**文件序**样本表。
     * 文件序是 seek 二分与「seek 后两流都从附近继续」的前提（idx1 天然文件序，这里
     * 归并补齐）；若某流条目偏移乱序，退回 seek 线性扫（与 idx1 路径同一守卫）。
     */
    private fun materializeSuper() {
        samples.clear()
        keyframesReliable = true
        samplesOffsetSorted = true
        val ordinals = IntArray(streams.size)
        val audioBytes = LongArray(streams.size)
        val cursors = IntArray(superOrder.size)
        var merged = 0
        while (true) {
            var pick = -1
            var pickOffset = Long.MAX_VALUE
            for (s in cursors.indices) {
                val data = superData[superOrder[s]] ?: continue
                if (cursors[s] < data.count && data.offset[cursors[s]] < pickOffset) {
                    pickOffset = data.offset[cursors[s]]
                    pick = s
                }
            }
            if (pick < 0) break
            val streamIx = superOrder[pick]
            val data = superData[streamIx] ?: break
            val c = cursors[pick]++
            val stream = streams[streamIx]
            val timeUs: Long = if (stream.videoMime != null || stream.blockAlign <= 0) {
                val n = ordinals[streamIx]
                ordinals[streamIx] = n + 1
                streamTicksToUs(stream, n.toLong())
            } else {
                // 音频 chunk 的时间戳是 chunk 起点；消费完本块后再推进累计字节。
                // 首个样本因此从 0 开始，而不是错误地从一个 chunk 时长开始。
                val startBytes = audioBytes[streamIx]
                audioBytes[streamIx] = startBytes + data.size[c].toLong()
                streamTicksToUs(stream, startBytes / stream.blockAlign)
            }
            samples.add(streamIx, data.offset[c], data.size[c], timeUs, !data.delta[c])
            merged++
        }
        // 收集器与样本表同量级（十几万条 × 流数），建完就放手
        superData.clear()
        var prev = Long.MIN_VALUE
        for (i in 0 until samples.count) {
            if (samples.offset[i] < prev) {
                samplesOffsetSorted = false
                break
            }
            prev = samples.offset[i]
        }
        Log.d(TAG, "wmv2 superidx samples=$merged streams=${superOrder.size} sorted=$samplesOffsetSorted")
    }

    private fun buildByScan(input: ExtractorInput, holder: PositionHolder): Int {
        if (scanCursor < 0) scanCursor = moviDataStart
        if (input.position != scanCursor) {
            holder.position = scanCursor
            return Extractor.RESULT_SEEK
        }
        // 每块回一次 SEEK 是 media3 的形状：ExtractorInput 没有 skip/seek，不返回 SEEK
        // 就只能把 payload 真读出来丢掉（两小时正片是几 GB 白读）。**往返的成本在探测侧
        // 每次 SEEK 都 close()+open()**，那半在 FormatProbe 收（A1）。这里改的是每块
        // 都要付的三样东西：String 分配、O(n²) 的"这条流见过没有"、以及一遇异常就
        // 让整片出局的抛法。
        if (scanCursor + 8 > moviEnd) {
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val hdr = ByteArray(8)
        try {
            readFully(input, hdr)
        } catch (_: EOFException) {
            // 文件截断，或流式输入报不出长度：用手上已认出的部分收尾，不把整片判死
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val ckid = AviChunk.fourcc(hdr, 0)
        val size = u32(hdr, 4)
        if (size > MAX_CHUNK) {
            // 单个样本不会这么大：走到这里说明解析已经跑偏。停住收尾（旧写法直接抛
            // IOException，整片出局）。
            materialize(0L, scanned, fromIndex = false)
            phase = Phase.EMIT
            return Extractor.RESULT_CONTINUE
        }
        val stream = AviChunk.streamIndex(ckid)
        // "这条流的第一块"当关键帧是**猜的**，所以 SeekMap 不据此宣称可拖（见 isSeekable）。
        // 见过没有用集合判，旧写法 `scanned.none { }` 是 O(n²) 次比较 + 每块一个 String。
        val key = if (stream != null && scannedStreams.add(stream)) KEYFRAME else 0L
        scanned.add(ckid, key, scanCursor, size.toInt())
        val next = scanCursor + 8 + size + AviChunk.pad(size)
        scanCursor = next
        // A1 收口：相邻 chunk 就地跳过 payload、CONTINUE 直读，让**一个** ExtractorInput
        // 实例扫完整个 movi。旧写法每块一次 RESULT_SEEK——探测侧与 media3 的
        // ProgressiveMediaPeriod 每次 SEEK 都重建一个 64KB 内部缓冲的 DefaultExtractorInput，
        // 十几万块 = GB 级短命对象，真机上把 256MB 堆直接顶满（E204 真机 OOM 实录）。
        // AVI 相邻块间距就是块本身（几 KB 量级），顺序跳读比逐块 SEEK 更快。
        // payload 超大（异常形态/JUNK 巨块）才回到 SEEK 跳过。
        if (size <= BUILD_INLINE_SKIP_LIMIT) {
            var remaining = (size + AviChunk.pad(size)).toInt()
            try {
                while (remaining > 0) {
                    val n = minOf(remaining, buildSkipScratch.size)
                    readFully(input, buildSkipScratch, 0, n)
                    remaining -= n
                }
            } catch (_: EOFException) {
                // 文件截断：用手上已认出的部分收尾，与头部 EOF 同一政策
                materialize(0L, scanned, fromIndex = false)
                phase = Phase.EMIT
                return Extractor.RESULT_CONTINUE
            }
            return Extractor.RESULT_CONTINUE
        }
        holder.position = next
        return Extractor.RESULT_SEEK
    }

    private fun materialize(base: Long, entries: IdxTable, fromIndex: Boolean) {
        samples.clear()
        keyframesReliable = fromIndex
        samplesOffsetSorted = true
        val ordinal = IntArray(streams.size)
        // 音频流的时间戳按**累计字节数**折算（E204）：AVI strh 对音频给的是
        // dwScale=nBlockAlign / dwRate=avgBytesPerSec，一块几 KB 的 PCM chunk 若按
        // 逐块 ordinal × scale/rate 计时只前进 ~20µs，600s 音轨的时间戳只覆盖
        // 0.6s——media3 的"缓冲 60s"门永远不触发，整条 PCM 全堆进 SampleQueue
        //（真机 105MB PCM 把 Java 堆顶到 215MB+）。字节数折算与 FFmpeg avidec 同源。
        val audioBytes = LongArray(streams.size)
        var videoSeen = false
        var previousOffset = Long.MIN_VALUE
        for (i in 0 until entries.count) {
            val streamIndex = AviChunk.streamIndex(entries.ckid[i]) ?: continue
            val stream = streams.getOrNull(streamIndex) ?: continue
            val isVideo = stream.videoMime != null
            if (!isVideo && stream.audioMime == null) continue
            val kind = AviChunk.chunkKind(entries.ckid[i])
            val videoChunk = kind == AviChunk.Kind.VIDEO
            val audioChunk = kind == AviChunk.Kind.AUDIO
            if (isVideo && !videoChunk) continue
            if (!isVideo && !audioChunk) continue
            val chunkBytes = entries.size[i].coerceAtLeast(0).toLong()
            val timeUs = if (isVideo) {
                val n = ordinal[streamIndex]
                ordinal[streamIndex] = n + 1
                streamTicksToUs(stream, n.toLong())
            } else if (stream.blockAlign > 0) {
                // strh 的 scale/rate 是采样时钟：先用累计字节计算当前 chunk 起点，
                // 再累加本 chunk，避免首样本凭空跳过一块音频。
                val startBytes = audioBytes[streamIndex]
                audioBytes[streamIndex] = startBytes + chunkBytes
                streamTicksToUs(stream, startBytes / stream.blockAlign)
            } else {
                val n = ordinal[streamIndex]
                ordinal[streamIndex] = n + 1
                streamTicksToUs(stream, n.toLong())
            }
            val key = entries.flags[i] and KEYFRAME != 0L || (isVideo && !videoSeen)
            if (isVideo) videoSeen = true
            val offset = if (base == 0L && !fromIndex) entries.offset[i] else base + entries.offset[i]
            // idx1 是**解码顺序**，偏移不保证单调；不单调就不能二分（见 seek）
            if (offset < previousOffset) samplesOffsetSorted = false
            previousOffset = offset
            samples.add(streamIndex, offset, entries.size[i].coerceAtLeast(0), timeUs, key)
        }
        // 扫描路径的中间表与 samples 同量级（十几万条），建完就放手，别两份一起驻留
        if (!fromIndex) scanned.clear()
        Log.d(TAG, "wmv2 samples=${samples.count} base=$base streams=${streams.size} idx1=$fromIndex")
    }

    private fun emit(holder: PositionHolder): Int {
        val sink = output ?: return Extractor.RESULT_END_OF_INPUT
        if (!emitted) {
            val video = streams.firstOrNull { it.videoMime != null } ?: return Extractor.RESULT_END_OF_INPUT
            for (stream in streams) {
                if (stream.videoMime != null) {
                    val maxIn = maxSampleSize(stream.index).coerceAtLeast(64 * 1024)
                    val format = Format.Builder()
                        .setSampleMimeType(stream.videoMime)
                        .setWidth(abs(stream.width).coerceAtLeast(1))
                        .setHeight(abs(stream.height).coerceAtLeast(1))
                        .setMaxInputSize(maxIn.coerceAtLeast(1))
                        .apply {
                            if (stream.extra.isNotEmpty()) setInitializationData(listOf(stream.extra))
                            if (stream.rate > 0 && stream.scale > 0) {
                                setFrameRate(stream.rate.toFloat() / stream.scale.toFloat())
                            }
                        }
                        .build()
                    val track = sink.track(stream.index, C.TRACK_TYPE_VIDEO)
                    track.format(format)
                    tracks[stream.index] = track
                } else if (stream.audioMime != null) {
                    // PCM 一帧就是 blockAlign 字节；WMA 的 blockAlign 是超帧上限。
                    // 旧实现只按样本最大值估，遇到空样本表会退 16KB，可能小于一帧。
                    val maxIn = maxOf(maxSampleSize(stream.index), stream.blockAlign, 1)
                    val format = Format.Builder()
                        .setSampleMimeType(stream.audioMime)
                        .setMaxInputSize(maxIn)
                        .apply {
                            if (stream.sampleRate > 0) setSampleRate(stream.sampleRate)
                            if (stream.channels > 0) setChannelCount(stream.channels)
                            if (stream.audioMime == MimeTypes.AUDIO_RAW) {
                                setPcmEncoding(pcmEncodingForBits(stream.bitsPerSample))
                            }
                            // WMA 族的 codec 身份（wmav1/2/pro/lossless）与解码参数都在
                            // WAVEFORMATEX 里——没有这段 extradata，FFmpeg 的 wma 解码器
                            // open 直接 EINVAL（真机实录"初始化 wmav2 失败"）。
                            // **只给 WMA 族**：把 WAVE 头塞给 MP3 平台解码器会让首帧
                            // Error 0xe（E207 FLV 路径同类缺陷，E208 审查补上 AVI 路径）。
                            wmvAudioInitializationData(stream.audioMime, stream.audioExtra)
                                ?.let { setInitializationData(it) }
                        }
                        .build()
                    val track = sink.track(stream.index, C.TRACK_TYPE_AUDIO)
                    track.format(format)
                    tracks[stream.index] = track
                }
            }
            val duration = streams.maxOfOrNull { streamDurationUs(it) } ?: 0L
            buildSeekTable()
            sink.seekMap(object : SeekMap {
                override fun isSeekable(): Boolean =
                    keyframesReliable && hasKeyframes
                override fun getDurationUs(): Long = duration
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    // 关键帧寻点表时间升序（逐流 ordinal 递增），二分取"≤ timeUs 的最后一个
                    // 关键帧"；比旧写法对十几万条逐条线性扫强得多（拖动热路径）
                    if (seekTimes.isEmpty()) return SeekMap.SeekPoints(SeekPoint(0, 0))
                    var lo = 0
                    var hi = seekTimes.size
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (seekTimes[mid] <= timeUs) lo = mid + 1 else hi = mid
                    }
                    val pick = if (lo == 0) 0 else lo - 1
                    return SeekMap.SeekPoints(SeekPoint(seekTimes[pick], seekOffsets[pick]))
                }
            })
            sink.endTracks()
            emitted = true
            Log.d(TAG, "emit ${video.width}x${video.height} extra=${video.extra.size} durationUs=$duration")
        }
        if (samples.count == 0) return Extractor.RESULT_END_OF_INPUT
        sampleCursor = 0
        phase = Phase.SAMPLE
        holder.position = samples.offset[0]
        return Extractor.RESULT_SEEK
    }

    private fun streamDurationUs(stream: StreamInfo): Long {
        val rate = stream.rate
        if (rate <= 0 || stream.scale <= 0) return 0L
        // strh.dwLength 按 scale/rate 时钟计（视频=帧、音频=采样），有的话直接用
        if (stream.length > 0) return stream.length * 1_000_000L * stream.scale.coerceAtLeast(1L) / rate
        var count = 0
        for (i in 0 until samples.count) {
            if (samples.stream[i] == stream.index) count++
        }
        val n = if (stream.videoMime == null && stream.blockAlign > 0) {
            // length 缺失的音频：按字节折块
            var bytes = 0L
            for (i in 0 until samples.count) {
                if (samples.stream[i] == stream.index) bytes += samples.size[i].toLong()
            }
            bytes / stream.blockAlign
        } else {
            count.toLong()
        }
        return streamTicksToUs(stream, n)
    }

    /** AVI 时钟换算：非法时钟返回 TIME_UNSET，不把损坏的 0 修成 1。 */
    private fun streamTicksToUs(stream: StreamInfo, ticks: Long): Long {
        if (stream.scale <= 0L || stream.rate <= 0L) return C.TIME_UNSET
        if (ticks <= 0L) return 0L
        val factor = if (stream.scale <= Long.MAX_VALUE / 1_000_000L) {
            stream.scale * 1_000_000L
        } else {
            return java.math.BigInteger.valueOf(ticks)
                .multiply(java.math.BigInteger.valueOf(stream.scale))
                .multiply(java.math.BigInteger.valueOf(1_000_000L))
                .divide(java.math.BigInteger.valueOf(stream.rate))
                .coerceAtMost(java.math.BigInteger.valueOf(Long.MAX_VALUE))
                .toLong()
        }
        if (ticks <= Long.MAX_VALUE / factor) return ticks * factor / stream.rate
        return java.math.BigInteger.valueOf(ticks)
            .multiply(java.math.BigInteger.valueOf(factor))
            .divide(java.math.BigInteger.valueOf(stream.rate))
            .coerceAtMost(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            .toLong()
    }

    /** 某条流的最大样本字节数（emit 估 maxInputSize 用；一遍平铺列） */
    private fun maxSampleSize(streamIndex: Int): Int {
        var max = 0
        for (i in 0 until samples.count) {
            if (samples.stream[i] == streamIndex && samples.size[i] > max) max = samples.size[i]
        }
        return max
    }

    /**
     * emit 时把关键帧寻点表从样本表里抽出来（时间升序）：
     * 有视频轨用视频关键帧；没有视频轨退回全样本池，只认带关键帧位的条目，
     * 并保证首条池内样本必入表——getSeekPoints 永远有落点（与旧实现 `pick = pool.first()` 一致）。
     */
    private fun buildSeekTable() {
        var hasVideo = false
        var firstPool = -1
        for (i in 0 until samples.count) {
            val isVideo = streams.getOrNull(samples.stream[i])?.videoMime != null
            if (isVideo) {
                hasVideo = true
                firstPool = i
                break
            }
        }
        if (!hasVideo && samples.count > 0) firstPool = 0
        var keyCount = 0
        var n = 0
        for (i in 0 until samples.count) {
            if (seekEntryEligible(i, hasVideo, firstPool)) {
                n++
                if (samples.keyframe[i]) keyCount++
            }
        }
        hasKeyframes = keyCount > 0
        seekTimes = LongArray(n)
        seekOffsets = LongArray(n)
        var w = 0
        for (i in 0 until samples.count) {
            if (seekEntryEligible(i, hasVideo, firstPool)) {
                seekTimes[w] = samples.timeUs[i]
                seekOffsets[w] = samples.offset[i]
                w++
            }
        }
    }

    private fun seekEntryEligible(i: Int, hasVideo: Boolean, firstPool: Int): Boolean {
        if (hasVideo && streams.getOrNull(samples.stream[i])?.videoMime == null) return false
        return samples.keyframe[i] || i == firstPool
    }

    private fun readSample(input: ExtractorInput, holder: PositionHolder): Int {
        if (sampleCursor >= samples.count) return Extractor.RESULT_END_OF_INPUT
        if (input.position != samples.offset[sampleCursor]) {
            holder.position = samples.offset[sampleCursor]
            return Extractor.RESULT_SEEK
        }
        readFully(input, header8)
        val size = u32(header8, 4).toInt()
        if (size < 0 || size > MAX_CHUNK) throw IOException("sample too large: $size")
        // scratch 复用：不再每帧 ByteArray(size)（两小时片 = 十几万次分配 + GC 压力）
        if (size > sampleScratch.size) {
            sampleScratch = ByteArray(maxOf(size, sampleScratch.size * 2))
        }
        val data = sampleScratch
        if (size > 0) readFully(input, data, 0, size)
        if (size % 2 == 1) skipOne(input)
        val track = tracks[samples.stream[sampleCursor]]
        if (track != null) {
            val bytes = ParsableByteArray(data, size)
            track.sampleData(bytes, size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(
                samples.timeUs[sampleCursor],
                if (samples.keyframe[sampleCursor]) C.BUFFER_FLAG_KEY_FRAME else 0,
                size,
                0,
                null,
            )
        }
        sampleCursor++
        return Extractor.RESULT_CONTINUE
    }

    private fun peekIsWmvFamily(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        input.peekFully(head, 0, head.size)
        if (fcc(head, 0) != "RIFF" || fcc(head, 8) != "AVI ") return false
        var guard = 0
        while (guard++ < 64 && input.peekPosition < PEEK_LIMIT) {
            val hdr = ByteArray(8)
            input.peekFully(hdr, 0, hdr.size)
            val id = fcc(hdr, 0)
            val size = u32(hdr, 4)
            if (size > MAX_CHUNK || size > Int.MAX_VALUE) return false
            if (id == "LIST") {
                if (size < 4) return false
                val type = ByteArray(4)
                input.peekFully(type, 0, 4)
                if (fcc(type, 0) == "hdrl") {
                    val payload = ByteArray((size - 4).toInt())
                    if (payload.isNotEmpty()) input.peekFully(payload, 0, payload.size)
                    return parseHdrl(payload).any { it.videoMime != null }
                } else {
                    input.advancePeekPosition((size - 4).toInt())
                }
            } else if (size > 0) {
                input.advancePeekPosition(size.toInt())
            }
            if (size % 2L == 1L) input.advancePeekPosition(1)
        }
        return false
    }

    private fun parseHdrl(payload: ByteArray): List<StreamInfo> {
        val out = mutableListOf<StreamInfo>()
        var p = 0
        while (p + 8 <= payload.size) {
            val id = fcc(payload, p)
            val size = u32(payload, p + 4).toInt()
            val data = p + 8
            if (size < 0 || data + size > payload.size) break
            if (id == "LIST" && size >= 4 && fcc(payload, data) == "strl") {
                out.add(parseStrl(payload, data + 4, data + size, out.size))
            }
            val step = 8 + size + (size and 1)
            if (step <= 0) break
            p += step
        }
        return out
    }

    private fun parseStrl(buf: ByteArray, start: Int, end: Int, index: Int): StreamInfo {
        var p = start
        var fccType = ""
        var fccHandler = ""
        var scale = 1L
        var rate = 1L
        var length = 0L
        var strf: ByteArray? = null
        var superIndex: SuperIndexRef? = null
        while (p + 8 <= end) {
            val id = fcc(buf, p)
            val size = u32(buf, p + 4).toInt()
            val data = p + 8
            if (size < 0 || data > end || data + size > end) break
            if (id == "strh" && size >= 36) {
                fccType = fcc(buf, data)
                fccHandler = fcc(buf, data + 4)
                scale = u32(buf, data + 20)
                rate = u32(buf, data + 24)
                length = u32(buf, data + 32)
            } else if (id == "strf") {
                strf = buf.copyOfRange(data, data + size)
            } else if (id == "indx") {
                superIndex = parseSuperIndexRef(buf, data, data + size, index)
            }
            val step = 8 + size + (size and 1)
            if (step <= 0) break
            p += step
        }
        val video = fccType == "vids"
        val compression = if (video && strf != null && strf.size >= 20) fcc(strf, 16) else fccHandler
        val width = if (video && strf != null && strf.size >= 8) u32(strf, 4).toInt() else 0
        val height = if (video && strf != null && strf.size >= 12) u32(strf, 8).toInt() else 0
        // extradata 的切分对齐 FFmpeg 的 avidec：BITMAPINFOHEADER 之后的全部字节
        // （size - 40）。WMV2 解码器的 decode_ext_header 要读这 4 字节决定
        // j_type/abt/mbrl 等标志位；若按 biSize 判断，biSize==strf.size 的编码器
        // （本样本 biSize=44）会把 extradata 吞掉，解码器标志位全零 → 花屏。
        val extra = if (video && strf != null && strf.size > BITMAPINFOHEADER) {
            val biSize = u32(strf, 0).toInt()
            val extraSize = if (biSize == strf.size - 1 && (biSize and 1) == 1) {
                biSize - BITMAPINFOHEADER
            } else {
                strf.size - BITMAPINFOHEADER
            }
            if (extraSize in 1..(strf.size - BITMAPINFOHEADER)) {
                strf.copyOfRange(BITMAPINFOHEADER, BITMAPINFOHEADER + extraSize)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        val tag = if (!video && strf != null && strf.size >= 16) u16(strf, 0) else -1
        // A11：旧实现只认 MP3/MP2，PCM 与 WMA 整条轨被丢弃——用户只看到"有画无声"，
        // 排障面板也没有原因。现在所有已知 tag 都建轨；本机解不了的 WMA 由探测侧
        // 标记 audioUnsupported，UI 明确提示，不再静默消失。
        val audioMime = wmvAudioMimeForTag(tag)
        val blockAlign = if (!video && strf != null && strf.size >= 14) u16(strf, 12) else 0
        val bitsPerSample = if (!video && strf != null && strf.size >= 16) u16(strf, 14) else 0
        return StreamInfo(
            index = index,
            videoMime = if (video) wmvVideoMimeForFourcc(compression, fccHandler) else null,
            audioMime = audioMime,
            scale = scale,
            rate = rate,
            length = length,
            width = width,
            height = height,
            extra = extra,
            sampleRate = if (strf != null && strf.size >= 8 && !video) u32(strf, 4).toInt() else 0,
            channels = if (strf != null && strf.size >= 4 && !video) u16(strf, 2) else 0,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            audioExtra = if (!video) strf else null,
            superIndex = superIndex,
        )
    }

    /**
     * strl 里的 `indx`（AVISUPERINDEX，bIndexType=AVI_INDEX_OF_INDEXES）：
     * wLongsPerEntry=4、每条 16 字节 {qwOffset=ix## 块的**绝对**文件偏移, dwSize,
     * dwDuration}。dwBase 按规范不参与（超级索引条目是绝对偏移）。dwChunkId 必须是
     * 本流的媒体 chunk id（`'0Xdc'`/`'0Xwb'`），不一致说明索引错位，直接放弃。
     */
    private fun parseSuperIndexRef(buf: ByteArray, start: Int, end: Int, streamIndex: Int): SuperIndexRef? {
        if (start + 20 > end) return null
        val longsPer = u16(buf, start)
        val indexType = buf[start + 3].toInt() and 0xFF
        val inUse = u32(buf, start + 4).toInt()
        val chunkId = u32(buf, start + 8)
        if (longsPer != SUPER_LONGS_PER_ENTRY ||
            indexType != INDEX_OF_INDEXES ||
            inUse <= 0 ||
            inUse > MAX_SUPER_ENTRIES ||
            AviChunk.streamIndex(chunkId.toInt()) != streamIndex
        ) {
            return null
        }
        if (start + 20 + inUse * 16L > end) return null
        val offsets = LongArray(inUse)
        for (i in 0 until inUse) {
            val p = start + 20 + i * 16
            val off = (u32(buf, p + 4) shl 32) or u32(buf, p)
            if (off <= 0L) return null
            offsets[i] = off
        }
        return SuperIndexRef(chunkId.toInt(), offsets)
    }

    private fun skipPad(input: ExtractorInput, size: Long) {
        if (size % 2L == 1L) skipOne(input)
    }

    private fun skipOne(input: ExtractorInput) {
        val one = ByteArray(1)
        val n = input.read(one, 0, 1)
        if (n == C.RESULT_END_OF_INPUT) throw EOFException("pad")
    }

    private fun readFully(input: ExtractorInput, dest: ByteArray) {
        readFully(input, dest, 0, dest.size)
    }

    private fun readFully(input: ExtractorInput, dest: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(dest, off + read, len - read)
            if (n == C.RESULT_END_OF_INPUT) throw EOFException("need $len got $read")
            read += n
        }
    }

    private fun pad(size: Long): Long = if (size % 2L == 0L) 0L else 1L

    private fun fcc(b: ByteArray, offset: Int): String = String(b, offset, 4, Charsets.ISO_8859_1)

    private fun u32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xff) or
            ((b[offset + 1].toLong() and 0xff) shl 8) or
            ((b[offset + 2].toLong() and 0xff) shl 16) or
            ((b[offset + 3].toLong() and 0xff) shl 24)

    private fun u16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val TAG = "Wmv2Avi"
        const val PEEK_LIMIT = 1_048_576L
        const val MAX_CHUNK = 32L * 1024 * 1024
        const val KEYFRAME = 0x10L

        // OpenDML 超级索引（AVI MTL File Format v1.02 §7）：AVI_INDEX_OF_INDEXES 的
        // indx 头 20 字节、条目 16 字节（wLongsPerEntry 恒 4）；AVI_INDEX_OF_CHUNKS 的
        // ix## 头 20 字节、条目 8 字节（wLongsPerEntry 恒 2），dwSize 最高位是
        // AVISTDINDEX_DELTAFRAME（置位 = 差分帧，清零 = 关键帧）。
        const val INDEX_OF_INDEXES = 0x00
        const val INDEX_OF_CHUNKS = 0x01
        const val SUPER_LONGS_PER_ENTRY = 4
        const val IX_LONGS_PER_ENTRY = 2
        const val IX_HEADER_BYTES = 20L
        const val IX_ENTRY_BYTES = 8L
        val DELTAFRAME_BIT: Int = Int.MIN_VALUE

        /** 单个 ix## 块与整份 indx 的条目上限：远超任何真实 muxer 的写法，只防畸形头把内存顶爆 */
        const val MAX_IX_ENTRIES = 1 shl 20
        const val MAX_SUPER_ENTRIES = 1 shl 16

        /** 扫描就地跳读的 payload 上限：常规样本几 KB～几百 KB；超过按 SEEK 跳过 */
        const val BUILD_INLINE_SKIP_LIMIT = 2L * 1024 * 1024

        /** 标准 BITMAPINFOHEADER 长度；extradata 从它之后开始（与 FFmpeg avidec 一致） */
        const val BITMAPINFOHEADER = 40

        /** media3 没有 WMA 的 MimeTypes 常量；FFmpeg 的解码表按这个 mime 认 wmav1/2 */
        const val AUDIO_WMA = "audio/x-ms-wma"
    }

    /**
     * `ix##` 块的 fourcc 形状：`'00ix'`（流号在前，规范写法）或 `'ix00'`（ix 在前，
     * 少数 muxer 的变体）都认，流号必须十进制两位数字。
     */
    private fun isIxFourcc(four: Int): Boolean {
        val c0 = (four and 0xFF).toChar()
        val c1 = ((four ushr 8) and 0xFF).toChar()
        val c2 = ((four ushr 16) and 0xFF).toChar()
        val c3 = ((four ushr 24) and 0xFF).toChar()
        fun digit(c: Char) = c in '0'..'9'
        return (digit(c0) && digit(c1) && c2 == 'i' && c3 == 'x') ||
            (c0 == 'i' && c1 == 'x' && digit(c2) && digit(c3))
    }
}

/** WAVEFORMATEX 位深 → media3 的 PCM 编码；未知位深按 16-bit（AVI PCM 的绝对多数）。 */
internal fun pcmEncodingForBits(bitsPerSample: Int): Int = when {
    bitsPerSample <= 0 -> C.ENCODING_PCM_16BIT
    bitsPerSample <= 8 -> C.ENCODING_PCM_8BIT
    bitsPerSample <= 16 -> C.ENCODING_PCM_16BIT
    bitsPerSample <= 24 -> C.ENCODING_PCM_24BIT
    else -> C.ENCODING_PCM_32BIT
}

/**
 * AVI 音频轨的 csd：只有 WMA 族吃整段 WAVEFORMATEX（tag/blockAlign/解码参数）。
 * MP3 等平台轨必须为空——WAVE 头会被平台解码器当非法 csd-0（E207 的 FLV 路径
 * 已实锤 c2 MP3 首帧 Error 0xe，E208 审查把 AVI 路径一起收口）。
 */
internal fun wmvAudioInitializationData(
    mime: String?,
    strf: ByteArray?,
): List<ByteArray>? =
    if (strf != null && NpFfmpegCodecs.isWmaFamily(mime)) listOf(strf) else null

/** AVI WAVE tag → media3 mime（A11；纯函数便于 JVM 测试）。 */
internal fun wmvAudioMimeForTag(tag: Int): String? = when (tag) {
    0x0001, 0x0003 -> MimeTypes.AUDIO_RAW
    0x0050, 0x0055 -> MimeTypes.AUDIO_MPEG
    0x0160, 0x0161 -> MediaNames.MIME_WMA
    0x0162 -> MediaNames.MIME_WMAPRO
    0x0163 -> MediaNames.MIME_WMALOSSLESS
    else -> null
}

/**
 * AVI 视频 fourcc → WMV 家族 mime（纯函数便于 JVM 测试）。compression（strf 的
 * biCompression）与 handler（strh 的 fccHandler）都要查：编码器对两处写哪个并不统一，
 * 旧 WMV2 判据就是双查的；白名单外返回 null，交给 Media3Avi。
 */
internal fun wmvVideoMimeForFourcc(compression: String, handler: String): String? = when {
    compression == "WMV2" || handler == "WMV2" -> MediaNames.MIME_WMV2
    compression == "WMV1" || handler == "WMV1" -> MediaNames.MIME_WMV1
    compression == "WMV3" || handler == "WMV3" ||
        compression == "WMVP" || handler == "WMVP" -> MediaNames.MIME_WMV3
    compression == "WVC1" || handler == "WVC1" ||
        compression == "WMVA" || handler == "WMVA" -> MediaNames.MIME_VC1
    else -> null
}
