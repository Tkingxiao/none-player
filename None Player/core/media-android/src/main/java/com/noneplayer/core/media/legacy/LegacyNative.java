package com.noneplayer.core.media.legacy;

import android.view.Surface;
import java.nio.ByteBuffer;

/** JNI 入口。库由 [LegacyDecoderLibrary] 加载，本类的静态初始化不做 System.load。 */
public final class LegacyNative {
    private LegacyNative() {}

    static native long nativeCreate(String codecName, byte[] extradata, int width, int height);

    /** @return 0 成功，负数是 FFmpeg 错误码 */
    static native int nativeSend(long handle, byte[] data, int size, long ptsUs, boolean eos);

    /**
     * 直接 ByteBuffer 版（必须 direct；A1 批）：渲染器侧 DecoderInputBuffer 本就是
     * direct buffer，旧路径要先 `ByteArray(remaining())` 拷一份再 `GetByteArrayRegion`
     * 拷进 packet——每帧两份全尺寸拷贝。direct 版 JNI 里 `GetDirectBufferAddress`
     * + memcpy，只留一份。
     *
     * @return 0 成功，负数是 FFmpeg 错误码
     */
    static native int nativeSendDirect(long handle, ByteBuffer data, int size, long ptsUs, boolean eos);

    static native int nativePending(long handle);

    static native boolean nativeEof(long handle);

    static native boolean nativePeek(long handle, int[] info, long[] pts);

    static native boolean nativeCopy(long handle, ByteBuffer y, ByteBuffer u, ByteBuffer v);

    /** @return 0 成功，负数表示 Surface 拒绝绘制 */
    static native int nativeRender(
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

    static native void nativeFlush(long handle);

    static native void nativeRelease(long handle);
}
