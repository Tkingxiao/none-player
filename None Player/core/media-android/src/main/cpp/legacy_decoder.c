#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "LegacyDecoder"
#define ALOG(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define MAX_QUEUE 16

typedef struct Frame {
    int width;
    int height;
    int y_stride;
    int uv_stride;
    int colorspace;
    int64_t pts;
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
} Frame;

typedef struct Legacy {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    Frame queue[MAX_QUEUE];
    int qh;
    int qn;
} Legacy;

static void frame_clear(Frame *f) {
    free(f->y);
    free(f->u);
    free(f->v);
    memset(f, 0, sizeof(*f));
}

static void queue_drop_all(Legacy *L) {
    while (L->qn > 0) {
        frame_clear(&L->queue[L->qh]);
        L->qh = (L->qh + 1) % MAX_QUEUE;
        L->qn--;
    }
}

static int enqueue_frame(Legacy *L, const AVFrame *f) {
    if (L->qn >= MAX_QUEUE) {
        ALOG("frame queue full");
        return AVERROR(ENOMEM);
    }
    if (f->format != AV_PIX_FMT_YUV420P && f->format != AV_PIX_FMT_YUVJ420P) {
        ALOG("unsupported pix_fmt %d", f->format);
        return AVERROR(EINVAL);
    }
    int w = f->width;
    int h = f->height;
    if (w <= 0 || h <= 0) return AVERROR(EINVAL);
    int y_stride = w;
    int uv_stride = (w + 1) / 2;
    int uv_h = (h + 1) / 2;
    Frame *out = &L->queue[(L->qh + L->qn) % MAX_QUEUE];
    memset(out, 0, sizeof(*out));
    out->y = (uint8_t *)malloc((size_t)y_stride * (size_t)h);
    out->u = (uint8_t *)malloc((size_t)uv_stride * (size_t)uv_h);
    out->v = (uint8_t *)malloc((size_t)uv_stride * (size_t)uv_h);
    if (!out->y || !out->u || !out->v) {
        frame_clear(out);
        return AVERROR(ENOMEM);
    }
    for (int row = 0; row < h; row++) {
        memcpy(out->y + (size_t)row * y_stride, f->data[0] + (size_t)row * f->linesize[0], (size_t)w);
    }
    int chroma_w = (w + 1) / 2;
    for (int row = 0; row < uv_h; row++) {
        memcpy(out->u + (size_t)row * uv_stride, f->data[1] + (size_t)row * f->linesize[1], (size_t)chroma_w);
        memcpy(out->v + (size_t)row * uv_stride, f->data[2] + (size_t)row * f->linesize[2], (size_t)chroma_w);
    }
    out->width = w;
    out->height = h;
    out->y_stride = y_stride;
    out->uv_stride = uv_stride;
    out->colorspace = (f->format == AV_PIX_FMT_YUVJ420P) ? 0 : 1;
    out->pts = (f->best_effort_timestamp == AV_NOPTS_VALUE) ? f->pts : f->best_effort_timestamp;
    L->qn++;
    return 0;
}

static int drain(Legacy *L) {
    for (;;) {
        int r = avcodec_receive_frame(L->ctx, L->frame);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return 0;
        if (r < 0) return r;
        int e = enqueue_frame(L, L->frame);
        av_frame_unref(L->frame);
        if (e < 0) return e;
    }
}

static int clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}

static void yuv_to_rgb(int y, int u, int v, int full, int *r, int *g, int *b) {
    int c = full ? y : y - 16;
    int d = u - 128;
    int e = v - 128;
    if (!full) {
        *r = clamp8((298 * c + 409 * e + 128) >> 8);
        *g = clamp8((298 * c - 100 * d - 208 * e + 128) >> 8);
        *b = clamp8((298 * c + 516 * d + 128) >> 8);
    } else {
        *r = clamp8((256 * c + 359 * e + 128) >> 8);
        *g = clamp8((256 * c - 88 * d - 183 * e + 128) >> 8);
        *b = clamp8((256 * c + 454 * d + 128) >> 8);
    }
}

JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeCreate(
        JNIEnv *env, jclass clazz, jstring codec_name, jbyteArray extradata, jint width, jint height) {
    (void)clazz;
    const char *name = (*env)->GetStringUTFChars(env, codec_name, NULL);
    if (!name) return 0;
    const AVCodec *codec = avcodec_find_decoder_by_name(name);
    if (!codec) {
        ALOG("decoder not found: %s", name);
        (*env)->ReleaseStringUTFChars(env, codec_name, name);
        return 0;
    }
    Legacy *L = (Legacy *)calloc(1, sizeof(Legacy));
    if (!L) {
        (*env)->ReleaseStringUTFChars(env, codec_name, name);
        return 0;
    }
    L->ctx = avcodec_alloc_context3(codec);
    L->frame = av_frame_alloc();
    L->pkt = av_packet_alloc();
    if (!L->ctx || !L->frame || !L->pkt) {
        ALOG("alloc failed for %s", name);
        goto fail;
    }
    if (extradata) {
        jsize extra_size = (*env)->GetArrayLength(env, extradata);
        if (extra_size > 0) {
            L->ctx->extradata = (uint8_t *)av_mallocz((size_t)extra_size + AV_INPUT_BUFFER_PADDING_SIZE);
            if (!L->ctx->extradata) goto fail;
            (*env)->GetByteArrayRegion(env, extradata, 0, extra_size, (jbyte *)L->ctx->extradata);
            L->ctx->extradata_size = extra_size;
        }
    }
    if (width > 0) L->ctx->width = width;
    if (height > 0) L->ctx->height = height;
    L->ctx->thread_count = 1;
    L->ctx->pkt_timebase = (AVRational){1, 1000000};
    if (avcodec_open2(L->ctx, codec, NULL) < 0) {
        ALOG("avcodec_open2 failed: %s", name);
        goto fail;
    }
    (*env)->ReleaseStringUTFChars(env, codec_name, name);
    return (jlong)L;
fail:
    (*env)->ReleaseStringUTFChars(env, codec_name, name);
    if (L) {
        avcodec_free_context(&L->ctx);
        av_frame_free(&L->frame);
        av_packet_free(&L->pkt);
        free(L);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeSend(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray data, jint size, jlong pts_us, jboolean eos) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || !L->ctx) return AVERROR(EINVAL);
    int r;
    if (eos) {
        r = avcodec_send_packet(L->ctx, NULL);
        if (r < 0 && r != AVERROR_EOF) return r;
        return drain(L);
    }
    if (size < 0) return AVERROR(EINVAL);
    if (av_new_packet(L->pkt, size) < 0) return AVERROR(ENOMEM);
    if (size > 0 && data) {
        (*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *)L->pkt->data);
    }
    L->pkt->pts = pts_us;
    L->pkt->dts = pts_us;
    r = avcodec_send_packet(L->ctx, L->pkt);
    av_packet_unref(L->pkt);
    if (r < 0) return r;
    return drain(L);
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativePending(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    return L ? L->qn : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativePeek(
        JNIEnv *env, jclass clazz, jlong handle, jintArray info, jlongArray pts) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || L->qn <= 0) return JNI_FALSE;
    Frame *f = &L->queue[L->qh];
    jint values[5];
    values[0] = f->width;
    values[1] = f->height;
    values[2] = f->y_stride;
    values[3] = f->uv_stride;
    values[4] = f->colorspace;
    (*env)->SetIntArrayRegion(env, info, 0, 5, values);
    jlong pts_value = f->pts;
    (*env)->SetLongArrayRegion(env, pts, 0, 1, &pts_value);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeCopy(
        JNIEnv *env, jclass clazz, jlong handle, jobject y, jobject u, jobject v) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || L->qn <= 0) return;
    Frame *f = &L->queue[L->qh];
    int uv_h = (f->height + 1) / 2;
    size_t y_bytes = (size_t)f->y_stride * (size_t)f->height;
    size_t uv_bytes = (size_t)f->uv_stride * (size_t)uv_h;
    uint8_t *yd = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *ud = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *vd = (*env)->GetDirectBufferAddress(env, v);
    if (!yd || !ud || !vd) return;
    if ((size_t)(*env)->GetDirectBufferCapacity(env, y) < y_bytes ||
        (size_t)(*env)->GetDirectBufferCapacity(env, u) < uv_bytes ||
        (size_t)(*env)->GetDirectBufferCapacity(env, v) < uv_bytes) {
        return;
    }
    memcpy(yd, f->y, y_bytes);
    memcpy(ud, f->u, uv_bytes);
    memcpy(vd, f->v, uv_bytes);
    frame_clear(f);
    L->qh = (L->qh + 1) % MAX_QUEUE;
    L->qn--;
}

static void write_pixel(uint8_t *row, int format, int x, int r, int g, int b) {
    if (format == WINDOW_FORMAT_RGB_565) {
        uint16_t px = (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
        memcpy(row + x * 2, &px, 2);
    } else {
        uint8_t *p = row + x * 4;
        p[0] = (uint8_t)r;
        p[1] = (uint8_t)g;
        p[2] = (uint8_t)b;
        p[3] = 255;
    }
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeRender(
        JNIEnv *env,
        jclass clazz,
        jobject y,
        jint y_stride,
        jobject u,
        jint uv_stride,
        jobject v,
        jint width,
        jint height,
        jint colorspace,
        jobject surface) {
    (void)clazz;
    if (!surface || width <= 0 || height <= 0) return -1;
    uint8_t *yp = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *up = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *vp = (*env)->GetDirectBufferAddress(env, v);
    if (!yp || !up || !vp) return -2;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) return -3;
    ANativeWindow_setBuffersGeometry(win, width, height, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) != 0) {
        ANativeWindow_release(win);
        return -4;
    }
    int format = buf.format;
    if (format != WINDOW_FORMAT_RGBA_8888 && format != WINDOW_FORMAT_RGBX_8888 &&
        format != WINDOW_FORMAT_RGB_565) {
        ANativeWindow_unlockAndPost(win);
        ANativeWindow_release(win);
        return -5;
    }
    int full = colorspace == 0;
    int dw = buf.width > 0 ? buf.width : width;
    int dh = buf.height > 0 ? buf.height : height;
    uint8_t *base = (uint8_t *)buf.bits;
    for (int dy = 0; dy < dh; dy++) {
        int sy = (int)(((int64_t)dy * height) / dh);
        if (sy >= height) sy = height - 1;
        uint8_t *row = base + (size_t)dy * (size_t)buf.stride * (format == WINDOW_FORMAT_RGB_565 ? 2 : 4);
        for (int dx = 0; dx < dw; dx++) {
            int sx = (int)(((int64_t)dx * width) / dw);
            if (sx >= width) sx = width - 1;
            int Y = yp[(size_t)sy * y_stride + sx];
            int cx = sx / 2;
            int cy = sy / 2;
            int U = up[(size_t)cy * uv_stride + cx];
            int V = vp[(size_t)cy * uv_stride + cx];
            int r, g, b;
            yuv_to_rgb(Y, U, V, full, &r, &g, &b);
            write_pixel(row, format, dx, r, g, b);
        }
    }
    ANativeWindow_unlockAndPost(win);
    ANativeWindow_release(win);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeFlush(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || !L->ctx) return;
    avcodec_flush_buffers(L->ctx);
    queue_drop_all(L);
}

JNIEXPORT void JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L) return;
    queue_drop_all(L);
    avcodec_free_context(&L->ctx);
    av_frame_free(&L->frame);
    av_packet_free(&L->pkt);
    free(L);
}
