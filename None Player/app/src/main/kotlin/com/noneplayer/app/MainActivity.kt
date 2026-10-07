package com.noneplayer.app

import android.content.Intent
import android.content.pm.PackageManager
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
     * 因此放在 `super` 之后：**任何 Compose 组件处理过的键都不经过这里**（播放页自己的
     * 快捷键、搜索框里的空格），只接没人要的键，天然不与页面级快捷键争抢。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        if (event.action == KeyEvent.ACTION_UP) {
            val kernel = (application as? NonePlayerApplication)?.kernel ?: return false
            if (kernel.registry.query<SessionKeyProvider>()
                    .any { it.onSessionKey(event.keyCode, event.isShiftPressed, event.isCtrlPressed) }
            ) return true
        }
        return false
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
            val windowInfo = rememberNpWindowInfo(activity = this@MainActivity)
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
