package com.noneplayer.plugins.scanner.local

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.ui.NpListRow
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
            NpListRow(
                title = path.substringAfterLast('/'),
                subtitle = path,
                trailing = {
                    Text(
                        text = "移除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(enabled = !scanning) {
                            scope.launch { source.removeScanFolder(uriStr) }
                        },
                    )
                },
            )
        }
        NpListRow(
            title = if (folderUris.isEmpty()) "选择扫描目录" else "添加扫描目录",
            subtitle = if (folderUris.isEmpty()) "未选择（选择后才会扫描该目录下的视频）" else null,
            modifier = Modifier.clickable(enabled = !scanning) { folderPicker.launch(null) },
            trailing = {
                Text(
                    text = "添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        NpListRow(
            title = "重新扫描",
            subtitle = last?.let { summary ->
                buildString {
                    append("上次：新增 ${summary.added}，移除 ${summary.removed}，共 ${summary.total} 个视频")
                    summary.note?.let { append("（$it）") }
                }
            } ?: if (folderUris.isNotEmpty()) "扫描所选目录中的视频" else null,
            modifier = Modifier.clickable(enabled = !scanning && folderUris.isNotEmpty()) {
                scope.launch { source.scan() }
            },
            trailing = {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
}
