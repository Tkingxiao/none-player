package com.noneplayer.plugins.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.kernel.api.NpChapter

/**
 * 批次 3 播放页面板（§4.5 / §2.4 / §12.5）：
 * - [TrackOptionRow]：内嵌音轨/字幕轨的选项行（当前项 accent 标记）；
 * - [PicturePanelContent]：画面调节（比例/镜像/旋转），显示记忆由调用方落盘；
 * - [ChaptersPanel]：章节抽屉（§12.5，空列表时调用方直接隐藏入口）。
 *
 * 面板外壳沿用既有的 `NpSheet` / 底部抽屉语言（EpisodePanel 同配方），
 * 本文件只提供内容，弹层开合仍由 [PlayerScreen] 统一驱动。
 */

/** 画面比例模式（§2.4：适应/填充裁剪/拉伸/16:9/4:3；原始 = FIT 同义，故不单列） */
enum class VideoScaleMode(val label: String) {
    /** 按视频比例适配窗口（默认，黑边交给布局） */
    FIT("适应"),

    /** 满窗裁剪（cover，引擎侧 SCALE_TO_FIT_WITH_CROPPING） */
    CROP("裁剪填充"),

    /** 忽略比例拉伸到窗口（变形， Rare 使用） */
    STRETCH("拉伸"),

    /** 强制 16:9 */
    R16_9("16:9"),

    /** 强制 4:3 */
    R4_3("4:3"),
}

/** 解码显示记忆串（"<mode>|<mirror 0/1>|<rotate 0/180>"）；损坏输入回落默认值 */
fun parseDisplaySettings(raw: String?): Triple<VideoScaleMode, Boolean, Int> {
    if (raw.isNullOrBlank()) return Triple(VideoScaleMode.FIT, false, 0)
    return runCatching {
        val parts = raw.split("|")
        val mode = VideoScaleMode.entries.firstOrNull { it.name == parts[0] } ?: VideoScaleMode.FIT
        Triple(mode, parts.getOrNull(1) == "1", parts.getOrNull(2)?.toIntOrNull()?.let { r -> if (r == 180) 180 else 0 } ?: 0)
    }.getOrDefault(Triple(VideoScaleMode.FIT, false, 0))
}

fun serializeDisplaySettings(mode: VideoScaleMode, mirrored: Boolean, rotation: Int): String =
    "${mode.name}|${if (mirrored) 1 else 0}|$rotation"

/**
 * 内嵌轨道选项行：当前项 accent 底 + "正在使用"标记。
 * [trailing] 供字幕面板放"已挂载外挂"之类的补充说明。
 */
@Composable
fun TrackOptionRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NpRadius.tile))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = NpMonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text(
                text = "正在使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 画面调节面板内容（§2.4）：比例五档 + 镜像 + 旋转 180°。
 * 全部为**显示层**变换：比例走布局与引擎裁剪，镜像/旋转走 graphicsLayer
 * （Android 12+ SurfaceView 支持视图变换；字幕层在变换之外，不会被镜像）。
 */
@Composable
fun PicturePanelContent(
    currentMode: VideoScaleMode,
    mirrored: Boolean,
    rotation: Int,
    onMode: (VideoScaleMode) -> Unit,
    onMirror: (Boolean) -> Unit,
    onRotate: (Int) -> Unit,
) {
    val ink = MaterialTheme.colorScheme
    Text(
        text = "画面比例",
        style = MaterialTheme.typography.titleSmall,
        color = ink.onSurface,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(ink.surfaceContainerHigh)
            .padding(5.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        VideoScaleMode.entries.forEach { mode ->
            val on = mode == currentMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(if (on) ink.primary else Color.Transparent)
                    .clickable { onMode(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) ink.onPrimary else ink.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Text(
        text = "画面变换",
        style = MaterialTheme.typography.titleSmall,
        color = ink.onSurface,
        modifier = Modifier.padding(top = 16.dp),
    )
    ToggleLine(
        label = "左右镜像",
        checked = mirrored,
        onChecked = onMirror,
    )
    ToggleLine(
        label = "旋转 180°",
        checked = rotation == 180,
        onChecked = { onRotate(if (it) 180 else 0) },
    )
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NpRadius.tile))
            .clickable { onChecked(!checked) }
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * 章节抽屉（§12.5）：复用选集面板的版式（下半屏 + 半透明 scrim）。
 * 当前章 = position 落在其 [NpChapter.startMs, endMs) 区间。
 */
@Composable
fun ChaptersPanel(
    chapters: List<NpChapter>,
    positionMs: Long,
    onSelect: (NpChapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = NpRadius.sheet, topEnd = NpRadius.sheet),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column {
                Text(
                    text = "章节（${chapters.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(chapters) { index, chapter ->
                        val current = positionMs in chapter.startMs until (chapter.endMs.takeIf { it > chapter.startMs } ?: Long.MAX_VALUE)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(chapter) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = NpMonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatTimestamp(chapter.startMs),
                                style = NpMonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (current) {
                                Text(
                                    text = "  播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
