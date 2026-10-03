package com.noneplayer.kernel

import android.media.MediaCodecList
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.ABOUT_ROUTE
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.PLUGINS_ROUTE
import com.noneplayer.kernel.ui.api.SETTINGS_ROUTE
import com.noneplayer.kernel.ui.api.SettingsProvider
import com.noneplayer.kernel.ui.api.TabProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.kernel.ui.api.LocalNpBottomInset

/**
 * 内核内置设置页（设计文档 §8："设置页内核实现"）。
 *
 * 聚合渲染：所有插件通过 SettingsProvider 贡献设置区块，
 * 内核按 order 排序，区块标题由内核统一绘制——
 * 插件新增设置项无需改动设置页代码。
 *
 * **它是标签页，不是压栈二级页**：设计稿把设置当作第四个顶层标签
 * （`.tabbar` 四项 / `.rail` 底部单独一项），所以这里实现 [TabProvider]——
 * 有选中态、切过去时标签栏始终可见、顶部没有返回箭头。
 * [pinnedToEnd] 让它在 Rail 上被推到底部（设计稿的空间分离语义）。
 *
 * KSP 生成索引时按 `PluginIndex` 收集的扩展点是 `TabProvider`，
 * 但本类由内核直接注册（不经 KSP），无影响。
 *
 * 视觉按设计稿 `settingsView()`：**不是卡片**，而是一列带底边发丝线的行
 * （`.rows` / `.row-item`），区块之间用 `.eyebrow` 等宽小标题分隔。
 * 这个取舍让长设置页看起来像"一张连续的清单"，而不是一堆浮起来的盒子。
 */
internal class KernelSettingsScreenProvider(private val kernel: Kernel) : TabProvider {

    override val tabId: String = "settings"
    override val label: String = "设置"
    override val icon: Any = NpShellIcons.Gear
    override val selectedIcon: Any? = NpIcon.GearFill
    override val order: Int = 40
    override val pinnedToEnd: Boolean = true

    override val route: String = SETTINGS_ROUTE
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            KernelSettingsScreen(
                plugins = kernel.plugins,
                onOpenAbout = { navigator.navigate(ABOUT_ROUTE) },
                onOpenPlugins = { navigator.navigate(PLUGINS_ROUTE) },
            )
        }
    }
}

/**
 * 齿轮图标。
 *
 * 内核骨架层不能引用 `core:ui`（§13.2：那会把设计系统拖进骨架层），
 * 而 `androidx.compose.material.icons` 的 core 构件不含 Gear。
 * 这里自备一份，**路径与 `NpIcon.Gear` 逐字一致**——两处若画得不一样，
 * 同一个设置入口在标签栏和页面里会长成两个图标。
 */
private object NpShellIcons {
    val Gear: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpShellGear",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0" +
                    "M19.4 15a1.6 1.6 0 0 0 .3 1.7l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.7-.3 " +
                    "1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 9 19.4a1.6 1.6 0 0 0-1.7.3l-.1.1" +
                    "a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.7 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1" +
                    "A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.3-1.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.7.3" +
                    "H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.7-.3l.1-.1" +
                    "a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.7V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1" +
                    "a1.6 1.6 0 0 0-1.5 1z",
            ),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

/** 设置页状态：外观模式等用户偏好，经 AppSettings 持久化 */
class SettingsViewModel(private val settings: AppSettings) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }
}

@Composable
private fun KernelSettingsScreen(
    plugins: List<Plugin>,
    onOpenAbout: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val kernel = LocalKernel.current
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(kernel.services.get<AppSettings>()) }
        },
    )
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    // 设计稿的主题行图标反映**当前解析后的明暗**（system 跟随系统）
    val darkNow = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // 页面渲染时聚合各插件的设置区块（而非 boot 时缓存，保证动态发现语义）
    val sections = kernel.registry.query<SettingsProvider>().sortedBy { it.order }
    val gutter = if (compact) 14.dp else 20.dp
    NpPageScaffold(
        maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
        topBar = {
            NpAppTopBar(
                title = "设置",
                subtitle = "播放、后台、手势与外观",
                compact = compact,
            )
        },
    ) { contentModifier ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = contentModifier.fillMaxSize(),
                contentPadding = PaddingValues(top = LocalNpTopBarInset.current, bottom = LocalNpBottomInset.current + 24.dp),
            ) {
                // ---- 内核自带区块：播放 / 后台与屏幕 / 手势（设计稿 settingsView 前三组） ----
                //
                // 这三组此前整块缺失，而其中「默认播放倍速」的数据层（AppSettings.playbackSpeed）
                // 早已被播放页使用——有存储、有消费、没有入口。现在逐项真接线：
                //   switch 一律写 AppSettings，消费端（播放页/会话/服务）读取生效；
                //   没有数据层的项（字幕语言 / 音频输出 / 双击动作）如实展示只读值，
                //   不给一个点了没反应的假开关（02 报告：不引入假开关）。
                item(key = "playback-group") {
                    PlaybackSettingsGroup(gutter = gutter)
                }
                item(key = "screen-group") {
                    BackgroundScreenSettingsGroup(gutter = gutter)
                }
                item(key = "gesture-group") {
                    GestureSettingsGroup(gutter = gutter)
                }

                // ---- 插件贡献的设置区块 ----
                sections.forEach { section ->
                    item(key = "section-head-${section.sectionId}") {
                        EyebrowBlock(text = section.title, gutter = gutter)
                    }
                    item(key = "section-${section.sectionId}") {
                        Column(modifier = Modifier.padding(horizontal = gutter)) {
                            section.Render()
                        }
                    }
                }

                // ---- 内核自带区块：外观与关于（设计稿 settingsView 第四组）----
                //
                // 设计稿把「主题外观 / 解码能力检测 / 版本 / 清除播放缓存」收在**同一个**
                // `.eyebrow 外观与关于` 下；此前 App 拆成「外观」（主题 segmented）+「应用」
                // （插件 / 关于）两段，与设计稿的区块划分不符。这里合并为一段，并把
                // 主题控件从 `NpSegmented` 改成设计稿 `rowVal` 的形态（值行 + 下钻箭头）——
                // 设置页每一行都是"标签在左、值在右"，塞一排 segmented 会破坏这条视线。
                //
                // 「解码能力检测」「清除播放缓存」此前缺失，现按设计稿补回：前者用
                // `MediaCodecList` 真实枚举硬解编码器（只读值，不编造）；后者做真实清理
                // （断点续播进度是唯一可清的"播放缓存"）。
                item(key = "about-head") { EyebrowBlock(text = "外观与关于", gutter = gutter) }
                item(key = "about-entries") {
                    Column(modifier = Modifier.padding(horizontal = gutter)) {
                        ValuePickerRow(
                            title = "主题外观",
                            // 设计稿 `rowVal(light ? "sun" : "moon", …)`：图标随当前明暗切换
                            icon = if (darkNow) NpIcon.Moon else NpIcon.Sun,
                            value = themeMode.label,
                            options = ThemeMode.entries.map { it.label },
                            onSelect = { label ->
                                ThemeMode.entries.firstOrNull { it.label == label }
                                    ?.let(vm::setThemeMode)
                            },
                        )
                        DecoderCapabilityRow()
                        // 插件 / 关于：App 的**正向**增量（设计稿这里只有一行"版本"）。
                        // 两者各自是独立二级页——插件是产品的核心概念且随运行时可增减，
                        // 关于是静态元信息；并进一页会把架构主张降级成一行附注。
                        NpSettingRow(
                            title = "插件",
                            subtitle = "${plugins.size} 个已加载 · 扩展点即插即用",
                            icon = NpIcon.GridView,
                            onClick = onOpenPlugins,
                            trailing = { ChevronTrail() },
                        )
                        NpSettingRow(
                            title = "关于",
                            subtitle = "版本、构建号与运行环境",
                            icon = NpIcon.Info,
                            onClick = onOpenAbout,
                            trailing = { ChevronTrail() },
                        )
                        ClearPlaybackCacheRow()
                    }
                }
            }
        }
    }
}

/** 行尾的右向箭头——表示"这一行会打开另一个页面"。 */
@Composable
private fun ChevronTrail() {
    Icon(
        imageVector = NpIcon.ChevronRight,
        contentDescription = null,
        tint = LocalNpInk.current.muted,
    )
}

/**
 * `.eyebrow` 区块标题。
 *
 * 用 accent 色的等宽小字而不是 Material 的 `NpSectionHeader`：设计稿里
 * 分区标题是"标签"（你在哪一块），不是"标题"（这一块叫什么）——
 * 等宽 + 字距展开 + accent 把它和内容行明确区分开。
 */
@Composable
private fun EyebrowBlock(text: String, gutter: Dp) {
    Column(modifier = Modifier.padding(horizontal = gutter, vertical = 0.dp)) {
        Spacer(modifier = Modifier.height(20.dp))
        NpEyebrow(text = text)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ================= 设计稿 settingsView 的三组真设置 =================

/** 可选的默认倍速档位（设计稿 speed sheet 的 3×2 网格同一组值）。 */
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun speedText(f: Float): String =
    "%.2fx".format(f).removeSuffix("0").removeSuffix(".")

/** `.eyebrow`：播放（硬件解码优先 / 自动连播 / 默认倍速 / 默认字幕语言）。 */
@Composable
private fun PlaybackSettingsGroup(gutter: Dp) {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val scope = rememberCoroutineScope()
    val hardwareDecode by settings.hardwareDecodePreferred.collectAsStateWithLifecycle(initialValue = true)
    val autoplayNext by settings.autoplayNext.collectAsStateWithLifecycle(initialValue = true)
    val speed by settings.playbackSpeed.collectAsStateWithLifecycle(initialValue = 1f)

    EyebrowBlock(text = "播放", gutter = gutter)
    Column(modifier = Modifier.padding(horizontal = gutter)) {
        NpSettingRow(
            title = "硬件解码优先",
            subtitle = "关闭后使用 CPU 软解，兼容性更好但更耗电",
            icon = NpIcon.Gear,
            trailing = {
                NpSwitch(
                    checked = hardwareDecode,
                    onCheckedChange = { scope.launch { settings.setHardwareDecodePreferred(it) } },
                )
            },
        )
        NpSettingRow(
            title = "自动连播下一集",
            subtitle = "当前视频结束后自动播放",
            icon = NpIcon.Play,
            trailing = {
                NpSwitch(
                    checked = autoplayNext,
                    onCheckedChange = { scope.launch { settings.setAutoplayNext(it) } },
                )
            },
        )
        ValuePickerRow(
            title = "默认播放倍速",
            icon = NpIcon.Speed,
            value = speedText(speed),
            options = SPEED_OPTIONS.map(::speedText),
            onSelect = { label ->
                SPEED_OPTIONS.firstOrNull { speedText(it) == label }?.let {
                    scope.launch { settings.setPlaybackSpeed(it) }
                }
            },
        )
        // 字幕语言偏好尚无数据层（字幕按媒体记忆外挂轨）：如实展示只读值
        NpSettingRow(
            title = "默认字幕语言",
            subtitle = "字幕按媒体单独记忆，此处偏好即将支持",
            icon = NpIcon.Subtitle,
            showDivider = false,
            trailing = { ValueReadonly(text = "跟随系统") },
        )
    }
}

/** `.eyebrow`：后台与屏幕（后台播放 / 屏幕常亮 / 音频输出）。 */
@Composable
private fun BackgroundScreenSettingsGroup(gutter: Dp) {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val scope = rememberCoroutineScope()
    val backgroundPlay by settings.backgroundPlay.collectAsStateWithLifecycle(initialValue = true)
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle(initialValue = true)

    EyebrowBlock(text = "后台与屏幕", gutter = gutter)
    Column(modifier = Modifier.padding(horizontal = gutter)) {
        NpSettingRow(
            title = "后台播放",
            subtitle = "打开后返回列表仍继续播放，并在底部显示迷你播放条",
            icon = NpIcon.Sound,
            trailing = {
                NpSwitch(
                    checked = backgroundPlay,
                    onCheckedChange = { scope.launch { settings.setBackgroundPlay(it) } },
                )
            },
        )
        NpSettingRow(
            title = "播放时保持屏幕常亮",
            subtitle = "全屏播放时阻止息屏",
            icon = NpIcon.Clock,
            trailing = {
                NpSwitch(
                    checked = keepScreenOn,
                    onCheckedChange = { scope.launch { settings.setKeepScreenOn(it) } },
                )
            },
        )
        // 音频输出：真实值来自 AudioManager（系统路由），展示只读
        NpSettingRow(
            title = "音频输出",
            subtitle = "由系统媒体音量与路由决定",
            icon = NpIcon.Sound,
            showDivider = false,
            trailing = { ValueReadonly(text = "系统默认") },
        )
    }
}

/** `.eyebrow`：手势（启用音量/亮度手势 / 双击动作）。 */
@Composable
private fun GestureSettingsGroup(gutter: Dp) {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val scope = rememberCoroutineScope()
    val gestures by settings.gesturesEnabled.collectAsStateWithLifecycle(initialValue = true)

    EyebrowBlock(text = "手势", gutter = gutter)
    Column(modifier = Modifier.padding(horizontal = gutter)) {
        NpSettingRow(
            title = "启用音量/亮度手势",
            subtitle = "左右滑动调节音量与亮度",
            icon = NpIcon.Sound,
            trailing = {
                NpSwitch(
                    checked = gestures,
                    onCheckedChange = { scope.launch { settings.setGesturesEnabled(it) } },
                )
            },
        )
        NpSettingRow(
            title = "双击动作",
            subtitle = "播放页双击左右两侧",
            icon = NpIcon.Gear,
            showDivider = false,
            trailing = { ValueReadonly(text = "快退 / 快进 10 秒") },
        )
    }
}

/**
 * `.row-item` 的 rowVal 形态：整行可点 + 右侧 mono 值 + 箭头，点击弹出 [NpFilterMenu]。
 *
 * 不用 [NpFilterSelect]（chip 触发器）是因为设置行的 anatomy 是"标签在左、值在右"，
 * 塞一个描边胶囊进去会让这一行与上下行不在同一条视线上。
 */
@Composable
private fun ValuePickerRow(
    title: String,
    icon: ImageVector,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val ink = LocalNpInk.current
    Box(
        modifier = Modifier.onGloballyPositioned {
            anchor = it.boundsInWindow()
        },
    ) {
        NpSettingRow(
            title = title,
            icon = icon,
            onClick = { open = true },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = value, style = com.noneplayer.core.ui.NpMonoSmall, color = ink.muted)
                    Icon(
                        imageVector = NpIcon.ChevronRight,
                        contentDescription = null,
                        tint = ink.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        if (open) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember(anchor) {
                    SettingsMenuPositionProvider(anchor)
                },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                com.noneplayer.core.ui.NpFilterMenu(
                    options = options,
                    selected = value,
                    onSelect = {
                        onSelect(it)
                        open = false
                    },
                )
            }
        }
    }
}

/** 设置行的弹层落点：锚定行下方 8dp，右对齐（与 `.fsel-menu` 同规则）。 */
private class SettingsMenuPositionProvider(
    private val anchor: androidx.compose.ui.geometry.Rect,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = anchor.right.toInt() - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(
            x.coerceIn(0, maxX),
            anchor.bottom.toInt() + 8,
        )
    }
}

/** 只读值（无数据层 / 由系统决定的项）：mono 值 + 无箭头，明确"不可点"。 */
@Composable
private fun ValueReadonly(text: String) {
    Text(
        text = text,
        style = com.noneplayer.core.ui.NpMonoSmall,
        color = LocalNpInk.current.muted,
        maxLines = 1,
    )
}

/**
 * 设计稿 1213「解码能力检测」：只读值 `H.264 / H.265 / AV1 ✓`。
 *
 * 用 `MediaCodecList` **真实枚举**本机带硬解加速的解码器（与 FormatProbe 同源），
 * 而不是写死设计稿演示壳里的字符串——设备换了、编码支持也变，写死就是假信息。
 * 缺失的编码由内置 FFmpeg 软解兜底，因此只标注"硬解命中"的那几个。
 */
@Composable
private fun DecoderCapabilityRow() {
    val ink = LocalNpInk.current
    // 枚举 `MediaCodecList` 要走一次系统媒体服务的查询（数毫秒到数十毫秒）。
    // 放在组合里同步做，会在"设置页滚到这一行"时卡一帧；移到 IO 线程，
    // 先给占位文案、探测完再填真值。
    val caps by produceState<DecoderCapability?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { probeHardwareCodecs() }
    }
    NpSettingRow(
        title = "解码能力检测",
        subtitle = "硬解优先，缺失编码由内置 FFmpeg 软解兜底",
        icon = NpIcon.Film,
        trailing = {
            Text(
                text = caps?.label ?: "检测中…",
                style = com.noneplayer.core.ui.NpMonoSmall,
                color = if (caps?.allHardware == true) ink.success else ink.muted,
                maxLines = 1,
            )
        },
    )
}

/** 硬解能力探测结果：展示值与"三种常见编码是否全部硬解"。 */
private class DecoderCapability(val label: String, val allHardware: Boolean)

private fun probeHardwareCodecs(): DecoderCapability {
    val wanted = listOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265",
        "video/av01" to "AV1",
    )
    val hardwareTypes = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { info ->
                // isHardwareAccelerated 需要 API 29；更低版本无法区分，按"有解码器即可"计
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                    runCatching { info.isHardwareAccelerated }.getOrDefault(false)
            }
            .flatMap { info -> runCatching { info.supportedTypes.asList() }.getOrDefault(emptyList()).asSequence() }
            .map { it.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())

    val hits = wanted.filter { it.first in hardwareTypes }
    val label = if (hits.isEmpty()) {
        "软解兜底"
    } else {
        hits.joinToString(" / ") { it.second } + " ✓"
    }
    return DecoderCapability(label, hits.size == wanted.size)
}

/**
 * 设计稿 1215「清除播放缓存」：真实清理断点续播进度 + 最近播放记录。
 *
 * 值是**真实条数**而非设计稿演示壳的"共 128 MB"——本应用没有媒体文件缓存，
 * 唯一的可清数据就是这份播放记忆，报一个磁盘占用数字就是编造。
 */
@Composable
private fun ClearPlaybackCacheRow() {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val count by settings.playbackMemoryCount.collectAsStateWithLifecycle(initialValue = 0)
    val ink = LocalNpInk.current
    NpSettingRow(
        title = "清除播放缓存",
        subtitle = "断点续播进度与最近播放记录",
        icon = NpIcon.Trash,
        onClick = {
            val cleared = count
            scope.launch {
                settings.clearPlaybackMemory()
                Toast.makeText(
                    context,
                    if (cleared > 0) "已清除 $cleared 条播放记录" else "没有可清除的播放记录",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        showDivider = false,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$count 条",
                    style = com.noneplayer.core.ui.NpMonoSmall,
                    color = ink.muted,
                    maxLines = 1,
                )
                Icon(
                    imageVector = NpIcon.ChevronRight,
                    contentDescription = null,
                    tint = ink.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}
