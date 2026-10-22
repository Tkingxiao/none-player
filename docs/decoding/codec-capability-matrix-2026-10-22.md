# 平台编解码能力矩阵（R0/T1）

日期：2026-10-22　状态：**两台基线设备已导出；声明能力 ≠ 首帧/转码实测**
方案：`docs/编解码与转码优化方案-2026-10-22.md` §6.3、§6.8 T1
基线：`2c5cb82` + 本轮工作树

本文记录 `CodecCapabilitySnapshot` 的抓取方法、两台设备的运行时声明与判定口径。数据全部来自
`MediaCodecList.REGULAR_CODECS`，不是厂商 XML 推演；`ProfileLevel` 在厂商 XML 里拿不到，
这正是本页存在的原因。

---

## 1. 产物与复核

| 设备 | 快照文件 | 字节 | SHA-256 | 内容 |
| --- | --- | ---: | --- | --- |
| 骁龙平板 `60dd2f94` / `23043RP34C` | [`snapshots/codec-capability-23043RP34C-2026-10-22.json`](snapshots/codec-capability-23043RP34C-2026-10-22.json) | 78,667 | `5569f5ed63e269bbb16b20fdc5b579d4a1efe1251bd8fbf374bb269968abe874` | 72 codec（45 解码 / 27 编码）、0 查询错误 |
| 天玑手机 `HUBQRGI7B6VGVGY9` / `2407FRK8EC` | [`snapshots/codec-capability-2407FRK8EC-2026-10-22.json`](snapshots/codec-capability-2407FRK8EC-2026-10-22.json) | 93,881 | `8971f26b6e0b375d881ffda4a7919f6ce2d1879531b9f33ecea4156e7fb2d14b` | 90 codec（64 解码 / 26 编码）、0 查询错误 |

- 快照含 `capturedAt` 时间戳，重新导出会改变文件哈希；上表哈希对应仓库内的这一份。
- `cacheKey`：骁龙 `cfd7d9380d6a4a321f4da8dceaedf330fcba4a679fd8b9b934d50b50be64936b`，
  天玑 `665d01fd2a8fb76e2c966a0e5e5df3cebc071b0f159f6873f803e436d160913b`。
  键由指纹、API、应用版本（本轮测试 APK 无 `versionName`，落 `null`）与 codec/能力摘要生成。
- 本页判定来自 instrumented `CodecCapabilitySnapshotTest`，两机均 `OK (3 tests)`；
  JVM 决策由 `CodecCapabilityDecisionTest` 覆盖。

### 导出命令（PowerShell）

```powershell
cd "D:\Code\Player\None Player"
./gradlew :core:media-android:assembleDebugAndroidTest --offline
adb -s <serial> install -r -t core/media-android/build/outputs/apk/androidTest/debug/media-android-debug-androidTest.apk
adb -s <serial> shell am instrument -w -e class com.noneplayer.core.media.probe.CodecCapabilitySnapshotTest `
  com.noneplayer.core.media.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> pull `
  /storage/emulated/0/Android/data/com.noneplayer.core.media.android.test/files/codec-capability-snapshot.json `
  docs/decoding/snapshots/codec-capability-<model>-2026-10-22.json
```

Git Bash 下 `adb pull` 前加 `MSYS_NO_PATHCONV=1`，本地目标用 `D:/...`，否则 `/storage/...`
会被 MSYS 改写成本地路径。

---

## 2. 设备身份

| 项 | 骁龙平板 | 天玑手机 |
| --- | --- | --- |
| 型号 / 设备 | `23043RP34C` / `pipa` | `2407FRK8EC` / `rothko` |
| 系统 | Android 14 / API 34 | Android 16 / API 36 |
| SoC | QTI `SM8250` | MediaTek `MT6989` |
| ABI | `arm64-v8a,armeabi-v7a,armeabi` | **仅 `arm64-v8a`** |
| 指纹 | `Xiaomi/pipa/pipa:14/UKQ1.240624.001/OS2.0.20.0.UMZCNXM:user/release-keys` | `Redmi/rothko/rothko:16/BP2A.250605.031.A3/OS3.0.303.0.WNNCNXM:user/release-keys` |

---

## 3. 视频解码矩阵（运行时声明）

| 项 | 骁龙平板 | 天玑手机 |
| --- | --- | --- |
| HEVC 硬解组件 | `OMX.qcom.video.decoder.hevc` | `c2.mtk.hevc.decoder`（另有 `OMX.MTK…HEVC`、`.lowlatency`） |
| HEVC 硬解尺寸 / 实例 | 128x128–8192x8192 / 16 | 16x16–8192x4352 / 15 |
| HEVC 硬解 profile | Main、**Main10**、MainStill、Main10 HDR10、Main10 HDR10+，均为 High tier 6.1 | Main、**Main10**、MainStill、Main10 HDR10，均为 High tier 6.1；**未声明 HDR10+** |
| HEVC 软解兜底 | `c2.android.hevc.decoder`：Main/MainStill @5.2H，2x2–4096x4096，**无 Main10** | 同名软解：Main/MainStill @5.2H，2x2–1920x1088，**无 Main10** |
| **Main10 合成流判定** | 1920x1080@23.976、level 5.1 → **SUPPORTED**（`OMX.qcom…hevc`） | 同流 → **SUPPORTED**（`c2.mtk.hevc.decoder`） |
| AVC 硬解 | 8192x8192 / 16；Baseline/Main/High/Constrained @6.1，无 High10 | 8192x4320 / 15；Baseline/Main/High/**High10**/ConstrainedBaseline @6 |
| AV1 | **无硬解**；软解 `c2.android.av1.decoder` Main8/Main10/HDR10/HDR10+，2048x2048 | **硬解** `c2.mtk.av1.decoder`（含 lowlatency）Main8/Main10/HDR10，8192x4320 / 15；软解 1080p 级 |
| VP9 | 硬解 Profile0/2/HDR/HDR10+，8192x8192 / 6 | 硬解 Profile0/1/2/HDR，8192x4320 / 15 |
| Dolby Vision | `video/dolby-vision` 挂在 **HEVC 与 AVC 硬解**上 | 独立 `c2.dolby.decoder.hevc`（仅 `video/dolby-vision`） |
| low-latency 解码变体 | **无** | AVC / HEVC / VP9 / AV1 **均有 `.lowlatency`** |

判定口径：`CodecCapabilityProbe.decoderDecision(Format)` 现场问
`findDecoderForFormat` 真正选中的那一个，再用 HEVC `hvcC` 的 `profile_idc` / `general_tier_flag` /
`general_level_idc` 与它的 `profileLevels` 求交。profile 或 level 读不出时返回 **UNKNOWN**，
不冒充 SUPPORTED。

**Main10 结论**：两台机器的硬件解码器都声明 Main10；软件兜底都只有 Main。因此 10bit HEVC
不能依赖软解兜底，必须在 `configure` 前确认实际选中的是声明 Main10 的硬解组件。方案
§12 的“两台机器 Main10 无证据”至此关闭；实际首帧仍需 T2/T3 素材与转码基准。

---

## 4. 视频编码矩阵（运行时声明）

| 项 | 骁龙平板 | 天玑手机 |
| --- | --- | --- |
| HEVC 硬编 | `OMX.qcom.video.encoder.hevc`：CBR+VBR，128x128–7680x7680，16，Surface ✓ | `c2.mtk.hevc.encoder`：**CBR+VBR+CQ**，160x128–7680x4320，10，Surface ✓ |
| HEVC CQ 专用组件 | `OMX.qcom.video.encoder.hevc.cq`：仅 CQ，**128x128–512x512**，16 | 无独立组件；同一 `c2.mtk.hevc.encoder` 声明 CQ |
| HEVC 1080p CQ 判定 | **UNSUPPORTED**（`findEncoderForFormat` 找不到 1080p CQ 编码器） | **SUPPORTED**（`c2.mtk.hevc.encoder`） |
| HEVC 1080p VBR 判定 | SUPPORTED（`OMX.qcom.video.encoder.hevc`） | SUPPORTED（`c2.mtk.hevc.encoder`） |
| HEVC 512x512 CQ 判定 | SUPPORTED，但选中 **软编** `c2.android.hevc.encoder`（CBR+VBR+CQ，2x2–512x512） | SUPPORTED，选中硬编 `c2.mtk.hevc.encoder` |
| AVC 硬编 | `OMX.qcom.video.encoder.avc`：**仅 VBR**，8192x8192，16，Surface ✓ | `c2.mtk.avc.encoder`：**仅 VBR**，7680x4320，10，Surface ✓ |
| AVC 1080p VBR 判定 | SUPPORTED | SUPPORTED |

结论：

1. **CQ 必须逐 Format/尺寸探测，不能按设备家族假设**。骁龙有 CQ 组件但上限 512x512；
   1080p 必须回退 VBR。天玑 1080p CQ 可用。
2. 512x512 CQ 在骁龙会选中软编而不是 `.hevc.cq`；调用方若需要硬件，必须按组件名显式选择，
   但方案 §6.3 禁止按前缀猜能力——只能按该组件的 `VideoCapabilities` 与 Surface 声明再判定。
3. 两台硬编的 **AVC 都只有 VBR**；默认 H.264 输出不能承诺 CBR/CQ。
4. 所有视频编码器都声明了 `COLOR_FormatSurface`，P2 的 Surface→Surface 地基成立；
   声明成立不等于 `configure`/首帧成立，仍需 T3 验证。

---

## 5. 本轮修正的实现假设

运行时数据暴露了两处旧常量/假设错误，已在本轮源码修正：

1. **HEVC level 常量不是单调旧枚举**。Android 14+ 使用
   `HEVCMainTierLevel*`（1/4/16/…）与 `HEVCHighTierLevel*`（2/8/32/…）两套位序列；
   骁龙硬解报 `8388608`（High tier 6.1），不是旧的 `HEVCLevel6_1`。判定改为
   “level 排名 + tier”，High tier 码流要求解码器也声明 High tier。
2. **`HEVCProfileMainStill = 4`**（不是 3）。`hvcC` 的 `profile_idc=3` 映射到 Android 常量 4。
3. `HEVCProfileMain10HDR10/HDR10+` 视为满足 Main10 求交；HDR10+ 只是 HDR10 的超集，
   不要求流本身声明 HDR10+。

---

## 6. 判定与缓存口径

- 纯决策：`CodecCapabilityDecider.evaluate/choose`，三态 `SUPPORTED / UNSUPPORTED / UNKNOWN`。
- 实时闸门：`CodecCapabilityProbe.decoderDecision` / `encoderDecision` 永远现场查询；
  持久化快照只作加速，`configure`/`addTrack` 前必须复核。
- `CodecCapabilitySnapshot.cacheKey()` 覆盖指纹、API、应用版本、codec/能力摘要；
  任一变化即失效。
- 只有 **声明证据**，不是首帧/吞吐/温控证据；转码验收仍按方案 §6.8 的 T2/T3 固定素材基线。

---

## 7. 仍未覆盖

| 项 | 状态 | 下一步 |
| --- | --- | --- |
| HEVC Main10 / HDR10 / DV / AV1 10bit 的**实片首帧** | 未测 | T2/T3 素材与固定设备基线 |
| 转码吞吐、温升、CQ/VBR 实际画质 | 未测 | T3 |
| AVC / AV1 码流 profile 解析 | 未实现 | 方案 A4（R3）；本页只解析 HEVC `hvcC` |
| 持久化快照/DataStore 缓存 | 未实现 | 转码作业落库时接入；不得替代实时复核 |
| 生产探测接入新闸门 | 未接入 | A4/R3；当前 `FormatProbe` 仍只对 mp4v 做 profile 求交 |
| 应用版本进入快照 | 测试 APK 为 `null` | 生产调用传 `versionName` |
| Dolby Vision 分流 | 未实现 | C1；两台设备都在运行时暴露了 DV 组件，**不识别就会静默偏色** |
