package com.noneplayer.plugins.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noneplayer.kernel.api.SubtitleCue
import androidx.compose.material3.Text

/**
 * 字幕浮层（E1）——渲染引擎输出的 Cue。
 *
 * 外挂字幕（srt/ass/vtt）与内嵌轨道（MKV 文本轨、PGS 位图轨）经引擎统一映射为
 * [SubtitleCue]，本层只负责画：
 * - 文本 Cue：底部居中、白字 + 阴影（不遮挡画面，暗背景上依然可读）；
 * - 位图 Cue：按 Cue 的归一化摆放绘制在视频画面内。
 *
 * 挂载位置：视频 Box（aspectRatio 约束）之内，因此归一化坐标天然对应画面而非黑边。
 * 样式面板（字号/描边/位置）属批次 3，本层先按默认样式。
 */
@Composable
fun SubtitleLayer(
    cues: List<SubtitleCue>,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return
    val textCues = cues.filter { it.text != null }
    val bitmapCues = cues.filter { it.bitmap is Bitmap }

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
                        style = SubtitleTextStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // 位图字幕（PGS/VobSub）：按归一化摆放绘制
        bitmapCues.forEach { cue ->
            val bitmap = cue.bitmap as Bitmap
            val placement = cue.placement
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthPx = maxWidth * (placement?.width ?: 1f)
                val heightPx = maxHeight * (placement?.height ?: 1f)
                val leftPx = maxWidth * (placement?.left ?: 0f)
                val topPx = maxHeight * (placement?.top ?: 0f)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset(x = leftPx, y = topPx)
                        .size(width = widthPx, height = heightPx),
                )
            }
        }
    }
}

/** 字幕默认样式：白字 + 阴影（批次 3 由样式面板接管） */
private val SubtitleTextStyle = TextStyle(
    color = Color.White,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.Medium,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.9f),
        offset = Offset(0f, 1.5f),
        blurRadius = 5f,
    ),
)

/** 底部留白：控制层（进度条）之上，避免字幕被控件遮挡 */
private val SUBTITLE_BOTTOM_PADDING = 56.dp
