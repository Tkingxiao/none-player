package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noneplayer.kernel.api.Navigator
import com.noneplayer.kernel.ui.api.BottomChromeProvider
import com.noneplayer.kernel.ui.api.LocalKernel

/**
 * 迷你播放条（§3.5 / G1）——常驻底部 Chrome 槽位的"正在播放"入口。
 *
 * **解决的问题**：删除播放页后，用户退回媒体库就彻底失去了"刚才在播什么"的入口，
 * 也无法一键回到播放；连播/预载更无宿主。迷你条把"播放会话"做成跨页常驻的 UI 实体。
 *
 * **无会话时完全不可见**：`NowPlaying == null` 时渲染为空（且不占高度）——
 * 它不是"空壳占位"，只有真正有媒体在播时才让内容区让位（G1 定稿："占位 56dp，
 * 内容让位"指的是**有条时**的让位；无条时不该凭空空出一条高度）。
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

        AnimatedVisibility(
            visible = nowPlaying != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            // nowPlaying 在退出动画期间会变 null，逐帧读取需要兜底快照
            val snapshot = nowPlaying ?: return@AnimatedVisibility
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    MiniPlayerRow(
                        snapshot = snapshot,
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
                    )
                    // 细进度条：不占高度层级，只做"放到哪了"的弱提示（G1 的 56dp 内的纹理）
                    LinearProgressIndicator(
                        progress = { snapshot.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
        }
    }

    /** 转义路由参数：标题可能含 `&`/`=`/中文/空格，裸露拼进 route 会截断参数 */
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

@Composable
private fun MiniPlayerRow(
    snapshot: NowPlaying,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
    ) {
        MiniCover(snapshot)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = snapshot.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = miniStatusText(snapshot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (snapshot.isPlaying) {
                    NpIcons.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
                contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
            )
        }
        IconButton(onClick = onNext, enabled = snapshot.hasNext) {
            Icon(NpIcons.SkipNext, contentDescription = "下一集")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "停止播放")
        }
    }
}

/** 封面：远程/本地一律走 [MiniCoverImage]；无封面时用一块主题色底代替，不留空洞 */
@Composable
private fun MiniCover(snapshot: NowPlaying) {
    val uri = snapshot.coverUri
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (uri != null) {
            MiniCoverImage(
                uriString = uri,
                contentDescription = snapshot.title,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** "3 / 12 · 01:23" —— 队列位置 + 当前进度，迷你条上最有信息量的两件事 */
private fun miniStatusText(snapshot: NowPlaying): String {
    val position = com.noneplayer.core.ui.formatTimestamp(snapshot.positionMs)
    return if (snapshot.queueSize > 1) {
        "${snapshot.index + 1} / ${snapshot.queueSize} · $position"
    } else {
        position
    }
}

/**
 * 迷你条封面。
 *
 * 不用 Coil：本模块**没有** Coil 依赖，而为了一个小封面把它引进来，会让插件多背一个
 * 图片库（且 Coil 的 `VideoFrameDecoder` 需要单独配置才认视频封面 URI——库页面用的是
 * 应用级 `ImageLoaderFactory`，插件拿不到）。这里直接读字节解码：媒体库给的都是本地
 * `content://`/`file://` 缩略图，尺寸小、无网络等待，同步解码后可缓存。
 *
 * 解码失败一律保持底色占位，不抛异常（迷你条不该因为一张封面挂掉）。
 */
@Composable
private fun MiniCoverImage(
    uriString: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = uriString,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val uri = uriString.toUri()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }
    }
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
