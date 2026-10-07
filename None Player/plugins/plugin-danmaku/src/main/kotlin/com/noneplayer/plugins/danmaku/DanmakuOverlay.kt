package com.noneplayer.plugins.danmaku

import android.content.Intent
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.kernel.api.PlaybackState
import com.noneplayer.kernel.api.get
import com.noneplayer.kernel.ui.api.LocalKernel
import com.noneplayer.kernel.ui.api.PlaybackContext
import com.noneplayer.kernel.ui.api.PlaybackOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.core.net.toUri

/**
 * 弹幕覆盖层（批次 6 渲染任务①②③）——经 `PlaybackOverlay` 扩展点挂进播放页
 * 视频舞台（E23 响应式挂载，本插件不改内核与播放页一行代码）。
 *
 * **来源优先级**：内嵌弹幕轨（用户在弹幕菜单里显式选择，任务③）>
 * 手动选择的本地文件（按视频 uri 持久化）> 同名自动匹配（[DanmakuLocator]）。
 * 文件来源在 IO 线程加载，屏蔽词在装载时过滤。
 *
 * **渲染**：[DanmakuPlanner] 纯函数摆位 + 单 Canvas 绘制。帧循环只写一个
 * `mutableLongStateOf`（§9.2 绘制层纪律：逐帧只进 draw 阶段，不重组组合树）；
 * 播放位置用"锚点 + 壁钟外推"——引擎的 positionMs 是粗粒度发射，两次发射
 * 之间靠帧时钟外推，发射本身负责 seek/暂停的再锚定。
 *
 * **开关/状态入口（任务②）**：右上角一枚「弹」pill（避开顶栏 trailing 图标，
 * 下移一行）——点按总开关；长按菜单：SAF 手动选择弹幕文件 / 清除手动 /
 * 内嵌弹幕轨列表。无任何来源时不显示 pill，不给纯看视频的用户添堵。
 *
 * **内嵌弹幕轨的已知限制**：`SubtitleCue` 契约没有时间轴（只有当前屏上的
 * cue），所以内嵌通路是"新 cue 出现 → 立即按当前位置生成一条滚动弹幕"；
 * 且字幕层（SubtitleLayer）与弹幕层会**同时显示**同一条 cue——这是
 * `PlaybackOverlay` 契约下无法单方面关掉的（关字幕轨 = 关 cues 通路）。
 * 菜单项上如实标注，完整时间线通道（引擎自定义命令，参照 np.scrub 模式）
 * 留作引擎侧增强。
 */
class DanmakuOverlay : PlaybackOverlay {

    override val overlayId: String = "danmaku.core"

    @Composable
    override fun Render(context: PlaybackContext) {
        DanmakuOverlayContent(context)
    }
}

/** 弹幕来源。None = 没找到任何来源（pill 也隐藏） */
private sealed interface DanmakuSource {
    data object None : DanmakuSource
    data class File(val items: List<Danmaku>, val name: String) : DanmakuSource
    data class Embedded(val trackId: String, val label: String) : DanmakuSource
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DanmakuOverlayContent(context: PlaybackContext) {
    val kernel = LocalKernel.current
    val settings = remember { kernel.services.get<AppSettings>() }
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by settings.danmakuEnabled.collectAsStateWithLifecycle(initialValue = true)
    val areaFraction by settings.danmakuAreaFraction.collectAsStateWithLifecycle(initialValue = 0.5f)
    val speed by settings.danmakuSpeed.collectAsStateWithLifecycle(initialValue = 1f)
    val fontScale by settings.danmakuFontScale.collectAsStateWithLifecycle(initialValue = 1f)
    val maxOnScreen by settings.danmakuMaxOnScreen.collectAsStateWithLifecycle(initialValue = 0)
    val alpha by settings.danmakuAlpha.collectAsStateWithLifecycle(initialValue = 1f)
    val blocklist by settings.danmakuBlocklist.collectAsStateWithLifecycle(initialValue = emptyList())
    val manualUri by settings.danmakuManualUri(context.media.uri).collectAsStateWithLifecycle(initialValue = null)

    val positionMs by context.player.positionMs.collectAsStateWithLifecycle()
    val playbackState by context.player.state.collectAsStateWithLifecycle()
    val tracks by context.player.tracks.collectAsStateWithLifecycle()
    val textTracks = tracks.filter { it.type == com.noneplayer.kernel.api.NpTrackType.TEXT }

    // ---- 来源解析 ----
    var source by remember(context.media.uri) { mutableStateOf<DanmakuSource>(DanmakuSource.None) }
    var embeddedPicked by remember(context.media.uri) { mutableStateOf<String?>(null) }

    // 文件来源：手动 > 自动。manualUri / blocklist 变化都会触发重载。
    LaunchedEffect(context.media.uri, manualUri, blocklist) {
        if (embeddedPicked != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val loaded = loadFileSource(appContext, context.media, manualUri, blocklist)
            withContext(Dispatchers.Main) { source = loaded }
        }
    }
    // 内嵌轨选择优先于文件来源
    LaunchedEffect(embeddedPicked) {
        val trackId = embeddedPicked ?: return@LaunchedEffect
        context.player.selectTrack(com.noneplayer.kernel.api.NpTrackType.TEXT, trackId)
        source = DanmakuSource.Embedded(trackId, textTracks.firstOrNull { it.id == trackId }?.label ?: "内嵌弹幕轨")
    }

    // ---- 内嵌通路：新 cue 出现 → 按当前位置 spawn 滚动弹幕 ----
    var spawned by remember(context.media.uri) { mutableStateOf<List<Danmaku>>(emptyList()) }
    val anchor = remember { Anchor() }
    LaunchedEffect(positionMs) { anchor.reanchor(positionMs) }
    LaunchedEffect(source is DanmakuSource.Embedded) {
        if (source !is DanmakuSource.Embedded) return@LaunchedEffect
        var lastText: String? = null
        context.player.cues.collect { cues ->
            val text = cues.firstNotNullOfOrNull { it.text }?.trim()?.replace('\n', ' ').orEmpty()
            if (text.isNotEmpty() && text != lastText) {
                lastText = text
                spawned = (spawned + Danmaku(anchor.displayMs(playbackState), DanmakuMode.Scroll, 1f, 0xFFFFFF, text))
                    .takeLast(500)
            }
        }
    }

    // ---- 帧循环：只写一个 Long 状态，逐帧失效仅进 draw 阶段 ----
    val frame = remember { mutableLongStateOf(0L) }
    val hasContent = source is DanmakuSource.File || source is DanmakuSource.Embedded
    LaunchedEffect(enabled, hasContent) {
        if (!enabled || !hasContent) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame.longValue = it }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (enabled && hasContent) {
            val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                frame.longValue // 读帧号：订阅 draw 阶段逐帧失效
                val danmaku = when (val s = source) {
                    is DanmakuSource.File -> s.items
                    is DanmakuSource.Embedded -> spawned
                    DanmakuSource.None -> return@Canvas
                }
                // 0.040：相对**视频画面高**的字号密度（原 0.052 是按整个窗格/整屏
                // 的高度取的，叠加"覆盖层未裁剪到画面"时文字大到盖满半屏；
                // 裁剪修复后以画面高为基准，平板实拍 ≈ B 站同屏手感）
                val basePx = size.height * 0.040f * fontScale
                val laneHeight = basePx * 1.4f
                val placed = DanmakuPlanner.plan(
                    danmaku = danmaku,
                    displayPosMs = anchor.displayMs(playbackState, frame.longValue),
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    config = DanmakuPlanner.Config(areaFraction, speed, fontScale, maxOnScreen),
                    laneHeightPx = laneHeight,
                    measure = { d, px ->
                        textPaint.textSize = px
                        textPaint.measureText(d.text)
                    },
                )
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    placed.forEach { p ->
                        textPaint.textSize = p.textPx
                        textPaint.color = p.danmaku.colorArgb
                        textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                        textPaint.setShadowLayer(2f, 0f, 1f, android.graphics.Color.BLACK)
                        val x = if (p.x >= 0f) p.x else (size.width - textPaint.measureText(p.danmaku.text)) / 2f
                        val fm = textPaint.fontMetrics
                        val baseline = p.y - (fm.ascent + fm.descent) / 2f
                        nc.drawText(p.danmaku.text, x, baseline, textPaint)
                    }
                }
            }
        }

        // ---- 「弹」pill（任务②）：有点击处才有开关；无来源不显示 ----
        val pillVisible = hasContent || manualUri != null || textTracks.isNotEmpty()
        if (pillVisible) {
            var menuOpen by remember { mutableStateOf(false) }
            val filePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    scope.launch { settings.setDanmakuManualUri(context.media.uri, uri.toString()) }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 16.dp)
                    .clip(RoundedCornerShape(NpRadius.chip))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .combinedClickable(
                        onClick = { scope.launch { settings.setDanmakuEnabled(!enabled) } },
                        onLongClick = { menuOpen = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = pillLabel(enabled, source, manualUri),
                    color = Color.White.copy(alpha = if (enabled) 0.95f else 0.5f),
                    fontSize = 12.sp,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (enabled) "关闭弹幕" else "开启弹幕") },
                        onClick = {
                            menuOpen = false
                            scope.launch { settings.setDanmakuEnabled(!enabled) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("选择弹幕文件…") },
                        onClick = {
                            menuOpen = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                    )
                    if (manualUri != null) {
                        DropdownMenuItem(
                            text = { Text("清除手动选择") },
                            onClick = {
                                menuOpen = false
                                scope.launch { settings.setDanmakuManualUri(context.media.uri, null) }
                            },
                        )
                    }
                    textTracks.forEach { track ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("内嵌弹幕轨：${track.label}")
                                    Text(
                                        "cue 通路；字幕层会同步显示同一条轨",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                menuOpen = false
                                embeddedPicked = track.id
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 播放位置锚点：引擎 positionMs 粗粒度发射 + 壁钟外推，发射即再锚定 */
private class Anchor {
    var positionMs: Long = 0L
        private set
    var nanos: Long = 0L
        private set

    fun reanchor(positionMs: Long) {
        this.positionMs = positionMs
        this.nanos = System.nanoTime()
    }

    /** [frameNanos] 来自 withFrameNanos（同源时钟）；暂停/seek 态直接返回锚点 */
    fun displayMs(playbackState: PlaybackState, frameNanos: Long = System.nanoTime()): Long {
        if (playbackState != PlaybackState.Playing) return positionMs
        return positionMs + (frameNanos - nanos) / 1_000_000L
    }
}

private suspend fun loadFileSource(
    appContext: android.content.Context,
    media: MediaItem,
    manualUri: String?,
    blocklist: List<String>,
): DanmakuSource {
    // 手动 > 同名自动；读取失败（授权失效/文件被移）按"无来源"处理
    val candidates: List<Pair<String, DanmakuFormat>> = buildList {
        if (manualUri != null) {
            DanmakuFormat.fromFileName(manualUri)?.let { add(manualUri to it) }
        }
        if (isEmpty()) {
            DanmakuLocator(appContext).candidatesFor(media).forEach { add(it.uri to it.format) }
        }
    }
    for ((uri, format) in candidates) {
        val text = runCatching {
            appContext.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                stream.readBytes().decodeText()
            }
        }.getOrNull() ?: continue
        // E98：解析失败（畸形文件、平台正则差异）只丢这一个来源——弹幕是增益功能，
        // 任何解析异常都不许冒到播放主链路（此前一个坏 .ass 就让整进程闪退）
        val items = runCatching { parseDanmakuFile(text, format, blocklist) }.getOrDefault(emptyList())
        if (items.isNotEmpty()) {
            val name = java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
            return DanmakuSource.File(items, name)
        }
    }
    return DanmakuSource.None
}

/** UTF-8 优先；解出 U+FFFD（替换符）说明不是 UTF-8（GBK 一系常见），按 GBK 重解 */
private fun ByteArray.decodeText(): String {
    val utf8 = toString(Charsets.UTF_8).removePrefix("\uFEFF")
    return if ('\uFFFD' in utf8) String(this, charset("GBK")).removePrefix("\uFEFF") else utf8
}

/** content:// 文件取 DISPLAY_NAME（直接取 last segment 会拿到数字 id） */
private fun displayName(context: android.content.Context, uri: String): String? = runCatching {
    context.contentResolver.query(
        uri.toUri(),
        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

private fun pillLabel(enabled: Boolean, source: DanmakuSource, manualUri: String?): String {
    val name = when (source) {
        is DanmakuSource.File -> source.name.substringBeforeLast('.').take(12)
        is DanmakuSource.Embedded -> source.label.take(12)
        DanmakuSource.None -> if (manualUri != null) "手动" else "弹"
    }
    return if (enabled) "弹·$name" else "弹·关"
}
