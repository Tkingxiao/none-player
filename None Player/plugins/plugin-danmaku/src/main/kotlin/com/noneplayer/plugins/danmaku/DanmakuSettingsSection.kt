package com.noneplayer.plugins.danmaku

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.ui.api.SettingsProvider
import kotlinx.coroutines.launch

/**
 * 弹幕设置区块（批次 6 任务③）——经 `SettingsProvider` 扩展点聚合进设置页，
 * 不改设置页一行代码。规格项（审核文档 §13 批次 6 表）：
 * 开关 / 显示区域（1/4、1/3、半屏、全屏）/ 滚动速度 / 密度上限 / 字号 /
 * 透明度 / 本地屏蔽词表。
 *
 * 档位型设置走「点行循环切换」而非每档一行：七项规格若各铺四行，
 * 区块会淹没整个设置页。数值存档在 [AppSettings]（与 scanner 同模式），
 * 渲染层（PlaybackOverlay，本批后续任务）按同一份键读取。
 */
class DanmakuSettingsSection : SettingsProvider {

    override val sectionId: String = "danmaku"
    override val title: String = "弹幕"
    override val order: Int = 15

    @Composable
    override fun Render() {
        val kernel = com.noneplayer.kernel.ui.api.LocalKernel.current
        val settings = remember { kernel.services.get<AppSettings>() }
        val scope = rememberCoroutineScope()

        val enabled by settings.danmakuEnabled.collectAsStateWithLifecycle(initialValue = true)
        val area by settings.danmakuAreaFraction.collectAsStateWithLifecycle(initialValue = 0.5f)
        val speed by settings.danmakuSpeed.collectAsStateWithLifecycle(initialValue = 1f)
        val maxOnScreen by settings.danmakuMaxOnScreen.collectAsStateWithLifecycle(initialValue = 0)
        val fontScale by settings.danmakuFontScale.collectAsStateWithLifecycle(initialValue = 1f)
        val alpha by settings.danmakuAlpha.collectAsStateWithLifecycle(initialValue = 1f)
        val blocklist by settings.danmakuBlocklist.collectAsStateWithLifecycle(initialValue = emptyList())
        var blocklistEditorOpen by rememberSaveable { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxWidth()) {
            NpSettingRow(
                title = "显示弹幕",
                subtitle = "本地弹幕文件（B 站 XML / JSON / ASS），同名自动挂载",
                icon = NpIcon.List,
                trailing = {
                    NpSwitch(
                        checked = enabled,
                        onCheckedChange = { scope.launch { settings.setDanmakuEnabled(it) } },
                    )
                },
            )
            CycleRow(
                title = "显示区域",
                subtitle = "弹幕层占屏幕高度的比例",
                icon = NpIcon.AspectRatio,
                value = area,
                options = AREA_OPTIONS,
                describe = { f ->
                    when (f) {
                        0.25f -> "全屏 1/4"
                        0.33f -> "全屏 1/3"
                        0.5f -> "半屏"
                        else -> "全屏"
                    }
                },
                onSelect = { scope.launch { settings.setDanmakuAreaFraction(it) } },
            )
            CycleRow(
                title = "滚动速度",
                subtitle = "越大滚过屏幕越快",
                icon = NpIcon.Speed,
                value = speed,
                options = SPEED_OPTIONS,
                describe = { "${it}×" },
                onSelect = { scope.launch { settings.setDanmakuSpeed(it) } },
            )
            CycleRow(
                title = "密度上限",
                subtitle = "同屏最多同时显示的条数",
                icon = NpIcon.QueueMusic,
                value = maxOnScreen.toFloat(),
                options = DENSITY_OPTIONS,
                describe = { if (it == 0f) "不限" else "${it.toInt()} 条" },
                onSelect = { scope.launch { settings.setDanmakuMaxOnScreen(it.toInt()) } },
            )
            CycleRow(
                title = "字号",
                icon = NpIcon.Subtitle,
                value = fontScale,
                options = FONT_OPTIONS,
                describe = { "${it}×" },
                onSelect = { scope.launch { settings.setDanmakuFontScale(it) } },
            )
            CycleRow(
                title = "不透明度",
                icon = NpIcon.Sound,
                value = alpha,
                options = ALPHA_OPTIONS,
                describe = { "${(it * 100).toInt()}%" },
                onSelect = { scope.launch { settings.setDanmakuAlpha(it) } },
            )
            AllFilesAccessRow()
            NpSettingRow(
                title = "本地屏蔽词",
                subtitle = if (blocklist.isEmpty()) {
                    "包含任一关键词的弹幕不显示"
                } else {
                    "已设 ${blocklist.size} 个关键词：${blocklist.take(3).joinToString("、")}" +
                        if (blocklist.size > 3) "…" else ""
                },
                icon = NpIcon.Trash,
                showDivider = false,
                onClick = { blocklistEditorOpen = true },
            )
        }

        if (blocklistEditorOpen) {
            BlocklistEditorDialog(
                initial = blocklist.joinToString("\n"),
                onDismiss = { blocklistEditorOpen = false },
                onSave = { raw ->
                    scope.launch {
                        settings.setDanmakuBlocklistRaw(raw)
                        blocklistEditorOpen = false
                    }
                },
            )
        }
    }

    /**
     * 「所有文件访问」引导行（批次 6 实测补入）：同名弹幕/字幕文件是非媒体文件，
     * scoped storage 下 MediaStore 查询对它们不可见——自动匹配需要
     * MANAGE_EXTERNAL_STORAGE（见 [DanmakuLocator] 类注）。未授予时行尾提示
     * 去开启，点按跳系统设置；已授予时只读展示。API 30 以下走旧版
     * READ_EXTERNAL_STORAGE，无此行（minSdk 26 的 26–29 设备自动匹配天然可用）。
     */
    @Composable
    private fun AllFilesAccessRow() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val context = LocalContext.current
        val granted = remember {
            android.os.Environment.isExternalStorageManager()
        }
        NpSettingRow(
            title = "所有文件访问",
            subtitle = if (granted) {
                "已授予——同目录弹幕文件自动挂载可用"
            } else {
                "同名弹幕自动匹配需要读取视频所在目录的弹幕文件，点按去系统设置开启"
            },
            icon = NpIcon.Folder,
            showDivider = true,
            onClick = if (granted) {
                null
            } else {
                {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            },
        )
    }

    /** 档位循环行：点击切到下一档，当前档在 [describe] 里读给人看 */
    @Composable
    private fun CycleRow(
        title: String,
        value: Float,
        options: List<Float>,
        describe: (Float) -> String,
        onSelect: (Float) -> Unit,
        modifier: Modifier = Modifier,
        subtitle: String? = null,
        icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        showDivider: Boolean = true,
    ) {
        NpSettingRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            showDivider = showDivider,
            onClick = {
                val i = options.indexOf(value)
                // 不在档位上（如手工改过偏好文件）时，从第一档重新进入合法值域
                onSelect(options[(i + 1).mod(options.size)])
            },
            trailing = {
                Text(
                    text = describe(value),
                    style = com.noneplayer.core.ui.NpTypography.titleMedium,
                )
            },
            modifier = modifier.padding(end = 16.dp),
        )
    }

    @Composable
    private fun BlocklistEditorDialog(
        initial: String,
        onDismiss: () -> Unit,
        onSave: (String) -> Unit,
    ) {
        var text by rememberSaveable { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("本地屏蔽词") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("每行一个关键词，或用逗号分隔") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onSave(text) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }

    private companion object {
        val AREA_OPTIONS = listOf(0.25f, 0.33f, 0.5f, 1.0f)
        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
        val DENSITY_OPTIONS = listOf(0f, 10f, 20f, 50f) // 0 = 不限
        val FONT_OPTIONS = listOf(0.8f, 1.0f, 1.25f, 1.5f)
        val ALPHA_OPTIONS = listOf(1.0f, 0.85f, 0.7f, 0.5f)
    }
}
