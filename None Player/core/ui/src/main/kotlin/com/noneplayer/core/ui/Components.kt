package com.noneplayer.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "⊘ None" 签名标记——本应用最易被记住的元素。
 *
 * 圆与斜线即"空无一物"，是产品名的具象：媒体库为空时它天然成立，
 * 而非装饰。默认单色（随 onSurfaceVariant），让 accent 留给真正的动作。
 */
@Composable
fun NoneMark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strokeWidth: Dp = 5.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = size.width - stroke.width
        // 圆环
        drawCircle(
            color = color,
            radius = arcSize / 2,
            center = Offset(size.width / 2, size.height / 2),
            style = stroke,
        )
        // 斜线（"/"，与 ⊘ 字形一致）
        drawLine(
            color = color,
            start = Offset(inset + arcSize * 0.22f, size.height - inset - arcSize * 0.22f),
            end = Offset(size.width - inset - arcSize * 0.22f, inset + arcSize * 0.22f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 分组列表的区块标题（iOS 分组表格 header 风格）。
 */
@Composable
fun NpSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * 分组列表行：标题 + 可选副标题 + 尾部插槽。
 *
 * 最小高度 48dp（HIG accessibility.md 控件目标 ≥ 44pt），
 * 行内元素间保持 12dp 以上间距。
 */
@Composable
fun NpListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/**
 * 空状态：签名标记 + 标题 + 一句引导 + 可选的动作按钮。
 *
 * HIG 要求空屏邀请下一步动作；[actionLabel]/[onAction] 提供真实可点的
 * 恢复动作（如"授予媒体权限"），不放虚假操作。
 */
@Composable
fun NpEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        NoneMark(modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .padding(top = 24.dp)
                    .heightIn(min = 44.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * 按压缩放微动效——卡片类可点击内容的统一触感（Apple 式按压 + B 站式弹性）。
 *
 * 按下时缩放到 [pressedScale] 并以弹簧回弹；无边框涟漪，保持画面干净。
 */
fun Modifier.npPressScale(
    onClick: () -> Unit,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "npPressScale",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
