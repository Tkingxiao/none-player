package com.noneplayer.kernel.ui.api

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设备形态（§13.1 矩阵的落地枚举）。
 *
 * 判据是"**窗口**形态 + 平台能力"，不是"屏幕尺寸"——折叠屏展开/分屏会让
 * 屏幕宽度与实际窗口宽度脱钩（§13.2 开头）。
 *
 * **`FOLDABLE` 的语义边界（E129 / 审查 #5）**：这一档只表示"设备是折叠屏、
 * 且此刻存在非 FLAT 的折叠特征"，**不携带任何铰链几何**——没有 bounds、
 * 没有 orientation、没有 occlusion。消费方因此必须按"窗口是一个连续矩形"来布局，
 * 不得假定内容不会被折痕压住。要升级成真正的几何避让，入口在
 * `MainActivity.rememberFoldingFeature`：把 `FoldingFeature` 的
 * bounds/orientation 一并算进本 data class，而不是让页面各自去读
 * `WindowInfoTracker`（那会破坏 §13.2 的"一处判定、全局消费"）。
 */
enum class NpFormFactor { PHONE, FOLDABLE, TABLET, DESKTOP }

/**
 * 形态判定层（E45）——**一处判定，全局消费**。
 *
 * 插件与内核页面**不得各自读 `LocalConfiguration.screenWidthDp` 做档位分支**：
 * 那样每加一个页面就多一份真相，多形态适配必然分叉。需要档位时一律消费
 * [LocalNpWindowInfo]。
 *
 * **唯一例外（§13.2 已核实的边界）**：`screenWidthDp` / `screenHeightDp` 只允许
 * 用来回答"窗口是不是横向"这一个问题（播放页全屏的单一真相，E48）。
 * 不得在它之上继续长 `if (screenWidthDp > 600)` 这类档位判断。
 *
 * 归属 `kernel-ui-api` 而非 `core:ui`：本包是 app + 5 个插件的既有公共依赖，
 * 放这里零条新依赖边即可全覆盖；而 `core:ui` 与 `kernel-ui-api` 之间当前**没有**
 * 依赖边，放过去等于把主题/组件层拖进内核骨架层（§13.2 归属修正）。
 */
data class NpWindowInfo(
    /** 宽度档（material3 window-size-class） */
    val widthClass: WindowWidthSizeClass,
    /** 高度档 */
    val heightClass: WindowHeightSizeClass,
    /** 设备形态 */
    val formFactor: NpFormFactor,
    /** 窗口是否横向 */
    val isLandscape: Boolean,
    /** EXPANDED 且宽高比 > 1.4 → "类 PC"（§13.4 平板横屏三栏布局的判据） */
    val isDesktopLike: Boolean,
    /** 是否有指针设备（鼠标/触控板）——桌面化输入（E50）的前提 */
    val isHoverable: Boolean,
    /** 阅读型内容的最大宽度约束，防超宽拉伸；`Dp.Unspecified` 表示不约束 */
    val maxContentWidth: Dp,
) {
    /** 紧凑档：底部 NavigationBar + 单列（现状） */
    val isCompact: Boolean get() = widthClass == WindowWidthSizeClass.Compact

    /** 中等档：NavigationRail + 可双栏 */
    val isMedium: Boolean get() = widthClass == WindowWidthSizeClass.Medium

    /** 扩展档：Rail/三栏 + 列表详情并列 */
    val isExpanded: Boolean get() = widthClass == WindowWidthSizeClass.Expanded

    /**
     * 页面 gutter（设计稿 `.content{padding:0 20px}` / compact `14px`）。
     * 各页统一消费此值，不再各自写一个横边距。
     */
    val pageGutter: Dp get() = if (isCompact) 14.dp else 20.dp
}

/**
 * 形态判定结果的下发通道。
 *
 * 未提供时读取即抛错——形态未注入就渲染页面属于编程错误，应尽早失败
 * （与 [LocalKernel] 同一约定）。
 */
val LocalNpWindowInfo: androidx.compose.runtime.ProvidableCompositionLocal<NpWindowInfo> =
    staticCompositionLocalOf {
        error("LocalNpWindowInfo 未提供：请在 MainActivity 用 CompositionLocalProvider 注入（§13.2）")
    }

/**
 * 由 Activity 计算形态判定结果（唯一注入点用，§13.2）。
 *
 * [hasFoldingFeature] 由调用方从 `WindowInfoTracker.windowLayoutInfo` 流里取
 * （铰链/折叠状态是异步的，无法在组合期同步读到）。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberNpWindowInfo(
    activity: Activity,
    hasFoldingFeature: Boolean = false,
): NpWindowInfo {
    val context = LocalContext.current
    val sizeClass: WindowSizeClass = calculateWindowSizeClass(activity)
    val configuration = LocalConfiguration.current
    val hoverable = rememberHoverable(context)
    return remember(sizeClass, configuration, hasFoldingFeature, hoverable) {
        buildNpWindowInfo(
            sizeClass = sizeClass,
            isLandscape = configuration.screenWidthDp > configuration.screenHeightDp,
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            smallestScreenWidthDp = configuration.smallestScreenWidthDp,
            hasFoldingFeature = hasFoldingFeature,
            isHoverable = hoverable,
            isDesktopPlatform = isDesktopPlatform(context),
        )
    }
}

/**
 * 形态判定核心（纯函数，便于单测）。
 *
 * 形态优先级：桌面平台 > 折叠屏 > 平板 > 手机。折叠屏判在平板之前——
 * 展开态折叠屏的 `smallestScreenWidthDp` 可能已过 600，但它需要的是
 * **铰链避让**语义，不是平板的分栏语义。
 */
internal fun buildNpWindowInfo(
    sizeClass: WindowSizeClass,
    isLandscape: Boolean,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    hasFoldingFeature: Boolean,
    isHoverable: Boolean,
    isDesktopPlatform: Boolean,
): NpWindowInfo {
    val formFactor = when {
        isDesktopPlatform -> NpFormFactor.DESKTOP
        hasFoldingFeature -> NpFormFactor.FOLDABLE
        smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP -> NpFormFactor.TABLET
        else -> NpFormFactor.PHONE
    }
    val aspect = if (screenHeightDp > 0) screenWidthDp.toFloat() / screenHeightDp else 1f
    // "类 PC"：窗口足够宽、**高度也够**、且横向——平板横屏与桌面窗口属于这一类（§13.4）。
    //
    // 为什么必须带 heightClass 这一条（真机/模拟器实测得出）：
    // 现代手机横屏的宽度普遍 ≥840dp（Pixel 10 Pro 横屏是 952×427dp），只看"宽度
    // Expanded + 宽高比 > 1.4"会把**手机横屏**也判成类 PC，于是播放页不再走
    // §4.2 的横屏全屏，而是被塞进"嵌入 + 底部集中控制"的桌面版式——这正是把
    // §13.1 的"手机横屏（高 < 480dp）"一行读漏的后果。高度档 Compact 即
    // "手机横屏"的判据，故类 PC 要求高度至少 Medium。
    val isDesktopLike = sizeClass.widthSizeClass == WindowWidthSizeClass.Expanded &&
        sizeClass.heightSizeClass != WindowHeightSizeClass.Compact &&
        isLandscape && aspect > DESKTOP_LIKE_ASPECT
    return NpWindowInfo(
        widthClass = sizeClass.widthSizeClass,
        heightClass = sizeClass.heightSizeClass,
        formFactor = formFactor,
        isLandscape = isLandscape,
        isDesktopLike = isDesktopLike,
        isHoverable = isHoverable,
        maxContentWidth = maxContentWidthFor(sizeClass.widthSizeClass),
    )
}

/** 阅读型内容最大宽度：紧凑档不约束，越宽越需要收窄以防行长失控 */
private fun maxContentWidthFor(widthClass: WindowWidthSizeClass): Dp = when (widthClass) {
    WindowWidthSizeClass.Compact -> Dp.Unspecified
    WindowWidthSizeClass.Medium -> 840.dp
    else -> 1100.dp
}

/** 平板判据：最小宽度 ≥ 600dp（Android 官方 sw600dp 分界） */
private const val TABLET_SMALLEST_WIDTH_DP = 600

/** "类 PC" 判据：宽高比 > 1.4（§13.2 定义） */
private const val DESKTOP_LIKE_ASPECT = 1.4f

/**
 * 与 [buildNpWindowInfo] 的 `isDesktopLike` 同一条判据，供转屏回调在组合之前使用。
 * 宽度 Expanded ≥840dp，高度非 Compact ≥480dp，且横向、宽高比 > 1.4。
 */
internal fun isDesktopLikeWindow(screenWidthDp: Int, screenHeightDp: Int): Boolean {
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return false
    val aspect = screenWidthDp.toFloat() / screenHeightDp
    return screenWidthDp >= 840 &&
        screenHeightDp >= 480 &&
        screenWidthDp > screenHeightDp &&
        aspect > DESKTOP_LIKE_ASPECT
}

/** 桌面平台（ChromeOS / Android PC 模式） */
// FEATURE_PC 常量自 API 27 才进入 SDK，但 String 常量编译期内联，
// hasSystemFeature 在 API 26 上同样可用——抑制 InlinedApi。
@SuppressLint("InlinedApi")
private fun isDesktopPlatform(context: Context): Boolean = runCatching {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
}.getOrDefault(false)

/**
 * 是否有指针设备：输入设备里存在鼠标或触控板。
 *
 * 用 InputManager 实查而非猜"平板就有鼠标"——平板也可能纯触屏。
 */
/**
 * 指针设备的**纯判据**（输入源位）：鼠标或触控板即"有指针"。
 * 提出来是为了能被单测钉住——检测本身要 InputManager（设备枚举），没法在 JVM 里跑。
 */
internal fun hasPointerSource(sources: Int): Boolean =
    sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
        sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD

/**
 * 「有没有指针设备」的**可失效**状态（§14 #18）。
 *
 * 关键在失效键：**插拔鼠标不会触发 Configuration 变化**，此前拿 `configuration` 当失效键，
 * 于是"插上鼠标仍自动隐藏 / 拔掉鼠标不恢复 3.5s 超时"两个方向都不对（§14 #18 的两半）。
 * 正确的信号是 [InputManager.InputDeviceListener]——增/删/改各来一发，都能被它抓到。
 * 监听只挂在组合期（`DisposableEffect`），退出即注销。
 */
@Composable
private fun rememberHoverable(context: Context): Boolean {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { version++ }
            override fun onInputDeviceRemoved(deviceId: Int) { version++ }
            override fun onInputDeviceChanged(deviceId: Int) { version++ }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    return remember(version) { detectHoverable(context) }
}

private fun detectHoverable(context: Context): Boolean = runCatching {
    val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        ?: return@runCatching false
    inputManager.inputDeviceIds.any { id ->
        val device = inputManager.getInputDevice(id) ?: return@any false
        val sources = device.sources
        sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
            sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
    }
}.getOrDefault(false)
