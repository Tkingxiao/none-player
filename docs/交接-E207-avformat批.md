# 交接文档：E207 avformat 解封装批（可删除）

> **本文档是临时交接件**：新会话接手并完成本批后，按《任务文档》流程走完文档流转即可删除本文件。
> 会话背景：E206（FFmpeg 解码白名单）已于提交 `57e3bdc` 完成入库；本批（E207）在使 avformat 解封装（asf/rm/flv/mpegps/nut）跑通，**工作区有未提交改动**，真机验证进行到 FLV 卡在最后一环。

## 1. 本批目标（任务文档 §9.3）

`--enable-avformat` + demuxer asf/rm/flv/mpegps/nut + 自建 FFmpegExtractor + SeekMap，使 VC-1/WMV3-ASF、RV-RM、FLV1/VP6-FLV、MPEG2-PS、FFV1-NUT 正片可播（E198 D-3 裁定：avformat 随本批做，不单独提前开）。

## 2. 当前工作区状态（未提交，全部属于本批）

### 已完成并验证 ✅

1. **构建**：`None Player/core/media-android/src/main/cpp/build-ffmpeg.sh` 已解禁 avformat、加 `--enable-demuxer=asf,rm,flv,mpegps,nut` 与相关 parser、拷贝段带上 `libavformat.a`/`libswresample.a`。**四 ABI 已全部重编完成**（WSL：`wsl -d Ubuntu -- bash -lc 'cd "/mnt/d/Code/Player/None Player/core/media-android/src/main/cpp" && NPFF_ABIS=<abi> bash ./build-ffmpeg.sh'`，逐 ABI 跑，约 1 分钟/ABI）。产物已入 `prebuilt/<abi>/lib/`（libavformat.a ≈674KB/arm64，include/libavformat 头已带）。
2. **C 解封装模块**：`np_ffmpeg_extractor.c`（新增）——AVIO 读桥（回调转译 Kotlin 的 `NpExtractorReaderImpl.readFully/getLength`）+ `npffExt*` 全套 JNI（Open/FindStreams/StreamType/StreamCodecName/StreamInfo/StreamExtraData/StreamWavHeader/DurationUs/Read/PacketStream/PacketPtsUs/PacketFlags/PacketSize/PacketData/PendingSeekPos/SeekTimeUs/Close）。设计要点：**无 JNI 回调到 TrackOutput**（包缓存在 C 侧，Kotlin 二段式查询+drain，与 E206 音频段同风格）；AVIO seek 回调只记 `pending_seek`，由 `npffExtRead` 返回 1 上报，Kotlin 回 RESULT_SEEK。CMakeLists 已加 npavformat IMPORTED 库并链入 npffmpeg；npffmpeg.map 已导出全部 npffExt*；含 `ff_aom_uninit_film_grain_params` 空 stub（avformat 引用但 aom 解码器未编入）。
3. **Kotlin 侧**：
   - `core/media-android/.../ffmpeg/NpFfmpegNative.java`：npffExt* 声明（注意：没有 `npffExtSetInput`——输入由 Kotlin 桥自持）。
   - `core/media-android/.../ffmpeg/NpExtractorReaderImpl.kt`：读桥实现（`readFully([BI)I`、`getLength()J`——**C 侧 GetMethodID 按这两个名字找**；Kotlin 属性 `length` 编译成 getLength）。当前 readFully 里带调试日志（readFully len/n/head），收尾时可留可删。
   - `core/media-android/.../extract/NpFfmpegExtractor.kt`（新增）：media3 Extractor。sniff 只比 4 字节 magic；read 流程 = attachInput →（首次）isAvailable 闸 + npffExtOpen → findStreams/emitStreams → 读包/dispatchPending；SeekMap 恒 isSeekable（seek 走 npffExtSeekTimeUs + AVIO 挂起循环）。**已修的坑见 §3**。
   - `NpExtractorsFactory.kt`：六个 NpFfmpegExtractor（asf/rm/flv/mpegps/nut）插在 Wmv2AviExtractor 之后、media3 defaults 之前。
   - `FormatProbe.kt`：`containerFromExtractor` 识别 NpFfmpegExtractor（返回 containerDisplayName）。
   - `app/proguard-rules.pro`：`-keep class com.noneplayer.core.media.ffmpeg.NpExtractorReaderImpl { *; }`（**必须整类 keep**——JNI GetObjectClass+GetMethodID 依赖类名/方法名恒定；keepclassmembers 不够，R8 类合并会绕过）。
4. **真机验证进度**：
   - **ASF ✅ 完全通过**：`wl-wmv2-wmav2.asf`（WMV2+wmav2）`npff open ok streams=2` → PLAYING、position 推进、出画（截图在仓库根 `asf-playing.png`，testsrc2 彩条+时间码可见）。其后的 ERROR 是 10 秒片播完后连播到设备上既有的 xvid-clip.avi（320x240 mp4v-es）失败——既有行为，非本批问题。
   - **FLV ⏸ 卡最后一环**（详见 §4）。
   - PS/NUT 未验（夹具已在设备 /sdcard/Movies/：`wl-mpeg2-mp2.mpg`、`wl-ffv1.nut`；NUT 未入 MediaStore，push 时 NUT 没扫上，需重推/重扫）。
5. **夹具**：`D:/Code/Player/fixtures/e207/`（wl-wmv2-wmav2.asf / wl-flv1-mp3.flv / wl-mpeg2-mp2.mpg / wl-ffv1.nut），生成命令在 §4。设备 content id：asf=1000015394、flv=1000015395、mpg=1000015396、nut 未入库。

### 真机操作备忘（新会话直接用）

- 设备 60dd2f94（小米平板 6）。装包：`adb -s 60dd2f94 install -r "None Player/app/build/outputs/apk/release/app-release.apk"`。
- **全程静音要求**：`adb shell "cmd notification set_dnd on"`，结束 `set_dnd off`。
- 深链播放：`MSYS_NO_PATHCONV=1 adb shell "am start -a android.intent.action.VIEW -d 'noneplayer://player?uri=content%3A%2F%2Fmedia%2Fexternal%2Fvideo%2Fmedia%2F<id>&mime=video%2Favi' com.noneplayer.app"`（**MSYS_NO_PATHCONV=1 必带**，否则路径被 Git Bash 改写）。
- 看状态：`adb shell "dumpsys media_session | grep -m1 state=PlaybackState"`；日志过滤 tag：`NpFfmpegExtractor` / `FormatProbe` / `NpPerf` / `NpFfmpeg`。
- 注意：`adb logcat -d` 偶发只回 2 行——用 `adb shell "logcat -d" > 文件` 或 `adb logcat -d > 文件` 重试；build 出现 `bundleLibCompileToJarRelease FAILED`（文件锁）时 `./gradlew --stop` 后重跑。

## 3. 本批已修的坑（真机逐一实锤，勿回退）

1. **attach 必须先于 open**：`npffExtOpen` 内 avformat_open_input 的首个读回调就发生在 open 调用栈内——先 open 后 attachInput 会拿到 NULL 输入（真机 `readFully: input NULL` → INVALIDDATA）。
2. **FLV magic 只有 3 字节**：第 4 字节是版本号 0x01，`46 4C 56 00` 永远匹配不上 → 让 media3 的 FlvExtractor 抢走、只解出音频。已改为 3 字节。
3. **FLV 首 tag 启发式已删**：首 tag 常是 onMetaData（0x12 脚本 tag），按"首视频 tag codec"分流不可靠；FLV 全量走 avformat（其 H.264-FLV 一样能解）。
4. **reader 桥必须整类 keep**（见上）；`length` 属性在 JVM 名为 `getLength`，C 侧签名 `"getLength" "()J"`。
5. **libnpffmpeg 加载闸**：NpFfmpegExtractor.read 里先 `NpFfmpegCodecs.isAvailable()`——否则 UnsatisfiedLinkError 直接崩 Loader。
6. E206 遗留同款：`npffmpeg.map` 漏导出符号 = UnsatisfiedLinkError；`prebuilt-nextlib/` 共享库路线已废弃删除（CMake 现在只链静态 prebuilt）。

## 4. 当前卡点：FLV 播放 STOPPED（下一步从这里开始）

现象（16:17 会话日志，工作区 `dlf2.txt` 有全量）：
- `npff open ok streams=0`（avformat 惰性建流正常）→ 探测 `matched=zm2`（我们的 NpFfmpegExtractor）、`mimes=[audio/mpeg, video/x-flv1]` **两条都出来了**（说明 findStreams/emitStreams 在探测会话中成功）。
- 播放会话：`NpFfmpegRenderer surface clone ok`（视频渲染器认领了 x-flv1）→ **之后无 Playback error、无 FATAL，状态直接 STOPPED/NONE**，audio focus request 后无下文。
- 疑点排序（按可能性）：
  1. `NpFfmpegExtractor` 对 `streams=0` 的处理：`npffExtOpen ok` 但 `npffExtFindStreams` 返回 0 → emitStreams 空 → `endTracks` 后立即 END_OF_INPUT？读 `read()` 的 `if (n <= 0) return RESULT_END_OF_INPUT`——**findStreams==0 时直接退出了**，但探测会话里两条 mime 又出来了（探测与播放是两次 open，第二次 open 可能复用了同一 C handle 状态或 seek 到了尾部）。重点查：播放会话中 `npffExtFindStreams` 返回值与 `E->fmt->nb_streams` 的关系；FLV 的 avformat 需要 `find_stream_info` 后才建流，而我们的 open 时 `avformat_open_input` 不够。
  2. SeekMap 的 SeekPoint position 恒 0 + seek(position, timeUs) 的实现：media3 在起播时会 seek(0, 0)——我们的 `seek()` 调 `npffExtSeekTimeUs`（走 avformat_seek_file → AVIO seek 挂起），若 open 后立即 seek 引发读循环异常，会静默结束。建议真机抓 `npffExtRead` 返回值日志（C 侧临时 ALOG）确认 read 循环是否活着。
  3. x-flv1 的 mime→解码器路由：`NpFfmpegCodecs.videoCodecName("video/x-flv1")` **当前表里没有这个 mime**（E206 表只有 wmv 族/vc1/mp42/mp43）→ NpFfmpegVideoRenderer 不认领 → 视频轨无人接 → 可能整会话静默失败。**这是最大嫌疑**：修法 = 在 NpFfmpegCodecs.videoCodecs 表加 `"video/x-flv1" to "flv1"`（以及 FLV 的 vp6 → "vp6/vp6f"，mpegps 的 mpeg2video → "mpeg2video" 若平台没有，rm 的 rv 系 → "rv10" 等），同时 NpFfmpegExtractor.mimeForCodecName 与之对齐。改完跑 `NpFfmpegCodecsTest` 更新断言。
  4. 音频 mp3 走 NextLib 标准链（audio/mpeg 平台/NextLib 都支持），不是问题。

调试工具链：`adb shell "logcat -d -s NpFfmpegExtractor FormatProbe NpFfmpeg"`（-s 筛 tag）；读桥日志在 NpExtractorReaderImpl.readFully（len/n/head），可看 avformat 读流是否正常。

## 5. 剩余工作清单（按序）

1. **修 FLV 停在 STOPPED**（§4，先查嫌疑 3：x-flv1 解码路由缺表项；再查嫌疑 1/2）。
2. **验证 PS**（wl-mpeg2-mp2.mpg，id=1000015396）：mpeg2video 平台一般有软解；若路由缺失同 §4-3 处理。
3. **NUT 夹具入库**（wl-ffv1.nut 未进 MediaStore）：`adb push` + MEDIA_SCANNER_SCAN_FILE 重扫，拿 id 后深链验证。ffv1 平台无解码器、NextLib 也没有——**NUT 预期"探测可读、视频无解码器"的诚实失败**，验证点是探测结论正确（不是崩溃/误报），这本身是合格边界。
4. **rm 真机夹具缺**：本地 ffmpeg 无 RealMedia 编码器（imageio-ffmpeg 构建）；rv10/rv20 可用 `-f rm` 试，编不出就按 §4-3 的表注册 + 探测读数记边界（与 E206 的 vc1/wmv3 同口径）。
5. **收尾**：readFully 调试日志可精简；跑全量门禁 `./gradlew test lint :app:assembleRelease`（文件锁时 `./gradlew --stop`）；体积读数（E206 后 release 28.1MB，avformat 预计 +1.5~2MB 总量）；任务文档 §9.3 的 FFmpeg 白名单条目收口（avformat 部分完成、rm 夹具边界如实记）；完成文档加 E207 条目；证据入 `docs/evidence/e207-2026-10-24-device.txt`；提交（含 prebuilt 静态库二进制）。
6. **清理**：本文件 + 仓库根的所有 `dl*.txt / ms*.txt / lc*.txt / devlog*.txt / asf-playing.png` 取证产物（有价值行摘录入 evidence 后删）。

## 6. 环境备忘

- WSL Ubuntu 存活且构建环境完整：`~/src/nplegacy-ffmpeg/`（ffmpeg-7.1.1 源 + NDK r27c + 四 ABI install）。
- 本地 ffmpeg：`python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"`（7.1，含 wmav2/flv1/mpeg2video/mp2/ffv1/nut muxer；无 RealMedia 编码器）。
- 夹具生成参考命令（E207 实际用的）：
  - asf: `-c:v wmv2 -b:v 400k -c:a wmav2 -b:a 96k wl-wmv2-wmav2.asf`
  - flv: `-c:v flv1 -b:v 400k -c:a mp3 -b:a 128k wl-flv1-mp3.flv`
  - ps: `-c:v mpeg2video -b:v 500k -c:a mp2 -b:a 128k -f vob wl-mpeg2-mp2.mpg`
  - nut: `-c:v ffv1 -f nut wl-ffv1.nut`
- 全局 adb 有时双设备（60dd2f94 平板 + HUBQRGI7B6VGVGY9 手机），统一 `export ANDROID_SERIAL=60dd2f94`。
- 门禁纪律：`./gradlew --stop` 解 classes.jar 文件锁；门禁命令不接 `| head`（SIGPIPE 假绿）。

## 7. 架构速查（本批相关文件）

```
None Player/core/media-android/src/main/cpp/
  build-ffmpeg.sh          # avformat + demuxer 已开；NPFF_ABIS 逐 ABI
  np_ffmpeg_extractor.c    # AVIO 桥 + npffExt* JNI（本批新增）
  npffmpeg.map             # 导出表（含全部 npffExt*）
  CMakeLists.txt           # npavformat 链入
  prebuilt/<abi>/lib/      # 静态库（libavformat.a 新增）
None Player/core/media-android/src/main/
  java/.../ffmpeg/NpFfmpegNative.java   # npffExt* 声明
  kotlin/.../ffmpeg/NpExtractorReaderImpl.kt  # 读桥（keep 整类）
  kotlin/.../extract/NpFfmpegExtractor.kt     # media3 Extractor ×5 容器
  kotlin/.../extract/NpExtractorsFactory.kt   # 六实例插入
  kotlin/.../probe/FormatProbe.kt             # 容器名识别
None Player/plugins/plugin-player-exo/.../
  NpRenderersFactory.kt     # 视/音频渲染器链（E206）
  NpFfmpegCodecs.kt(在 core/media-android/.../ffmpeg/)  # mime→解码器表【FLV/PS 的 mime 可能要补进 videoCodecs】
None Player/app/proguard-rules.pro  # -keep NpExtractorReaderImpl
fixtures/e207/            # 四夹具
```
