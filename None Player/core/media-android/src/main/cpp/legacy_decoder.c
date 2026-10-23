#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "LegacyDecoder"
#define ALOG(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
/* A19: 4 帧足以喂饱 Media3 的输出池；更深的队列只会增加常驻 YUV 内存。 */
#define MAX_QUEUE 4
#define POOL_MAX MAX_QUEUE

typedef struct Frame {
    int width;
    int height;
    int y_stride;
    int uv_stride;
    int colorspace; /* 0 = full range, 1 = limited */
    int matrix;     /* 0 = BT.601, 1 = BT.709 */
    int64_t pts;
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
    size_t y_cap;
    size_t uv_cap;
} Frame;

typedef struct PlanePool {
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
    size_t y_cap;
    size_t uv_cap;
} PlanePool;

typedef struct Legacy {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    Frame queue[MAX_QUEUE];
    int qh;
    int qn;
    /* A19: 解码帧离开队列后按尺寸回收到池里，稳态不再每帧 malloc/free。 */
    PlanePool pool[POOL_MAX];
    int pool_n;
    /* A19: 队列满时暂存一帧，等 Kotlin 取走后再入队，形成背压而不是报错。 */
    AVFrame *pending;
    int has_pending;
    int eof;
    /* A19: 窗口句柄按 Surface 缓存，几何只在尺寸变化时设置。 */
    ANativeWindow *win;
    jobject surface_ref;
    int win_w;
    int win_h;
} Legacy;

static void frame_clear(Legacy *L, Frame *f) {
    if (f->y) {
        if (L && L->pool_n < POOL_MAX) {
            PlanePool *p = &L->pool[L->pool_n++];
            p->y = f->y;
            p->u = f->u;
            p->v = f->v;
            p->y_cap = f->y_cap;
            p->uv_cap = f->uv_cap;
        } else {
            free(f->y);
            free(f->u);
            free(f->v);
        }
    }
    memset(f, 0, sizeof(*f));
}

static void pool_clear(Legacy *L) {
    for (int i = 0; i < L->pool_n; i++) {
        free(L->pool[i].y);
        free(L->pool[i].u);
        free(L->pool[i].v);
    }
    L->pool_n = 0;
}

static int frame_alloc(Legacy *L, Frame *out, size_t y_bytes, size_t uv_bytes) {
    for (int i = 0; i < L->pool_n; i++) {
        PlanePool *p = &L->pool[i];
        if (p->y_cap >= y_bytes && p->uv_cap >= uv_bytes) {
            out->y = p->y;
            out->u = p->u;
            out->v = p->v;
            out->y_cap = p->y_cap;
            out->uv_cap = p->uv_cap;
            L->pool[i] = L->pool[--L->pool_n];
            return 0;
        }
    }
    if (L->pool_n >= POOL_MAX) {
        PlanePool *p = &L->pool[--L->pool_n];
        free(p->y);
        free(p->u);
        free(p->v);
    }
    uint8_t *y = (uint8_t *)malloc(y_bytes);
    uint8_t *u = (uint8_t *)malloc(uv_bytes);
    uint8_t *v = (uint8_t *)malloc(uv_bytes);
    if (!y || !u || !v) {
        free(y);
        free(u);
        free(v);
        return AVERROR(ENOMEM);
    }
    out->y = y;
    out->u = u;
    out->v = v;
    out->y_cap = y_bytes;
    out->uv_cap = uv_bytes;
    return 0;
}

static void queue_drop_all(Legacy *L) {
    while (L->qn > 0) {
        frame_clear(L, &L->queue[L->qh]);
        L->qh = (L->qh + 1) % MAX_QUEUE;
        L->qn--;
    }
}

static int enqueue_frame(Legacy *L, const AVFrame *f) {
    // A19：队列满返回 EAGAIN，由调用方暂存 pending，不再丢帧也不再报终态错误。
    if (L->qn >= MAX_QUEUE) return AVERROR(EAGAIN);
    if (f->format != AV_PIX_FMT_YUV420P && f->format != AV_PIX_FMT_YUVJ420P) {
        // A19：非 YUV420 帧跳过并计数，不要让整部片子以解码失败告终。
        ALOG("unsupported pix_fmt %d, dropping frame", f->format);
        return 0;
    }
    int w = f->width;
    int h = f->height;
    if (w <= 0 || h <= 0) return AVERROR(EINVAL);
    int y_stride = w;
    int uv_stride = (w + 1) / 2;
    int uv_h = (h + 1) / 2;
    Frame *out = &L->queue[(L->qh + L->qn) % MAX_QUEUE];
    memset(out, 0, sizeof(*out));
    int a = frame_alloc(L, out, (size_t)y_stride * (size_t)h, (size_t)uv_stride * (size_t)uv_h);
    if (a < 0) {
        frame_clear(L, out);
        return a;
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
    out->colorspace = (f->format == AV_PIX_FMT_YUVJ420P || f->color_range == AVCOL_RANGE_JPEG) ? 0 : 1;
    /* A19: AVFrame 的 matrix 与 range 分开带出；未标注时 HD 按 BT.709、SD 按 BT.601。 */
    switch (f->colorspace) {
        case AVCOL_SPC_BT709:
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            out->matrix = 1;
            break;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M:
            out->matrix = 0;
            break;
        default:
            out->matrix = (w >= 1280 || h >= 720) ? 1 : 0;
            break;
    }
    out->pts = (f->best_effort_timestamp == AV_NOPTS_VALUE) ? f->pts : f->best_effort_timestamp;
    L->qn++;
    return 0;
}

static int enqueue_pending(Legacy *L) {
    if (!L->has_pending) return 0;
    int e = enqueue_frame(L, L->pending);
    if (e == AVERROR(EAGAIN)) return 0;
    if (e < 0) return e;
    av_frame_unref(L->pending);
    L->has_pending = 0;
    return 0;
}

static int drain(Legacy *L) {
    for (;;) {
        int p = enqueue_pending(L);
        if (p < 0) return p;
        if (L->has_pending) return 0;
        int r = avcodec_receive_frame(L->ctx, L->frame);
        if (r == AVERROR(EAGAIN)) return 0;
        if (r == AVERROR_EOF) {
            L->eof = 1;
            return 0;
        }
        if (r == AVERROR_INVALIDDATA) {
            // A19：截断/损坏帧丢掉继续，不把整部片子打成终态。
            ALOG("invalid data, dropping frame");
            av_frame_unref(L->frame);
            continue;
        }
        if (r < 0) return r;
        int e = enqueue_frame(L, L->frame);
        if (e == AVERROR(EAGAIN)) {
            if (av_frame_ref(L->pending, L->frame) < 0) {
                av_frame_unref(L->frame);
                return AVERROR(ENOMEM);
            }
            L->has_pending = 1;
            e = 0;
        }
        av_frame_unref(L->frame);
        if (e < 0) return e;
        if (L->has_pending) return 0;
    }
}

/* 队列空出来后继续向解码器要帧；EOS 后的尾帧因此不会被 4 帧上限截断。 */
static int refill(Legacy *L) {
    int e;
    if (L->qn >= MAX_QUEUE) return 0;
    e = drain(L);
    if (e < 0) {
        ALOG("refill failed: %d", e);
        return e;
    }
    return 0;
}

static int clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}

static void yuv_to_rgb(int y, int u, int v, int full, int matrix, int *r, int *g, int *b) {
    int c = full ? y : y - 16;
    int d = u - 128;
    int e = v - 128;
    if (!full) {
        if (matrix == 1) { /* BT.709 limited */
            *r = clamp8((298 * c + 459 * e + 128) >> 8);
            *g = clamp8((298 * c - 55 * d - 136 * e + 128) >> 8);
            *b = clamp8((298 * c + 541 * d + 128) >> 8);
        } else { /* BT.601 limited */
            *r = clamp8((298 * c + 409 * e + 128) >> 8);
            *g = clamp8((298 * c - 100 * d - 208 * e + 128) >> 8);
            *b = clamp8((298 * c + 516 * d + 128) >> 8);
        }
    } else {
        if (matrix == 1) { /* BT.709 full */
            *r = clamp8((256 * c + 403 * e + 128) >> 8);
            *g = clamp8((256 * c - 48 * d - 120 * e + 128) >> 8);
            *b = clamp8((256 * c + 475 * d + 128) >> 8);
        } else { /* BT.601 full */
            *r = clamp8((256 * c + 359 * e + 128) >> 8);
            *g = clamp8((256 * c - 88 * d - 183 * e + 128) >> 8);
            *b = clamp8((256 * c + 454 * d + 128) >> 8);
        }
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
    L->pending = av_frame_alloc();
    if (!L->ctx || !L->frame || !L->pkt || !L->pending) {
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
    /* A19: legacy 软解不再单线程。slice 线程不引入帧级延迟，pump 的时序不变。 */
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    int threads = cores > 0 ? (int)cores : 2;
    if (threads > 4) threads = 4;
    if (threads < 1) threads = 1;
    L->ctx->thread_count = threads;
    L->ctx->thread_type = FF_THREAD_SLICE;
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
        av_frame_free(&L->pending);
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
    if (r == AVERROR(EAGAIN)) {
        // A19：先 drain 再重试一次，不把可恢复的背压当失败。
        int d = drain(L);
        if (d < 0) {
            av_packet_unref(L->pkt);
            return d;
        }
        r = avcodec_send_packet(L->ctx, L->pkt);
    }
    av_packet_unref(L->pkt);
    if (r < 0) return r;
    L->eof = 0;
    return drain(L);
}

/* A1 批：direct ByteBuffer 送入。渲染器侧 DecoderInputBuffer 本就是 direct buffer，
 * 旧路径要先 ByteArray(remaining()) 拷一份再 GetByteArrayRegion 拷进 packet——
 * 每帧两份全尺寸拷贝；这里 GetDirectBufferAddress + memcpy，只留一份。 */
JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeSendDirect(
        JNIEnv *env, jclass clazz, jlong handle, jobject data, jint size, jlong pts_us, jboolean eos) {
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
        void *addr = (*env)->GetDirectBufferAddress(env, data);
        if (!addr) {
            av_packet_unref(L->pkt);
            return AVERROR(EINVAL);
        }
        memcpy(L->pkt->data, addr, (size_t)size);
    }
    L->pkt->pts = pts_us;
    L->pkt->dts = pts_us;
    r = avcodec_send_packet(L->ctx, L->pkt);
    if (r == AVERROR(EAGAIN)) {
        // A19：先 drain 再重试一次，不把可恢复的背压当失败。
        int d = drain(L);
        if (d < 0) {
            av_packet_unref(L->pkt);
            return d;
        }
        r = avcodec_send_packet(L->ctx, L->pkt);
    }
    av_packet_unref(L->pkt);
    if (r < 0) return r;
    L->eof = 0;
    return drain(L);
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativePending(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L) return 0;
    refill(L);
    return L->qn;
}

JNIEXPORT jboolean JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeEof(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    return (L && L->eof) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativePeek(
        JNIEnv *env, jclass clazz, jlong handle, jintArray info, jlongArray pts) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || L->qn <= 0) return JNI_FALSE;
    Frame *f = &L->queue[L->qh];
    jint values[6];
    values[0] = f->width;
    values[1] = f->height;
    values[2] = f->y_stride;
    values[3] = f->uv_stride;
    values[4] = f->colorspace;
    values[5] = f->matrix;
    (*env)->SetIntArrayRegion(env, info, 0, 6, values);
    jlong pts_value = f->pts;
    (*env)->SetLongArrayRegion(env, pts, 0, 1, &pts_value);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeCopy(
        JNIEnv *env, jclass clazz, jlong handle, jobject y, jobject u, jobject v) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || L->qn <= 0) return JNI_FALSE;
    Frame *f = &L->queue[L->qh];
    int uv_h = (f->height + 1) / 2;
    size_t y_bytes = (size_t)f->y_stride * (size_t)f->height;
    size_t uv_bytes = (size_t)f->uv_stride * (size_t)uv_h;
    uint8_t *yd = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *ud = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *vd = (*env)->GetDirectBufferAddress(env, v);
    if (!yd || !ud || !vd) return JNI_FALSE;
    if ((size_t)(*env)->GetDirectBufferCapacity(env, y) < y_bytes ||
        (size_t)(*env)->GetDirectBufferCapacity(env, u) < uv_bytes ||
        (size_t)(*env)->GetDirectBufferCapacity(env, v) < uv_bytes) {
        return JNI_FALSE;
    }
    memcpy(yd, f->y, y_bytes);
    memcpy(ud, f->u, uv_bytes);
    memcpy(vd, f->v, uv_bytes);
    frame_clear(L, f);
    L->qh = (L->qh + 1) % MAX_QUEUE;
    L->qn--;
    return JNI_TRUE;
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
        jlong handle,
        jobject y,
        jint y_stride,
        jobject u,
        jint uv_stride,
        jobject v,
        jint width,
        jint height,
        jint colorspace,
        jint matrix,
        jobject surface) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L || !surface || width <= 0 || height <= 0) return -1;
    uint8_t *yp = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *up = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *vp = (*env)->GetDirectBufferAddress(env, v);
    if (!yp || !up || !vp) return -2;

    /* A19: Surface 句柄缓存；几何只在视频尺寸变化时设置，缩放交给 GPU。 */
    if (!L->surface_ref || !(*env)->IsSameObject(env, L->surface_ref, surface)) {
        if (L->win) {
            ANativeWindow_release(L->win);
            L->win = NULL;
        }
        if (L->surface_ref) {
            (*env)->DeleteGlobalRef(env, L->surface_ref);
            L->surface_ref = NULL;
        }
        L->win = ANativeWindow_fromSurface(env, surface);
        if (!L->win) return -3;
        L->surface_ref = (*env)->NewGlobalRef(env, surface);
        L->win_w = 0;
        L->win_h = 0;
    }
    if (L->win_w != width || L->win_h != height) {
        ANativeWindow_setBuffersGeometry(L->win, width, height, WINDOW_FORMAT_RGBA_8888);
        L->win_w = width;
        L->win_h = height;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(L->win, &buf, NULL) != 0) return -4;
    int format = buf.format;
    if (format != WINDOW_FORMAT_RGBA_8888 && format != WINDOW_FORMAT_RGBX_8888 &&
        format != WINDOW_FORMAT_RGB_565) {
        ANativeWindow_unlockAndPost(L->win);
        return -5;
    }
    int full = colorspace == 0;
    int dw = buf.width > 0 ? buf.width : width;
    int dh = buf.height > 0 ? buf.height : height;
    uint8_t *base = (uint8_t *)buf.bits;
    int bpp = format == WINDOW_FORMAT_RGB_565 ? 2 : 4;
    size_t stride_bytes = (size_t)buf.stride * (size_t)bpp;

    if (dw == width && dh == height) {
        /* 1:1 快路径：GPU 已按视频原始尺寸显示，不再做两百万次除法。 */
        for (int sy = 0; sy < height; sy++) {
            uint8_t *row = base + (size_t)sy * stride_bytes;
            const uint8_t *yr = yp + (size_t)sy * (size_t)y_stride;
            const uint8_t *ur = up + (size_t)(sy >> 1) * (size_t)uv_stride;
            const uint8_t *vr = vp + (size_t)(sy >> 1) * (size_t)uv_stride;
            for (int sx = 0; sx < width; sx++) {
                int Y = yr[sx];
                int U = ur[sx >> 1];
                int V = vr[sx >> 1];
                int r, g, b;
                yuv_to_rgb(Y, U, V, full, matrix, &r, &g, &b);
                write_pixel(row, format, sx, r, g, b);
            }
        }
    } else {
        /* 非 1:1 退路：预计算 dx→sx 与每行 sy，避免逐像素 64 位除法。 */
        int *sx_map = (int *)malloc(sizeof(int) * (size_t)dw);
        int *sy_map = (int *)malloc(sizeof(int) * (size_t)dh);
        if (sx_map && sy_map) {
            for (int dx = 0; dx < dw; dx++) {
                int sx = (int)(((int64_t)dx * width) / dw);
                sx_map[dx] = sx < width ? sx : width - 1;
            }
            for (int dy = 0; dy < dh; dy++) {
                int sy = (int)(((int64_t)dy * height) / dh);
                sy_map[dy] = sy < height ? sy : height - 1;
            }
        }
        for (int dy = 0; dy < dh; dy++) {
            int sy = sy_map ? sy_map[dy] : (int)(((int64_t)dy * height) / dh);
            if (sy >= height) sy = height - 1;
            uint8_t *row = base + (size_t)dy * stride_bytes;
            const uint8_t *yr = yp + (size_t)sy * (size_t)y_stride;
            const uint8_t *ur = up + (size_t)(sy >> 1) * (size_t)uv_stride;
            const uint8_t *vr = vp + (size_t)(sy >> 1) * (size_t)uv_stride;
            for (int dx = 0; dx < dw; dx++) {
                int sx = sx_map ? sx_map[dx] : (int)(((int64_t)dx * width) / dw);
                if (sx >= width) sx = width - 1;
                int Y = yr[sx];
                int U = ur[sx >> 1];
                int V = vr[sx >> 1];
                int r, g, b;
                yuv_to_rgb(Y, U, V, full, matrix, &r, &g, &b);
                write_pixel(row, format, dx, r, g, b);
            }
        }
        free(sx_map);
        free(sy_map);
    }
    ANativeWindow_unlockAndPost(L->win);
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
    av_frame_unref(L->pending);
    L->has_pending = 0;
    L->eof = 0;
}

JNIEXPORT void JNICALL
Java_com_noneplayer_core_media_legacy_LegacyNative_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    Legacy *L = (Legacy *)handle;
    if (!L) return;
    if (L->win) {
        ANativeWindow_release(L->win);
        L->win = NULL;
    }
    if (L->surface_ref) {
        (*env)->DeleteGlobalRef(env, L->surface_ref);
        L->surface_ref = NULL;
    }
    queue_drop_all(L);
    pool_clear(L);
    avcodec_free_context(&L->ctx);
    av_frame_free(&L->frame);
    av_packet_free(&L->pkt);
    av_frame_free(&L->pending);
    free(L);
}
