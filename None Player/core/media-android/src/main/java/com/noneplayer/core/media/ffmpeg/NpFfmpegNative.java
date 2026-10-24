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

    // ---- 解封装（npffExt*；E207 avformat 批）----

    /**
     * 打开容器。reader = 读桥（readFully/length，接口方法受 proguard keep 保护）。
     *
     * @return handle；0 = 打开失败
     */
    public static native long npffExtOpen(Object reader, String iformatName);

    /** @return 流数量；负数 = 错误 */
    public static native int npffExtFindStreams(long handle);

    /** @return 1=视频 2=音频 0=其它/越界 */
    public static native int npffExtStreamType(long handle, int index);

    /** avcodec_get_name（mime 路由用） */
    public static native String npffExtStreamCodecName(long handle, int index);

    /** {width, height, sampleRate, channels, blockAlign, codecTag} */
    public static native int[] npffExtStreamInfo(long handle, int index);

    /** codecpar->extradata（视频 csd） */
    public static native byte[] npffExtStreamExtraData(long handle, int index);

    /** 音频流：合成完整 WAVEFORMATEX（18B 头 + extradata），csd 语义与 AVI 路径一致 */
    public static native byte[] npffExtStreamWavHeader(long handle, int index);

    public static native long npffExtDurationUs(long handle);

    /** @return 0=包已缓存 1=需要 seek（PendingSeekPos 取目标） -1=EOF -2=错误 */
    public static native int npffExtRead(long handle);

    public static native int npffExtPacketStream(long handle);

    public static native long npffExtPacketPtsUs(long handle);

    /** @return 1=关键帧 */
    public static native int npffExtPacketFlags(long handle);

    public static native int npffExtPacketSize(long handle);

    /** 拷贝包数据（容量不足时截断）；包随即消费释放 */
    public static native int npffExtPacketData(long handle, ByteBuffer out, int capacity);

    public static native long npffExtPendingSeekPos(long handle);

    /** 按时间 seek；返回目标 us（AVIO seek 挂起由 npffExtRead 上报） */
    public static native long npffExtSeekTimeUs(long handle, long timeUs);

    public static native void npffExtClose(long handle);
}
