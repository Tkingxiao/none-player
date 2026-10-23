package com.noneplayer.core.media.ffmpeg;

import android.view.Surface;
import java.nio.ByteBuffer;

/**
 * `libnpffmpeg.so` 的 JNI 入口（E204 白名单批）。
 *
 * 与 {@link com.noneplayer.core.media.legacy.LegacyNative} 的差异：
 * 链接 <b>NextLib 随包分发的共享 libavcodec/libavutil/libswresample</b>（零体积增量），
 * 白名单解码器（wmv1/wmv3/vc1/msmpeg4/wma…）按名字打开（avcodec_find_decoder_by_name）；
 * 另有音频段（npff*）：音频帧经 swr 统一转 s16 交错 PCM 交回 Kotlin。
 *
 * <p>库由 {@link NpFfmpegCodecs} 加载；本类静态初始化不做 System.load。
 */
public final class NpFfmpegNative {
    private NpFfmpegNative() {}

    // ---- 视频（与 LegacyNative 同形）----

    /** @return handle；0 = 打开失败 */
    public static native long nativeCreate(String codecName, byte[] extradata, int width, int height);

    /** @return 0 成功，负数是 FFmpeg 错误码 */
    public static native int nativeSend(long handle, byte[] data, int size, long ptsUs, boolean eos);

    /** direct ByteBuffer 版（渲染器直送，省每帧 ByteArray 拷贝） */
    public static native int nativeSendDirect(long handle, ByteBuffer data, int size, long ptsUs, boolean eos);

    public static native int nativePending(long handle);

    public static native boolean nativeEof(long handle);

    public static native boolean nativePeek(long handle, int[] info, long[] pts);

    public static native boolean nativeCopy(long handle, ByteBuffer y, ByteBuffer u, ByteBuffer v);

    /** @return 0 成功，负数表示 Surface 拒绝绘制 */
    public static native int nativeRender(
            long handle,
            ByteBuffer y,
            int yStride,
            ByteBuffer u,
            int uvStride,
            ByteBuffer v,
            int width,
            int height,
            int colorspace,
            int matrix,
            Surface surface);

    public static native void nativeFlush(long handle);

    public static native void nativeRelease(long handle);

    // ---- 音频（npff*；契约镜像 NextLib ffaudio）----

    /** @return handle；0 = 打开失败 */
    public static native long npffAudioCreate(
            String codecName,
            byte[] extradata,
            int sampleRate,
            int channels,
            int blockAlign);

    /**
     * 一次 decode：send packet + receive 全部帧 + swr 转 s16 交错 PCM，
     * 结果累积在 C 侧缓冲（[npffAudioDrain] 取走）。
     *
     * @return 输出字节数；-1 = 数据无效（non-fatal），-2 = 其它错误
     */
    public static native int npffAudioDecode(long handle, ByteBuffer inputData, int inputSize);

    /** 把 C 侧累积的 PCM 拷进 out（容量须不小于 [npffAudioDecode] 的返回值）。 */
    public static native int npffAudioDrain(long handle, ByteBuffer out, int capacity);

    public static native int npffAudioGetChannelCount(long handle);

    public static native int npffAudioGetSampleRate(long handle);

    /** @return 复位后的 handle（白名单音频族 flush 即可，返回原 handle） */
    public static native long npffAudioReset(long handle, byte[] extradata);

    public static native void npffAudioRelease(long handle);

    /** 编译进共享 libavcodec 的解码器是否存在（能力表运行时的真相源） */
    public static native boolean npffHasDecoder(String codecName);
}
