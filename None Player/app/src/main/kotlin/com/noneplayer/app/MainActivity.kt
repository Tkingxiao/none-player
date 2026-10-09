package com.noneplayer.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NoneMark
import com.noneplayer.core.ui.NonePlayerTheme
import com.noneplayer.core.ui.npDuration
import com.noneplayer.kernel.BootState
import com.noneplayer.kernel.Kernel
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.SessionKeyProvider
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.KernelNavHost
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.rememberNpWindowInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 唯一 Activity：承载 Compose 根，注入 [LocalKernel]。
 *
 * 外观模式来自 AppSettings（DataStore 持久化），重启后仍生效；
 * 内核引导在 [BootState.Running] 前显示启动画面，
 * 失败时在界面内说明原因（不使用弹窗）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 热启动深链（E2）：singleTask 复用实例时系统走 [onNewIntent]，
     * 投递到这里交给 NavController 处理；冷启动的 intent 由
     * NavController.setGraph 自动 handleDeepLink（见 KernelNavHost 注释）。
     *
     * 用 [Channel] 而不是 `MutableSharedFlow(replay = 0)`：SharedFlow 在**没有订阅者**
     * 时直接丢弃发射，而订阅者（KernelNavHost 里那条收集协程）要等内核 Running、
     * NavHost 组合之后才出现。启动画面期间点媒体通知进来，事件会静默消失——用户看到
     * "点通知没反应"。Channel 会把无接收者时的元素缓冲下来，交给第一个后到的收集者，
     * 且每条只投递一次。
     */
    private val deepLinkIntents = Channel<Intent>(Channel.UNLIMITED)
    private val deepLinkFlow = deepLinkIntents.receiveAsFlow()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntents.trySend(intent)
    }

    /**
     * E99：会话级快捷键的**窗口层兜底派发**。
     *
     * Compose 的键事件只沿焦点路径下发，而类 PC 三栏浏览态里播放页根本不在组合树里
     * （右栏是另一份实现），快捷键随之消失；把处理函数放进右栏子树也救不了——用户
     * 点一下列表卡片，焦点就离开了那一栏。
     *
     * 挂在 `onKeyUp` 上而不是覆写 `dispatchKeyEvent`：框架的 `Activity.dispatchKeyEvent`
     * 本来就是**先把键交给窗口（含 Compose 视图树），没人要才回调 `onKeyDown/onKeyUp`**，
     * 所以这里拿到的正是"Compose 树没消费"的键——语义与"super 之后"完全一致，
     * `ACTION_UP` 也天然成立；而覆写 `dispatchKeyEvent` 会碰 `ComponentActivity` 上
     * 带 `@RestrictedApi` 的重载，对本工程是 lint **Error**（实测 `:app:lintDebug` 三条）。
     *
     * 于是：播放页自己的快捷键、搜索框里的空格天然优先，本回调只接没人要的键。
     *
     * **只看 UP 还不够**：文本框只消费 `ACTION_DOWN`（敲字发生在按下那一刻），`UP` 照样漏到
     * `onKeyUp`——搜索框里敲空格/M，字符插进去了，兜底派发却顺手把播放暂停/静音也做了一遍
     * （模拟器实测：搜索框里按 `M` 把音量静音）。所以用「这个键的 DOWN 有没有人接」当判据：
     * DOWN 被视图树消费过 = 这个键有人在管，它的 UP 也归它。
     */
    private var unclaimedDownKeyCode = 0

    /** E102：已被我们抢下的方向键（DOWN 认领后 UP 也要吞掉，不能漏回视图树） */
    private var preemptedArrow = 0

    /**
     * E102：**方向键要抢在视图树之前**派发。
     *
     * 其余键走 [onKeyUp] 的"兜底"（视图树没要才轮到我们）就够；方向键不行——Compose 的
     * **焦点导航**会在视图树里把它们吃掉：实测浏览态焦点落在顶栏按钮上时，`↑` 只把焦点
     * 挪到另一个按钮、音量纹丝不动（焦点 bounds `[748,88]` → `[934,48]`），`↓` 也复现过；
     * 而 §8.4 要求方向键是"桌面化输入"的音量与 seek。
     *
     * 抢在视图树之前只能靠覆写 `dispatchKeyEvent`（框架先问 Activity 再进视图树）。
     * `super` 撞到 `ComponentActivity` 上带 `@RestrictedApi` 的重载，**抑制理由**：那是
     * androidx 的库边界声明，而这里就是 Android 官方的"覆写后交回框架"写法，语义与
     * 未覆写时逐字一致；不抑制就得自己复刻 Window 派发链，反而更容易漂。
     *
     * 三条纪律：只在**会话真能处理**时才抢（没有会话 → 交回框架，焦点导航照旧）；输入态
     * （软键盘在屏）一律不抢，方向键归输入框；抢下的 DOWN 对应 UP 一并吞掉。
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isArrowKey(event.keyCode) && !imeVisible()) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (dispatchToSession(event)) {
                    preemptedArrow = event.keyCode
                    return true
                }
                KeyEvent.ACTION_UP -> if (preemptedArrow == event.keyCode) {
                    preemptedArrow = 0
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchToSession(event: KeyEvent): Boolean {
        val kernel = (application as? NonePlayerApplication)?.kernel ?: return false
        return kernel.registry.query<SessionKeyProvider>()
            .any { it.onSessionKey(event.keyCode, event.isShiftPressed, event.isCtrlPressed) }
    }

    private fun isArrowKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /**
     * 软键盘是否在屏（输入态）：打字时方向键归输入框（移动光标），会话不抢。
     * 如实记档的代价：外接硬键盘且软键盘收起时，输入框里的方向键仍会被抢——
     * 没有可靠的"Compose 焦点是不是文本框"查询口（见档案 §19.16 E102）。
     */
    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true
        unclaimedDownKeyCode = keyCode
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val unclaimed = unclaimedDownKeyCode == keyCode
        unclaimedDownKeyCode = 0
        if (unclaimed) {
            val kernel = (application as? NonePlayerApplication)?.kernel
            if (kernel != null &&
                kernel.registry.query<SessionKeyProvider>()
                    .any { it.onSessionKey(keyCode, event.isShiftPressed, event.isCtrlPressed) }
            ) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val kernel = (application as NonePlayerApplication).kernel

        setContent {
            // B9：以持久化主题作为首帧初值，避免"系统亮色 + 应用强制暗色"首帧闪白
            val app = application as NonePlayerApplication
            val themeMode by (app.kernel.services.get<AppSettings>())
                .themeMode.collectAsStateWithLifecycle(app.initialThemeMode)
            val motionLevel by (app.kernel.services.get<AppSettings>())
                .motionLevel.collectAsStateWithLifecycle(1)
            // E45/E52：形态判定层的**唯一注入点**（§13.2）——全应用一次判定，
            // 经 LocalNpWindowInfo 下发；各页面一律消费它，不得各自读 LocalConfiguration
            // 做档位分支（唯一例外是"窗口是否横向"，见 NpWindowInfo 文档注释）。
            // A2-9（E51 余项）：折叠/铰链真值接进来。此前 `hasFoldingFeature` 只是形参、
            // 零调用方喂值——折叠屏需要的是「铰链避让」语义而不是平板分栏语义（§13.2 优先级），
            // 那条判定链一直拿的是默认 false，等于形同虚设。
            // WindowLayoutInfo 是异步流，只能在组合期 collect，故提到这里而不是 rememberNpWindowInfo 内部。
            val hasFoldingFeature = rememberFoldingFeature(this@MainActivity)
            val windowInfo = rememberNpWindowInfo(
                activity = this@MainActivity,
                hasFoldingFeature = hasFoldingFeature,
            )
            val settings = remember { app.kernel.services.get<AppSettings>() }
            val scope = rememberCoroutineScope()
            // B6：Rail 底部「主题」按钮的二态语义——按"当前实际是否暗色"翻转：
            // 暗 → 浅色、亮 → 深色（跟随系统视为"当前生效值"的翻转，
            // 切换后即固化为显式选择；完整三态仍在设置页"外观"区块）
            val isDarkNow = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val themeToggle = remember(isDarkNow) {
                com.noneplayer.kernel.ui.api.NpThemeToggle(
                    isDark = isDarkNow,
                    onToggle = {
                        scope.launch {
                            settings.setThemeMode(if (isDarkNow) ThemeMode.LIGHT else ThemeMode.DARK)
                        }
                    },
                )
            }
            NonePlayerTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                // 性能模式（R13）：用户档位在这里进内核，`NonePlayerTheme` 负责与
                // 设备自动档取低者、再叠加播放中降档后下发 LocalNpMotionQuality。
                // §9.4 的 decodingVideo 待批次 5 后段接（会话在插件内部，app 够不着）。
                motionQuality = com.noneplayer.core.ui.NpMotionQuality.fromLevel(motionLevel),
                isDesktopLike = windowInfo.isDesktopLike,
            ) {
                val state by kernel.bootState.collectAsStateWithLifecycle()
                // 启动屏 → 主界面交叉淡入，避免生硬切换。
                // M17 的 280 落到 `NavEnter`（启动就是第一次"入页"）；曲线显式给 Silk，
                // 默认的 FastOutSlowIn 和 KernelNavHost 里后续所有入页不是同一条速度。
                Crossfade(
                    targetState = state,
                    animationSpec = tween(npDuration(Motion.NavEnter), easing = NpEasing.Silk),
                    label = "boot",
                ) { s ->
                    when (s) {
                        BootState.Idle, BootState.Booting -> LaunchScreen()
                        BootState.Running -> CompositionLocalProvider(
                            LocalKernel provides kernel,
                            LocalNpWindowInfo provides windowInfo,
                            com.noneplayer.kernel.ui.api.LocalNpThemeToggle provides themeToggle,
                            // 设计稿 2.7 的 hover 效果统一关在 `@media (hover:hover) and
                            // (pointer:fine)` 里——把"是否有指针设备"（实查鼠标/触控板）
                            // 下发给 core:ui 的卡片组件，触屏不会留下粘住的 hover 态
                            com.noneplayer.core.ui.LocalNpHoverEnabled provides windowInfo.isHoverable,
                        ) {
                            PermissionRequester(kernel)
                            KernelNavHost(deepLinkIntents = deepLinkFlow)
                        }
                        is BootState.Failed -> BootErrorScreen(cause = s.cause)
                    }
                }
            }
        }
    }
}

/** 启动画面：签名标记 + 应用名，安静等待内核就绪 */
@Composable
private fun LaunchScreen() {
    val ink = com.noneplayer.core.ui.LocalNpInk.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoneMark(
            modifier = Modifier.size(56.dp),
            color = ink.accent,
            strokeWidth = 5.dp,
        )
        Text(
            text = "None Player",
            style = MaterialTheme.typography.titleLarge,
            color = ink.fg,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * 权限统一申请（设计文档 §9）：聚合所有插件的 PermissionProvider，
 * 在宿主就绪后一次性申请缺失的权限。
 */
@Composable
private fun PermissionRequester(kernel: Kernel) {
    val context = LocalContext.current
    // E29：响应式收集——新插件动态启用后，其声明的权限会被补申请
    val providers by remember { kernel.registry.observe<PermissionProvider>() }
        .collectAsStateWithLifecycle(initialValue = kernel.registry.query<PermissionProvider>())
    val permissions = providers.flatMap { it.permissions }.distinct()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 结果由各来源的 isAvailable() 反映，界面自行重算 */ }

    LaunchedEffect(permissions) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

/** 内核引导失败：说明发生了什么（HIG：错误信息直述原因，不用弹窗） */
@Composable
private fun BootErrorScreen(cause: Throwable) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "无法启动",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = cause.message ?: "未知错误",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 折叠屏铰链态（A2-9 / E51 余项）。
 *
 * 判据取"**存在非 FLAT 的 FoldingFeature**"= 这台设备此刻是折起来/半展开的折叠屏，
 * 只要布尔值不要角度与矩形：形态判定层（§13.2）要回答的是"这是折叠屏还是平板"，
 * 而折叠屏的语义是**铰链避让**、不是平板的分栏，故它的优先级排在平板之前。
 *
 * 非折叠设备上 `windowLayoutInfo` 只会发射空列表，代价是一次 Flow 订阅。
 */
@Composable
private fun rememberFoldingFeature(activity: Activity): Boolean {
    val context = LocalContext.current
    val layoutInfo by androidx.window.layout.WindowInfoTracker
        .getOrCreate(context)
        .windowLayoutInfo(activity)
        .collectAsStateWithLifecycle(initialValue = null)
    return layoutInfo?.displayFeatures?.any { feature ->
        feature is androidx.window.layout.FoldingFeature &&
            feature.state != androidx.window.layout.FoldingFeature.State.FLAT
    } == true
}
