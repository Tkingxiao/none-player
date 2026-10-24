# None Player R8 规则
#
# 插件发现为 KSP 编译期代码生成（显式 new，无反射），理论上无需 keep。
# 若后续引入 ServiceLoader/反射路径，在此补充对应 keep 规则。

# ---- 扩展点接口：禁止 R8 类合并（2026-10-12 release 闪退根因）----
#
# `DefaultExtensionRegistry` 以 **Class 对象**为键分桶（flowFor(clazz)）。R8 的类合并
# 会把"单实现者的接口"合并成同一个运行时类——实测 release 包中 `ScanFolderController`
# 与另一扩展接口被合并：扫描来源同时以 MediaSourceProvider 与 ScanFolderController
# 两个"键"注册，实际落进同一个桶，`query/observe<MediaSourceProvider>()` 返回同一
# 实例两份 → 媒体库 UI 的合并列表整表翻倍 → Lazy 重复 key 直接闪退
# （"Key \"local:ms-…\" was already used"，debug 包（无 R8）不可复现）。
#
# `-keep,allowobfuscation`：只禁止合并/裁剪，不牺牲混淆。
# **新增任何 `ExtensionPoint` 子接口（含 kernel-ui-api 包）时必须在此登记**，
# 否则同一故障会以另一种面貌回归。
-keep,allowobfuscation interface com.noneplayer.kernel.api.ExtensionPoint
-keep,allowobfuscation interface com.noneplayer.kernel.api.BackgroundTask
-keep,allowobfuscation interface com.noneplayer.kernel.api.CacheProvider
-keep,allowobfuscation interface com.noneplayer.kernel.api.ContextMenuItem
-keep,allowobfuscation interface com.noneplayer.kernel.api.MediaSourceProvider
-keep,allowobfuscation interface com.noneplayer.kernel.api.MetadataEnricher
-keep,allowobfuscation interface com.noneplayer.kernel.api.PermissionProvider
-keep,allowobfuscation interface com.noneplayer.kernel.api.PlayerEngineProvider
-keep,allowobfuscation interface com.noneplayer.kernel.api.ScanFolderController
-keep,allowobfuscation interface com.noneplayer.kernel.api.SearchProvider
-keep,allowobfuscation interface com.noneplayer.kernel.api.SessionKeyProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.BottomChromeProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.DetailPaneProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.InlinePlaybackProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.PlaybackMenuContributor
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.PlaybackOverlay
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.ScreenProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.SettingsProvider
-keep,allowobfuscation interface com.noneplayer.kernel.ui.api.TabProvider

# ---- E207 avformat 解封装：AVIO 读桥（JNI GetObjectClass+GetMethodID 依赖
# 类名/方法名恒定——R8 类合并会拆掉 keepclassmembers 语义，必须整类 keep）----
-keep class com.noneplayer.core.media.ffmpeg.NpExtractorReaderImpl { *; }
