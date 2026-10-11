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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpChip
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.npMotion
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
 *
 * §9.2：位置以 [State] 传入、读在本面板内，并折成"当前章下标"这一个派生值。
 * 进度约 250ms 上报一次，若逐行判区间，每跳一次整个章节列表就重组一次；
 * 派生值只在**换章**时才通知，列表因此基本静止。
 */
@Composable
fun ChaptersPanel(
    /** 进出场驱动（审查批次 C3）：false 时播退场，由外层撑到播完再卸载 */
    visible: Boolean = true,
    chapters: List<NpChapter>,
    positionMs: State<Long>,
    onSelect: (NpChapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
    // 审查批次 C3：与选集面板同一套底部抽屉进出场（旧实现硬切）。
    // 实现与 `PlayerScreen.npSheetSlide` 同参同曲线，只是本文件拿不到那个 private 函数。
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = if (visible) npMotion(Motion.Panel) else npMotion(Motion.Field),
        label = "chaptersSlide",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (1f - slide).coerceIn(0f, 1f) }
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
                .graphicsLayer { translationY = size.height * slide }
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column {
                Text(
                    text = "章节（${chapters.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    // 章节表随条目一次性换页，本身不增删，故只给稳定 key（M24 的 fade+collapse
                // 是为"会增删的列表"准备的——队列/收藏），这里加 animateItem 只会白跑动画
                itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                        val current = index == activeIndex.value
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


/** 均衡器预设（A2-11）：只定义**形状**，段数由设备能力决定 */
enum class EqPreset { FLAT, BASS, VOCAL, TREBLE }

/**
 * 预设的第 [index] 段归一增益。`t` = 段序在整条谱上的位置（0 = 最低频段）。
 *
 * 用形状函数而不是写死数组：设备报 3 段、5 段、8 段都要能画出同一种"味道"，
 * 写死五档数组在别的段数上只能截断或补零，预设就变形了。
 */
internal fun eqPresetLevel(preset: EqPreset, index: Int, bands: Int): Float {
    if (bands <= 0) return 0f
    val t = if (bands == 1) 0.5f else index.toFloat() / (bands - 1)
    return when (preset) {
        EqPreset.FLAT -> 0f
        EqPreset.BASS -> 0.8f * (1f - t) - 0.15f * t
        EqPreset.VOCAL -> 0.7f * (1f - (2f * t - 0.8f) * (2f * t - 0.8f)).coerceAtLeast(0f) - 0.2f
        EqPreset.TREBLE -> 0.8f * t - 0.15f * (1f - t)
    }.coerceIn(-1f, 1f)
}

/**
 * 均衡器面板（A2-11 / 设计文档 §15.4）：总开关 + 每段滑杆 + 预设。
 *
 * 三条来自引擎现实的约束：
 * ① **段数与量程不写死**——framework `Equalizer` 各机型不同（5 段 ±1500 只是常见的
 *    一档），能力由服务侧实测经 `SessionExtras` 回传；拿不到能力时如实说"没有"，
 *    不画一排拨了没反应的滑杆（同「睡眠定时」那格的立场）。
 * ② 滑杆是**归一值 -1..1**，映射到 mB 在引擎侧做（客户端不知道量程）。
 * ③ 预设按形状函数生成，段数变了味道不变。
 */
@Composable
fun EqualizerPanel(
    capabilities: com.noneplayer.kernel.api.EqualizerCapabilities?,
    enabled: Boolean,
    levels: List<Float>,
    onEnabled: (Boolean) -> Unit,
    onLevels: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalNpInk.current
    val bands = capabilities?.bands ?: 0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = NpSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NpSpace.md),
        ) {
            Text(
                text = if (capabilities == null) "正在读取设备能力…" else "启用均衡器",
                style = NpTypography.bodyMedium,
                color = ink.fg,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled && capabilities != null, onCheckedChange = onEnabled)
        }
        if (capabilities == null) {
            Text(
                text = "本引擎未回传均衡器能力（设备无系统均衡器时即为此态）。",
                style = NpTypography.bodySmall,
                color = ink.muted,
                modifier = Modifier.padding(top = NpSpace.xs),
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = NpSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(NpSpace.sm),
        ) {
            EqPreset.entries.forEach { preset ->
                NpChip(
                    text = when (preset) {
                        EqPreset.FLAT -> "平坦"
                        EqPreset.BASS -> "低音"
                        EqPreset.VOCAL -> "人声"
                        EqPreset.TREBLE -> "明亮"
                    },
                    selected = false,
                    onClick = {
                        onLevels((0 until bands).map { eqPresetLevel(preset, it, bands) })
                    },
                )
            }
        }
        Text(
            text = "${capabilities.bands} 段 · 量程 ${capabilities.minLevelMb}…${capabilities.maxLevelMb} mB（设备实测）",
            style = com.noneplayer.core.ui.NpMonoSmall,
            color = ink.muted,
            modifier = Modifier.padding(bottom = NpSpace.sm),
        )
        repeat(bands) { index ->
            val value = levels.getOrElse(index) { 0f }.coerceIn(-1f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NpSpace.sm),
            ) {
                Text(
                    text = "段 ${index + 1}",
                    style = com.noneplayer.core.ui.NpMonoSmall,
                    color = ink.muted,
                    modifier = Modifier.width(52.dp),
                )
                androidx.compose.material3.Slider(
                    value = value,
                    onValueChange = { next ->
                        onLevels((0 until bands).map { i ->
                            if (i == index) next else levels.getOrElse(i) { 0f }
                        })
                    },
                    valueRange = -1f..1f,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%+.2f".format(value),
                    style = com.noneplayer.core.ui.NpMonoSmall,
                    color = if (enabled) ink.accent else ink.muted,
                    modifier = Modifier.width(46.dp),
                )
            }
        }
    }
}
