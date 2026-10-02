package com.noneplayer.kernel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.noneplayer.core.ui.NpListRow
import com.noneplayer.core.ui.NpSectionHeader
import com.noneplayer.core.ui.npCardSurface
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.api.Plugin
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.api.query
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.ScreenProvider
import com.noneplayer.kernel.ui.api.SettingsProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 内核内置设置页（设计文档 §8："设置页内核实现"）。
 *
 * 聚合渲染：所有插件通过 SettingsProvider 贡献设置区块，
 * 内核按 order 排序，区块标题由内核统一绘制——
 * 插件新增设置项无需改动设置页代码。
 */
internal class KernelSettingsScreenProvider(private val kernel: Kernel) : ScreenProvider {

    override val route: String = "kernel.settings"
    override val deepLinks: List<String> = emptyList()

    override fun register(builder: NavGraphBuilder, navigator: Navigator) {
        builder.composable(route) {
            KernelSettingsScreen(
                plugins = kernel.plugins,
                onBack = navigator::popBackStack,
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KernelSettingsScreen(
    plugins: List<Plugin>,
    onBack: () -> Unit,
) {
    val kernel = LocalKernel.current
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(kernel.services.get<AppSettings>()) }
        },
    )
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    // 页面渲染时聚合各插件的设置区块（而非 boot 时缓存，保证动态发现语义）
    val sections = kernel.registry.query<SettingsProvider>().sortedBy { it.order }
    // E47 响应式：平板/折叠展开后收窄居中
    val maxContentWidth = com.noneplayer.kernel.ui.api.LocalNpWindowInfo.current.maxContentWidth

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (maxContentWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                        Modifier.widthIn(max = maxContentWidth)
                    } else {
                        Modifier
                    },
                ),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---- 内核自带区块：外观 ----
            item { NpSectionHeader("外观") }
            item(key = "theme") {
                SettingsCard {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size,
                                ),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }
            }
            // ---- 插件贡献的设置区块 ----
            sections.forEach { section ->
                item(key = "section-${section.sectionId}") { NpSectionHeader(section.title) }
                item(key = "content-${section.sectionId}") {
                    SettingsCard { section.Render() }
                }
            }
            // ---- 内核信息 ----
            item { NpSectionHeader("内核") }
            item(key = "plugins") {
                SettingsCard {
                    plugins.forEachIndexed { index, plugin ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        NpListRow(
                            title = plugin.name,
                            subtitle = "${plugin.id} · v${plugin.version}",
                        )
                    }
                }
            }
        }
        }
    }
}

/** 设置分组卡片：surface1 实底 + accent 派生发丝描边 + 顶边高光，与"我的"页一致。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val ink = com.noneplayer.core.ui.LocalNpInk.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .npCardSurface(ink, MaterialTheme.shapes.medium),
    ) {
        content()
    }
}
