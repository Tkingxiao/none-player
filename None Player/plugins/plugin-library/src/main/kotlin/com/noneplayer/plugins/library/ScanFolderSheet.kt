package com.noneplayer.plugins.library

import android.content.Context
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpSheet
import com.noneplayer.core.ui.NpTypography
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
                            onClick = { onRemoveFolder(uriStr) },
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
                NpSettingRow(
                    title = "取消扫描",
                    subtitle = if (scanProgress.total > 0) {
                        "已处理 ${scanProgress.processed} / ${scanProgress.total} 项"
                    } else {
                        "正在准备扫描…"
                    },
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

/** 释放 SAF 持久化授权（未持有/已撤销时静默） */
internal fun releasePersistedTree(context: Context, uriStr: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            uriStr.toUri(),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
