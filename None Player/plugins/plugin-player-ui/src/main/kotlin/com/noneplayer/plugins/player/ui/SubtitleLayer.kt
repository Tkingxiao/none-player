package com.noneplayer.plugins.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noneplayer.kernel.api.CuePlacement
import com.noneplayer.kernel.api.SubtitleCue
import com.noneplayer.kernel.api.VideoPlayer
import androidx.compose.material3.Text
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
) {
    val cues by player.cues.collectAsStateWithLifecycle()
    SubtitleLayer(cues = cues, modifier = modifier, style = style)
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
    val textStyle = SubtitleTextStyle(
        color = Color(style.colorArgb.toInt()),
        scale = style.scale,
        italic = style.italic,
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 文本字幕：多行合并为一个列，底部居中，给控制层留出空间
        if (textCues.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = SUBTITLE_BOTTOM_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                textCues.forEach { cue ->
                    Text(
                        text = cue.text.orEmpty(),
                        style = textStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
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
