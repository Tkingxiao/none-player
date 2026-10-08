package com.noneplayer.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * 封面取色（§4.7）——把"当前这一集封面的主色"变成播放页与音频模式的底。
 *
 * **算法照抄 ECHOAndroid 的 `extractPalette`，不引 palette 库**：
 * 采样到 128 → 再缩到 32×32 → 每像素打分 `score = s·1.4 + (1 − |v − 0.62|)` → 取最高分。
 * 两个系数各有含义：饱和度加权（×1.4）让"有颜色的封面"赢过灰蒙蒙的均值，
 * 亮度向 0.62 靠拢让"够亮但不刺眼"的那一色赢过死黑与高光。
 * 最后把选中的色压进 `V ∈ [0.28, 0.42]`、`S ≤ 0.55`——它是**垫在文字下面的 scrim**，
 * 不是拿来当装饰的强调色；不压的话白字压上去对比度不够（红线：≥4.5:1）。
 *
 * 为什么不用 `androidx.palette`：它要引一整条图像流水线依赖，而这里只需要一个色；
 * Release 体积增量预算 ≤0.5MB（§9.1），为一个色引库不划算。
 */
object NpCoverAccent {

    /**
     * 封面串 → 可打开的 [Uri]。
     *
     * `MediaItem.coverUri` 有**两种形态**：扫描器给的是缩略图缓存的绝对路径
     * （`/data/user/0/…/video_thumbs/1000000084.jpg`），深链与通知给的是带 scheme 的
     * uri 串。Coil 会把前者当文件路径自动补全，`ContentResolver` 不会——直接
     * `toUri()` 得到无 scheme 的 uri，打开时抛 `FileNotFoundException: No content
     * provider`。手写解码的三处（迷你条、音频模式底、取色）都必须先过这一道。
     */
    fun coverUriOf(uriString: String): Uri =
        if (uriString.contains("://")) uriString.toUri() else Uri.fromFile(File(uriString))

    /** 采样上限（长边）：再大也不会改变 32×32 的结果，只是多解码几倍像素。 */
    private const val SAMPLE_MAX_PX = 128

    /** 打分网格：32×32 = 1024 次循环，IO 线程上一两毫秒。 */
    private const val GRID = 32

    /**
     * 小容量 LRU 缓存。
     *
     * 存在的理由：音频模式底与播放页舞台底要的是**同一个**主色，各自解码一次封面
     * 等于把最贵的一步做两遍。键是 uri 字符串，值已折算成 Compose 的 [Color]；
     * 解不出的 uri 也缓存（值为 null），否则每次重组都要重试一张坏图。
     */
    private val cache: MutableMap<String, Color?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Color?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color?>): Boolean =
                size > 12
        },
    )

    /**
     * 同步取色（命中缓存即返回，否则在调用线程解码）。
     *
     * 公开给已经在 IO 线程上的调用方；UI 侧一律走 [rememberCoverAccent]，
     * 不要拿它直接在主线程解码。
     */
    fun extract(context: Context, uriString: String): Color? {
        if (cache.containsKey(uriString)) return cache[uriString]
        val result = runCatching {
            val sampled = decodeSampled(context, uriString) ?: return@runCatching null
            try {
                scoreDominant(sampled)
            } finally {
                if (!sampled.isRecycled) sampled.recycle()
            }
        }.getOrNull()
        cache[uriString] = result
        return result
    }

    /**
     * 两段式解码：先只读尺寸算 `inSampleSize`，再按 1/2ⁿ 解一张小图。
     *
     * 第一段的返回值**不能**当成败看：`decodeStream` 给的是 Bitmap，而
     * `inJustDecodeBounds` 模式下它恒为 null——拿 `?: return null` 判空会让
     * 每次取色都静默失败。成败只看 `outWidth`。
     */
    private fun decodeSampled(context: Context, uriString: String): Bitmap? {
        val uri = coverUriOf(uriString)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > SAMPLE_MAX_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    }

    private fun scoreDominant(source: Bitmap): Color? {
        val grid = source.scale(GRID, GRID)
        val pixels = IntArray(GRID * GRID)
        grid.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
        if (grid !== source) grid.recycle()
        val hsv = FloatArray(3)
        var bestScore = -Float.MAX_VALUE
        var bestArgb = 0
        for (pixel in pixels) {
            if (AndroidColor.alpha(pixel) < 128) continue
            AndroidColor.colorToHSV(pixel, hsv)
            val score = hsv[1] * 1.4f + (1f - abs(hsv[2] - 0.62f))
            if (score > bestScore) {
                bestScore = score
                bestArgb = pixel
            }
        }
        if (bestScore == -Float.MAX_VALUE) return null
        AndroidColor.colorToHSV(bestArgb, hsv)
        return scrimFor(hsv[0], hsv[1], hsv[2])
    }

    /**
     * 选中色 → scrim 用色（§4.7）：**饱和度上限 0.55、亮度压进 [0.28,0.42]**。
     *
     * 纯函数（不碰 `android.graphics`，JVM 单测可跑）：这两条钳制本身就是"白字对比度 ≥4.5"
     * 的红线——两个上界都顶满时（`S=0.55`、`V=0.42`、色相取最亮的绿向）白字对比度约 5.3，
     * 所以这条路的 **scrim 永远不需要回退纯黑**；[whiteTextContrast] 是留给"将来有人改钳制"
     * 的复核口，`ContrastPolicyTest` 用整域扫描守着它。
     */
    fun scrimFor(hueDeg: Float, saturation: Float, value: Float): Color {
        val s = saturation.coerceAtMost(0.55f)
        val v = value.coerceIn(0.28f, 0.42f)
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val i = floor(h).toInt() % 6
        val f = h - floor(h)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val pv = v * (1f - s)
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, pv)
            1 -> Triple(q, v, pv)
            2 -> Triple(pv, v, t)
            3 -> Triple(pv, q, v)
            4 -> Triple(t, pv, v)
            else -> Triple(v, pv, q)
        }
        return Color(r, g, b)
    }

    /** §4.7 的对比度复核：scrim 上压白字必须 ≥4.5（`ContrastPolicyTest` 用整域扫描守着）。 */
    fun whiteTextContrast(scrim: Color): Double = npContrastRatio(Color.White, scrim)
}

/**
 * 取当前封面的主色（异步、缓存、切 uri 自动重算）。
 *
 * 拿不到（无封面 / 解码失败）返回 null，调用方**必须**有回退——
 * 回退到 `ink.bg`，而不是回退到一个编出来的紫色。
 */
@Composable
fun rememberCoverAccent(uriString: String?): Color? {
    val context = LocalContext.current
    val state = produceState<Color?>(initialValue = null, key1 = uriString) {
        // produceState 的协程跑在组合上下文（主线程）里，解码必须显式换到 IO——
        // 首次进音频模式正处在最忙的一帧，主线程解码一张封面会直接卡住入场动画。
        value = uriString?.let { uri -> withContext(Dispatchers.IO) { NpCoverAccent.extract(context, uri) } }
    }
    return state.value
}

/**
 * 切集时主色的过渡色（§4.7：`AccentShift=400` + `Silk`）。
 *
 * 只此**一条**颜色动画：新色进来后由它插值，底下所有派生色（渐变各站、scrim）
 * 都从这一个值现算。规格明确禁止"36 个独立颜色动画"——那是把一次插值
 * 拆成 36 次逐帧写值，既贵又会在中途出现自相矛盾的配色。
 *
 * 无封面时目标色是 `Color.Transparent`：叠加层自然淡出，不留一块死色。
 */
@Composable
fun npCoverScrim(uriString: String?): Color {
    val target = rememberCoverAccent(uriString) ?: Color.Transparent
    return animateColorAsState(
        targetValue = target,
        animationSpec = tweenOrFade(npDuration(Motion.AccentShift)),
        label = "coverScrim",
    ).value
}
