package com.noneplayer.plugins.danmaku

import android.content.Intent
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.npMotion
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

    val playbackState by context.player.state.collectAsStateWithLifecycle()
    val tracks by context.player.tracks.collectAsStateWithLifecycle()
    val textTracks = tracks.filter { it.type == com.noneplayer.kernel.api.NpTrackType.TEXT }

    // ---- 来源解析 ----
    //
    // 审查批次 B2：来源改成**显式单一状态机**——`embeddedPicked` 非空即"内嵌轨"。
    // 旧实现的缺陷是切不回去：`embeddedPicked != null` 时文件加载 effect 直接 return，
    // 而菜单里没有任何把 `embeddedPicked` 置回 null 的动作。于是用户先选了内嵌轨、
    // 再选弹幕文件，URI 存进去了、界面却仍停在旧的内嵌来源上——菜单承诺与结果不符。
    // 现在选文件或"恢复自动匹配"都会先清掉内嵌选择，文件 effect 也把它列为 key，
    // 所以切源必然触发重载。
    var source by remember(context.media.uri) { mutableStateOf<DanmakuSource>(DanmakuSource.None) }
    var embeddedPicked by remember(context.media.uri) { mutableStateOf<String?>(null) }
    // 内嵌通路累积的弹幕（切回文件来源时要清空，否则旧的内嵌弹幕会残留）
    var spawned by remember(context.media.uri) { mutableStateOf<List<Danmaku>>(emptyList()) }

    /** 切到"自动/手动文件"来源：清掉内嵌轨选择，交由文件 effect 重载 */
    fun useFileSource() {
        embeddedPicked = null
        spawned = emptyList()
    }

    // 文件来源：手动 > 自动。manualUri / blocklist / 内嵌选择变化都会触发重载
    // （embeddedPicked 进 key，是"从内嵌切回文件也能生效"的关键）。
    LaunchedEffect(context.media.uri, manualUri, blocklist, embeddedPicked) {
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
    val anchor = remember { Anchor() }
    // 帧号状态先于锚定效果声明——暂停态的锚点修正要写它
    val frame = remember { mutableLongStateOf(0L) }
    // 位置锚定走 Flow 收集，不用 `LaunchedEffect(positionMs)`：后者把位置读进组合，
    // 引擎每 250ms 一次的上报会重组整个覆盖层（§9.2 反例）。
    // 暂停态帧循环停转，锚点变化（seek）补一次重绘把新摆位画出来。
    LaunchedEffect(context.player) {
        context.player.positionMs.collect { position ->
            anchor.reanchor(position)
            if (context.player.state.value != PlaybackState.Playing) {
                frame.longValue += 1L
            }
        }
    }
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
    // 播放中逐帧推进；暂停/缓冲态画面本应定格——不再逐帧重绘（纯浪费），
    // 但把壁钟锚点保持在当下：否则恢复播放的瞬间会把整段暂停时长当作"已播"
    // 外推进显示位置，弹幕窗口闪跳一次。
    val hasContent = source is DanmakuSource.File || source is DanmakuSource.Embedded
    LaunchedEffect(enabled, hasContent) {
        if (!enabled || !hasContent) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { frameNanos ->
                if (context.player.state.value == PlaybackState.Playing) {
                    frame.longValue = frameNanos
                } else {
                    anchor.reanchor(context.player.positionMs.value)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (enabled && hasContent) {
            // 阴影层只在 Paint 建立时设一次（E122）：`setShadowLayer` 会重建 paint 的
            // shadow layer，原先逐帧调一次 = 每帧一次重建，而它是"可见条数无关"的常量。
            // FontMetrics 同理复用——无参 `getFontMetrics()` 每次 new 一个对象，
            // 而它按"可见条数 × 帧率"被叫。
            val textPaint = remember {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    setShadowLayer(2f, 0f, 1f, android.graphics.Color.BLACK)
                }
            }
            val fontMetrics = remember { android.graphics.Paint.FontMetrics() }
            // 逐帧 measureText 是纯排版热（CJK 每次都要走 shaping）；
            // 窗口内文字在字号不变时宽度恒定，按 (文字, 字号) 记忆
            val textWidth = remember { TextWidthCache(textPaint) }
            // plan 的入参对象也提到组合期：Config 是纯数据、measure 是稳定函数引用，
            // 每帧新建等于把 GC 压力挂在 60fps 上（E122）
            val planConfig = remember(areaFraction, speed, fontScale, maxOnScreen) {
                DanmakuPlanner.Config(areaFraction, speed, fontScale, maxOnScreen)
            }
            val measureText: (Danmaku, Float) -> Float = remember(textWidth) {
                { d, px -> textWidth.width(d.text, px) }
            }
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
                    config = planConfig,
                    laneHeightPx = laneHeight,
                    measure = measureText,
                )
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    // 整帧一致的常量属性提到循环外；alpha 例外——setColor 会把
                    // alpha 重置回色值自带的 FF，必须在各条 color 之后重设
                    val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
                    placed.forEach { p ->
                        textPaint.textSize = p.textPx
                        textPaint.color = p.danmaku.colorArgb
                        textPaint.alpha = alphaInt
                        val x = if (p.x >= 0f) p.x else (size.width - textWidth.width(p.danmaku.text, p.textPx)) / 2f
                        // 复用同一个 FontMetrics 实例（E122）：无参版每次 new 一个
                        textPaint.getFontMetrics(fontMetrics)
                        val baseline = p.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
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
            // 与控制层同显隐：控制栏（顶/底）隐去时这枚 pill 也必须收走——
            // 此前它常驻右上角，用户找不到入口也关不掉（实报）。
            // 长按菜单开着时保持可见：菜单锚在这枚 pill 上，pill 一走锚点就没了。
            AnimatedVisibility(
                visible = context.controlsVisible || menuOpen,
                enter = fadeIn(animationSpec = npMotion(Motion.Field)),
                exit = fadeOut(animationSpec = npMotion(Motion.Field)),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Box(
                    modifier = Modifier
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
                                // 审查批次 B2：先切回"文件来源"，否则内嵌轨选中时
                                // 文件加载 effect 会 continue 跳过，选了文件也不生效
                                useFileSource()
                                filePicker.launch(arrayOf("*/*"))
                            },
                        )
                        if (manualUri != null) {
                            DropdownMenuItem(
                                text = { Text("清除手动选择") },
                                onClick = {
                                    menuOpen = false
                                    useFileSource()
                                    scope.launch { settings.setDanmakuManualUri(context.media.uri, null) }
                                },
                            )
                        }
                        // 审查批次 B2：内嵌轨选中后必须有**退出内嵌轨**的入口。
                        // 没有它，用户切到内嵌轨之后就再也回不到自动/文件来源——
                        // 这是"选了就出不来"的单向门。
                        if (embeddedPicked != null) {
                            DropdownMenuItem(
                                text = { Text("退出内嵌轨（回到自动匹配）") },
                                onClick = {
                                    menuOpen = false
                                    useFileSource()
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

/**
 * 文字宽度缓存：`Paint.measureText` 每次都要走一遍排版 shaping，而弹幕窗口内的
 * 文字宽度在字号不变时恒定——逐帧重算是叠加在刷新率上的纯浪费（CJK 尤甚）。
 * 按字号分代记忆：字号一变整体失效重算，退化为"不缓存"但绝不产出错值。
 */
private class TextWidthCache(private val paint: Paint) {
    private var sizePx = Float.NaN
    private val widths = HashMap<String, Float>()

    fun width(text: String, textSizePx: Float): Float {
        if (textSizePx != sizePx) {
            widths.clear()
            sizePx = textSizePx
        }
        widths[text]?.let { return it }
        if (widths.size >= MAX_ENTRIES) widths.clear()
        paint.textSize = textSizePx
        return paint.measureText(text).also { widths[text] = it }
    }

    private companion object {
        /** 记忆上限：同屏窗口只有几十条；超限说明输入异常，丢弃重来即可 */
        const val MAX_ENTRIES = 4_096
    }
}
