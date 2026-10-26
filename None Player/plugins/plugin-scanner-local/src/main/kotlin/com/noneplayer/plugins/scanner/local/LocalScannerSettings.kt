package com.noneplayer.plugins.scanner.local

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npClickable
import com.noneplayer.kernel.ui.api.SettingsProvider
import kotlinx.coroutines.launch

/**
 * 本地媒体设置区块——多扫描目录管理 + 重新扫描。
 *
 * 每个目录一行（完整路径 + 移除）；「添加扫描目录」经 SAF 选择器，
 * 授权持久化到 AppSettings（旧版单目录数据读取时自动迁移）。
 */
class LocalScannerSettings(private val source: LocalMediaSource) : SettingsProvider {

    override val sectionId: String = "scanner.local"
    override val title: String = "本地媒体"
    override val order: Int = 10

    @Composable
    override fun Render() {
        val context = LocalContext.current
        val ink = LocalNpInk.current
        val scope = rememberCoroutineScope()
        val scanning by source.scanning.collectAsStateWithLifecycle()
        val last by source.lastScan.collectAsStateWithLifecycle()
        val folderUris by source.settings.scanFolderUris.collectAsStateWithLifecycle(
            // 首帧取进程级快照（E105）：初值给 emptyList() 会把"还没读到"当成"确实没配"，
            // 设置页重进时闪一帧"未选择（选择后才会扫描该目录下的媒体）"
            initialValue = source.settings.scanFolderUrisSnapshot ?: emptyList(),
        )
        // E230：移除目录要二次确认（与库页 ScanFolderSheet 同口径）——误触立即
        // 释放授权并重扫，最后一个目录被移除时媒体库清空。插件间禁止互相依赖，
        // 确认卡在此自持（同 describeTree 与库侧 describeScanFolder 的分工）。
        var pendingRemove by rememberSaveable { mutableStateOf<String?>(null) }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // E64 修复：take 侧此前无 try/catch，而对称的 release
                // （LocalMediaSource.removeScanFolder）早已用 runCatching 包裹——
                // 非持久化授权或达到系统每应用 128 个授权上限时会抛 SecurityException。
                // E233：READ|WRITE 同持久化（删除需要在 SAF 树里有写授权）。
                val granted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
                if (granted) {
                    scope.launch { source.addScanFolder(uri.toString()) }
                } else {
                    Toast.makeText(
                        context,
                        "无法持久化该目录的访问权限（可能已达到系统上限）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        folderUris.forEach { uriStr ->
            val path = describeTree(uriStr) ?: "所选目录"
            NpSettingRow(
                title = path.substringAfterLast('/'),
                subtitle = path,
                icon = NpIcon.Folder,
                trailing = {
                    // `.opt-tile.danger` 的语义：移除是**危险动作**，但它是行内次要操作，
                    // 所以只给图标与色，不铺一整块红底——一个红按钮在设置页里太吵。
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
            title = if (folderUris.isEmpty()) "选择扫描目录" else "添加扫描目录",
            subtitle = if (folderUris.isEmpty()) "未选择（选择后才会扫描该目录下的媒体）" else null,
            icon = NpIcon.Search,
            onClick = if (scanning) null else ({ folderPicker.launch(null) }),
            trailing = {
                // 行本身已经可点，这里只重复一次"可以加"的信号；命中区仍是行级的 56dp
                Text(
                    text = "添加",
                    style = NpTypography.labelLarge,
                    color = if (scanning) ink.muted else ink.accent,
                )
            },
        )
        NpSettingRow(
            title = "重新扫描",
            subtitle = last?.let { summary ->
                buildString {
                    append("上次：新增 ${summary.added}，移除 ${summary.removed}，共 ${summary.total} 个媒体")
                    summary.note?.let { append("（$it）") }
                }
            } ?: if (folderUris.isNotEmpty()) "扫描所选目录中的媒体" else null,
            icon = NpIcon.Rotate,
            onClick = if (scanning || folderUris.isEmpty()) {
                null
            } else {
                ({ scope.launch { source.scan() } })
            },
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

        pendingRemove?.let { uriStr ->
            RemoveFolderConfirmDialog(
                folderLabel = describeTree(uriStr) ?: uriStr,
                isLastFolder = folderUris.size == 1,
                onDismiss = { pendingRemove = null },
                onConfirm = {
                    pendingRemove = null
                    scope.launch { source.removeScanFolder(uriStr) }
                },
            )
        }
    }
}

/**
 * E230：移除扫描目录的确认卡（与库页 ScanFolderSheet 的确认同款窗口壳：
 * 遮罩盖住底栏、返回只关确认框）。文案与最后一目录提示两处共用一套。
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
