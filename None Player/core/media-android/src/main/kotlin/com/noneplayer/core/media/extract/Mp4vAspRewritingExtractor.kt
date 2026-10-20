package com.noneplayer.core.media.extract

import android.util.Log
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
import com.noneplayer.core.media.MediaNames
import com.noneplayer.core.media.legacy.LegacyDecoderLibrary
import com.noneplayer.core.media.probe.Mp4vAspGate
import java.io.EOFException

/**
 * 在第一份 `video/mp4v-es` Format 交给播放器之前，用与 profile 拦截同一套规则决定要不要改 mime。
 *
 * 不认领、也不改写 Simple Profile、设备已声明 ASP、或 profile 列表为空的流。
 * 软解加载失败时同样不改 mime，探测维持今天的 `videoProfileUnsupported`。
 */
@OptIn(UnstableApi::class)
class Mp4vAspRewritingExtractor(private val inner: Extractor) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(RewritingOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    private class RewritingOutput(private val downstream: ExtractorOutput) : ExtractorOutput {
        private var video: RewritingVideoTrack? = null
        private var endPending = false
        private var ended = false

        override fun track(id: Int, type: Int): TrackOutput {
            val track = downstream.track(id, type)
            if (type != C.TRACK_TYPE_VIDEO) return track
            return RewritingVideoTrack(track, onFormatReady = ::onVideoFormatReady).also { video = it }
        }

        override fun endTracks() {
            video?.flushPending()
            // 头在首帧里时，endTracks 先到、样本后到。先把 endTracks 压住，
            // 否则探测看到「有音频、无视频」就会停，视频 mime 永远交不出去。
            if (video?.awaitingSample == true) {
                endPending = true
                return
            }
            finish()
        }

        private fun onVideoFormatReady() {
            if (endPending) finish()
        }

        private fun finish() {
            if (ended) return
            ended = true
            downstream.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)
    }

    private class RewritingVideoTrack(
        private val downstream: TrackOutput,
        private val onFormatReady: () -> Unit,
    ) : TrackOutput {
        var awaitingSample: Boolean = false
            private set
        private var pendingFormat: Format? = null
        private var decided = false

        /**
         * 决策头有界缓冲：只保留前 [HEAD_CAP] 字节（REV-05：禁止累积整样本）。
         * 旧实现用 ByteArrayOutputStream 装整样本，大样本会直接 OOM。
         */
        private val head = ByteArray(HEAD_CAP)
        private var headSize = 0

        /** 决策后剩余字节的固定转发 scratch（不随样本 length 增长） */
        private val scratch = ByteArray(SAMPLE_FORWARD_SCRATCH_BYTES)

        override fun format(format: Format) {
            if (decided || format.sampleMimeType != MIME_MP4V) {
                downstream.format(format)
                decided = true
                pendingFormat = null
                return
            }
            val csd = format.initializationData.firstOrNull { it.isNotEmpty() }
            if (csd != null) {
                downstream.format(rewrite(format, csd))
                decided = true
            } else {
                pendingFormat = format
                awaitingSample = true
            }
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            if (decided) return downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
            var read = 0
            // 先有界地读满决策头（最多 HEAD_CAP，绝不为整样本分配）；头满即决策并转发。
            while (read < length && headSize < HEAD_CAP) {
                val n = input.read(head, headSize, minOf(HEAD_CAP - headSize, length - read))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) break
                    throw EOFException("mp4v sample")
                }
                headSize += n
                read += n
            }
            if (headSize >= HEAD_CAP) {
                decide()
                flushHead(sampleDataPart)
            }
            // 决策后剩余字节：固定 scratch 分块边读边转发（保持字节顺序，不累积整样本）。
            while (read < length) {
                val n = input.read(scratch, 0, minOf(scratch.size, length - read))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) break
                    throw EOFException("mp4v sample")
                }
                downstream.sampleData(ParsableByteArray(scratch, n), n, sampleDataPart)
                read += n
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (decided) {
                downstream.sampleData(data, length, sampleDataPart)
                return
            }
            val start = data.position
            val available = minOf(length, data.bytesLeft())
            val headTake = minOf(available, HEAD_CAP - headSize)
            if (headTake > 0) {
                System.arraycopy(data.data, start, head, headSize, headTake)
                headSize += headTake
            }
            if (headSize >= HEAD_CAP) {
                decide()
                flushHead(sampleDataPart)
                // 头部已转发，剩余可用字节零拷贝转发（超 HEAD_CAP 的部分同样不得丢弃）。
                val rest = available - headTake
                if (rest > 0) {
                    val restView = ParsableByteArray(data.data, start + headTake + rest)
                    restView.setPosition(start + headTake)
                    downstream.sampleData(restView, rest, sampleDataPart)
                }
            }
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (!decided) {
                decide()
                flushHead(TrackOutput.SAMPLE_DATA_PART_MAIN)
            }
            downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        fun flushPending() {
            // AviExtractor 在读到 movi 样本之前就 endTracks。这时还没有 VOS/VOL，
            // 不能把 video/mp4v-es 先交出去，否则后面的样本来了也改不了 mime。
            if (decided || pendingFormat == null || headSize == 0) return
            decide()
            flushHead(TrackOutput.SAMPLE_DATA_PART_MAIN)
        }

        private fun decide() {
            if (decided) return
            val format = pendingFormat
            if (format == null) {
                decided = true
                return
            }
            val header = if (headSize > 0) head.copyOf(headSize) else ByteArray(0)
            downstream.format(if (header.isEmpty()) format else rewrite(format, header))
            decided = true
            awaitingSample = false
            pendingFormat = null
            onFormatReady()
        }

        private fun flushHead(sampleDataPart: Int) {
            if (headSize == 0) return
            val bytes = head.copyOf(headSize)
            headSize = 0
            downstream.sampleData(ParsableByteArray(bytes), bytes.size, sampleDataPart)
        }

        private fun rewrite(format: Format, header: ByteArray): Format {
            // R3-14/A17：探测路径与改写路径必须同口径——把本次决定改写的视频格式一起带上，
            // 让 gate 查「马上要被选中的那一个解码器」的 profileLevels，而不是全设备并集
            val legacy = Mp4vAspGate.requiresDedicatedDecoder(header, format) &&
                LegacyDecoderLibrary.supports(MediaNames.MIME_MPEG4_ASP)
            if (!legacy) return format
            Log.d(TAG, "mp4v-es -> ${MediaNames.MIME_MPEG4_ASP}")
            return format.buildUpon().setSampleMimeType(MediaNames.MIME_MPEG4_ASP).build()
        }
    }

    private companion object {
        const val TAG = "Mp4vAsp"
        const val MIME_MP4V = "video/mp4v-es"
        const val HEAD_CAP = 4 * 1024

        /** REV-05：决策后剩余字节的转发块大小（固定上限，不随样本 length 增长） */
        const val SAMPLE_FORWARD_SCRATCH_BYTES = 64 * 1024
    }
}
