package com.noneplayer.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.data.ThemeMode
import com.noneplayer.core.ui.NoneMark
import com.noneplayer.core.ui.NonePlayerTheme
import com.noneplayer.kernel.BootState
import com.noneplayer.kernel.Kernel
import com.noneplayer.kernel.api.PermissionProvider
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.observe
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.KernelNavHost
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.rememberNpWindowInfo
import kotlinx.coroutines.flow.MutableSharedFlow

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
     */
    private val deepLinkIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntents.tryEmit(intent)
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
            // E45/E52：形态判定层的**唯一注入点**（§13.2）——全应用一次判定，
            // 经 LocalNpWindowInfo 下发；各页面一律消费它，不得各自读 LocalConfiguration
            // 做档位分支（唯一例外是"窗口是否横向"，见 NpWindowInfo 文档注释）。
            val windowInfo = rememberNpWindowInfo(activity = this@MainActivity)
            NonePlayerTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                val state by kernel.bootState.collectAsStateWithLifecycle()
                // 启动屏 → 主界面交叉淡入，避免生硬切换
                Crossfade(
                    targetState = state,
                    animationSpec = tween(280),
                    label = "boot",
                ) { s ->
                    when (s) {
                        BootState.Idle, BootState.Booting -> LaunchScreen()
                        BootState.Running -> CompositionLocalProvider(
                            LocalKernel provides kernel,
                            LocalNpWindowInfo provides windowInfo,
                        ) {
                            PermissionRequester(kernel)
                            KernelNavHost(deepLinkIntents = deepLinkIntents)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoneMark(
            modifier = Modifier.size(56.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 5.dp,
        )
        Text(
            text = "None Player",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
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
