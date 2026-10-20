package com.noneplayer.plugins.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.DEFAULT_SUBTITLE_COLOR_ARGB
import com.noneplayer.core.media.SubtitleEffectInfo
import com.noneplayer.core.media.SubtitleEffectNotice
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.NpTrackType
import com.noneplayer.kernel.api.SubtitleAlignment
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.VideoPlayer
import com.noneplayer.kernel.ui.api.PlaybackTextCueArbiter
import com.noneplayer.kernel.ui.api.TextCueClaim
import kotlin.math.roundToInt

/**
 * 字幕样式快照（E131）——「字幕设置」面板写入 `AppSettings`，渲染层只读它。
 *
 * 默认值 = 旧版写死的样式（白 / 1.0x / 非斜体 / 开启），因此不传等于行为不变。
 */
@Immutable
data class SubtitleStyle(
    /** 「开启字幕」总开关：关掉后字幕（文本与位图）一律不渲染 */
    val enabled: Boolean = true,
    /** 字幕文字颜色（ARGB） */
    val colorArgb: Long = 0xFFFFFFFFL,
    /** 文字大小倍率（基准 20sp） */
    val scale: Float = 1f,
    /** 文字字幕用斜体 */
    val italic: Boolean = false,
    /** A12：垂直位置（0 = 画面顶端，1 = 底端）；Cue 自带 lineFraction 时以 Cue 为准 */
    val verticalPosition: Float = 1f,
    /** A12：给文字垫一条半透明黑底，亮画面可读 */
    val background: Boolean = false,
    /** A12：文字描边（黑），与软阴影二选一叠加 */
    val outline: Boolean = false,
    /** A12：同屏每条字幕最多显示几行；超出省略 */
    val maxLines: Int = 3,
)

/**
 * 字幕浮层（E1）——渲染引擎输出的 Cue。
 *
 * 外挂字幕（srt/ass/vtt）与内嵌轨道（MKV 文本轨、PGS 位图轨）经引擎统一映射为
 * [SubtitleCue]，本层只负责画：
 * - 文本 Cue：底部居中、白字 + 阴影（不遮挡画面，暗背景上依然可读）；
 * - 位图 Cue：按 Cue 的归一化摆放绘制在视频画面内。
 *
 * 挂载位置：视频 Box（aspectRatio 约束）之内，因此归一化坐标天然对应画面而非黑边。
 *
 * E131：样式不再写死——由 [style] 传入（对齐参考示例的「字幕设置」面板）。
 */
/**
 * 播放器的字幕订阅点（A6）：Cue **只在这一层**解包。
 *
 * 页根与舞台都不再 `by` 解包 `player.cues`——那会让每来一组字幕都重组整棵播放页
 * （PGS 可以一秒切好几次；无字幕的片子测不出来，所以此前一直没暴露）。
 * 这里是一个独立的 `@Composable`：Cue 变化只重组它自己，舞台、覆盖层、控制层都不参与。
 */
@Composable
fun PlayerSubtitleLayer(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    style: SubtitleStyle = SubtitleStyle(),
    /** 当前媒体 uri：内嵌弹幕认领同一批 Cue 时，字幕层据此让位（A12） */
    mediaUri: String = "",
) {
    val cues by player.cues.collectAsStateWithLifecycle()
    // A12：文本轨被弹幕层认领（内嵌弹幕源）时不再重复画文本；位图字幕不受影响。
    val claim by PlaybackTextCueArbiter.claim.collectAsStateWithLifecycle()
    val tracks by player.tracks.collectAsStateWithLifecycle()
    val selectedTextId = tracks.firstOrNull { it.type == NpTrackType.TEXT && it.selected }?.id
    val suppressText = shouldSuppressTextCues(claim, mediaUri, selectedTextId)
    // A12 第一期：外挂字幕含 `\pos`/卡拉OK/旋转等当前做不到的特效时，诚实提示一句。
    // 检测发生在挂载前处理（SubtitleFile.prepareSubtitleUri），这里只订阅结果。
    val effectInfo by SubtitleEffectNotice.info.collectAsStateWithLifecycle()
    // 关闭状态按「字幕来源」记：换一份字幕重新提示；同一份字幕重新挂载不重复打扰
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val dismissed = effectInfo?.let { dismissedFor == it.sourceUri } == true
    Box(modifier = modifier.fillMaxSize()) {
        SubtitleLayer(
            cues = visibleCuesForArbitration(cues, suppressText),
            modifier = Modifier.fillMaxSize(),
            style = style,
        )
        if (shouldShowSubtitleEffectNotice(style.enabled, effectInfo, dismissed)) {
            SubtitleEffectNoticePill(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                onDismiss = { dismissedFor = effectInfo?.sourceUri },
            )
        }
    }
}

/**
 * A12 第一期：「此字幕含特效，当前只显示文本」提示条。
 *
 * 贴在画面**上沿居中**、只占一行、背景半透明，不压底部字幕与控制层；右侧「×」
 * 点一下永久关掉**这一份字幕**的提示（换字幕会重新提示）。提示只说明「含特效」，
 * 不做逐条判断——ASS 的 span 富文本/卡拉OK/旋转是第二阶段的事。
 */
@Composable
private fun SubtitleEffectNoticePill(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(NpRadius.chip))
            .padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SUBTITLE_EFFECT_NOTICE_TEXT,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Text(
            text = "×",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun SubtitleLayer(
    cues: List<SubtitleCue>,
    modifier: Modifier = Modifier,
    style: SubtitleStyle = SubtitleStyle(),
) {
    if (cues.isEmpty() || !style.enabled) return
    val textCues = cues.filter { it.text != null }
    val bitmapCues = cues.filter { it.bitmap is Bitmap }

    Box(modifier = modifier.fillMaxSize()) {
        // 文本字幕（A12 第一期）：按 Cue 自己的对齐 / 垂直位置摆放，缺省才是底部居中。
        // 用户显式改过颜色时优先用户值；否则吃容器给的主色（ASS ForegroundColorSpan）。
        textCues.forEach { cue ->
            val cueColor = if (style.colorArgb != DEFAULT_SUBTITLE_COLOR_ARGB) {
                Color(style.colorArgb.toInt())
            } else {
                cue.textColorArgb?.let { Color(it.toInt()) } ?: Color(style.colorArgb.toInt())
            }
            TextCue(
                cue = cue,
                style = style,
                color = cueColor,
            )
        }

        // 位图字幕（PGS/VobSub）：按归一化摆放绘制。
        // A6：不再每条 Cue 套一层 `BoxWithConstraints` + `offset`（那是布局位移，
        // 高频位图轨每换一帧都要重新测量），改在一块 Canvas 里按目标矩形直接画。
        val frames = remember(bitmapCues) {
            bitmapCues.map { cue ->
                SubtitleBitmapFrame(
                    image = (cue.bitmap as Bitmap).asImageBitmap(),
                    placement = cue.placement,
                )
            }
        }
        if (frames.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                frames.forEach { frame ->
                    val placement = frame.placement
                    val width = size.width * (placement?.width ?: 1f)
                    val height = size.height * (placement?.height ?: 1f)
                    drawImage(
                        image = frame.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(frame.image.width, frame.image.height),
                        dstOffset = IntOffset(
                            (size.width * (placement?.left ?: 0f)).roundToInt(),
                            (size.height * (placement?.top ?: 0f)).roundToInt(),
                        ),
                        dstSize = IntSize(
                            width.roundToInt().coerceAtLeast(1),
                            height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 一条文本 Cue 的摆放（A12 第一期）。
 *
 * - 水平：Cue 的 [SubtitleCue.positionFraction] 优先（按中心点），否则按
 *   [SubtitleCue.alignment] 左/中/右；
 * - 垂直：Cue 的 [SubtitleCue.lineFraction] 优先（≤0.5 当顶边锚、>0.5 当底边锚），
 *   否则按用户设置的 [SubtitleStyle.verticalPosition]（0 顶、1 底，默认 1 + 底部留白）；
 * - 样式：颜色 / 字号 / 斜体 / 背景条 / 黑描边 / 最大行数。
 *
 * 摆放用 `layout` 相位完成（文字更新本来低频；不额外套 `BoxWithConstraints`）。
 */
@Composable
private fun TextCue(
    cue: SubtitleCue,
    style: SubtitleStyle,
    color: Color,
) {
    val density = LocalDensity.current
    val textStyle = SubtitleTextStyle(color = color, scale = style.scale, italic = style.italic)
    val strokeStyle = textStyle.copy(
        color = Color.Black,
        drawStyle = Stroke(width = with(density) { 3.dp.toPx() }),
    )
    val textAlign = when (cue.alignment) {
        SubtitleAlignment.LEFT -> TextAlign.Start
        SubtitleAlignment.CENTER -> TextAlign.Center
        SubtitleAlignment.RIGHT -> TextAlign.End
    }
    val maxLines = style.maxLines.coerceAtLeast(1)
    val text = cue.text.orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val stageW = constraints.maxWidth
                val stageH = constraints.maxHeight
                if (stageW <= 0 || stageH <= 0) {
                    return@layout layout(stageW, stageH) { }
                }
                val hPad = (stageW * 0.04f).roundToInt()
                val maxTextWidth = (stageW - hPad * 2).coerceAtLeast(1)
                val placeable = measurable.measure(Constraints(maxWidth = maxTextWidth))
                // A12 第一期：摆放算式抽成纯函数（纯 JVM 单测钉住），这里只做测量与落位
                val x = textCueLeftX(
                    stageWidth = stageW,
                    textWidth = placeable.width,
                    positionFraction = cue.positionFraction,
                    alignment = cue.alignment,
                    horizontalPaddingPx = hPad,
                )
                val y = textCueTopY(
                    stageHeight = stageH,
                    textHeight = placeable.height,
                    lineFraction = cue.lineFraction,
                    verticalPosition = style.verticalPosition,
                    bottomPaddingPx = SUBTITLE_BOTTOM_PADDING.roundToPx(),
                )
                layout(stageW, stageH) { placeable.place(x, y) }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = if (style.background) {
                Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(NpRadius.chip))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            } else {
                Modifier
            },
        ) {
            if (style.outline) {
                Text(
                    text = text,
                    style = strokeStyle,
                    textAlign = textAlign,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = text,
                style = textStyle,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 字幕文本样式（E131：由「字幕设置」面板驱动）。
 *
 * 基准仍是设计稿的白字 + 阴影（暗背景可读），新增三项用户可调：
 * 颜色 / 大小倍率 / 斜体。行高按同一倍率缩放，否则放大字号会挤成一行。
 */
private fun SubtitleTextStyle(color: Color, scale: Float, italic: Boolean): TextStyle {
    val size = 20f * scale
    return TextStyle(
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        fontWeight = FontWeight.Medium,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            offset = Offset(0f, 1.5f),
            blurRadius = 5f,
        ),
    )
}

/** 一帧位图字幕：把 `Bitmap → ImageBitmap` 的包装留在 Cue 变化时做一次，不在绘制相位重复。 */
private class SubtitleBitmapFrame(
    val image: ImageBitmap,
    val placement: CuePlacement?,
)

/** 底部留白：控制层（进度条）之上，避免字幕被控件遮挡 */
private val SUBTITLE_BOTTOM_PADDING = 56.dp

// ---------------------------------------------------------------------------
// A12 纯逻辑（无 Compose 依赖，纯 JVM 单测直接调用）
// ---------------------------------------------------------------------------

/**
 * A12：文本 Cue 要不要让位给认领者（内嵌弹幕层）。
 *
 * 必须**同一媒体 + 同一条已选文本轨**才让位：换媒体（上一条片子的认领）或同 URI
 * 分轨（认领的是另一条轨）都不能压住当前字幕。
 */
internal fun shouldSuppressTextCues(
    claim: TextCueClaim?,
    mediaUri: String,
    selectedTextId: String?,
): Boolean = claim != null &&
    claim.mediaUri == mediaUri &&
    selectedTextId != null &&
    claim.trackId == selectedTextId

/** A12：让位时只保留位图 Cue（文本 Cue 交给认领者渲染）。 */
internal fun visibleCuesForArbitration(
    cues: List<SubtitleCue>,
    suppressText: Boolean,
): List<SubtitleCue> = if (suppressText) cues.filter { it.bitmap != null } else cues

/**
 * A12 第一期：特效提示的显隐口径。
 *
 * 字幕总开关关掉时连提示也不出现；没有检测到特效不提示；用户点过「×」后，同一份
 * 字幕不再出现（换字幕才重新提示）。
 */
internal fun shouldShowSubtitleEffectNotice(
    enabled: Boolean,
    info: SubtitleEffectInfo?,
    dismissed: Boolean,
): Boolean = enabled && info != null && !dismissed

/** A12 第一期提示文案（任务口径，逐字一致；iOS/设计稿对齐时改这里）。 */
internal const val SUBTITLE_EFFECT_NOTICE_TEXT = "此字幕含特效，当前只显示文本"

/**
 * A12：文本 Cue 的水平落点（像素）。
 *
 * Cue 自带 [positionFraction] 时按中心点摆放（ASS `\pos`/`\an` 的水平份额），否则
 * 按 [alignment] 左/中/右；左右各留 [horizontalPaddingPx]，最后钳进画面内。
 */
internal fun textCueLeftX(
    stageWidth: Int,
    textWidth: Int,
    positionFraction: Float?,
    alignment: SubtitleAlignment,
    horizontalPaddingPx: Int,
): Int {
    val raw = when {
        positionFraction != null ->
            (stageWidth * positionFraction - textWidth / 2f).roundToInt()
        alignment == SubtitleAlignment.LEFT -> horizontalPaddingPx
        alignment == SubtitleAlignment.RIGHT -> stageWidth - textWidth - horizontalPaddingPx
        else -> (stageWidth - textWidth) / 2
    }
    return raw.coerceIn(0, (stageWidth - textWidth).coerceAtLeast(0))
}

/**
 * A12：文本 Cue 的垂直落点（像素）。
 *
 * - Cue 自带 [lineFraction]（ASS `\an`/`\pos` 的垂直份额）时**优先于**用户设置：
 *   ≤0.5 视为顶边锚，>0.5 视为底边锚（与抽取前的 inline 行为一致）；
 * - 否则按用户 [verticalPosition]（0 顶、1 底）在画面高 - 文字高 - 底部留白上插值。
 */
internal fun textCueTopY(
    stageHeight: Int,
    textHeight: Int,
    lineFraction: Float?,
    verticalPosition: Float,
    bottomPaddingPx: Int,
): Int {
    val raw = if (lineFraction != null) {
        if (lineFraction <= 0.5f) {
            (stageHeight * lineFraction).roundToInt()
        } else {
            (stageHeight * lineFraction).roundToInt() - textHeight
        }
    } else {
        ((stageHeight - textHeight - bottomPaddingPx) * verticalPosition).roundToInt()
    }
    return raw.coerceIn(0, (stageHeight - textHeight).coerceAtLeast(0))
}
