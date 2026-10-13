package com.noneplayer.core.media.legacy;

import android.view.Surface;
import java.nio.ByteBuffer;

/** JNI 入口。库由 [LegacyDecoderLibrary] 加载，本类的静态初始化不做 System.load。 */
public final class LegacyNative {
    private LegacyNative() {}

    static native long nativeCreate(String codecName, byte[] extradata, int width, int height);

    /** @return 0 成功，负数是 FFmpeg 错误码 */
    static native int nativeSend(long handle, byte[] data, int size, long ptsUs, boolean eos);

    static native int nativePending(long handle);

    static native boolean nativePeek(long handle, int[] info, long[] pts);

    static native void nativeCopy(long handle, ByteBuffer y, ByteBuffer u, ByteBuffer v);

    /** @return 0 成功，负数表示 Surface 拒绝绘制 */
    static native int nativeRender(
            ByteBuffer y,
            int yStride,
            ByteBuffer u,
            int uvStride,
            ByteBuffer v,
            int width,
            int height,
            int colorspace,
            Surface surface);

    static native void nativeFlush(long handle);

    static native void nativeRelease(long handle);
}
