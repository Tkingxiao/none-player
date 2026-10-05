package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.core.media.MediaKind
import com.noneplayer.core.media.kind
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpCoverAccent
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpHaptic
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpIconButton
import com.noneplayer.core.ui.NpMediaColor
import com.noneplayer.core.ui.NpMiniProgress
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.npClickable
import com.noneplayer.core.ui.npGlassSurface
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.rememberNpHaptics
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.BottomChromeProvider
import com.noneplayer.kernel.ui.api.LocalKernel

/**
 * 迷你播放条（设计稿 `.mini`）——常驻底部 Chrome 槽位的"正在播放"入口。
 *
 * **解决的问题**：删除播放页后，用户退回媒体库就彻底失去了"刚才在播什么"的入口，
 * 也无法一键回到播放；连播/预载更无宿主。迷你条把"播放会话"做成跨页常驻的 UI 实体。
 *
 * **无会话时完全不可见**：`NowPlaying == null` 时渲染为空（且不占高度）——
 * 它不是"空壳占位"，只有真正有媒体在播时才让内容区让位（G1 定稿："占位 56dp，
 * 内容让位"指的是**有条时**的让位；无条时不该凭空空出一条高度）。
 *
 * 设计稿的三个保真点：
 * 1. **左缘 2dp accent 进度线**（`.mini .lead-line`）——"进度线是骨架"的品牌纪律，
 *    它让这一条在任何页面上都被认成"播放中的那一条"；
 * 2. **实底材质**（`--glass` = `--bg` 不透明 + 顶边 1px `--edge` 高光）——条底不做半透明，
 *    滚动内容从它下面经过而不是透出来，省掉逐帧背后取样；
 * 3. **底部 2dp 细进度**（`.mini-prog`）——不可交互，只回答"放到哪了"。
 *
 * **点条回播放页**：走 `player?...` 深链参数等价形态（uri/title/mime/id 齐全），
 * 因此回来时能与会话里的同一媒体对上号（PlayerScreen 的 B1 续播分支）。
 */
class MiniPlayerChrome : BottomChromeProvider {

    /** 排在底栏最上沿：比任何未来插件贡献的 Chrome 更贴近系统导航栏 */
    override val order: Int = 0

    @Composable
    override fun Render(navigator: Navigator) {
        val kernel = LocalKernel.current
        val session = SessionHolder.of(sessionFactoryOf(kernel.services))
        val nowPlaying by session.nowPlaying.collectAsStateWithLifecycle()
        val audioOnly by session.audioOnly.collectAsStateWithLifecycle()

        val miniVisible = nowPlaying != null
        val haptics = rememberNpHaptics()
        // M3（§7.5 `LightTap`）：出现与收起各回执一次，与 `Panel` 动画同帧起步。
        // 冷启动就带着会话时条是"一直在那"，不是刚出现——初值直接取当前状态把它滤掉。
        var lastVisible by remember { mutableStateOf(miniVisible) }
        LaunchedEffect(miniVisible) {
            if (miniVisible != lastVisible) {
                lastVisible = miniVisible
                haptics.performHaptic(NpHaptic.LightTap)
            }
        }

        AnimatedVisibility(
            visible = nowPlaying != null,
            // M3：迷你条的显隐带"内容让位"（expand/shrink），两者共用 `Panel`(180) 的 spring。
            // 默认的 fadeIn()/expandVertically() 是 150ms linear-ish，和底栏其余部分的
            // 手感对不上；这里显式落到令牌上，档位与 Reduce Motion 才能一起生效。
            enter = fadeIn(animationSpec = npMotion(Motion.Panel)) +
                expandVertically(animationSpec = npMotion(Motion.Panel)),
            exit = fadeOut(animationSpec = npMotion(Motion.Panel)) +
                shrinkVertically(animationSpec = npMotion(Motion.Panel)),
        ) {
            // nowPlaying 在退出动画期间会变 null，逐帧读取需要兜底快照
            val snapshot = nowPlaying ?: return@AnimatedVisibility
            val ink = LocalNpInk.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // L2 条配方（§4）：不透明底 + 顶高光 + 顶发丝线——
                    // 与 Tab 栏、顶栏同一个 modifier，三处不再各写一份线。
                    .npGlassSurface(ink, topHighlight = true, topHairline = true),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 左缘 2dp accent 进度线：品牌色只在"正在播放"的这一条上常驻
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(NpDimens.railLeadLine)
                            .height(NpDimens.miniHeight)
                            .background(ink.accent),
                    )
                    MiniPlayerRow(
                        snapshot = snapshot,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            // 设计稿 `.mini-in{max-width:1100px;margin-inline:auto}`
                            .widthIn(max = 1100.dp),
                        onOpen = {
                            navigator.navigate(
                                "player?uri=${encode(snapshot.item.uri)}" +
                                    "&title=${encode(snapshot.title)}" +
                                    "&mime=${encode(snapshot.item.mimeType)}" +
                                    "&id=${encode(snapshot.item.id)}",
                            )
                        },
                        onToggle = { session.toggle() },
                        onNext = { session.next() },
                        onClose = { session.stop() },
                        onAudioToggle = {
                            if (!audioOnly) {
                                // 视频在播：进音频页（先切模式再导航，音频页按模式短路渲染）
                                session.toggleAudioOnly()
                                navigator.navigate(
                                    "player?uri=${encode(snapshot.item.uri)}" +
                                        "&title=${encode(snapshot.title)}" +
                                        "&mime=${encode(snapshot.item.mimeType)}" +
                                        "&id=${encode(snapshot.item.id)}",
                                )
                            } else {
                                // 已在仅听模式：切回视频（若当前就在播放页则原地回视频版式）
                                session.toggleAudioOnly()
                            }
                        },
                    )
                }
                // 细进度条：不占高度层级，只做"放到哪了"的弱提示（G1 的 56dp 内的纹理）
                NpMiniProgress(progress = snapshot.progress)
            }
        }
    }

    /**
     * 转义路由参数：标题可能含 `&`/`=`/中文/空格，裸露拼进 route 会截断参数。
     *
     * 编码约定与媒体库 `openPlayer`、服务侧深链（`Uri.Builder.appendQueryParameter`）
     * 统一为 `Uri.encode(value, "")`（E55）：`URLEncoder` 把空格写成 `+`，
     * 而查询参数按 URI 规范解码时 `+` 就是加号，标题会变成 "Big+Buck+Bunny"。
     */
    private fun encode(value: String): String = android.net.Uri.encode(value, "")
}

/**
 * `.mini-in` 行：封面 40dp + 标题/时间 + 四个图标按钮。
 *
 * 图标按钮的排列与设计稿一致：播放/暂停 → 耳机（切换仅听音频）→ 下一集 → 关闭。
 * 设计稿把"关闭"放在最右而不是最左，是因为这一条的主要动作是"回到播放"；
 * 关闭是破坏性动作，放在需要刻意移动手指才能碰到的地方。
 *
 * 按钮尺寸取 40dp（设计稿紧凑档 `.iconbtn{width:40px}`）而不是 44dp：
 * 4×44dp 会把 360dp 宽的手机上留给标题的空间压到不可用，而迷你条的按钮
 * 本身有一个 54dp 高的整行可点区兜底，误触成本很低。
 */
@Composable
private fun MiniPlayerRow(
    snapshot: NowPlaying,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onAudioToggle: (() -> Unit)?,
) {
    val ink = LocalNpInk.current
    val isAudio = snapshot.item.kind == MediaKind.AUDIO
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(NpDimens.miniHeight)
            .npClickable(onClick = onOpen)
            .padding(start = NpSpace.md, end = NpSpace.sm),
    ) {
        MiniCover(snapshot)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = snapshot.title,
                // 设计稿 `.mini .meta b`：13px/550
                style = NpTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = miniStatusText(snapshot),
                style = NpMonoSmall,
                color = ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NpIconButton(
            icon = if (snapshot.isPlaying) NpIcon.Pause else NpIcon.Play,
            contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
            onClick = onToggle,
            size = MINI_BUTTON,
            iconSize = 21.dp,
        )
        // 耳机钮 = 仅听音频切换（设计稿 #miniAudio）；按钮下缘 9sp accent kind 角标。
        // [onAudioToggle] 为 null（音频页未接线）时整钮隐藏，避免语义重复的假入口。
        if (onAudioToggle != null) {
            Box {
                NpIconButton(
                    icon = NpIcon.Headphones,
                    contentDescription = if (isAudio) "当前为音频 · 切回视频" else "当前为视频 · 仅听音频",
                    onClick = onAudioToggle,
                    size = MINI_BUTTON,
                    iconSize = 21.dp,
                )
                Text(
                    text = if (isAudio) "音频" else "视频",
                    style = NpTypography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 9.sp,
                    ),
                    color = ink.accent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 3.dp),
                )
            }
        }
        NpIconButton(
            icon = NpIcon.SkipNext,
            contentDescription = "下一集",
            onClick = onNext,
            size = MINI_BUTTON,
            iconSize = 21.dp,
            enabled = snapshot.hasNext,
        )
        NpIconButton(
            icon = NpIcon.Close,
            contentDescription = "停止播放",
            onClick = onClose,
            size = MINI_BUTTON,
            iconSize = 21.dp,
        )
    }
}

/** 迷你条图标按钮尺寸（设计稿紧凑档 `.iconbtn`）。 */
private val MINI_BUTTON = 40.dp

/**
 * "01:23 / 04:30" —— 当前进度 / 总时长（设计稿 `#miniTime` 的口径）；
 * 队列 > 1 时保留 "i / N · " 前缀（App 增值信息）。
 */
private fun miniStatusText(snapshot: NowPlaying): String {
    val position = formatTimestamp(snapshot.positionMs)
    val duration = formatTimestamp(snapshot.durationMs)
    val time = if (snapshot.durationMs > 0) "$position / $duration" else position
    return if (snapshot.queueSize > 1) {
        "${snapshot.index + 1} / ${snapshot.queueSize} · $time"
    } else {
        time
    }
}

/**
 * 封面 40dp / 圆角 8dp / 1dp 描边（设计稿 `.mini img{border:1px solid var(--border)}`）。
 *
 * 不用 Coil：本模块**没有** Coil 依赖，而为了一个小封面把它引进来，会让插件多背一个
 * 图片库（且 Coil 的 `VideoFrameDecoder` 需要单独配置才认视频封面 URI——库页面用的是
 * 应用级 `ImageLoaderFactory`，插件拿不到）。这里直接读字节解码：媒体库给的都是本地
 * `content://`/`file://` 缩略图，尺寸小、无网络等待，同步解码后可缓存。
 *
 * 解码失败一律退回色相占位底，不抛异常（迷你条不该因为一张封面挂掉）。
 */
@Composable
private fun MiniCover(snapshot: NowPlaying) {
    val uri = snapshot.coverUri
    val isAudio = snapshot.item.kind == MediaKind.AUDIO
    val ink = LocalNpInk.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(NpRadius.cover))
            .background(
                NpMediaColor.coverBrush(
                    NpMediaColor.itemHue(snapshot.item.folder, isAudio = isAudio),
                ),
            )
            .border(1.dp, ink.border, RoundedCornerShape(NpRadius.cover)),
    ) {
        if (uri == null && isAudio) {
            androidx.compose.material3.Icon(
                imageVector = NpIcon.Sound,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
        }
        if (uri != null) {
            MiniCoverImage(
                uriString = uri,
                contentDescription = snapshot.title,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
                // 40dp @ 3x ≈ 120px：解到 144 已经比显示尺寸更清晰
                maxLongestPx = 144,
            )
        }
    }
}

/**
 * 封面位图：**按目标显示尺寸采样解码**，不整张解出来。
 *
 * 原先唯一的实现直接把原图 `decodeStream` 给一个 40dp 缩略图用——一张 1080p 海报
 * 就是 ~8MB 堆内存 + 一次全尺寸解码，而迷你条是每次切集都会走的路径。
 * 两段式（先 `inJustDecodeBounds` 只读尺寸，再按 1/2ⁿ 解）把成本压到与显示尺寸同阶。
 *
 * 键含 [maxLongestPx]：同一个 uri 在迷你条（40dp）与音频模式底（全屏）要的分辨率
 * 本来就不同，共用一张大图等于两头都付最贵的那份代价。
 */
@Composable
internal fun rememberCoverBitmap(uriString: String?, maxLongestPx: Int): android.graphics.Bitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = uriString,
        key2 = maxLongestPx,
    ) {
        value = uriString?.let { target ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { decodeSampledCover(context, target, maxLongestPx) }.getOrNull()
            }
        }
    }
    return bitmap
}

private fun decodeSampledCover(
    context: android.content.Context,
    uriString: String,
    maxLongestPx: Int,
): android.graphics.Bitmap? {
    // 扫描器给的封面是绝对路径，必须先补成 file:// 才能过 ContentResolver
    val uri = NpCoverAccent.coverUriOf(uriString)
    val resolver = context.contentResolver
    // 第一段只读尺寸：`decodeStream` 返回的是 Bitmap，`inJustDecodeBounds` 下它**恒为 null**，
    // 所以这里不能拿返回值判成败（写了 `?: return null` 会让每张封面都解不出来），
    // 成败看 `outWidth`。
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream ->
        android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > maxLongestPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { stream ->
        android.graphics.BitmapFactory.decodeStream(stream, null, opts)
    }
}

@Composable
internal fun MiniCoverImage(
    uriString: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxLongestPx: Int = 256,
) {
    val bitmap = rememberCoverBitmap(uriString, maxLongestPx)
    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
