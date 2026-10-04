package com.noneplayer.plugins.library

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.cardTitle
import com.noneplayer.core.media.episodeLabel
import com.noneplayer.core.media.kind
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpCoverBox
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpOptRow
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npShadow
import com.noneplayer.kernel.api.ContextMenuItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 媒体项长按菜单（§3.6）——**`ContextMenuItem` 扩展点的首个消费端**（E34 的一格）。
 *
 * 锚定卡片的浮层（不是 ModalBottomSheet：更快更近）：封面缩略图 + 标题 +
 * 内置项（播放 / 从头播放 / 加入收藏 / 详情）+ 插件贡献项（经扩展点聚合）。
 * 菜单项可见性由各插件自己的 [ContextMenuItem.isVisible] 决定。
 */
@Composable
internal fun MediaContextMenu(
    item: MediaItem,
    anchor: Rect,
    isFavorite: Boolean,
    extensionItems: List<ContextMenuItem>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** null = 不显示「详情」（当前仅类 PC 形态有详情页） */
    onDetail: (() -> Unit)?,
) {
    val ink = LocalNpInk.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val entrance by animateFloatAsState(
        targetValue = if (shown) 1f else 0.92f,
        animationSpec = tween(Motion.Field, easing = NpEasing.Silk),
        label = "mediaContextMenu",
    )
    val isAudio = item.kind == MediaKind.AUDIO
    val hue = NpMediaColor.itemHue(item.folder, isAudio)
    val subtitle = buildList {
        item.episodeLabel?.let { add(it) }
        item.folder?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")

    Popup(
        popupPositionProvider = remember(anchor) {
            ContextMenuPositionProvider(anchor, with(density) { 8.dp.toPx().roundToInt() })
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(276.dp)
                .graphicsLayer {
                    scaleX = entrance
                    scaleY = entrance
                    alpha = 0.4f + 0.6f * ((entrance - 0.92f) / 0.08f).coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(16.dp))
                .background(ink.surface)
                .npShadow(ink, RoundedCornerShape(16.dp), elevation = 14.dp)
                .border(1.dp, ink.border, RoundedCornerShape(16.dp))
                .padding(8.dp),
        ) {
            // 头：封面缩略图 + 标题（+「第 N 集」/文件夹）
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(76.dp)) {
                    NpCoverBox(
                        aspectRatio = 16f / 9f,
                        placeholderHue = hue,
                        isAudio = isAudio,
                        showPlayGlyph = false,
                        compactKindTag = true,
                        tintRing = NpMediaColor.accent(hue).copy(alpha = 0.30f),
                    ) {
                        MediaCover(item = item, hue = hue)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = item.cardTitle,
                        style = NpTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ink.fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = NpMonoSmall,
                            color = ink.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .background(ink.border),
            )

            ContextMenuRow(icon = NpIcon.Play, label = "播放", onClick = onPlay)
            ContextMenuRow(icon = NpIcon.Rotate, label = "从头播放", onClick = onPlayFromStart)
            ContextMenuRow(
                icon = NpIcon.Heart,
                label = if (isFavorite) "取消收藏" else "加入收藏",
                active = isFavorite,
                onClick = onToggleFavorite,
            )
            onDetail?.let { detail -> ContextMenuRow(icon = NpIcon.Info, label = "详情", onClick = detail) }
            // 插件贡献项（当前：本插件自己的「分享」，见 [ShareContextMenuItem]）
            extensionItems.forEach { ext ->
                ContextMenuRow(
                    icon = ext.icon as? ImageVector ?: NpIcon.Dots,
                    label = ext.label,
                    onClick = {
                        onDismiss()
                        scope.launch { ext.onClick(item) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val ink = LocalNpInk.current
    NpOptRow(
        text = label,
        onClick = onClick,
        selected = active,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) ink.accent else ink.fg.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/**
 * 锚定卡片的弹层定位：优先在卡片**下方** 8dp、左对齐卡片；下方放不下翻到上方；
 * 最后统一夹进窗口（右侧/底部不出界）。
 */
private class ContextMenuPositionProvider(
    private val anchor: Rect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchor.left.roundToInt()
            .coerceIn(gapPx, (windowSize.width - popupContentSize.width - gapPx).coerceAtLeast(gapPx))
        val below = anchor.bottom.roundToInt() + gapPx
        val above = anchor.top.roundToInt() - gapPx - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height - gapPx) below else above
        return IntOffset(x, y.coerceIn(gapPx, (windowSize.height - popupContentSize.height - gapPx).coerceAtLeast(gapPx)))
    }
}

/**
 * 「分享」菜单项——**用公开契约贡献的第一个 `ContextMenuItem`**（E34：契约不再没有实现）。
 *
 * 分享同样走 scheme 白名单（E57/E76 同根因：不把未经校验的地址交给系统选择器）。
 * 注册在 `plugin-library`：本插件既贡献项、也在长按菜单里消费它，
 * 后续投屏/字幕插件按同一契约注入即可，无需改本文件。
 */
class ShareContextMenuItem(private val context: Context) : ContextMenuItem {

    override val itemId: String = "library.share"
    override val label: String = "分享"
    override val icon: Any = NpIcon.Share

    override fun isVisible(item: MediaItem): Boolean = item.uri.isShareableScheme()

    override suspend fun onClick(item: MediaItem) {
        if (!item.uri.isShareableScheme()) return
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "video/*" }
                putExtra(Intent.EXTRA_STREAM, item.uri.toUri())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** 只放行本机内容来源（content/file）——纯本地播放器的信任边界（E57/E76 同口径） */
private fun String.isShareableScheme(): Boolean =
    runCatching { this.toUri().scheme?.lowercase() }.getOrNull() in setOf("content", "file")
