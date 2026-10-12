package com.noneplayer.plugins.library

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.TransformOrigin
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
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npShadow
import com.noneplayer.kernel.api.ContextMenuItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * 「删除」（删除设备上的文件；确认流程由系统对话框把关）。
     * null = 不显示——宿主未接删除链路时不摆一个点了没反应的条目。
     */
    onDelete: (() -> Unit)? = null,
) {
    val ink = LocalNpInk.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // M21：锚定菜单从**靠锚点的那角**长出来（下方展开=左上角）。
    // 用户实报"长按选项的出现动画看不见"——5% 缩放 + 150ms 淡入读起来就是"啪地弹出来"。
    // 入场改成 0.90 → 1 + 180ms（Panel 档）：从锚点角"长出来"的方向感一眼可读；
    // 退场保持 150ms（退场要更快离开，§3.9）。
    val scaleAllowed = npDisplacementAllowed()
    val scaleFrom = if (scaleAllowed) 0.90f else 1f
    val exitMs = npDuration(Motion.Field)
    var visible by remember { mutableStateOf(false) }
    var exitJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { visible = true }
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else scaleFrom,
        animationSpec = if (visible) {
            npMotion<Float>(Motion.Panel, easing = NpEasing.Snappy)
        } else {
            npMotion<Float>(Motion.Field, easing = NpEasing.Snappy)
        },
        label = "mediaContextMenuScale",
    )
    val layerAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            npMotion<Float>(Motion.Panel, easing = NpEasing.Snappy)
        } else {
            npMotion<Float>(Motion.Field, easing = NpEasing.Snappy)
        },
        label = "mediaContextMenuAlpha",
    )
    // 每一项都先退场再执行：父级回调一律把 contextMenu 置空，直接调会让 Popup 当场卸载。
    fun requestDismiss(after: () -> Unit) {
        if (!visible) return
        visible = false
        exitJob?.cancel()
        exitJob = scope.launch {
            delay(exitMs.toLong())
            onDismiss()
            after()
        }
    }
    val withDismiss: (() -> Unit) -> (() -> Unit) = { action -> { requestDismiss(action) } }
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
        onDismissRequest = { requestDismiss {} },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(276.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = entrance
                    scaleY = entrance
                    this.alpha = layerAlpha
                }
                // E129：阴影在**白底之外**（阴影层内先画投影、白底后画盖住它）——
                // 与 NpGlass / NpMenuSurface 同序；反写会留下内缘灰带（错位白框感）。
                .npShadow(ink, RoundedCornerShape(16.dp), elevation = 14.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ink.surface)
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

            ContextMenuRow(icon = NpIcon.Play, label = "播放", onClick = withDismiss(onPlay))
            ContextMenuRow(icon = NpIcon.Rotate, label = "从头播放", onClick = withDismiss(onPlayFromStart))
            ContextMenuRow(
                icon = NpIcon.Heart,
                label = if (isFavorite) "取消收藏" else "加入收藏",
                active = isFavorite,
                onClick = withDismiss(onToggleFavorite),
            )
            onDetail?.let { detail -> ContextMenuRow(icon = NpIcon.Info, label = "详情", onClick = withDismiss(detail)) }
            // 插件贡献项（当前：本插件自己的「分享」，见 [ShareContextMenuItem]）
            extensionItems.forEach { ext ->
                ContextMenuRow(
                    icon = ext.icon as? ImageVector ?: NpIcon.Dots,
                    label = ext.label,
                    onClick = {
                        requestDismiss { scope.launch { ext.onClick(item) } }
                    },
                )
            }
            // 「删除」压轴且用危险色：删除设备文件是破坏性动作，与上面一组操作
            // 用一条发丝线隔开（用户实报"长按多功能选项没有删除"）
            if (onDelete != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(ink.border),
                )
                ContextMenuRow(
                    icon = NpIcon.Trash,
                    label = "删除",
                    danger = true,
                    onClick = withDismiss(onDelete),
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
    /** 破坏性条目（删除）：图标走 danger 色，其余样式不变——行内次要提示，不铺红底 */
    danger: Boolean = false,
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
                tint = when {
                    danger -> ink.danger
                    active -> ink.accent
                    else -> ink.fg.copy(alpha = 0.8f)
                },
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
