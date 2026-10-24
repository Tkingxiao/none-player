package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.resolutionLabel
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatFileSize
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.NpIconButton

@Composable
internal fun FileInfoCard(
    /** 进出场驱动（审查批次 C2）：false 时播退场，由外层 [PanelExitHost] 撑到播完 */
    visible: Boolean = true,
    title: String,
    item: MediaItem?,
    durationMs: Long,
    container: String?,
    onDismiss: () -> Unit,
) {
    val (scale, alpha) = npPanelTransition(visible = visible, from = 0.96f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 遮罩透明度随进出场走（旧实现是恒定 0.55 的黑底，配上硬切就更突兀）
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
                .clip(RoundedCornerShape(NpRadius.sheet))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, LocalNpInk.current.border, RoundedCornerShape(NpRadius.sheet))
                // 审查批次 C2：卡片本体**消费点击**。旧实现里点标题/大小/编码这些
                // 文字也会冒泡到 scrim 把卡片关掉——用户正在看（或想选中复制）
                // 的那一刻被退掉，这是纯误触。
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* 有意吸收：卡片内的点击不关闭卡片 */ }
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "视频详情",
                    style = NpTypography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 显式出口：与"点遮罩关闭"并列，且给读屏一个可发现的动作名
                NpIconButton(
                    icon = NpIcon.Close,
                    contentDescription = "关闭文件信息",
                    onClick = onDismiss,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            val rows = buildList {
                add("标题" to title)
                item?.sizeBytes?.takeIf { it > 0 }?.let { add("大小" to formatFileSize(it)) }
                item?.resolutionLabel?.takeIf { it.isNotEmpty() }?.let { add("分辨率" to it) }
                add("编码" to (container ?: "未知"))
                item?.folder?.takeIf { it.isNotBlank() }?.let { add("文件夹" to it) }
                if (durationMs > 0) add("时长" to formatTimestamp(durationMs))
            }
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = label,
                        style = NpTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        text = value,
                        style = NpMonoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

