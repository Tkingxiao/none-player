/* =========================================================================
 * libnpffmpeg 的解封装段（E207 avformat 批）：asf/rm/flv/mpegps/nut 容器。
 *
 * 设计：
 *  - Kotlin 侧 NpFfmpegExtractor 实现 media3 Extractor；sniff 用容器 magic（纯 Kotlin）。
 *  - avformat 需要可寻址流：AVIO 回调桥到 Kotlin reader（NpExtractorReaderImpl，
 *    类名/方法名受 proguard keep 保护——R8 类合并会破坏 keepclassmembers 语义）。
 *    输入的"有无"由 Kotlin 桥自持（attachInput 先于 open、每次 read 重新 attach），
 *    C 回调只负责转译，不再自持 input 引用（E207 真机：input_ref 时序导致假 EOF，
 *    以及 Kotlin 桥读抛错转译成 obj==null 的 JNI abort——回调一律 try/catch 自兜）。
 *  - AVIO seek 回调只记录目标位置；npffExtRead 返回 1（SEEK_PENDING），Kotlin 向
 *    media3 返回 RESULT_SEEK（holder=目标文件偏移），media3 重开数据源后把新的
 *    ExtractorInput attach 进来，继续读。
 *  - 包不做 JNI 回调（TrackOutput 在 Kotlin）：npffExtRead 把"下一包"缓存到 C 侧，
 *    Kotlin 查询 stream/pts/flags 并 drain 数据——与音频段同一套二段式风格。
 *  - 音频流的 csd 在 C 侧重建成完整 WAVEFORMATEX（18B 头 + avformat extradata），
 *    使 WMA 族与 AVI 路径共用同一套 Kotlin extradata 语义（tag@0、blockAlign@12）。
 * ========================================================================= */

#include <android/log.h>
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>

/* avformat 的 err/referenced-frame 机制引用 aom film-grain 清理钩子（对应解码器未编入）。
 * 白名单容器/编码不含 AV1，给空实现补链。 */
void ff_aom_uninit_film_grain_params(void *p) { (void)p; }

#define ALOG(...) __android_log_print(ANDROID_LOG_DEBUG, "NpFfmpegExtractor", __VA_ARGS__)

static JavaVM *g_vm = NULL;

typedef struct ReaderBridge {
    jobject reader;      /* global ref（Kotlin 保证非 null） */
    jmethodID read_mid;  /* ([BI)I → 读到的字节数，-1=EOF/异常 */
    jmethodID length_mid;/* getLength ()J → 流长度，-1=未知 */
    jbyteArray jbuf;     /* 读桥中转数组（global ref，64KB） */
} ReaderBridge;

typedef struct NpFfExt {
    AVFormatContext *fmt;
    ReaderBridge bridge;
    AVIOContext *avio;
    int64_t pending_seek;    /* -1 = 无 */
    AVPacket *pkt;
    int has_packet;
    int eof;
    int64_t duration_us;
    int64_t seek_target_us;  /* 无 read_seek 容器的"回起点+丢包"寻位目标；<0 = 无 */
    int seek_wait_key;       /* 回起点寻位时等视频关键帧；音频照常放行 */
    int has_video;
} NpFfExt;

/* AVIO 缓冲区末端的**文件偏移**（= 底层输入已经读到的位置）。
 * avformat_seek_file 若在缓冲区内部完成寻位（未触发 seek 回调），media3 重新打开的
 * 位置必须取这个值：缓冲区里 [目标, 末端) 的数据仍然有效，从末端继续读才不会重复/错位。
 * 旧实现只在回调发生时告诉 media3 目标；缓冲区内部寻位时 pending=-1，media3 会按
 * SeekMap 的 0 重开，随后 AVIO 又把旧缓冲吐一遍 → E208 审查修复。 */
static int64_t npff_data_pos(NpFfExt *E) {
    if (!E || !E->avio || !E->avio->buffer) return 0;
    int64_t p = E->avio->pos + (E->avio->buf_end - E->avio->buffer);
    return p < 0 ? 0 : p;
}

static jint npff_type_of(const AVStream *st) {
    switch (st->codecpar->codec_type) {
        case AVMEDIA_TYPE_VIDEO: return 1;
        case AVMEDIA_TYPE_AUDIO: return 2;
        default: return 0;
    }
}

static int avio_read_callback(void *opaque, uint8_t *buf, int buf_size) {
    NpFfExt *E = (NpFfExt *)opaque;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) return -1;
    if (!E->bridge.jbuf || !E->bridge.read_mid) return -1;
    jint n = 0;
    n = (*env)->CallIntMethod(env, E->bridge.reader, E->bridge.read_mid,
                                   E->bridge.jbuf, (jint)buf_size);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return AVERROR_EOF;
    }
    /* 桥契约：n<=0 = EOF/不可读（Kotlin 侧 read 的 -1 与 C.RESULT_END_OF_INPUT 已归一）。
     * 必须回 AVERROR_EOF——裸 -1 会被 avio 当 AVERROR_UNKNOWN 透传，
     * av_read_frame 返回 -1 → Kotlin 侧 throw → 播放会话报
     * "Unexpected runtime error"（E207 真机实录；探测路径被 try/catch 掩盖）。 */
    if (n <= 0) return AVERROR_EOF;
    (*env)->GetByteArrayRegion(env, E->bridge.jbuf, 0, n, (jbyte *)buf);
    return n;
}

static int64_t avio_seek_callback(void *opaque, int64_t offset, int whence) {
    NpFfExt *E = (NpFfExt *)opaque;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) return -1;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) {
        return (*env)->CallLongMethod(env, E->bridge.reader, E->bridge.length_mid);
    }
    if (whence == SEEK_END) {
        int64_t len = (*env)->CallLongMethod(env, E->bridge.reader, E->bridge.length_mid);
        if (len < 0) return -1;
        offset += len;
        whence = SEEK_SET;
    }
    if (offset < 0) return -1;
    /* whence: SEEK_SET=0 / SEEK_CUR=1（avio 内部相对寻位，归一成绝对量） */
    int64_t target = (whence == SEEK_CUR) ? avio_tell(E->avio) + offset : offset;
    E->pending_seek = target;
    return target;
}

JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtOpen(
        JNIEnv *env, jclass clazz, jobject reader, jstring iformat_name) {
    (void)clazz;
    if (g_vm == NULL && (*env)->GetJavaVM(env, &g_vm) != JNI_OK) return 0;

    NpFfExt *E = (NpFfExt *)calloc(1, sizeof(NpFfExt));
    if (!E) return 0;

    E->bridge.reader = (*env)->NewGlobalRef(env, reader);
    if (!E->bridge.reader) goto fail;
    jclass reader_class = (*env)->GetObjectClass(env, reader);
    if (!reader_class) goto fail;
    E->bridge.read_mid = (*env)->GetMethodID(env, reader_class, "readFully", "([BI)I");
    E->bridge.length_mid = (*env)->GetMethodID(env, reader_class, "getLength", "()J");
    (*env)->DeleteLocalRef(env, reader_class);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto fail; }
    if (!E->bridge.read_mid || !E->bridge.length_mid) {
        ALOG("reader bridge methods not found read=%p length=%p",
             (void*)E->bridge.read_mid, (void*)E->bridge.length_mid);
        goto fail;
    }
    {
        jbyteArray jbuf_local = (*env)->NewByteArray(env, 64 * 1024);
        if (!jbuf_local) goto fail;
        E->bridge.jbuf = (jbyteArray)(*env)->NewGlobalRef(env, jbuf_local);
        (*env)->DeleteLocalRef(env, jbuf_local);
        if (!E->bridge.jbuf) goto fail;
    }

    E->pending_seek = -1;
    E->seek_target_us = -1;

    const char *fmt_name = iformat_name ? (*env)->GetStringUTFChars(env, iformat_name, NULL) : NULL;
    const AVInputFormat *iformat = NULL;
    if (fmt_name) {
        iformat = av_find_input_format(fmt_name);
        (*env)->ReleaseStringUTFChars(env, iformat_name, fmt_name);
    }

    unsigned char *avio_buf = (unsigned char *)av_malloc(64 * 1024);
    E->avio = avio_alloc_context(avio_buf, 64 * 1024, 0, E,
                                 avio_read_callback, NULL, avio_seek_callback);
    if (!E->avio) goto fail;
    /* 流模式：open/find_stream_info 期间禁内部回跳 seek（AVSEEK_SIZE 仍可用）。
     * 我们的 seek 回调只是"记账"（pending_seek 由 media3 重建输入物化），在
     * find_stream_info 内部发生时，其后续读会拿到错误位置的数据——E207 真机：
     * mpegps 的 PTS 估时回跳后视频流定型失败（codec 未知被丢轨）。 */
    E->avio->seekable = 0;

    E->fmt = avformat_alloc_context();
    if (!E->fmt) goto fail;
    E->fmt->pb = E->avio;
    E->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    AVDictionary *opts = NULL;
    int rc = avformat_open_input(&E->fmt, NULL, iformat, &opts);
    av_dict_free(&opts);
    if (rc < 0) {
        ALOG("avformat_open_input failed rc=%d", rc);
        goto fail;
    }
    ALOG("npff open ok streams=%d", E->fmt->nb_streams);
    return (jlong)(intptr_t)E;
fail:
    if (E->fmt) avformat_close_input(&E->fmt);
    if (E->avio) {
        if (E->avio->buffer) av_freep(&E->avio->buffer);
        avio_context_free(&E->avio);
    }
    if (E->bridge.reader) (*env)->DeleteGlobalRef(env, E->bridge.reader);
    if (E->bridge.jbuf) (*env)->DeleteGlobalRef(env, E->bridge.jbuf);
    free(E);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtFindStreams(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt) return -2;
    int rc = avformat_find_stream_info(E->fmt, NULL);
    if (rc < 0) { ALOG("find_stream_info failed rc=%d (%s)", rc, av_err2str(rc)); return -2; }
    /* 恢复可寻址：此后的用户 seek（npffExtSeekTimeUs）发生在 avformat_seek_file
     * 返回与下一次读之间，pending_seek 会被 Kotlin 在读之前物化，不产生失步 */
    E->avio->seekable = AVIO_SEEKABLE_NORMAL;
    E->has_video = 0;
    for (int i = 0; i < E->fmt->nb_streams; i++) {
        if (npff_type_of(E->fmt->streams[i]) == 1) { E->has_video = 1; break; }
    }
    if (E->fmt->duration > 0) E->duration_us = E->fmt->duration;
    ALOG("find_stream_info ok nb_streams=%d", E->fmt->nb_streams);
    return E->fmt->nb_streams;
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtStreamType(
        JNIEnv *env, jclass clazz, jlong handle, jint index) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt || index < 0 || index >= E->fmt->nb_streams) return 0;
    return npff_type_of(E->fmt->streams[index]);
}

JNIEXPORT jstring JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtStreamCodecName(
        JNIEnv *env, jclass clazz, jlong handle, jint index) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt || index < 0 || index >= E->fmt->nb_streams) return NULL;
    const char *name = avcodec_get_name(E->fmt->streams[index]->codecpar->codec_id);
    return (*env)->NewStringUTF(env, name ? name : "");
}

JNIEXPORT jintArray JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtStreamInfo(
        JNIEnv *env, jclass clazz, jlong handle, jint index) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt || index < 0 || index >= E->fmt->nb_streams) return NULL;
    const AVStream *st = E->fmt->streams[index];
    jint info[6];
    info[0] = st->codecpar->width;
    info[1] = st->codecpar->height;
    info[2] = st->codecpar->sample_rate;
    info[3] = st->codecpar->ch_layout.nb_channels;
    info[4] = st->codecpar->block_align;
    info[5] = st->codecpar->codec_tag;
    jintArray arr = (*env)->NewIntArray(env, 6);
    (*env)->SetIntArrayRegion(env, arr, 0, 6, info);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtStreamExtraData(
        JNIEnv *env, jclass clazz, jlong handle, jint index) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt || index < 0 || index >= E->fmt->nb_streams) return NULL;
    const AVStream *st = E->fmt->streams[index];
    if (st->codecpar->extradata_size <= 0 || !st->codecpar->extradata) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, st->codecpar->extradata_size);
    (*env)->SetByteArrayRegion(env, arr, 0, st->codecpar->extradata_size,
                               (const jbyte *)st->codecpar->extradata);
    return arr;
}

/** 音频流：合成完整 WAVEFORMATEX（18B 头 + extradata），csd 语义与 AVI 路径一致。 */
JNIEXPORT jbyteArray JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtStreamWavHeader(
        JNIEnv *env, jclass clazz, jlong handle, jint index) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt || index < 0 || index >= E->fmt->nb_streams) return NULL;
    const AVStream *st = E->fmt->streams[index];
    const AVCodecParameters *par = st->codecpar;
    if (par->codec_type != AVMEDIA_TYPE_AUDIO) return NULL;
    int extra = (par->extradata_size > 0 && par->extradata) ? par->extradata_size : 0;
    jbyteArray arr = (*env)->NewByteArray(env, 18 + extra);
    jbyte hdr[18];
    memset(hdr, 0, sizeof(hdr));
    jint tag = (jint)par->codec_tag;
    hdr[0] = (jbyte)(tag & 0xff);
    hdr[1] = (jbyte)((tag >> 8) & 0xff);
    jint ch = par->ch_layout.nb_channels;
    hdr[2] = (jbyte)(ch & 0xff);
    hdr[3] = (jbyte)((ch >> 8) & 0xff);
    jint rate = par->sample_rate;
    hdr[4] = (jbyte)(rate & 0xff);
    hdr[5] = (jbyte)((rate >> 8) & 0xff);
    hdr[6] = (jbyte)((rate >> 16) & 0xff);
    hdr[7] = (jbyte)((rate >> 24) & 0xff);
    jint byterate = par->bit_rate > 0 ? (jint)(par->bit_rate / 8) : 0;
    hdr[8] = (jbyte)(byterate & 0xff);
    hdr[9] = (jbyte)((byterate >> 8) & 0xff);
    hdr[10] = (jbyte)((byterate >> 16) & 0xff);
    hdr[11] = (jbyte)((byterate >> 24) & 0xff);
    jint align = par->block_align;
    hdr[12] = (jbyte)(align & 0xff);
    hdr[13] = (jbyte)((align >> 8) & 0xff);
    jint bits = par->bits_per_coded_sample > 0 ? par->bits_per_coded_sample : 16;
    hdr[14] = (jbyte)(bits & 0xff);
    hdr[15] = (jbyte)((bits >> 8) & 0xff);
    hdr[16] = (jbyte)(extra & 0xff);
    hdr[17] = (jbyte)((extra >> 8) & 0xff);
    (*env)->SetByteArrayRegion(env, arr, 0, 18, hdr);
    if (extra > 0) {
        (*env)->SetByteArrayRegion(env, arr, 18, extra, (const jbyte *)par->extradata);
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtDurationUs(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    return (E && E->duration_us > 0) ? E->duration_us : 0;
}

/** 读下一包：0=包已缓存，1=需要 seek（PendingSeekPos 取目标），-1=EOF，-2=错误 */
JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtRead(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt) return -2;
    if (E->pending_seek >= 0) return 1;
    if (E->eof) return -1;
    if (E->has_packet) return 0;
    if (!E->pkt) E->pkt = av_packet_alloc();
    while (1) {
        int rc = av_read_frame(E->fmt, E->pkt);
        if (rc == AVERROR(EAGAIN)) continue;
        if (rc == AVERROR_EOF) { E->eof = 1; return -1; }
        if (rc < 0) { ALOG("read err rc=%d (%s)", rc, av_err2str(rc)); return -2; }
        AVStream *st = E->fmt->streams[E->pkt->stream_index];
        if (npff_type_of(st) == 0 || E->pkt->size <= 0) {
            av_packet_unref(E->pkt);
            continue;
        }
        if (E->seek_target_us >= 0 || E->seek_wait_key) {
            int type = npff_type_of(st);
            int key = (E->pkt->flags & AV_PKT_FLAG_KEY) != 0;
            int64_t ts = (E->pkt->pts != AV_NOPTS_VALUE) ? E->pkt->pts : E->pkt->dts;
            int64_t us = (ts == AV_NOPTS_VALUE)
                             ? 0
                             : av_rescale_q(ts, st->time_base, (AVRational){1, 1000000});
            if (E->seek_target_us >= 0) {
                /* "回起点重读"寻位的丢包闸：目标之前的包（含早到的音频）全部丢弃。
                 * **不能**在第一个 ≥ target 的包上清掉目标：音频与视频交错时，
                 * 首个 ≥ target 的往往是视频，后面还会跟一批 pts 仍 < target 的
                 * 音频（本夹具视频 30.02s 时音频才 29.60s）。旧写法会让音频从
                 * 目标前 0.4s 开始播；目标保留到下一次 seek，天然只丢迟到包。 */
                if (us < E->seek_target_us) {
                    av_packet_unref(E->pkt);
                    continue;
                }
            }
            /* 注意 type 是 npff_type_of 的映射（1=视频/2=音频），不是 AVMediaType
             * 原生枚举（AVMEDIA_TYPE_VIDEO=0）；旧写法把 1 和 0 比较，等待关键帧
             * 从未生效（E208 真机测试发现）。 */
            if (E->seek_wait_key && type == 1) {
                /* 视频必须等到关键帧；音频/其它轨照常放行，别拿空白拖住媒体时钟。 */
                if (!key) {
                    av_packet_unref(E->pkt);
                    continue;
                }
                E->seek_wait_key = 0;
                ALOG("seek keyframe reached: pkt=%lldus", (long long)us);
            }
        }
        E->has_packet = 1;
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPacketStream(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    return (E && E->has_packet && E->pkt) ? E->pkt->stream_index : -1;
}

JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPacketPtsUs(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->has_packet || !E->pkt || !E->fmt) return 0;
    AVStream *st = E->fmt->streams[E->pkt->stream_index];
    /* pts 缺失时回退 dts：部分 PES 只在部分包上带 PTS，恒回 0 会让媒体时钟倒退。 */
    int64_t ts = (E->pkt->pts != AV_NOPTS_VALUE) ? E->pkt->pts : E->pkt->dts;
    if (ts == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(ts, st->time_base, (AVRational){1, 1000000});
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPacketFlags(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    return (E && E->has_packet && E->pkt && (E->pkt->flags & AV_PKT_FLAG_KEY)) ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPacketSize(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    return (E && E->has_packet && E->pkt) ? E->pkt->size : 0;
}

/** 把缓存包的数据拷进 direct buffer；返回拷贝字节数。包随即释放（消费即取走）。 */
JNIEXPORT jint JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPacketData(
        JNIEnv *env, jclass clazz, jlong handle, jobject out, jint capacity) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->has_packet || !E->pkt) return 0;
    uint8_t *out_ptr = out ? (*env)->GetDirectBufferAddress(env, out) : NULL;
    /* 以 direct buffer 的**真实** capacity 为准再夹一次。旧实现只信 Kotlin 传来的
     * pendingSize，包大于缓冲时会写穿 native 堆（E208 审查发现的高危缺陷）。 */
    jlong cap = out ? (*env)->GetDirectBufferCapacity(env, out) : 0;
    jint n = 0;
    if (out_ptr && cap > 0) {
        n = E->pkt->size < capacity ? E->pkt->size : capacity;
        if ((jlong)n > cap) n = (jint)cap;
        memcpy(out_ptr, E->pkt->data, (size_t)n);
    }
    av_packet_unref(E->pkt);
    E->has_packet = 0;
    return n;
}

JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtPendingSeekPos(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E) return -1;
    int64_t p = E->pending_seek;
    E->pending_seek = -1;
    return p;
}

/** 回文件起点重读（mpegps 以及所有 avformat_seek_file 失败的容器）。
 *
 * **必须先 avformat_flush**：字节流回卷后 demuxer 的 relative-ts 重锚
 * （cur_dts/first_dts 平移）会在"已消费 N 秒回到 0"上算出 +1e6s 的垃圾平移
 * （E207 真机：视频包 pts 全体 +1e12µs，时钟冻结）。
 * **还必须 avio_seek(0)**：avformat_flush 不清 AVIO 读缓冲，下一轮 av_read_frame
 * 会先吐旧位置的数据 —— E208 审查发现。avio_seek 把缓冲作废，media3 重开数据源后
 * 从 0 重新读；若 0 恰在缓冲内（缓冲从文件头开始），重开位置取缓冲末端，先消费
 * 0..末端 的有效数据再从末端续读，不重复也不跳。 */
static void npff_restart_from_zero(NpFfExt *E, int64_t target_us) {
    avformat_flush(E->fmt);
    int64_t rc = avio_seek(E->avio, 0, SEEK_SET);
    E->seek_target_us = target_us < 0 ? 0 : target_us;
    E->seek_wait_key = E->has_video;
    E->eof = 0;
    E->pending_seek = (rc >= 0) ? npff_data_pos(E) : 0;
    ALOG("seek fallback: target=%lld restart dataPos=%lld waitKey=%d",
         (long long)E->seek_target_us, (long long)E->pending_seek, E->seek_wait_key);
}

/** 按时间 seek：优先 demuxer 自带 read_seek（asf/rm/flv 有）；**没有 read_seek 的容器
 * （mpegps/nut）绝不走 avformat_seek_file**——其通用字节寻位会在内部做 avio 探测
 * seek，在"记账式"AVIO 桥下失步，把 relative-ts 重锚算出 +1e6s 的垃圾平移
 * （E207 真机：此后视频包 pts 全体 +1e12µs，媒体时钟冻结）。改用"回到文件起点
 * 重读 + npffExtRead 按目标时间丢包"的安全路径（ demuxer 在下一 pack 起始码自然重同步）。 */
JNIEXPORT jlong JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtSeekTimeUs(
        JNIEnv *env, jclass clazz, jlong handle, jlong time_us) {
    (void)env; (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E || !E->fmt) return -1;
    if (E->has_packet) {
        av_packet_unref(E->pkt);
        E->has_packet = 0;
    }
    E->pending_seek = -1; /* 清残留：seek 目标由本次寻位重新记账 */
    E->eof = 0;
    E->seek_wait_key = 0;
    int64_t target = time_us < 0 ? 0 : time_us;
    /* FFmpeg 7 起 read_seek 在内部 FFInputFormat 上，公共头看不到；我们只有五个
     * 容器，其中仅 MPEG-PS 没有可用 demuxer seek。**按名判断必须用 "mpeg"**：
     * `av_find_input_format("mpegps")` 能按别名找到，但 iformat->name 是 "mpeg"
     * （libavformat/mpeg.c 的 ff_mpegps_demuxer），旧写法永远不命中，实际一直走
     * 通用 avformat_seek_file（E208 审查发现）。 */
    const char *iformat_name = E->fmt->iformat->name;
    if (iformat_name != NULL &&
        (strcmp(iformat_name, "mpeg") == 0 || strcmp(iformat_name, "mpegps") == 0)) {
        npff_restart_from_zero(E, target);
        return target;
    }
    int rc = avformat_seek_file(E->fmt, -1, INT64_MIN, target, target, 0);
    if (rc < 0) {
        /* 该容器没有可用 seek（FLV 未开 FAST_SEEK 就走这里）：退回通用
         * "回起点 + 丢包"，旧实现退回 seek(0) 会让拖动落到片头。 */
        npff_restart_from_zero(E, target);
        return target;
    }
    E->seek_target_us = -1;
    E->seek_wait_key = 0;
    /* 缓冲区内部寻位不会触发回调（pending 仍是 -1）：重开位置必须取缓冲区末端，
     * 否则 AVIO 的旧缓冲和重开数据源会重复读同一段。 */
    E->pending_seek = npff_data_pos(E);
    ALOG("seek ok target=%lld dataPos=%lld", (long long)target, (long long)E->pending_seek);
    return target;
}

JNIEXPORT void JNICALL
Java_com_noneplayer_core_media_ffmpeg_NpFfmpegNative_npffExtClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    NpFfExt *E = (NpFfExt *)(intptr_t)handle;
    if (!E) return;
    if (E->pkt) av_packet_free(&E->pkt);
    if (E->fmt) avformat_close_input(&E->fmt);
    if (E->avio) {
        if (E->avio->buffer) av_freep(&E->avio->buffer);
        avio_context_free(&E->avio);
    }
    if (E->bridge.reader) (*env)->DeleteGlobalRef(env, E->bridge.reader);
    if (E->bridge.jbuf) (*env)->DeleteGlobalRef(env, E->bridge.jbuf);
    free(E);
}
