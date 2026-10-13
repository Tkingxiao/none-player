package com.noneplayer.kernel

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NoneMark
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.ABOUT_ROUTE
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.ScreenProvider
import androidx.compose.ui.res.stringResource
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.kernel.ui.api.LocalNpBottomInset

/**
 * 「关于」页——**应用自身的静态元信息**：版本、构建号、运行环境。
 *
 * 分工（三者各回答一个问题，不堆在一坨）：
 *   关于（本页）—— 这是什么版本？跑在什么设备上？
 *   插件（[KernelPluginsScreenProvider]）—— 装了哪些能力？
 *   设置（`KernelSettingsScreenProvider`）—— 我能改什么？
 *
 * 为什么单独成页而不塞进设置页：设置项是**可写的用户偏好**，
 * 而这里是**只读的运行时事实**。混在同一张清单里，用户翻到下面会以为自己能改。
 *
 * 它压在栈上（`ScreenProvider`）而不是顶层标签：它是从设置里下钻的详情页，
 * 顶栏有返回键——这正是设计稿 `.listhead` 的形态（聚合列表页也是这么做的）。
 */
internal class KernelAboutScreenProvider(private val kernel: Kernel) : ScreenProvider {

    override val route: String = ABOUT_ROUTE
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            KernelAboutScreen(onBack = navigator::popBackStack)
        }
    }
}

@Composable
private fun KernelAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ink = LocalNpInk.current
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    val gutter = if (compact) 14.dp else 20.dp

    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "—"
    // PackageInfoCompat 内部处理 API 26/27 与 28+ 的版本号差异，替代直接读 longVersionCode
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) }?.toString() ?: "—"

    NpPageScaffold(
        maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
        topBar = {
            NpAppTopBar(
                title = stringResource(com.noneplayer.kernel.R.string.about_title),
                subtitle = stringResource(com.noneplayer.kernel.R.string.about_subtitle),
                compact = compact,
                onBack = onBack,
            )
        },
    ) { contentModifier ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = contentModifier.fillMaxSize(),
                contentPadding = PaddingValues(top = LocalNpTopBarInset.current, bottom = LocalNpBottomInset.current + 24.dp),
            ) {
                // ---- 品牌头 ----
                item(key = "brand") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = 24.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NoneMark(
                                modifier = Modifier.size(48.dp),
                                color = ink.accent,
                                strokeWidth = 4.dp,
                            )
                            Column(modifier = Modifier.padding(start = 14.dp)) {
                                Text(
                                    text = "None Player",
                                    style = NpTypography.headlineSmall,
                                    color = ink.fg,
                                )
                                Text(
                                    text = stringResource(com.noneplayer.kernel.R.string.about_tagline),
                                    style = NpTypography.bodySmall,
                                    color = ink.muted,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "app-head") { EyebrowBlock(text = stringResource(com.noneplayer.kernel.R.string.about_group_app), gutter = gutter) }
                item(key = "app") {
                    Column(modifier = Modifier.padding(horizontal = gutter)) {
                        NpSettingRow(
                            title = stringResource(com.noneplayer.kernel.R.string.about_version),
                            subtitle = stringResource(com.noneplayer.kernel.R.string.about_version_desc),
                            icon = NpIcon.Info,
                            trailing = { MonoValue(versionName) },
                        )
                        NpSettingRow(
                            title = stringResource(com.noneplayer.kernel.R.string.about_build),
                            subtitle = stringResource(com.noneplayer.kernel.R.string.about_build_desc),
                            icon = NpIcon.Info,
                            trailing = { MonoValue(versionCode) },
                        )
                        NpSettingRow(
                            title = stringResource(com.noneplayer.kernel.R.string.about_framework),
                            subtitle = "Jetpack Compose",
                            icon = NpIcon.Film,
                            showDivider = false,
                        )
                    }
                }

                // 运行环境：排查问题时最先要问的两件事
                item(key = "env-head") { EyebrowBlock(text = stringResource(com.noneplayer.kernel.R.string.about_group_env), gutter = gutter) }
                item(key = "env") {
                    Column(modifier = Modifier.padding(horizontal = gutter)) {
                        NpSettingRow(
                            title = stringResource(com.noneplayer.kernel.R.string.about_os),
                            icon = NpIcon.Info,
                            trailing = {
                                MonoValue(stringResource(com.noneplayer.kernel.R.string.about_os_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                            },
                        )
                        NpSettingRow(
                            title = stringResource(com.noneplayer.kernel.R.string.about_device),
                            icon = NpIcon.Info,
                            showDivider = false,
                            trailing = { MonoValue(Build.MODEL) },
                        )
                    }
                }

                item(key = "footer") {
                    Text(
                        text = stringResource(com.noneplayer.kernel.R.string.about_footer),
                        style = NpTypography.bodySmall,
                        color = ink.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = NpSpace.xxl),
                    )
                }
            }
        }
    }
}

/**
 * 行尾的等宽值（版本号 / 插件数 / 系统版本）。
 *
 * 用等宽字体是因为这些值会随状态变化位数（"9" → "10" 个插件），
 * 变宽时等宽数字不会让整行左右抖动——与时间码同一条纪律。
 */
@Composable
private fun MonoValue(text: String) {
    Text(
        text = text,
        style = NpMono,
        color = LocalNpInk.current.muted,
        maxLines = 1,
    )
}

/** `.eyebrow` 区块标题（与设置页同款）。 */
@Composable
private fun EyebrowBlock(text: String, gutter: Dp) {
    Column(modifier = Modifier.padding(horizontal = gutter)) {
        Spacer(modifier = Modifier.height(20.dp))
        NpEyebrow(text = text)
        Spacer(modifier = Modifier.height(8.dp))
    }
}
