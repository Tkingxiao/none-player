package com.noneplayer.plugins.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noneplayer.core.ui.NpListRow
import com.noneplayer.core.ui.NpSectionHeader
import com.noneplayer.core.ui.NoneMark
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.TabProvider

/** "我的" Tab（route "profile"） */
class ProfileTabProvider : TabProvider {

    override val tabId: String = "profile"
    override val label: String = "我的"
    override val icon: Any = Icons.Filled.Person
    override val order: Int = 30
    override val route: String = "profile"
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            ProfileScreen(navigator = navigator)
        }
    }
}

@Composable
private fun ProfileScreen(navigator: Navigator) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 应用信息头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoneMark(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "None Player",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "插件化微内核播放器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NpSectionHeader("通用")
        GroupedCard {
            NpListRow(
                title = "设置",
                subtitle = "外观、扫描目录、播放偏好",
                modifier = Modifier.clickable { navigator.navigate("kernel.settings") },
                trailing = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        NpSectionHeader("插件")
        GroupedCard {
            val plugins = kernel.plugins
            plugins.forEachIndexed { index, plugin ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NpListRow(
                    title = plugin.name,
                    subtitle = "${plugin.id} · v${plugin.version}",
                )
            }
        }

        NpSectionHeader("关于")
        GroupedCard {
            NpListRow(title = "版本", subtitle = versionName)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NpListRow(title = "架构", subtitle = "微内核 · ${kernel.plugins.size} 个插件已加载")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** iOS 分组列表风格的圆角卡片容器 */
@Composable
private fun GroupedCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column { content() }
    }
}
