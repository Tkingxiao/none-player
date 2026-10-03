package com.noneplayer.kernel

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpAppTopBar
import com.noneplayer.core.ui.NpEyebrow
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpPageScaffold
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.ui.api.LocalNpWindowInfo
import com.noneplayer.kernel.ui.api.PLUGINS_ROUTE
import com.noneplayer.kernel.ui.api.ScreenProvider
import com.noneplayer.core.ui.LocalNpTopBarInset
import com.noneplayer.kernel.ui.api.LocalNpBottomInset

/**
 * 「插件」页——已加载插件清单的**唯一归属**。
 *
 * 为什么独立成页而不是塞进「关于」：插件是这款播放器的**核心概念**
 * （微内核 + 扩展点即插即用），它是一块会随安装/停用而变动的**运行时状态**，
 * 而不是"关于本应用"那种静态元信息。把它和版本号堆在一页，
 * 等于把产品的架构主张降级成一行附注。
 */
internal class KernelPluginsScreenProvider(private val kernel: Kernel) : ScreenProvider {

    override val route: String = PLUGINS_ROUTE
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            KernelPluginsScreen(
                plugins = kernel.plugins,
                extensionCount = kernel.registry.let { registry ->
                    PLUGIN_EXTENSION_TYPES.count { registry.query(it).isNotEmpty() }
                },
                onBack = navigator::popBackStack,
            )
        }
    }
}

/**
 * 会被统计的扩展点类型。
 *
 * 只列**用户可感知**的那几类——统计"注册了多少个扩展点"如果连内部类型都算进去，
 * 数字会变成一个没人能解释的魔法值；这里每加一项，用户都能在界面里指出对应物。
 */
private val PLUGIN_EXTENSION_TYPES: List<Class<out ExtensionPoint>> = listOf(
    com.noneplayer.kernel.ui.api.TabProvider::class.java,
    com.noneplayer.kernel.ui.api.ScreenProvider::class.java,
    com.noneplayer.kernel.ui.api.SettingsProvider::class.java,
    com.noneplayer.kernel.api.MediaSourceProvider::class.java,
    com.noneplayer.kernel.api.PlayerEngineProvider::class.java,
)

@Composable
private fun KernelPluginsScreen(
    plugins: List<Plugin>,
    extensionCount: Int,
    onBack: () -> Unit,
) {
    val ink = LocalNpInk.current
    val windowInfo = LocalNpWindowInfo.current
    val compact = windowInfo.isCompact
    val gutter = if (compact) 14.dp else 20.dp

    NpPageScaffold(
        maxContentWidth = if (compact) Dp.Unspecified else windowInfo.maxContentWidth,
        topBar = {
            NpAppTopBar(
                title = "插件",
                subtitle = "${plugins.size} 个已加载 · $extensionCount 类扩展点在用",
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
                item(key = "intro") {
                    Text(
                        text = "本应用是微内核架构：核心不含任何业务能力，页面、媒体来源、" +
                            "播放引擎都由插件经扩展点接入。装一个插件就多一项能力，无需改动内核。" +
                            "插件之间互不依赖。",
                        style = NpTypography.bodySmall,
                        color = ink.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = 20.dp),
                    )
                }

                item(key = "count") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(NpSpace.xl),
                    ) {
                        Stat(label = "已加载", value = "${plugins.size}")
                        Stat(label = "扩展点", value = "$extensionCount")
                    }
                }

                item(key = "plugins-head") { EyebrowBlock(text = "插件清单", gutter = gutter) }
                item(key = "plugins") {
                    Column(modifier = Modifier.padding(horizontal = gutter)) {
                        plugins.forEachIndexed { index, plugin ->
                            NpSettingRow(
                                title = plugin.name,
                                subtitle = plugin.id,
                                icon = NpIcon.GridView,
                                showDivider = index != plugins.lastIndex,
                                trailing = { MonoValue("v${plugin.version}") },
                            )
                        }
                    }
                }

                item(key = "footer") {
                    Text(
                        text = "插件清单只读：安装与停用由宿主装配决定，运行时不可在此开关。",
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

/** 一个统计数字（大号等宽 + 小标签）。数字用等宽，位数变化时不抖动。 */
@Composable
private fun Stat(label: String, value: String) {
    val ink = LocalNpInk.current
    Column {
        Text(text = value, style = NpMono.copy(fontSize = NpTypography.titleLarge.fontSize), color = ink.accent)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = NpTypography.bodySmall, color = ink.muted)
    }
}

/**
 * 行尾的等宽值（版本号）。
 *
 * 用等宽字体是因为这些值会随状态变化位数（"9" → "10"），
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
