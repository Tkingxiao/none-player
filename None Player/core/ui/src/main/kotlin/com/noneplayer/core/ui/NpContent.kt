package com.noneplayer.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 内容型组件——封面卡、进度轨、角标。这些是设计稿里"信息密度最高"的部分，
 * 也是保真度最容易掉的地方（角标位置、圆角、进度线粗细差 1dp 就变味）。
 */

/**
 * 设计稿的色彩系统：**每个文件夹一个色相**，音频固定 258（蓝紫）。
 *
 * 这不是装饰——媒体库动辄上百个封面，色相是用户"扫一眼定位"的唯一线索。
 * 色相表与设计稿 `folderHue` 的数组逐位一致（22/52/152/190/326/96/12/205）。
 */
object NpMediaColor {
    private val hues = listOf(22, 52, 152, 190, 326, 96, 12, 205)

    /** 音频统一色相（设计稿 `itemHue` 对音频返回 258）。 */
    private const val AUDIO_HUE = 258

    /**
     * 音频标识青：`accent(258)` 落在 226..280 那一段的蓝（#73B2FF），读起来不像"音频"，
     * 所以设计稿给音频单独定了一支青。标签与波形条共用这一支，两处不许各写一遍。
     */
    val audioTint = Color(0xFF0EC7DE)

    /** 由文件夹名稳定映射到一个色相（同名必同色，跨会话不变）。 */
    fun folderHue(name: String?): Int {
        val key = name ?: "local"
        var s = 0
        for (c in key) s = (s * 31 + c.code) and 0x7FFFFFFF
        return hues[s % hues.size]
    }

    /** 条目色相：音频固定 258，其余跟随文件夹。 */
    fun itemHue(folder: String?, isAudio: Boolean): Int =
        if (isAudio) AUDIO_HUE else folderHue(folder)

    /**
     * 设计稿 `itemAccent(v) = oklch(0.76 0.15 <hue>)` 的 sRGB 近似。
     *
     * 用固定的明度/彩度（L .76 / C .15）在 HSL 里近似：oklch 的 L 与 HSL 的 L
     * 不同，所以这里按色相预先取好一组"同明度感"的实色，避免逐色相做色彩空间转换。
     */
    fun accent(hue: Int): Color = when (((hue % 360) + 360) % 360) {
        in 0..20 -> Color(0xFFFF8785)
        in 21..45 -> Color(0xFFFF8A5C)
        in 46..70 -> Color(0xFFFA934E)
        in 71..110 -> Color(0xFFCEB01E)
        in 111..170 -> Color(0xFF5BCC80)
        in 171..200 -> Color(0xFF00CEC6)
        in 201..225 -> Color(0xFF00CBDE)
        in 226..280 -> Color(0xFF73B2FF)
        in 281..340 -> Color(0xFFE18DE4)
        else -> Color(0xFFFF8598)
    }

    /**
     * 封面渐变底：设计稿 `art()` 生成的 `oklch(.34 .11 h) → oklch(.19 .06 h+40)`。
     * 真实封面加载前/失败后用它兜底，不留空洞。
     */
    fun coverBrush(hue: Int): Brush = Brush.linearGradient(
        listOf(darken(accent(hue), 0.78f), darken(accent(hue), 0.90f)),
    )

    private fun darken(color: Color, amount: Float): Color = Color(
        red = color.red * (1f - amount),
        green = color.green * (1f - amount),
        blue = color.blue * (1f - amount),
        alpha = 1f,
    )
}

/**
 * 封面上的"视频/音频"角标（设计稿 `.thumb .kindtag`）。
 *
 * 两条边框色规则：视频跟随 accent 色相，音频固定青蓝——
 * 一眼分辨"这条是视频还是纯音频"比读到文字快得多。
 */
@Composable
fun NpKindTag(
    isAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val tint = if (isAudio) NpMediaColor.audioTint else ink.accent
    Row(
        modifier = modifier
            // 圆角底直接用带 shape 的 background 画：`clip + background` 会为这个
            // 小角标多开一次**模板裁剪**（HWUI 的 clipSmoothCorner + stencil），
            // 而这里没有任何需要被裁的子内容——背景形状够用，绘制指令少一遍。
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(6.dp))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(start = 5.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (isAudio) NpIcon.Sound else NpIcon.Film,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = if (isAudio) "音频" else "视频",
            style = NpTypography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * `.track`：进度轨 —— 3dp 轨道 + accent 填充 + 可拖拽圆形滑块（拖动时 12→18dp）。
 *
 * 设计稿的关键细节：**滑块只在拖动时才长大**。常驻一个大圆点会把"播放进度"
 * 读成"可拖控件"，而长大这个动作本身就是在告诉用户"你现在抓住它了"。
 *
 * 命中区 20dp（设计稿 `.track { height:20px }`）包住 3dp 的细线——细线好看但点不中。
 *
 * 未播段取 [InkTokens.border]：它是本系统里唯一"在中性底上安静可见"的色。
 * 不能用 `fgSoft`（暗 6%/亮 4.5% 前景，贴在 `ink.bg` 上等于没有轨道，
 * 进度就成了一段悬空的 accent 短条），更不能硬编白（白只适用于压在**视频画面**
 * 上的件，见 `NpSeekBar`；页面底色在亮色主题下是纯白）。
 */
@Composable
fun NpTrackBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color? = null,
    fillColor: Color? = null,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val ink = LocalNpInk.current
    val track = trackColor ?: ink.border
    val fill = fillColor ?: ink.accent
    var dragging by remember { mutableFloatStateOf(0f) }
    var draggedProgress by remember { mutableFloatStateOf(progress) }
    val shown = if (dragging > 0f) draggedProgress else progress
    val knobSize by animateFloatAsState(
        targetValue = if (dragging > 0f) 18f else 12f,
        animationSpec = npMotion(Motion.Hover),
        label = "knobSize",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = 1f
                        draggedProgress = progress
                        onDragStart()
                    },
                    onDragEnd = {
                        onSeek(draggedProgress)
                        dragging = 0f
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = 0f
                        onDragEnd()
                    },
                ) { change, _ ->
                    draggedProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NpDimens.trackLine)
                // §19.14 同族：圆角交给 background 自己画，不做 RenderNode 裁剪
                // （`clip()` 每处都会开一次模板裁剪；卡片热路径上每帧几十次）。
                .background(track, RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .height(NpDimens.trackLine)
                    // 填充轨自带同形圆角：方角会盖住底轨两端的圆角
                    .background(fill, RoundedCornerShape(2.dp)),
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    // 滑块中心对齐填充末端：用 fraction 定位，避免在测量前拿不到宽度
                    translationX = (shown.coerceIn(0f, 1f) * size.width) - knobSize.dp.toPx() / 2f
                }
                .size(knobSize.dp)
                .background(fill, RoundedCornerShape(NpRadius.chip)),
        )
    }
}

/**
 * 迷你条 / 播放页底部的 2dp 细进度线（设计稿 `.mini-prog`）。
 *
 * 与 [NpTrackBar] 的区别：**不可交互**。它是"放到哪了"的弱提示，
 * 不是控件——加了命中区反而会和下方内容的滚动手势打架。
 *
 * 底轨同 [NpTrackBar] 取 [InkTokens.border]（`fgSoft` 在 `ink.glass = ink.bg`
 * 的迷你条上，亮色主题下白底白线不可见）。
 */
@Composable
fun NpMiniProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color? = null,
    fillColor: Color? = null,
) {
    val ink = LocalNpInk.current
    // 设计稿 `.mini-prog i{transition:width .25s linear}`——线性而非弹性：
    // 进度线是"匀速走"的读法，spring 回弹会让它看起来在抖。
    //
    // E128：动画值**只在绘制相位解包**（`animated.value` 写在 drawBehind 里，不 `by` 到组合期）。
    // 播放中这条 250ms 的线性补齐是一条接一条连续的，旧写法等于迷你条每帧重组一次自己；
    // 而迷你条挂在**每个列表页**的底栏上——滚动 + 后台播放时这是一笔稳定的逐帧账。
    // draw 里"方角左端 + 圆角右端"的写法与原 `fillMaxWidth(f) + topEnd/bottomEnd 2dp`
    // 逐像素等价（左端圆角被先行画满的矩形盖住）。
    val animated = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = npDuration(Motion.MiniProgress), easing = LinearEasing),
        label = "npMiniProgress",
    )
    val track = trackColor ?: ink.border
    val fill = fillColor ?: ink.accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NpDimens.miniProgress)
            .drawBehind {
                drawRect(track)
                val fraction = animated.value.coerceIn(0f, 1f)
                if (fraction <= 0f) return@drawBehind
                val w = size.width * fraction
                val r = minOf(2.dp.toPx(), size.height / 2f)
                drawRect(fill, size = Size((w - r).coerceAtLeast(0f), size.height))
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(w - 2f * r, 0f),
                    size = Size(2f * r, size.height),
                    cornerRadius = CornerRadius(r, r),
                )
            },
    )
}

/**
 * `.medcard .m .foldertag`：网格卡与列表行 meta 里的**文件夹色相胶囊**。
 *
 * 字 accent（hue）、底 16%、边 38%——设计稿里这个胶囊承担"这条属于哪个文件夹"，
 * 与封面的色相内环是同一套编码；纯文本文件夹名读不出这层关系。
 */
@Composable
fun NpFolderTag(
    folder: String,
    modifier: Modifier = Modifier,
    isAudio: Boolean = false,
) {
    val hue = NpMediaColor.itemHue(folder, isAudio)
    val facc = NpMediaColor.accent(hue)
    Text(
        text = folder.substringAfterLast('/').ifBlank { folder },
        style = NpTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
        color = facc,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(facc.copy(alpha = 0.16f), RoundedCornerShape(NpRadius.chip))
            .border(1.dp, facc.copy(alpha = 0.38f), RoundedCornerShape(NpRadius.chip))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/**
 * `.thumb`：16:9 封面 + 角标层。
 *
 * 角标层的位置是设计稿量出来的：kindtag 左上 8dp、"新增"右上 8dp、
 * 时长右下 8dp、播放键左下 8dp、进度线贴底。任何一个挪 2dp 都会失去"同一套网格"的观感。
 */
@Composable
fun NpCoverBox(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(NpRadius.card),
    placeholderHue: Int? = null,
    progress: Float? = null,
    isAudio: Boolean = false,
    showKindTag: Boolean = true,
    showPlayGlyph: Boolean = false,
    isNew: Boolean = false,
    durationText: String? = null,
    /** `.thumb::after` 的色相内环（accent 30% inset 1px），null 时不画 */
    tintRing: Color? = null,
    /** 列表行的小封面用图标角标（设计稿 `.lr-kind` 只有图标，放不下「视频」二字） */
    compactKindTag: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    // 底色画刷按 (色相, 主题底色) 记忆化：rail 里每张卡片每次重组都新建一条渐变
    // 属于纯分配开销，而它只是真实封面加载前后的一层兜底。
    val coverBrush = remember(placeholderHue, ink.surface2) {
        placeholderHue?.let { NpMediaColor.coverBrush(it) } ?: SolidColor(ink.surface2)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(shape)
            .background(coverBrush)
            .border(1.dp, ink.border, shape)
            .then(
                if (tintRing != null) {
                    // 几何值随尺寸缓存（E121）：这圈描边原先在 drawWithContent 里
                    // 每次绘制都新建 Stroke/CornerRadius/Offset/Size 并做 5 次 dp→px，
                    // 而封面卡是滚动列表里逐帧重绘的那一类。
                    Modifier.drawWithCache {
                        val strokeW = 1.dp.toPx()
                        val style = Stroke(width = strokeW)
                        val corner = CornerRadius(NpRadius.card.toPx(), NpRadius.card.toPx())
                        val topLeft = Offset(0.5f.dp.toPx(), 0.5f.dp.toPx())
                        val boxSize = Size(size.width - strokeW, size.height - strokeW)
                        onDrawWithContent {
                            drawContent()
                            drawRoundRect(
                                color = tintRing,
                                style = style,
                                cornerRadius = corner,
                                topLeft = topLeft,
                                size = boxSize,
                            )
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
        if (showKindTag) {
            if (compactKindTag) {
                val tint = if (isAudio) NpMediaColor.audioTint else ink.accent
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(6.dp))
                        .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isAudio) NpIcon.Sound else NpIcon.Film,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(11.dp),
                    )
                }
            } else {
                NpKindTag(
                    isAudio = isAudio,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
        }
        if (isNew) {
            Text(
                text = "新增",
                style = NpTypography.labelSmall,
                color = ink.bg,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(ink.accent, RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        if (durationText != null) {
            Text(
                text = durationText,
                style = NpMonoSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (showPlayGlyph) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .size(34.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(NpRadius.chip)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NpIcon.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (progress != null && progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(ink.accent),
                )
            }
        }
    }
}

/**
 * `.medcard`：封面 + 两行标题 + 等宽元信息。
 *
 * [metaChips] 走槽位是因为元信息行有三种形态：只有"时长 · 分辨率"、
 * 带"已看 xx%"前缀、或带文件夹 chip——固定参数会把其中两种挤变形。
 */
@Composable
fun NpMediaCard(
    title: String,
    meta: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** meta 行首的文件夹色相胶囊（设计稿 gridCard 的 `.foldertag`） */
    folderTag: String? = null,
    folderTagIsAudio: Boolean = false,
    /** 长按（上下文菜单入口，§3.6）；null = 不注册长按，点击行为不变 */
    onLongClick: (() -> Unit)? = null,
    cover: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    // 悬停（设计稿 2.7）：上浮 2dp + 标题转 accent，仅在"有指针设备"时生效
    val hoverEnabled = LocalNpHoverEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 触屏下 hover 恒不成立 → 不建动画状态（省掉每张卡一个 Animatable 与随动协程）
    val titleColor = if (!hoverEnabled) {
        ink.fg
    } else {
        val animated by animateColorAsState(
            targetValue = if (hovered) ink.accent else ink.fg,
            animationSpec = npMotion(Motion.Hover, easing = NpEasing.Silk),
            label = "npMediaCardTitle",
        )
        animated
    }
    Column(
        modifier = modifier
            // E128 语义合并：整张卡应当是**一个**可点节点（Material 卡片同款）。
            // 不合并时一张卡在语义树里是 8+ 个节点（标题/元信息/角标/时长各占一个），
            // 而滚动期每进来一张卡都要把这些节点加进无障碍树、离开再摘掉——
            // 开了无障碍服务的设备上实测这条链路占滚动主线程时间的大头。
            //
            // **E129 修正挂载位置**：E128 把它写在 `npPressScale` 之后（clickable 内侧），
            // 而 `mergeDescendants` 合并的是**它内侧**的后代、clickable 在它外侧，
            // 于是卡片在无障碍树里依然是"1 个可点 View + 4 个 TextView + 1 个 Button"
            // （实机 uiautomator 实测）——合并基本等于没做。现在挂到**最外层**，
            // 并把 `role` 一并写进这个块（`npPressScale` 那边也把 role 交给了 clickable）。
            // **实测口径**：改后每帧语义链成本 debug 侧 **2.12 → 1.75ms（−17%）**；
            // 但卡片节点**没有**收敛成 1 个（仍是 1 个可点 View + 2 TextView + 1 Button，
            // Compose 侧 `mergeDescendants` 与 `clickable` 的节点边界规则待查）——
            // 如实记录，不要按"已合并成 1 个节点"理解。
            .semantics(mergeDescendants = true) { role = Role.Button }
            .hoverable(interactionSource = interactionSource, enabled = hoverEnabled)
            .npHoverLift(hovered = hovered, enabled = hoverEnabled)
            .npPressScale(
                onClick = onClick,
                pressedScale = 0.975f,
                interactionSource = interactionSource,
                onLongClick = onLongClick,
            ),
    ) {
        cover()
        Text(
            text = title,
            // 设计稿 `.medcard .t`：13.5px/600/-.01em——标题是卡片的重心，Normal 字重会垮
            style = NpTypography.bodySmall.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).sp,
            ),
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (meta.isNotEmpty() || folderTag != null) {
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (folderTag != null) {
                    NpFolderTag(folder = folderTag, isAudio = folderTagIsAudio)
                }
                // 合并成一段再截断：逐段排布时超宽的最后一段会被卡片边缘**硬裁**
                // （实测「360P HEVC」裁成「360P HE」），分隔符与详情页 meta 同款
                Text(
                    text = meta.joinToString(" · "),
                    style = NpMonoSmall,
                    color = ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `.listrow`：列表视图行 —— 112dp 缩略图 + 标题 + 元信息 + 箭头。
 *
 * 缩略图宽度在 400dp 以下收窄到 92dp（设计稿媒体查询），
 * 靠 [compact] 显式传入而不是在这里读配置——组件不该自己感知屏幕。
 */
@Composable
fun NpListRow(
    title: String,
    meta: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** meta 行首的文件夹色相胶囊（设计稿 listCard 的 `.foldertag`） */
    folderTag: String? = null,
    folderTagIsAudio: Boolean = false,
    /** 长按（上下文菜单入口，§3.6）；null = 不注册长按，点击行为不变 */
    onLongClick: (() -> Unit)? = null,
    cover: @Composable () -> Unit = {},
) {
    val ink = LocalNpInk.current
    // 悬停（设计稿 2.7）：`.listrow:hover .lr-title{color:var(--accent)}`
    val hoverEnabled = LocalNpHoverEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 触屏下 hover 恒不成立 → 不建动画状态
    val titleColor = if (!hoverEnabled) {
        ink.fg
    } else {
        val animated by animateColorAsState(
            targetValue = if (hovered) ink.accent else ink.fg,
            animationSpec = npMotion(Motion.Hover, easing = NpEasing.Silk),
            label = "npListRowTitle",
        )
        animated
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .npCardSurface(ink, RoundedCornerShape(NpRadius.card))
            // E128：同 NpMediaCard——整行合并为一个语义节点（滚动期少一整族节点的增删）。
            // E129：必须挂在**最外层**（clickable 之外），见 NpMediaCard 的说明。
            .semantics(mergeDescendants = true) { role = Role.Button }
            .hoverable(interactionSource = interactionSource, enabled = hoverEnabled)
            .npPressScale(
                onClick = onClick,
                pressedScale = 0.99f,
                interactionSource = interactionSource,
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(if (compact) 92.dp else 112.dp)) { cover() }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // 设计稿 `.listrow .lr-title`：14px/600
                style = NpTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty() || folderTag != null) {
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (folderTag != null) {
                        NpFolderTag(folder = folderTag, isAudio = folderTagIsAudio)
                    }
                    // 同 NpMediaCard：合并成一段，超宽时省略号收尾而不是被行边缘硬裁
                    Text(
                        // 设计稿 `.lr-meta` 12px mono
                        text = meta.joinToString(" · "),
                        style = NpMono.copy(fontSize = 12.sp),
                        color = ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Icon(
            imageVector = NpIcon.ChevronRight,
            contentDescription = null,
            tint = ink.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * `.rail-scroll .cw`：横向走马灯里的一格。
 *
 * 固定 184dp 宽（设计稿 `.rail-scroll .cw{width:184px}`）——所有 rail 用同一个宽度，
 * 横向滑动时刻度一致；各 rail 自己定宽会让"下一屏"永远对不齐。
 */
@Composable
fun NpCarouselItem(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.width(NpDimens.railCardWidth)) { content() }
}

/**
 * `.folder`：文件夹卡片 —— 16:10 封面 + 底部色条 + 图标 + 名称 + 计数。
 *
 * 底部色条（`border-top: 2px solid <facc>60`）是这张卡片的签名：
 * 它把"这个文件夹是什么颜色"从封面延伸到文字区，色相编码才真正可读。
 */
@Composable
fun NpFolderCard(
    name: String,
    countLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable () -> Unit = {},
    coverPill: String? = null,
) {
    val ink = LocalNpInk.current
    val hue = NpMediaColor.folderHue(name)
    val facc = NpMediaColor.accent(hue)
    val shape = RoundedCornerShape(NpRadius.card)
    // 悬停（设计稿 2.7）：上浮 2dp + 描边色相加深（26% → 40%）
    val hoverEnabled = LocalNpHoverEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 触屏下 hover 恒不成立 → 不建动画状态（描边直接取常量 0.26）
    val borderAlpha = if (!hoverEnabled) {
        0.26f
    } else {
        val animated by animateFloatAsState(
            targetValue = if (hovered) 0.40f else 0.26f,
            animationSpec = npMotion(Motion.Hover, easing = NpEasing.Silk),
            label = "npFolderCardBorder",
        )
        animated
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // E128：同 NpMediaCard——整卡合并为一个语义节点。
            // E129：必须挂在**最外层**（clickable 之外），见 NpMediaCard 的说明。
            .semantics(mergeDescendants = true) { role = Role.Button }
            .hoverable(interactionSource = interactionSource, enabled = hoverEnabled)
            .npHoverLift(hovered = hovered, enabled = hoverEnabled)
            .clip(shape)
            .background(ink.surface)
            .border(1.dp, facc.copy(alpha = borderAlpha), shape)
            .npPressScale(
                onClick = onClick,
                pressedScale = 0.975f,
                interactionSource = interactionSource,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            cover()
            // 设计稿 `.folder .pill`：左下 mono 胶囊（"N 个视频"），facc 着色
            if (coverPill != null) {
                Text(
                    text = coverPill,
                    style = NpMonoSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        // 设计稿 `.folder .pill`：`color-mix(in oklch, facc 42%, #000 58%)`
                        // ——底色是文件夹色相与黑的混合，不是纯黑；纯黑会让胶囊与封面
                        // 的色相编码断开（这一枚 pill 也承担"哪个文件夹"的线索）。
                        .background(
                            facc.copy(alpha = 0.42f).compositeOver(Color.Black),
                            RoundedCornerShape(5.dp),
                        )
                        .border(1.dp, facc.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(facc.copy(alpha = 0.60f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(facc.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NpIcon.Folder,
                    contentDescription = null,
                    tint = facc,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = name,
                // 设计稿 `.folder .bar b`：14px/600
                style = NpTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 设计稿 `.folder .bar .cnt`：12px mono
            Text(
                text = countLabel,
                style = NpMono.copy(fontSize = 12.sp),
                color = ink.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * 空状态用的虚线圆环占位（封面缺失时）。用 [NpMediaColor] 的色相让占位也有归属感。
 */
@Composable
fun NpCoverPlaceholder(
    hue: Int,
    modifier: Modifier = Modifier,
) {
    // 同 NpCoverBox：色相画刷记忆化，避免逐次重组重建渐变
    val brush = remember(hue) { NpMediaColor.coverBrush(hue) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NpIcon.Film,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.50f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 百分比 → 0..1 的进度；[totalMs] 为 0 时返回 0（未知时长不该显示满格）。
 */
fun progressOf(positionMs: Long, totalMs: Long): Float =
    if (totalMs <= 0L) 0f else (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

/** 0..1 → 百分数字符串（角标与提示用）。 */
fun percentText(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"
