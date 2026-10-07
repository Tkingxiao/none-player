package com.noneplayer.plugins.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMediaPalette
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npDisplacementAllowed
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.kernel.api.NpChapter
import com.noneplayer.kernel.api.NpLoopMode
import com.noneplayer.kernel.api.NpTrack
import com.noneplayer.kernel.api.NpTrackType
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏形态的选项菜单体系（本轮 E80 收口）。
 *
 * 替代原先的三种"占屏"形态：全高右抽屉（fs-panel）、底部弹层（NpSheet）、
 * 横向倍速选择条。全屏下所有选项一律是**锚定触发按钮的紧凑纵向下拉列表**：
 * 上下拉开、不遮进度条、不把画面盖成半屏；菜单内用「推进-返回」两页切换
 * （drill-in），点外部/返回键即收。
 *
 * 材质沿用播放器覆盖层的暗色语言（黑玻璃 + 白字 + accent 选中），
 * 与底栏/顶栏同一条视觉基线——不再出现浅色主题下"白面板压在视频上"的割裂。
 */

/** 菜单页：MAIN 是 ⋯ 的一级页，其余为子页（同一锚点内切换，带返回行） */
enum class FsMenuPage { MAIN, SPEED, SUBTITLE, AUDIO, PICTURE, CHAPTERS, EPISODES }

/**
 * 下拉锚点：触发控件在窗口中的边界 + 展开方向。
 *
 * [preferAbove] = true 时向**上**展开（底栏的倍速/字幕钮），否则向下（顶栏的 ⋯/选集）。
 * [aboveLimitRect] 给向上展开加一条"到此为止"的参照（传进度条所在行的矩形）：
 * 底栏按钮上方紧挨着进度条，菜单若贴着按钮顶展开会遮住进度条的右段——
 * 用户实报的"选项盖住进度条"在底栏侧的同型问题，这里以参照矩形统一收口。
 */
class FsMenuAnchor(
    val rect: Rect,
    val alignEnd: Boolean = true,
    val preferAbove: Boolean = false,
    val aboveLimitRect: Rect? = null,
)

/**
 * 菜单容器：暗色玻璃 + 锚点定位 + 纵向滚动（超出可用高度时内部滚动）
 *
 * [content] 收到的 `dismiss` 是**带动画的收起**：菜单里的每一项都该走它而不是直接
 * 让父级置空页面——父级一置空 Popup 就当场卸载，退场动画没有机会播。
 */
@Composable
fun FsDropdown(
    anchor: FsMenuAnchor,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val gap = 10.dp
    val margin = 12.dp
    // 向上展开时的"下沿参照"：默认是触发控件顶；给了 aboveLimitRect（进度条）时以它为准，
    // 菜单整块落在进度条之上，绝不遮住进度条。
    val aboveEdge = (anchor.aboveLimitRect ?: anchor.rect).top
    val anchorTop = with(density) { aboveEdge.toDp() }
    val anchorBottom = with(density) { anchor.rect.bottom.toDp() }
    // 可用高度按锚点算：向上展开时 = 锚点上方到边距，向下 = 锚点下方到边距。
    // 菜单超出即内部滚动，永远不会顶到屏幕另一头（旧右抽屉"从顶贯到底"的根因）。
    // 再压一个 72% 屏幕高上限：向下展开时给底部的进度条/传输键留出可见余量，
    // 菜单与进度条永不重叠（用户实报的原始问题）。
    val space = if (anchor.preferAbove) {
        anchorTop - gap - margin
    } else {
        screenHeight - anchorBottom - gap - margin
    }
    val maxHeight = minOf(space, screenHeight * 0.72f).coerceAtLeast(180.dp)

    // M21：锚点在原点（顶栏⋯在上沿、底栏钮在下沿），缩放两个轴一起走才像"从按钮长出来"，
    // 只缩 Y 会读成面板被压扁。轻量档/Reduce Motion 把缩放归零 → 纯淡入淡出。
    val scaleAllowed = npDisplacementAllowed()
    val scaleFrom = if (scaleAllowed) 0.95f else 1f
    val exitMs = npDuration(Motion.Field)
    val scope = rememberCoroutineScope()
    // 退场由容器撑着：先把 visible 落下、动画跑完才回调真正的 onDismiss。
    var visible by remember { mutableStateOf(false) }
    var exitJob by remember { mutableStateOf<Job?>(null) }
    // 换了锚点 = 重新开了一次菜单（包括"退场没跑完又点开别处"）：取消待卸载并重新入场。
    // 页内 drill-in 不换锚点，所以推进/返回不会重播入场。
    LaunchedEffect(anchor) {
        exitJob?.cancel()
        visible = true
    }
    val requestDismiss: () -> Unit = {
        if (visible) {
            visible = false
            exitJob = scope.launch {
                delay(exitMs.toLong())
                onDismiss()
            }
        }
    }
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else scaleFrom,
        animationSpec = npMotion<Float>(Motion.Field, easing = NpEasing.Snappy),
        label = "fsMenuScale",
    )
    val layerAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = npMotion<Float>(Motion.Field, easing = NpEasing.Snappy),
        label = "fsMenuAlpha",
    )

    Popup(
        popupPositionProvider = remember(anchor) {
            FsMenuPositionProvider(
                anchor = anchor,
                gapPx = with(density) { gap.toPx().roundToInt() },
                marginPx = with(density) { margin.toPx().roundToInt() },
            )
        },
        onDismissRequest = requestDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(284.dp)
                .heightIn(max = maxHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, if (anchor.preferAbove) 1f else 0f)
                    scaleX = entrance
                    scaleY = entrance
                    this.alpha = layerAlpha
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.88f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            content = { content(requestDismiss) },
        )
    }
}

private class FsMenuPositionProvider(
    private val anchor: FsMenuAnchor,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (anchor.alignEnd) {
            anchor.rect.right.roundToInt() - popupContentSize.width
        } else {
            anchor.rect.left.roundToInt()
        }
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val aboveEdge = (anchor.aboveLimitRect ?: anchor.rect).top
        val y = if (anchor.preferAbove) {
            aboveEdge.roundToInt() - gapPx - popupContentSize.height
        } else {
            anchor.rect.bottom.roundToInt() + gapPx
        }
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        return IntOffset(
            x.coerceIn(marginPx, maxX),
            y.coerceIn(marginPx, maxY),
        )
    }
}

// ---- 行/标题件（暗色语言）----

/** 菜单头：标题 + 关闭（一级页用） */
@Composable
fun FsMenuHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = NpTypography.titleSmall,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        NpIconButton(
            icon = NpIcon.Close,
            contentDescription = "关闭菜单",
            onClick = onClose,
            size = 30.dp,
            iconSize = 15.dp,
            tint = Color.White,
        )
    }
}

/** 子页头：返回 + 页名（drill-in 的出口） */
@Composable
fun FsMenuBackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 命中 44dp 红线（§14 #13）：页头也是可点项（点它=返回），只靠 9dp 上下内边距是 ~42dp。
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = NpIcon.ArrowLeft,
            contentDescription = "返回",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = NpTypography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 危险项在**画面之上**的颜色。
 *
 * 不能用 `ink.danger`：整层下拉菜单永远压在视频上（深色 scrim + 白字），和应用主题无关，
 * 亮色主题下 `ink.danger`（.55 明度）在白字旁边几乎读不出来。
 */
private val OnMediaDanger = NpMediaPalette.onMediaDanger

/**
 * 菜单行：图标（可选）+ 文字 + 右侧值/勾选/箭头。
 *
 * [check] 非 null 时展示勾选位（未选中也占位，避免选中瞬间文字被挤动）；
 * [chevron] 表示"点进去还有一层"（drill-in 子页）。
 */
@Composable
fun FsMenuRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    value: String? = null,
    active: Boolean = false,
    check: Boolean? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> OnMediaDanger
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 命中 44dp 红线（§14 #13）：只靠 9dp 上下内边距撑出来是 ~38dp。
            // 面板本身限高 72% 且内部可滚（见本文件菜单容器），行高加 6dp 不会顶出屏幕。
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = NpTypography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = NpMonoSmall,
                color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        if (check != null) {
            Icon(
                imageVector = NpIcon.Check,
                contentDescription = null,
                tint = if (check) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.size(16.dp),
            )
        }
        if (chevron) {
            Icon(
                imageVector = NpIcon.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 分区小标题（mono 大写、55% 白） */
@Composable
fun FsMenuTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = NpMonoSmall.copy(fontWeight = FontWeight.Medium),
        color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 说明文字（已挂载字幕名 / 空态） */
@Composable
fun FsMenuCaption(text: String) {
    Text(
        text = text,
        style = NpMonoSmall,
        color = Color.White.copy(alpha = 0.5f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 滑杆行（亮度/音量）：标签 + 数值 + 滑杆，暗色配色 */
@Composable
fun FsMenuSlider(
    label: String,
    icon: ImageVector,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = NpTypography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value",
                style = NpMonoSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 循环模式四档（图标横排，与设计稿 .fs-panel 的顺序一致） */
@Composable
fun FsLoopChips(loopMode: NpLoopMode, onPick: (NpLoopMode) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            NpLoopMode.OFF to NpIcon.ListView,
            NpLoopMode.ONE to NpIcon.Rotate,
            NpLoopMode.SHUFFLE to NpIcon.Sort,
            NpLoopMode.ALL to NpIcon.Sound,
        ).forEach { (mode, icon) ->
            val on = loopMode == mode
            // 命中层 [npTouchTarget] 把槽位撑到 44dp（§14 #13），视觉层仍是设计稿 `.fs-opt`
            // 的 38dp 方块——底色/描边只画视觉层，否则一排四枚会连成一条 44dp 的粗带。
            Box(
                modifier = Modifier
                    .npTouchTarget(38.dp)
                    .clickable { onPick(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f),
                        )
                        .border(
                            1.dp,
                            if (on) Color.Transparent else Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = loopLabel(mode),
                        tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---- 各页内容 ----

/**
 * ⋯ 一级页（原 fs-panel 的全部条目，重排为紧凑列表）。
 *
 * 需要二级选择的条目（倍速/字幕/音轨/画面/章节/选集）以 drill-in 打开子页，
 * 其余为直接动作行。
 */
@Composable
fun FsMainMenuContent(
    speed: Float,
    subtitleText: String,
    loopMode: NpLoopMode,
    brightnessPercent: Int,
    volumePercent: Int,
    favorited: Boolean,
    queueSize: Int,
    queueIndex: Int,
    hasChapters: Boolean,
    sleepLabel: String,
    sleepActive: Boolean,
    onClose: () -> Unit,
    onPage: (FsMenuPage) -> Unit,
    onLoopMode: (NpLoopMode) -> Unit,
    onBrightness: (Int) -> Unit,
    onVolume: (Int) -> Unit,
    onPip: () -> Unit,
    onFavorite: () -> Unit,
    onScreenshot: () -> Unit,
    onSleep: () -> Unit,
) {
    FsMenuHeader(title = "播放选项", onClose = onClose)
    FsMenuTitle("循环模式")
    FsLoopChips(loopMode = loopMode, onPick = onLoopMode)
    FsMenuTitle("画面与声音")
    FsMenuSlider(label = "亮度", icon = NpIcon.Sun, value = brightnessPercent, onValueChange = onBrightness)
    FsMenuSlider(label = "音量", icon = NpIcon.Sound, value = volumePercent, onValueChange = onVolume)
    FsMenuTitle("播放")
    FsMenuRow(
        label = "播放倍速",
        value = speedLabel(speed),
        icon = NpIcon.Speed,
        active = kotlin.math.abs(speed - 1f) >= 0.001f,
        chevron = true,
        onClick = { onPage(FsMenuPage.SPEED) },
    )
    FsMenuRow(
        label = "字幕",
        value = subtitleText,
        icon = NpIcon.Subtitle,
        active = subtitleText != "关闭",
        chevron = true,
        onClick = { onPage(FsMenuPage.SUBTITLE) },
    )
    if (queueSize > 1) {
        FsMenuRow(
            label = "选集",
            value = "${queueIndex + 1} / $queueSize",
            icon = NpIcon.QueueMusic,
            chevron = true,
            onClick = { onPage(FsMenuPage.EPISODES) },
        )
    }
    FsMenuTitle("更多")
    FsMenuRow(label = "音轨", icon = NpIcon.Sound, chevron = true, onClick = { onPage(FsMenuPage.AUDIO) })
    FsMenuRow(label = "画面", icon = NpIcon.AspectRatio, chevron = true, onClick = { onPage(FsMenuPage.PICTURE) })
    if (hasChapters) {
        FsMenuRow(label = "章节", icon = NpIcon.ListView, chevron = true, onClick = { onPage(FsMenuPage.CHAPTERS) })
    }
    FsMenuRow(label = "画中画", icon = NpIcon.Pip, onClick = onPip)
    FsMenuRow(label = "截图", icon = NpIcon.Screenshot, onClick = onScreenshot)
    FsMenuRow(
        label = sleepLabel,
        icon = NpIcon.SleepTimer,
        active = sleepActive,
        onClick = onSleep,
    )
    FsMenuRow(
        label = if (favorited) "已收藏" else "收藏",
        icon = NpIcon.Heart,
        active = favorited,
        onClick = onFavorite,
    )
}

/** 倍速页：纵向档位列表（替代横向选择条） */
@Composable
fun FsSpeedMenuContent(
    speed: Float,
    onPick: (Float) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "播放倍速", onBack = onBack)
    SPEED_PRESETS.forEach { preset ->
        val on = kotlin.math.abs(preset - speed) < 0.001f
        FsMenuRow(
            label = speedLabel(preset),
            active = on,
            check = on,
            onClick = { onPick(preset) },
        )
    }
    FsMenuCaption("变速保持音调")
}

/** 字幕页：关闭/内嵌轨/外挂挂载/偏移 */
@Composable
fun FsSubtitleMenuContent(
    subtitleOn: Boolean,
    subtitleLoaded: Boolean,
    savedSubtitleName: String?,
    textTracks: List<NpTrack>,
    offsetMs: Long,
    onOff: () -> Unit,
    onPickTrack: (NpTrack) -> Unit,
    onMountExternal: () -> Unit,
    onOffset: (Long) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "字幕", onBack = onBack)
    FsMenuRow(label = "关闭字幕", check = !subtitleOn, onClick = onOff)
    FsMenuRow(
        label = "挂载外挂字幕…",
        check = subtitleLoaded,
        onClick = onMountExternal,
    )
    if (savedSubtitleName != null) {
        FsMenuCaption("已挂载：$savedSubtitleName")
    }
    FsMenuTitle("内嵌字幕轨")
    if (textTracks.isEmpty()) {
        FsMenuCaption("此视频没有内嵌字幕轨")
    } else {
        textTracks.forEach { track ->
            FsMenuRow(
                label = track.label,
                active = track.selected,
                check = track.selected,
                onClick = { onPickTrack(track) },
            )
        }
    }
    FsMenuTitle("字幕偏移")
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "当前偏移",
            style = NpTypography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (offsetMs == 0L) "0ms" else (if (offsetMs > 0) "+" else "") + "${offsetMs}ms",
            style = NpMonoSmall,
            color = if (offsetMs == 0L) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
        )
    }
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(-250L to "−250ms", -50L to "−50ms", 50L to "+50ms", 250L to "+250ms").forEach { (delta, label) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onOffset(delta) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, style = NpMonoSmall, color = Color.White)
            }
        }
    }
}

/** 音轨页：内嵌音频轨列表 */
@Composable
fun FsAudioMenuContent(
    tracks: List<NpTrack>,
    onPick: (NpTrack) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "音轨", onBack = onBack)
    if (tracks.isEmpty()) {
        FsMenuCaption("此视频没有内嵌音轨")
    } else {
        tracks.forEach { track ->
            FsMenuRow(
                label = track.label,
                active = track.selected,
                check = track.selected,
                onClick = { onPick(track) },
            )
        }
    }
}

/** 画面页：比例五档 + 变换两项（勾选式，与菜单语言一致） */
@Composable
fun FsPictureMenuContent(
    currentMode: VideoScaleMode,
    mirrored: Boolean,
    rotation: Int,
    onMode: (VideoScaleMode) -> Unit,
    onMirror: (Boolean) -> Unit,
    onRotate: (Int) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "画面", onBack = onBack)
    FsMenuTitle("画面比例")
    VideoScaleMode.entries.forEach { mode ->
        val on = mode == currentMode
        FsMenuRow(
            label = mode.label,
            active = on,
            check = on,
            onClick = { onMode(mode) },
        )
    }
    FsMenuTitle("画面变换")
    FsMenuRow(
        label = "左右镜像",
        icon = NpIcon.Mirror,
        check = mirrored,
        active = mirrored,
        onClick = { onMirror(!mirrored) },
    )
    FsMenuRow(
        label = "旋转 180°",
        check = rotation == 180,
        active = rotation == 180,
        onClick = { onRotate(if (rotation == 180) 0 else 180) },
    )
}

/**
 * 章节页：列表 + 当前章标记。
 *
 * §9.2：位置以 [State] 传入并折成派生的"当前章下标"——进度上报约 250ms 一次，
 * 直接逐行比对会让整张下拉菜单跟着重画（它是浮在播放画面上的重组热点）。
 */
@Composable
fun FsChaptersMenuContent(
    chapters: List<NpChapter>,
    positionMs: State<Long>,
    onSelect: (NpChapter) -> Unit,
    onBack: () -> Unit,
) {
    val activeIndex = remember(chapters) {
        derivedStateOf {
            val position = positionMs.value
            chapters.indexOfFirst { chapter ->
                position in chapter.startMs until
                    (chapter.endMs.takeIf { it > chapter.startMs } ?: Long.MAX_VALUE)
            }
        }
    }
    FsMenuBackRow(title = "章节（${chapters.size}）", onBack = onBack)
    chapters.forEachIndexed { index, chapter ->
        val current = index == activeIndex.value
        FsMenuRow(
            label = "${index + 1}. ${chapter.title}",
            value = formatTimestamp(chapter.startMs),
            active = current,
            onClick = { onSelect(chapter) },
        )
    }
}

/** 选集页：同文件夹队列 + 当前项标记 */
@Composable
fun FsEpisodesMenuContent(
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    FsMenuBackRow(title = "选集（${queue.size}）", onBack = onBack)
    queue.forEachIndexed { index, item ->
        val current = index == currentIndex
        FsMenuRow(
            label = "${index + 1}. " + item.title.ifBlank { item.uri.substringAfterLast('/') },
            value = if (current) "播放中" else null,
            active = current,
            onClick = { onSelect(index) },
        )
    }
}
