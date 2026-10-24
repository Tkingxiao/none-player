package com.noneplayer.core.media.ffmpeg

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput

/**
 * avformat 的 AVIO 读桥实现（E207 avformat 批）。
 *
 * 独立具体类的原因：C 侧回调要用 `GetObjectClass + GetMethodID` 找到
 * [readFully]/[length]——R8 会把普通类改名，JNI 方法名随之失配。本类的 keep 规则
 * 在 app proguard（`-keep class ...NpExtractorReaderImpl { *; }`），类名与方法名
 * 恒定，JNI 绑定确定成立。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class NpExtractorReaderImpl {

    private var input: ExtractorInput? = null

    /** 每次 Extractor.read() 开始时把当前输入交给桥（SEEK 后 media3 重建输入） */
    fun attachInput(value: ExtractorInput?) {
        input = value
    }

    /** avformat read_packet 回调入口（短读允许；-1 = EOF/不可读） */
    fun readFully(dest: ByteArray, len: Int): Int {
        val current = input
        if (current == null) {
            android.util.Log.d("NpFfmpegExtractor", "readFully: input NULL")
            return -1
        }
        val n = try {
            current.read(dest, 0, len)
        } catch (t: Throwable) {
            android.util.Log.e("NpFfmpegExtractor", "readFully threw", t)
            return -1
        }
        return if (n == C.RESULT_END_OF_INPUT) -1 else n
    }

    /** avformat AVSEEK_SIZE 回调入口 */
    val length: Long
        get() = input?.length ?: -1L
}
