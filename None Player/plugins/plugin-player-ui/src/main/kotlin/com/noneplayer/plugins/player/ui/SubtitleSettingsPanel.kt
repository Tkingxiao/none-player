package com.noneplayer.plugins.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.noneplayer.core.data.SUBTITLE_COLOR_OPTIONS
import com.noneplayer.core.data.SUBTITLE_DELAY_STEPS_MS
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MAX
import com.noneplayer.core.data.SUBTITLE_MAX_LINES_MIN
import com.noneplayer.core.data.SUBTITLE_SCALE_MAX
import com.noneplayer.core.data.SUBTITLE_SCALE_MIN
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MAX
import com.noneplayer.core.data.SUBTITLE_VERTICAL_MIN
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.kernel.api.NpTrack
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 挂载字幕的展示名（B4）：SAF 文档 uri 的末段是**百分号编码**的 docId
 * （`primary%3AMovies%2F…%2Fnp-long.srt`），直接按 `/` 切会切不开、把整串编码当文件名
 * 显示；先解码再取末段、去掉扩展名——面板里要读的是"挂了哪个字幕"，不是完整地址。
 * 竖屏面板、全屏下拉与内嵌菜单共用这一份口径。
 */
internal fun subtitleMountName(savedSubtitle: String?): String? = savedSubtitle
    ?.let { android.net.Uri.decode(it) }
    ?.substringAfterLast('/')
    ?.substringBeforeLast('.')
    ?.takeIf { it.isNotBlank() }

/**
 * 字幕状态行（E131）：`[没有字幕]` / `已挂载：<名>` / `内嵌：<轨名>`。
 *
 * 与参考示例的 `[没有字幕]` 同义——面板里改的是"样式"，这一行回答的是
 * "现在到底有没有字幕、来自哪儿"，避免用户调了半天颜色却发现根本没挂字幕。
 */
internal fun subtitleStatusText(
    enabled: Boolean,
    subtitleLoaded: Boolean,
    savedSubtitle: String?,
    embeddedLabel: String?,
): String = when {
    // 总开关关掉时先说"已关闭"：此时引擎侧字幕已被卸载，再说"已挂载"是误导
    // （挂载记录仍在，是为了重新开启时能装回去）。真机实测中发现的口径问题。
    !enabled -> "[已关闭]"
    subtitleLoaded && savedSubtitle != null ->
        "已挂载：" + (subtitleMountName(savedSubtitle) ?: savedSubtitle)
    embeddedLabel != null -> "内嵌：$embeddedLabel"
    else -> "[没有字幕]"
}

/** 字幕延时档位的显示文案（−1.0s / −0.1s / 0.0 / +0.1s / +1.0s）；竖屏面板与全屏下拉共用 */
internal fun subtitleDelayLabel(ms: Long): String = when {
    ms == 0L -> "0.0"
    else -> (if (ms > 0) "+" else "−") + "%.1f".format(kotlin.math.abs(ms) / 1000f) + "s"
}

/**
 * 字幕设置面板（E131）——版式对齐用户给的参考示例：
 * 开启字幕 / 字幕颜色 / 文字字幕大小 / 文字字幕用斜体 / 字幕延时 / 状态行 / 两个来源入口。
 *
 * 复用既有组件与令牌（`NpSwitch` / `NpDimens.touchTarget` / `NpRadius.chip` / Ink 令牌），
 * 不新造控件：颜色板是"命中 44dp 的透明盒 + 26dp 色点"，延时档是"命中 44dp 的胶囊"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleSettingsPanel(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    colorArgb: Long,
    onColor: (Long) -> Unit,
    scale: Float,
    onScale: (Float) -> Unit,
    italic: Boolean,
    onItalic: (Boolean) -> Unit,
    verticalPosition: Float,
    onVerticalPosition: (Float) -> Unit,
    background: Boolean,
    onBackground: (Boolean) -> Unit,
    outline: Boolean,
    onOutline: (Boolean) -> Unit,
    maxLines: Int,
    onMaxLines: (Int) -> Unit,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
    statusText: String,
    embeddedTracks: List<NpTrack>,
    onPickEmbedded: (NpTrack) -> Unit,
    onPickExternal: () -> Unit,
    onEmbeddedUnavailable: () -> Unit,
) {
    val ink = LocalNpInk.current
    var showEmbedded by remember { mutableStateOf(false) }

    SubtitleSettingRow(
        label = "开启字幕",
        checked = enabled,
        onCheckedChange = onEnabled,
    ) {
        // A27/REV-14：开关语义挂在整行（SubtitleSettingRow 的 toggleable），
        // 里层开关只负责画图形并清空语义，否则读屏会把同一开关报两遍。
        NpSwitch(
            checked = enabled,
            onCheckedChange = onEnabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }

    SubtitleSettingRow(label = "字幕颜色") {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SUBTITLE_COLOR_OPTIONS.forEach { argb ->
                val selected = argb == colorArgb
                Box(
                    modifier = Modifier
                        .size(NpDimens.touchTarget)
                        .clip(CircleShape)
                        .clickable { onColor(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(argb.toInt()))
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, ink.fg, CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }

    SubtitleSettingRow(label = "文字字幕大小") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = SUBTITLE_SCALE_MIN..SUBTITLE_SCALE_MAX,
                thumb = { NpMenuSliderThumb() },
                track = { state ->
                    NpMenuSliderTrack(
                        state = state,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = ink.border,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.1f".format(scale),
                style = NpMono,
                color = ink.muted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    SubtitleSettingRow(
        label = "文字字幕用斜体",
        checked = italic,
        onCheckedChange = onItalic,
    ) {
        NpSwitch(
            checked = italic,
            onCheckedChange = onItalic,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }

    // A12：竖直位置 / 背景条 / 描边 / 最大行数
    SubtitleSettingRow(label = "字幕垂直位置") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = verticalPosition,
                onValueChange = onVerticalPosition,
                valueRange = SUBTITLE_VERTICAL_MIN..SUBTITLE_VERTICAL_MAX,
                thumb = { NpMenuSliderThumb() },
                track = { state ->
                    NpMenuSliderTrack(
                        state = state,
                        activeColor = MaterialTheme.colorScheme.primary,
                        inactiveColor = ink.border,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    verticalPosition <= 0.01f -> "顶部"
                    verticalPosition >= 0.99f -> "底部"
                    else -> "${(verticalPosition * 100).roundToInt()}%"
                },
                style = NpMono,
                color = ink.muted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    SubtitleSettingRow(
        label = "字幕背景条",
        checked = background,
        onCheckedChange = onBackground,
    ) {
        NpSwitch(
            checked = background,
            onCheckedChange = onBackground,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }

    SubtitleSettingRow(
        label = "字幕描边",
        checked = outline,
        onCheckedChange = onOutline,
    ) {
        NpSwitch(
            checked = outline,
            onCheckedChange = onOutline,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }

    SubtitleSettingRow(label = "字幕最大行数") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (SUBTITLE_MAX_LINES_MIN..SUBTITLE_MAX_LINES_MAX).forEach { lines ->
                val selected = lines == maxLines
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = NpDimens.touchTarget)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(if (selected) ink.accentSoft else ink.fgSoft)
                        .clickable { onMaxLines(lines) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$lines",
                        style = NpMonoSmall,
                        color = if (selected) ink.accent else ink.fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    SubtitleSettingRow(label = "字幕延时") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SUBTITLE_DELAY_STEPS_MS.forEach { step ->
                val selected = step == offsetMs
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 44dp 命中红线（§14 #13）：与 D2 同一条判据
                        .heightIn(min = NpDimens.touchTarget)
                        .clip(RoundedCornerShape(NpRadius.chip))
                        .background(if (selected) ink.accentSoft else ink.fgSoft)
                        .clickable { onOffset(step) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = subtitleDelayLabel(step),
                        style = NpMonoSmall,
                        color = if (selected) ink.accent else ink.fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    Text(
        text = statusText,
        style = NpMonoSmall,
        color = ink.muted,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubtitleSourceButton(
            text = "选用视频内置字幕",
            modifier = Modifier.weight(1f),
            onClick = {
                if (embeddedTracks.isEmpty()) onEmbeddedUnavailable() else showEmbedded = !showEmbedded
            },
        )
        SubtitleSourceButton(
            text = "选用外挂字幕文件",
            modifier = Modifier.weight(1f),
            onClick = onPickExternal,
        )
    }

    if (showEmbedded && embeddedTracks.isNotEmpty()) {
        Text(
            // 与全屏下拉同一口径：这条列表里也可能出现刚挂上的外挂轨，标题不写"内嵌"。
            text = "字幕轨",
            style = NpTypography.titleSmall,
            color = ink.fg,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        embeddedTracks.forEach { track ->
            TrackOptionRow(
                label = track.label,
                detail = null,
                selected = track.selected,
                onClick = { onPickEmbedded(track) },
            )
        }
    }
}

/** 设置行：左标签（+可选当前值），右控件。版式对齐参考示例的"标签—控件"两列。 */
@Composable
private fun SubtitleSettingRow(
    label: String,
    value: String? = null,
    /**
     * A27：开关行整行可点。以前只有右端 46×27dp 的开关本体是命中区，
     * 整行看着像设置项、点标签没反应（无障碍扫描也报命中区过小）。
     * 传入 checked/onCheckedChange 时整行按开关语义可切换；其它行保持原样（行内自带控件）。
     */
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val ink = LocalNpInk.current
    val rowToggle = if (checked != null && onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            // 视觉不动：只扩命中区，不加行级涟漪（版式与 A27 之前一致）。
            indication = null,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NpDimens.touchTarget)
            .then(rowToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = NpTypography.bodyMedium,
            color = ink.fg,
            maxLines = 1,
        )
        if (value != null) {
            Text(
                text = value,
                style = NpMonoSmall,
                color = ink.muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            content()
        }
    }
}

/** 字幕来源入口按钮（参考示例底部两枚等宽按钮） */
@Composable
private fun SubtitleSourceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    Box(
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.button))
            .background(ink.fgSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NpTypography.bodySmall,
            color = ink.fg,
            maxLines = 1,
        )
    }
}

