package com.noneplayer.plugins.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noneplayer.core.ui.Motion
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpRadius
import com.noneplayer.core.ui.NpSpace
import com.noneplayer.core.ui.NpMono
import com.noneplayer.core.ui.formatTimestamp
import com.noneplayer.core.ui.NpEasing
import com.noneplayer.core.ui.LocalNpReduceMotion
import com.noneplayer.core.ui.npDuration
import com.noneplayer.core.ui.npQuality
import com.noneplayer.core.ui.npMotion
import com.noneplayer.core.ui.npPressScale
import com.noneplayer.core.ui.npTouchTarget
import com.noneplayer.plugins.player.ui.gesture.SeekGestureBubble
import com.noneplayer.plugins.player.ui.gesture.StageVerticalMode
import com.noneplayer.plugins.player.ui.gesture.VerticalAdjustIndicator
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 竖滑亮度/音量 HUD（§4.3）：百分比只读在这一条指示器自身。
 *
 * 单独成组件是 §9.2 的硬要求：竖滑每帧写一次百分比，读在版式层就等于
 * "一次滑动把整块舞台（视频 surface + 顶栏 + 底栏）重组一遍"。
 */
@Composable
internal fun BoxScope.VerticalAdjustHud(
    mode: StageVerticalMode,
    brightness: State<Int>,
    volume: State<Int>,
    sidePadding: Dp,
) {
    val onLeft = mode == StageVerticalMode.Brightness
    VerticalAdjustIndicator(
        mode = mode,
        percent = if (onLeft) brightness.value else volume.value,
        modifier = Modifier
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = sidePadding),
    )
}

/** 横滑 seek 气泡（§4.3）：目标与当前位置同样读在气泡自身，不牵动舞台版式。 */
@Composable
internal fun BoxScope.SeekGestureBubbleHost(
    positionMs: State<Long>,
    targetMs: State<Float>,
    durationMs: Long,
) {
    val target = targetMs.value
    SeekGestureBubble(
        deltaMs = (target - positionMs.value).roundToLong(),
        targetMs = target.roundToLong(),
        durationMs = durationMs,
        modifier = Modifier.align(Alignment.Center),
    )
}

/**
 * 左上角续播提示（B 站同款「已为您定位到 xx:xx」）：进页时亮一次，几秒后自行淡出。
 *
 * 与旧的竖屏续播胶囊相比，这里**不跟着 positionMs 走**——提示的是"进页那一刻
 * 的定位结果"，播起来之后进度条自己会说话；常驻胶囊每 250ms 重排信息区、
 * 还会在每次播放时都挂着一条，既噪音又浪费（§9.2）。
 *
 * 倍速常驻胶囊（[SpeedPill]）同占左上角：它可见时本提示下移一行避让。
 */
@Composable
internal fun ResumePositionNotice(
    resumeMs: Long?,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    // 退场动画期间 resumeMs 已置空——留住最后一次的值，避免淡出时文字跳成 0:00
    var lastMs by remember { mutableStateOf(0L) }
    LaunchedEffect(resumeMs) { if (resumeMs != null) lastMs = resumeMs }
    AnimatedVisibility(
        visible = resumeMs != null,
        // E119：原先 enter=Field(150) / exit=Panel(180) 把 §3.9 的"退场比进场更短"写反了
        // （同族正例是 Overlay 280 / OverlayExit 220）。续看提示是"看一眼就走"的东西，
        // 进得完整、走得干脆才对。
        enter = fadeIn(animationSpec = npMotion(Motion.Panel)),
        exit = fadeOut(animationSpec = npMotion(Motion.Field)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(start = NpSpace.lg, top = if (speed == 1f) 56.dp else 88.dp)
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = "已为您定位到 ${formatTimestamp(lastMs)}",
                style = NpMono,
                color = Color.White,
            )
        }
    }
}

/**
 * 中央"继续播放"钮（设计稿 `.bigplay`）：64dp 玻璃圆 + 白三角。
 * 只在暂停时出现——播放中它不该占着画面。
 *
 * **M6 水波**（§5.3/§9.6）：点按后一圈水波从钮后扩散并淡出（`Bubble` 500ms、
 * `Emphasized` 曲线——该曲线规格里"只给共享元素与主播放键"）。仅 High 档
 * （`centerBubble`），播放中自动降档与 Reduce Motion 下都不出现。
 * 动画值只读在 `graphicsLayer` 里（§9.2：逐帧只重绘，不重组）。
 */
@Composable
internal fun CenterResumeButton(
    visible: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleOn = npQuality().centerBubble && !LocalNpReduceMotion.current
    // 时长读在组合期：协程里拿不到 @Composable 的 npDuration（同 transitionSpec 那条约束）
    val bubbleMs = npDuration(Motion.Bubble)
    val scope = rememberCoroutineScope()
    val wave = remember { Animatable(0f) }
    fun playWithWave() {
        if (bubbleOn) {
            scope.launch {
                wave.snapTo(0f)
                wave.animateTo(1f, tween(bubbleMs, easing = NpEasing.Emphasized))
            }
        }
        onPlay()
    }
    // 槽位固定 124dp（水波的最大直径），按钮 64dp 居中——水波必须画在
    // AnimatedVisibility **之外**：点按会立刻让按钮隐去（播放开始），波纹要能自己走完。
    Box(modifier = modifier.size(124.dp), contentAlignment = Alignment.Center) {
        if (bubbleOn) {
            Box(
                modifier = Modifier
                    .size(124.dp)
                    // 水波：半径 64→124dp、整圈淡出——动画值只读在绘制层（§9.2）
                    .graphicsLayer {
                        val p = wave.value
                        val scale = (64f + 56f * p) / 124f
                        scaleX = scale
                        scaleY = scale
                        alpha = (0.5f * (1f - p)).coerceAtLeast(0f)
                    }
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = npMotion(Motion.Field)) +
                scaleIn(initialScale = 0.86f, animationSpec = npMotion(Motion.Field)),
            exit = fadeOut(animationSpec = npMotion(Motion.Field)) +
                scaleOut(targetScale = 0.86f, animationSpec = npMotion(Motion.Field)),
        ) {
            // 暗色玻璃 + 白图标（与锁定徽标/控制条同一套播放器覆盖层语言）。
            // 此前用 NpGlass 浅色玻璃 + 白 icon：浅色主题或浅色画面上是白底白图，
            // 图标整个隐形（用户实报"暂停按钮看不清"）。
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                    .clickable(onClick = ::playWithWave),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * 手势反馈胶囊（"亮度 62%" / "音量 40%" / "快进 10 秒"）——屏幕中央，1.2s 自动消失。
 *
 * 没有它，滑动调节就是"盲调"：用户不知道当前值，也不知道手势是否被识别。
 * 竖滑亮度/音量已升级为竖条指示器（§4.3），本胶囊现只承载双击快进/快退的回执。
 */
@Composable
internal fun GestureHintPill(text: String?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(NpRadius.chip))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = text.orEmpty(),
                style = NpMono.copy(fontSize = 13.sp),
                color = Color.White,
            )
        }
    }
}

/**
 * 锁定态徽标（§4.2 🔒 / 批次 2「锁屏锁定键」）：锁定后舞台上**唯一**可点元素。
 *
 * **随控制层显隐**（本轮修正）：锁定瞬间亮起作反馈，随后按同一节拍自动隐去；
 * 锁定态下单指点按随时把它唤回（见 [PlayerScreen] 的 stageGestures）。
 * 旧行为是"常驻不隐"，用户实报"已锁定按钮不会隐藏，一直存在"。
 */
@Composable
internal fun StageLockBadgeHost(
    visible: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = true,
) {
    // 独立成组件的另一个原因：舞台嵌在 Column 里时，直接调 AnimatedVisibility 会解析到
    // ColumnScope 的重载而编译不过；这里没有隐式接收者，落到顶层重载。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.Silk)),
        exit = fadeOut(animationSpec = tween(npDuration(Motion.Field), easing = NpEasing.SilkExit)),
        modifier = modifier,
    ) {
        StageLockBadge(onUnlock = onUnlock, locked = locked)
    }
}

/** 44dp 玻璃圆「解锁」钮本体（外观与按压反馈） */
@Composable
private fun StageLockBadge(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = true,
) {
    Box(
        modifier = modifier.npTouchTarget(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .npPressScale(onClick = onUnlock, pressedScale = 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (locked) NpIcon.Lock else NpIcon.LockOpen,
                contentDescription = if (locked) "解锁" else "锁屏",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 倍速常驻胶囊（§4.6）：任何非 1.0x 状态在左上角常驻，**不随控制层显隐**——
 * "我现在不是正常速度"必须永远看得见。
 */
@Composable
internal fun SpeedPill(speed: Float, modifier: Modifier = Modifier) {    if (speed == 1f) return
    Box(
        modifier = modifier
            .padding(start = NpSpace.lg, top = 56.dp)
            .clip(RoundedCornerShape(NpRadius.chip))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "%.2fx".format(speed).removeSuffix("0").removeSuffix("."),
            style = NpMono,
            color = Color.White,
        )
    }
}

/** 播放页主操作行的图标族（设计稿 .pactions 的三个 `.btnp`） */
internal enum class ActionGlyph { More, Audio, Fav, Info }

/**
 * 播放页主操作按钮（设计稿 `.btnp`）：**圆形图标 + 11px 文字**，等分横排。
 *
 * 为什么不是"大实底按钮"：播放页的主操作已经由画面中央的大播放钮承担，
 * 底部这排是"轻动作"——实底会把 accent"一色一义"的语义（只给"正在播放"
 * 与主操作）稀释掉。选中态用 accent 文字色，不加底色。
 */
@Composable
internal fun PlayerActionButton(
    glyph: ActionGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(NpRadius.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = NpSpace.sm),
    ) {
        when (glyph) {
            ActionGlyph.More -> Icon(
                imageVector = NpIcon.Dots,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            ActionGlyph.Audio -> Icon(
                imageVector = NpIcon.Headphones,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            ActionGlyph.Fav -> Icon(
                imageVector = NpIcon.Heart,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            ActionGlyph.Info -> Icon(
                imageVector = NpIcon.Info,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
