package com.noneplayer.plugins.library

import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npClickable
import com.noneplayer.kernel.api.ScanProgress

/**
 * 「扫描目录」管理面板（媒体库统一入口）。
 *
 * 背景（用户实报三项收口）：①"扫描只能一个文件夹"——设置页可以反复添加，但入口深、
 * 无回显，用户在库里找不到"再加一个"的地方；②"没有统一的文件夹管理界面"——
 * 库内此前只有消费目录的媒体树，管理动作散在设置页；③重复添加同一目录会导致
 * 重叠扫描范围（双份文件的历史来源之一）。
 *
 * 本面板是**库侧唯一的管理面**：列出全部扫描目录（可移除）、添加、重新扫描。
 * 增删一律走 [com.noneplayer.kernel.api.ScanFolderController]（由扫描插件实现）——
 * 释放 SAF 持久授权、重扫、移除最后一个目录时清列表，副作用归来源所有。
 */
@Composable
internal fun ScanFolderSheet(
    folders: List<String>,
    scanProgress: ScanProgress,
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    val ink = LocalNpInk.current
    val scanning = scanProgress.active
    // E230：移除目录要二次确认。误触一颗 44dp 垃圾桶会立即释放 SAF 授权并触发
    // 重扫；移除的是**最后一个**目录时整个媒体列表清空回到引导态——一颗按钮
    // 的代价太大。rememberSaveable：确认框开着时转屏/重组不丢待移除项。
    var pendingRemove by rememberSaveable { mutableStateOf<String?>(null) }

    NpSheet(onDismiss = onDismiss, title = "扫描目录") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "媒体库只扫描这些目录（可添加多个）。移除目录不会删除任何文件。",
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            folders.forEach { uriStr ->
                val label = describeScanFolder(uriStr)
                NpSettingRow(
                    title = label.first,
                    subtitle = label.second,
                    icon = NpIcon.Folder,
                    trailing = {
                        NpIconButton(
                            icon = NpIcon.Trash,
                            contentDescription = "移除目录",
                            onClick = { pendingRemove = uriStr },
                            size = NpDimens.touchTarget,
                            iconSize = 20.dp,
                            tint = ink.danger,
                            enabled = !scanning,
                        )
                    },
                )
            }
            NpSettingRow(
                title = "添加扫描目录",
                subtitle = "再选一个媒体文件夹加入扫描范围",
                icon = NpIcon.Folder,
                onClick = if (scanning) null else onPickFolder,
                trailing = {
                    Text(
                        text = "添加",
                        style = NpTypography.labelLarge,
                        color = if (scanning) ink.muted else ink.accent,
                    )
                },
            )
            if (scanning) {
                // 扫描中的阶段 + 进度（契约 §5）：此前只有一句「正在准备扫描…」，
                // 遍历隐藏目录这种长耗时段的几分钟里界面完全静止，用户以为卡死。
                // 现在按 phase 报阶段，有总数走确定进度，只有已发现条目数时走
                // indeterminate + 「已发现 N 项」。
                ScanProgressBlock(progress = scanProgress)
                // 取消入口在扫描中常驻可点：onClick 恒非空（NpSettingRow 无 enabled
                // 概念，传 null 才不可点），文案固定「取消扫描」，点一次即取消。
                NpSettingRow(
                    title = "取消扫描",
                    subtitle = "点一次即取消当前扫描，已入库的文件保留",
                    icon = NpIcon.Close,
                    onClick = onCancelScan,
                )
            }
            NpSettingRow(
                title = "重新扫描",
                subtitle = if (scanning) "正在扫描，完成后可再次对比" else "对比上次结果，只处理新增与移除的文件",
                icon = NpIcon.Rotate,
                onClick = if (scanning) null else onRescan,
                showDivider = false,
                trailing = {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ink.accent,
                        )
                    }
                },
            )
        }
    }

    pendingRemove?.let { uriStr ->
        RemoveFolderConfirmDialog(
            folderLabel = describeScanFolder(uriStr).second,
            isLastFolder = folders.size == 1,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                pendingRemove = null
                onRemoveFolder(uriStr)
            },
        )
    }
}

/**
 * E230：移除扫描目录的确认卡。结构仿媒体库 DeleteConfirmDialog / 我的页
 * ClearHistoryConfirmDialog 的 Dialog 窗口壳（遮罩盖住底栏与迷你条、返回
 * 只关确认框，不顺着 Sheet 链把面板一起关掉）。
 */
@Composable
private fun RemoveFolderConfirmDialog(
    folderLabel: String,
    isLastFolder: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ink = LocalNpInk.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler { onDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .npClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(NpRadius.sheet))
                    .background(ink.surface)
                    .border(1.dp, ink.border, RoundedCornerShape(NpRadius.sheet))
                    .padding(20.dp),
            ) {
                Text(
                    text = "移除扫描目录",
                    style = NpTypography.titleMedium,
                    color = ink.fg,
                )
                Text(
                    text = if (isLastFolder) {
                        "「$folderLabel」是最后一个扫描目录，移除后媒体库将清空（回到选择文件夹的引导页）。文件不受影响，重新添加即可恢复。"
                    } else {
                        "「$folderLabel」将移出扫描范围并立即重新扫描。文件不受影响，重新添加即可恢复。"
                    },
                    style = NpTypography.bodySmall,
                    color = ink.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "取消",
                        style = NpTypography.bodyMedium,
                        color = ink.fg,
                        modifier = Modifier
                            .npClickable(onClick = onDismiss)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Text(
                        text = "移除",
                        style = NpTypography.bodyMedium,
                        color = ink.danger,
                        modifier = Modifier
                            .npClickable(onClick = onConfirm)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 扫描阶段 + 进度指示（Sheet 与库页顶部紧凑条共用同一套文案口径）。
 *
 * - `total > 0`：确定进度条 + 「已处理 x / y 项」。
 * - `total == 0` 且阶段是 [com.noneplayer.kernel.api.ScanPhase.HIDDEN_WALK]：
 *   indeterminate + 「已发现 N 项」——目录树深度事先不可知，`processed` 报的是
 *   已发现条目数，给一个确定百分比就是假精确。
 * - 阶段未知（老来源）：保持既有「正在准备扫描…」，不引入新状态。
 */
@Composable
internal fun ScanProgressBlock(progress: ScanProgress, modifier: Modifier = Modifier) {
    val ink = LocalNpInk.current
    val fraction = scanProgressFraction(progress)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(NpSpace.xs),
    ) {
        Text(
            text = scanProgressHeadline(progress),
            style = NpTypography.titleSmall,
            color = ink.fg,
        )
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NpDimens.trackLine),
                color = ink.accent,
                trackColor = ink.border,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NpDimens.trackLine),
                color = ink.accent,
                trackColor = ink.border,
            )
        }
        scanProgressDetail(progress)?.let { detail ->
            Text(text = detail, style = NpMonoSmall, color = ink.muted)
        }
    }
}

/**
 * 库页顶部的紧凑扫描状态条：阶段文案 + 常驻取消入口。
 *
 * 与下拉刷新指示器同处页面顶部（调用方把它排在内容首行），扫描结束
 * （`active == false`）时由调用方不再渲染，本组件不自行消失。
 *
 * 这里不画进度条：顶部已经有下拉刷新指示器承担"有动作在跑"的弱信号，
 * 再叠一条进度线是两套并行的进度语义。
 */
@Composable
internal fun ScanStatusStrip(
    progress: ScanProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NpSpace.sm),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = NpSpace.lg),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = scanProgressHeadline(progress),
                style = NpTypography.bodySmall,
                color = ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            scanProgressDetail(progress)?.let { detail ->
                Text(
                    text = detail,
                    style = NpMonoSmall,
                    color = ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 触控目标 44dp（§14）：紧凑条只有文字高，取消键不能跟着缩水
        NpIconButton(
            icon = NpIcon.Close,
            contentDescription = "取消扫描",
            onClick = onCancel,
            size = NpDimens.touchTarget,
            iconSize = 20.dp,
            tint = ink.danger,
        )
    }
}

/**
 * SAF tree URI →（目录名, 完整描述）。
 *
 * 与扫描插件 `describeTree` 同一口径（`primary:Movies` → "内部存储/Movies"）；
 * 插件间禁止互相依赖，故此小函数在库侧自持。无法解析时退化为原始串，不隐藏条目。
 */
internal fun describeScanFolder(treeUriStr: String): Pair<String, String> {
    val pretty = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUriStr.toUri())
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return@runCatching null
        val relative = parts[1].trim('/')
        val rootName = if (parts[0] == "primary") "内部存储" else parts[0]
        if (relative.isEmpty()) "$rootName（根目录）" else "$rootName/$relative"
    }.getOrNull() ?: treeUriStr
    val name = pretty.substringAfterLast('/').ifBlank { pretty }
    return name to pretty
}

/** 释放 SAF 持久化授权（未持有/已撤销时静默）；E233 起按 READ|WRITE 释放（与持久化口径一致） */
internal fun releasePersistedTree(context: Context, uriStr: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            uriStr.toUri(),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
