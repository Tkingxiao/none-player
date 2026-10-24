package com.noneplayer.kernel

import android.media.MediaCodecList
import com.noneplayer.core.data.DEFAULT_DOUBLE_TAP_STEP_MS
import com.noneplayer.core.data.DOUBLE_TAP_STEP_OPTIONS_MS
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpChip
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.core.media.ProbeMemo
import com.noneplayer.core.media.SurfaceCloneMemo
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpAnchorHolder
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.npAnchor
import com.noneplayer.core.ui.rememberNpAnchorHolder
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
    override val selectedIcon: Any = NpIcon.GearFill
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

    /** 「性能模式」档位序（R13）：0 轻量 / 1 平衡 / 2 全效，默认平衡 */
    val motionLevel: StateFlow<Int> = settings.motionLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    fun setMotionLevel(level: Int) {
        viewModelScope.launch { settings.setMotionLevel(level) }
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
    val motionLevel by vm.motionLevel.collectAsStateWithLifecycle()
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
                        ValuePickerRow(
                            title = "性能模式",
                            // 仪表盘：这一档管的是"开销换多少视觉"，与倍速同类不同值
                            icon = NpIcon.Speed,
                            value = MOTION_LEVEL_LABELS[motionLevel.coerceIn(0, 2)],
                            options = MOTION_LEVEL_LABELS,
                            onSelect = { label ->
                                MOTION_LEVEL_LABELS.indexOf(label)
                                    .takeIf { it >= 0 }
                                    ?.let(vm::setMotionLevel)
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

/**
 * 「性能模式」三档文案（R13），下标即档位序，与 `NpMotionQuality.fromLevel(0/1/2)` 同序。
 *
 * 内核骨架层不引 `core:ui` 的档位定义（§13.2 分层）——这里只出文案，
 * 倍率/模糊/开关那一整套取值由 app 层把档位序换算成 `NpMotionQuality` 喂给主题。
 */
private val MOTION_LEVEL_LABELS = listOf("轻量", "平衡", "全效")

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
            // L2：解码器集合在播放器构建期定型，切换无法在本次播放中途改写——
            // 如实标注生效时机，而不是让用户以为马上就换（真实接线见 PlaybackService.onCreate）
            subtitle = "关闭后优先用 CPU 软解，兼容性更好但更耗电；下次播放生效",
            icon = NpIcon.Gear,
            // A27（2026-10-15 订正）：开关行整行可点——此前命中区只有 46×27dp 的开关本身。
            onClick = { scope.launch { settings.setHardwareDecodePreferred(!hardwareDecode) } },
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
            // A27：开关行整行可点——命中区不再只有 46×27dp 的开关本身。
            onClick = { scope.launch { settings.setAutoplayNext(!autoplayNext) } },
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
            // A27：开关行整行可点。
            onClick = { scope.launch { settings.setBackgroundPlay(!backgroundPlay) } },
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
            // A27：开关行整行可点。
            onClick = { scope.launch { settings.setKeepScreenOn(!keepScreenOn) } },
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
            // 审查批次 D3：文案与实际行为对齐。
            // 这个开关在播放页接的是 `gesturesEnabled`，它门控的是**整个舞台手势组**
            // （横滑 seek、竖滑亮度/音量、双击快退快进、长按 3x），不只是音量亮度。
            // 旧标题写"启用音量/亮度手势"、副标题写"左右滑动调节音量与亮度"，
            // 两处都不对：轴方向反了（音量/亮度是**竖**滑），范围也小了
            // ——用户以为只关掉音量亮度，结果双击快进也一起没了。
            // 语义未改（仍是同一个开关），只把话说准。
            title = "启用播放手势",
            subtitle = "横滑快进 · 竖滑亮度/音量 · 双击快退快进 · 长按 3x",
            icon = NpIcon.Sound,
            // A27：开关行整行可点。
            onClick = { scope.launch { settings.setGesturesEnabled(!gestures) } },
            trailing = {
                NpSwitch(
                    checked = gestures,
                    onCheckedChange = { scope.launch { settings.setGesturesEnabled(it) } },
                )
            },
        )
        // L14 自定义双击步长：改的是**双击**，横滑 seek 与键盘 `←/→` 不受影响
        val doubleTapStepMs by settings.doubleTapStepMs.collectAsStateWithLifecycle(
            initialValue = DEFAULT_DOUBLE_TAP_STEP_MS,
        )
        NpSettingRow(
            title = "双击动作",
            subtitle = if (gestures) {
                "播放页双击左右两侧：快退 / 快进"
            } else {
                "双击左右两侧：快退 / 快进（需先开启上方播放手势）"
            },
            icon = NpIcon.Gear,
            showDivider = false,
            trailing = {
                ValueReadonly(text = "${(doubleTapStepMs / 1000L).toInt()} 秒")
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = NpSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(NpSpace.sm),
        ) {
            DOUBLE_TAP_STEP_OPTIONS_MS.forEach { step ->
                NpChip(
                    text = "${step / 1000L}s",
                    selected = step == doubleTapStepMs,
                    onClick = { scope.launch { settings.setDoubleTapStepMs(step) } },
                )
            }
        }
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
    // E128：锚点走**非可观察持有者**（core:ui 的 NpAnchorHolder 同款）。
    // 旧写法 `anchor` 是 mutableStateOf——滚动设置页时这一行每帧移动，布局回调就每帧
    // 写一次状态、顺带每帧新建一个 Rect（并在弹层开着时每帧重建 PositionProvider），
    // 而锚点只在"打开弹层"的那一瞬被读一次。持有者的写入不进快照系统，滚动期零重组。
    val anchor = rememberNpAnchorHolder()
    val ink = LocalNpInk.current
    Box(modifier = Modifier.npAnchor(anchor)) {
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
                popupPositionProvider = remember { SettingsMenuPositionProvider(anchor) },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                com.noneplayer.core.ui.NpFilterMenu(
                    options = options,
                    selected = value,
                    // 落点右对齐（见 [SettingsMenuPositionProvider]），缩放原点跟着贴到右上角
                    alignEnd = true,
                    onSelect = {
                        onSelect(it)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 设置行的弹层落点：锚定行下方 8dp，右对齐（与 `.fsel-menu` 同规则）。
 *
 * 锚点矩形由 [NpAnchorHolder] 在**弹出那一刻**现取（`calculatePosition` 是弹层布局时
 * 才被调用的）——持有者因此不需要是状态，滚动期也就没有逐帧写入（E128）。
 */
private class SettingsMenuPositionProvider(
    private val anchor: NpAnchorHolder,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val rect = anchor.current()
        val x = rect.right.toInt() - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(
            x.coerceIn(0, maxX),
            rect.bottom.toInt() + 8,
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
 * 设计稿 1213「解码能力检测」→ 批次 7 扩为「解码诊断」面板（§12.3 余项）。
 *
 * 行值仍是不展开时的"硬解命中"摘要；**点按展开**能力矩阵：每个常见 mime 给出
 * `MediaCodecList.findDecoderForFormat` 的默认选择（与 FormatProbe 起播判定
 * 同一决策路径，即"复用同一份探测数据"）、是否硬解、支持的 profile/level 档数。
 *
 * 已知边界：**实时** renderer/codec 名与音频输出模式需要引擎侧诊断通道
 * （AnalyticsListener → 契约增量），不在此面板编造；单文件的 profile/level
 * 超限判定仍由起播探测（FormatProbe.profileMismatch）给出。
 */
@Composable
private fun DecoderCapabilityRow() {
    val ink = LocalNpInk.current
    var expanded by remember { mutableStateOf(false) }
    // 枚举 `MediaCodecList` 要走一次系统媒体服务的查询（数毫秒到数十毫秒）。
    // 放在组合里同步做，会在"设置页滚到这一行"时卡一帧；移到 IO 线程，
    // 先给占位文案、探测完再填真值。
    val caps by produceState<DecoderCapability?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { probeHardwareCodecs() }
    }
    // 展开时才探测明细（同为 IO 线程；收起不重排）
    val diagnostics by produceState<List<DecoderDiag>>(initialValue = emptyList(), expanded) {
        if (expanded) value = withContext(Dispatchers.IO) { probeDecodeDiagnostics() }
    }
    // 批次 8（E39 余项）：最近一次起播探测——排障时先看这里（"刚才那个文件为什么不行"）
    val lastProbe by ProbeMemo.last.collectAsStateWithLifecycle()
    val lastSurfaceClone by SurfaceCloneMemo.last.collectAsStateWithLifecycle()
    Column {
        NpSettingRow(
            title = "解码能力检测",
            subtitle = "硬解优先，缺失编码由内置 FFmpeg 软解兜底",
            icon = NpIcon.Film,
            onClick = { expanded = !expanded },
            trailing = {
                Text(
                    text = caps?.label ?: "检测中…",
                    style = com.noneplayer.core.ui.NpMonoSmall,
                    color = if (caps?.allHardware == true) ink.success else ink.muted,
                    maxLines = 1,
                )
            },
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (diagnostics.isEmpty()) {
                    Text(
                        text = "检测中…",
                        style = com.noneplayer.core.ui.NpMonoSmall,
                        color = ink.muted,
                    )
                } else {
                    lastProbe?.let { probe ->
                        Column {
                            Text(
                                text = "最近探测",
                                style = com.noneplayer.core.ui.NpTypography.bodySmall,
                                color = ink.fg,
                            )
                            Text(
                                text = buildString {
                                    append(probe.displayName)
                                    append(" · ")
                                    if (probe.playable) {
                                        append(listOfNotNull(probe.videoCodec, probe.hdr, probe.audioChannels).joinToString(" · "))
                                        if (!probe.audioChannels.isNullOrEmpty() || probe.videoCodec != null) append(" · ")
                                        append("可播")
                                    } else {
                                        append(probe.reason ?: "不可播")
                                    }
                                },
                                style = com.noneplayer.core.ui.NpMonoSmall,
                                color = if (probe.playable) ink.success else ink.danger,
                                maxLines = 2,
                            )
                        }
                    }
                    lastSurfaceClone?.let { message ->
                        Text(
                            text = "最近 Surface 克隆失败：$message",
                            style = com.noneplayer.core.ui.NpMonoSmall,
                            color = ink.danger,
                            maxLines = 2,
                        )
                    }
                    diagnostics.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = d.label,
                                    style = com.noneplayer.core.ui.NpTypography.bodySmall,
                                    color = ink.fg,
                                )
                                Text(
                                    text = "${d.defaultDecoder} · ${d.profileSummary}",
                                    style = com.noneplayer.core.ui.NpMonoSmall,
                                    color = ink.muted,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = if (d.hardware) "硬解" else "软解",
                                style = com.noneplayer.core.ui.NpMonoSmall,
                                color = if (d.hardware) ink.success else ink.muted,
                            )
                        }
                    }
                    Text(
                        text = "单文件的 profile/level 超限判定在起播前探测中给出。" +
                            "实时 renderer/codec 与音频输出模式需引擎诊断通道，随批次 8 评估。",
                        style = com.noneplayer.core.ui.NpTypography.bodySmall,
                        color = ink.muted,
                    )
                }
            }
        }
    }
}

/** 解码诊断明细行（§12.3 面板数据）：一个 mime 的默认解码器 + 硬/软 + profile 档数。 */
private class DecoderDiag(
    val label: String,
    val defaultDecoder: String,
    val hardware: Boolean,
    val profileSummary: String,
)

private fun probeDecodeDiagnostics(): List<DecoderDiag> {
    val wanted = listOf(
        "video/avc" to "H.264 / AVC",
        "video/hevc" to "H.265 / HEVC",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/mp4v-es" to "MPEG-4 ASP",
    )
    return runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val decoders = list.codecInfos.filter { !it.isEncoder }
        wanted.mapNotNull { (mime, label) ->
            runCatching {
                val format = android.media.MediaFormat.createVideoFormat(mime, 1920, 1080)
                val defaultName = list.findDecoderForFormat(format)
                val primary = decoders
                    .filter { info ->
                        runCatching { info.supportedTypes.contains(mime) }.getOrDefault(false)
                    }
                    .firstOrNull { it.name == defaultName }
                    ?: decoders.firstOrNull { info ->
                        runCatching { info.supportedTypes.contains(mime) }.getOrDefault(false)
                    }
                val hardware = primary?.let { info ->
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                        runCatching { info.isHardwareAccelerated }.getOrDefault(false)
                } ?: false
                val profileSummary = primary?.let { info ->
                    runCatching {
                        val count = info.getCapabilitiesForType(mime).profileLevels.size
                        if (count > 0) "$count 档 profile/level" else "—"
                    }.getOrDefault("—")
                } ?: "—"
                DecoderDiag(label, defaultName ?: "无解码器", hardware, profileSummary)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
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
 *
 * E11 之后断点/历史住在 Room（[HistoryRepository]），DataStore 只剩设置项——
 * 此前这里读的 `playbackMemoryCount` / 清的 `clearPlaybackMemory()` 都指向 DataStore，
 * 迁移完成的用户会看到"0 条"且点了没反应。现在以 Room 为准；DataStore 旧键一并清掉，
 * 覆盖"首次扫描尚未完成、还没迁移"的那批安装。
 */
@Composable
private fun ClearPlaybackCacheRow() {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val history = remember { kernel.services.get<HistoryRepository>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val count by history.count.collectAsStateWithLifecycle(initialValue = 0)
    // R2-1/REV-06：持久层降级状态可观察——失败过就在副题亮出来，恢复后自动回到常态文案。
    val storageStatus by history.storageStatus.collectAsStateWithLifecycle()
    val ink = LocalNpInk.current
    NpSettingRow(
        title = "清除播放缓存",
        subtitle = if (storageStatus.healthy) {
            "断点续播进度与最近播放记录（全部）"
        } else {
            "存储暂时不可用（${storageStatus.operation ?: "读写"}），稍后重试"
        },
        icon = NpIcon.Trash,
        onClick = {
            val cleared = count
            scope.launch {
                // R2-1/REV-06：清除失败时**不清 DataStore 旧键**、也不报"已清除"。
                if (!history.clearHistory()) {
                    Toast.makeText(context, "清除失败，请重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // 迁移前的旧键（以及只播过、没到落点门槛的那些）也清掉，避免两处口径残留
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
