package com.noneplayer.plugins.scanner.local

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpSettingRow
import com.noneplayer.core.ui.NpTypography
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
        val folderUris by source.settings.scanFolderUris.collectAsStateWithLifecycle(initialValue = emptyList())

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // E64 修复：take 侧此前无 try/catch，而对称的 release
                // （LocalMediaSource.removeScanFolder）早已用 runCatching 包裹——
                // 非持久化授权或达到系统每应用 128 个授权上限时会抛 SecurityException。
                val granted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
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
                        onClick = { scope.launch { source.removeScanFolder(uriStr) } },
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
    }
}
