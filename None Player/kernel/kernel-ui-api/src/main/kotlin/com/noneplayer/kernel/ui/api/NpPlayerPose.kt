package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min

/**
 * 转屏交接时画面的**刚体姿态**。
 *
 * 旧飞行把源矩形按窗口归一化位置搬进新窗口：横屏右栏的画面转竖屏后仍贴在右侧，
 * 等落点登记上来再跳到顶部预留位——这就是「先浮动一点，然后瞬间跳到正上方」。
 * 这里改成按**坐标系实际转过的角度**搬：右栏随手势旋到顶部或底部，再由弹簧滑进落点。
 *
 * 角度约定与 [android.view.Surface] 一致，但不引用它（本文件要能在 JVM 单测里跑）：
 * 0/1/2/3 = 0°/90°/180°/270°，顺时针，相对设备自然方向。
 */

/** 折叠铰链的一次采样。[separating] 为真时开合角不是转屏，不能再加进 rotationZ。 */
data class NpHingeSample(val horizontal: Boolean, val separating: Boolean)

/**
 * 活动期间的姿态输入：显示旋转、设备倾角、铰链。只在主线程写。
 * 飞行层在绘制期读 [tiltDegrees] / [hinge]，转屏回调在组合之前写 [noteDisplayRotation]。
 */
object NpDevicePose {
    var displayRotation by mutableIntStateOf(0)
        private set

    /** [android.view.OrientationEventListener] 的 0..359；未知（平放）为 null。 */
    var tiltDegrees by mutableStateOf<Int?>(null)
        private set

    var hinge by mutableStateOf<NpHingeSample?>(null)
        private set

    internal var previousRotation: Int = 0
        private set

    private var seeded = false

    /** 第一次只播种，避免进程起来那次把「未知 → 当前」算成一次 90° 自转。 */
    fun noteDisplayRotation(rotation: Int) {
        val snapped = rotation.coerceIn(0, 3)
        if (!seeded) {
            seeded = true
            displayRotation = snapped
            previousRotation = snapped
            return
        }
        if (snapped == displayRotation) return
        previousRotation = displayRotation
        displayRotation = snapped
    }

    /**
     * 这一下显示转过多少度（+90 = 顺时针）。形态交接要把旧窗口的矩形按它折进新窗口。
     *
     * 只在显示旋转真的变过时才有非零值；同一窗口内换版式（如平板横屏的「交还浏览态」）
     * 返回 0，起点就是原样，不做任何旋转搬运。
     */
    val turnDegrees: Int get() = pictureTurnDegrees(previousRotation, displayRotation)

    fun noteTilt(orientation: Int) {
        tiltDegrees = orientation.takeIf { it in 0..359 }
    }

    fun noteHinge(sample: NpHingeSample?) {
        if (hinge != sample) hinge = sample
    }

    /**
     * 用例之间复位。seeding 只在进程第一次上报显示旋转时发生，跨用例必须能重新播种，
     * 否则上一条用例留下的 [previousRotation] 会让本轮的 turn 算出莫明其妙的 90°。
     */
    internal fun resetForTests() {
        seeded = false
        displayRotation = 0
        previousRotation = 0
        tiltDegrees = null
        hinge = null
    }
}

/**
 * 新显示旋转相对旧值顺时针转过的角度，折进 (-180, 180]。
 * 只保留正交角：传感器在阈值附近抖动时不该把画面转到 37°。
 */
internal fun pictureTurnDegrees(fromRotation: Int, toRotation: Int): Int {
    val delta = signedDegrees(
        rotationQuadrantDegrees(fromRotation).toFloat(),
        rotationQuadrantDegrees(toRotation).toFloat(),
    )
    return when {
        delta > 135f -> 180
        delta < -135f -> -180
        delta > 45f -> 90
        delta < -45f -> -90
        else -> 0
    }
}

/** 画面在新窗口里的落位：中心、未缩放尺寸、起飞时的 rotationZ。 */
internal data class PicturePlacement(
    val center: Offset,
    val width: Float,
    val height: Float,
    val rotationZ: Float,
)

/**
 * 把旧窗口里的画面搬进新窗口。
 *
 * [turnDegrees] 为 0（同一窗口内返回，或只是改了尺寸）时走 [mapPictureRect]：
 * 归一化方位 + 统一缩放，不自转。
 *
 * 转了 ±90/180 且新窗口就是旧窗口转过来的那一个时，中心按坐标系旋转搬过去，
 * 尺寸保持刚体（不在中途把 16:9 拉成方形），rotationZ 取 **-turn**：
 * 位图像素在新坐标系里先呈「转屏前的物理朝向」，弹簧再把它回正到落点。
 * 新窗口尺寸对不上（分屏、自由窗口）时退回不自转的映射，避免开局就飞出屏外。
 */
internal fun placePicture(
    src: Rect,
    srcWindow: IntSize,
    dst: Rect,
    turnDegrees: Int,
): PicturePlacement {
    if (src.width <= 0f || src.height <= 0f) {
        return PicturePlacement(Offset(dst.center.x, dst.center.y), 1f, 1f, 0f)
    }
    if (srcWindow.width <= 0 || srcWindow.height <= 0 || turnDegrees == 0 || dst.width <= 0f || dst.height <= 0f) {
        val mapped = mapPictureRect(src, srcWindow, dst)
        return PicturePlacement(Offset(mapped.center.x, mapped.center.y), mapped.width, mapped.height, 0f)
    }
    val swap = abs(turnDegrees) == 90
    val expectW = if (swap) srcWindow.height.toFloat() else srcWindow.width.toFloat()
    val expectH = if (swap) srcWindow.width.toFloat() else srcWindow.height.toFloat()
    if (abs(dst.width - expectW) > 8f || abs(dst.height - expectH) > 8f) {
        val mapped = mapPictureRect(src, srcWindow, dst)
        return PicturePlacement(Offset(mapped.center.x, mapped.center.y), mapped.width, mapped.height, 0f)
    }
    val w = srcWindow.width.toFloat()
    val h = srcWindow.height.toFloat()
    val cx = src.center.x
    val cy = src.center.y
    // 设备相对房间转过 turn 度（显示内容顺时针补偿 turn 度）后，旧窗口的点落在新窗口的哪里。
    // +90：旧 (x, y) → (y, W - x)。-90：旧 (x, y) → (H - y, x)。180：对心。
    val (lx, ly) = when (turnDegrees) {
        90 -> cy to (w - cx)
        -90 -> (h - cy) to cx
        180, -180 -> (w - cx) to (h - cy)
        else -> cx to cy
    }
    return PicturePlacement(
        center = Offset(dst.left + lx, dst.top + ly),
        width = src.width,
        height = src.height,
        rotationZ = -turnDegrees.toFloat(),
    )
}

/** 从落位中心飞到落点矩形：translation 是中心差，scale 相对落位尺寸。 */
internal data class PictureTravel(
    val translationX: Float,
    val translationY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

internal fun pictureTravel(placement: PicturePlacement, target: Rect): PictureTravel {
    val sx = if (placement.width > 1f) target.width / placement.width else 1f
    val sy = if (placement.height > 1f) target.height / placement.height else 1f
    return PictureTravel(
        translationX = target.center.x - placement.center.x,
        translationY = target.center.y - placement.center.y,
        scaleX = sx.finiteOr(1f),
        scaleY = sy.finiteOr(1f),
    )
}

/**
 * 绘制期的 rotationZ。
 *
 * 弹簧值走完「转屏那一下」的大角度；残差是设备此刻离新直立方向还差的角度，
 * 让最后几十度跟手。铰链处于分离态时倾角读到的是开合而不是转腕，残差不加。
 */
internal fun pictureRotationZ(
    springDegrees: Float,
    tilt: Int?,
    displayRotation: Int,
    hinge: NpHingeSample?,
): Float {
    if (hinge?.separating == true) return springDegrees
    return springDegrees + tiltResidualDegrees(tilt, displayRotation)
}

/** 相对新显示方向，设备还差多少度。超过 50° 视为另一个象限的噪声，不加。 */
internal fun tiltResidualDegrees(tilt: Int?, displayRotation: Int): Float {
    if (tilt == null || tilt !in 0..359) return 0f
    val delta = signedDegrees(settledDeviceTilt(displayRotation).toFloat(), tilt.toFloat())
    if (abs(delta) > 50f) return 0f
    return delta
}

/**
 * [android.view.OrientationEventListener] 在该显示旋转下的直立读数。
 * 0→0°，90（常量 1）→270°，180→180°，270（常量 3）→90°。
 */
internal fun settledDeviceTilt(displayRotation: Int): Int = when (displayRotation) {
    1 -> 270
    2 -> 180
    3 -> 90
    else -> 0
}

private fun rotationQuadrantDegrees(rotation: Int): Int = when (rotation) {
    1 -> 90
    2 -> 180
    3 -> 270
    else -> 0
}

internal fun signedDegrees(from: Float, to: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta <= -180f) delta += 360f
    return delta
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite() && this > 0f) this else fallback

/**
 * 跨窗口搬一个矩形：中心按**窗口归一化位置**搬过来，尺寸按窗口比例统一缩放。
 *
 * 转屏前后两个窗口尺寸互换，"归一化位置"就是这块画面在屏幕上的方位（贴右的转过来还贴右，
 * 居中的转过来还居中），用户读到的连续感来自这里；"统一缩放"保证画面不被拉伸——两个窗口
 * 长短边比例不同，逐轴缩放会把 16:9 的中间帧挤成方形。
 *
 * 搬过来后还要夹进本次窗口：源矩形贴边时允许被窗口裁掉的部分收敛掉，免得开局就缺一角。
 * 源窗口尺寸为零（同一窗口内，比如只是改了版式没转屏）时原样返回，不做映射。
 */
internal fun mapPictureRect(src: Rect, srcWindow: IntSize, dst: Rect): Rect {
    if (srcWindow.width <= 0 || srcWindow.height <= 0) return src
    if (src.width <= 0f || src.height <= 0f || dst.width <= 0f || dst.height <= 0f) return src
    val scale = min(dst.width / srcWindow.width, dst.height / srcWindow.height)
    val w = src.width * scale
    val h = src.height * scale
    val cx = dst.left + (src.center.x / srcWindow.width) * dst.width
    val cy = dst.top + (src.center.y / srcWindow.height) * dst.height
    val left = (cx - w / 2f).coerceIn(dst.left, (dst.right - w).coerceAtLeast(dst.left))
    val top = (cy - h / 2f).coerceIn(dst.top, (dst.bottom - h).coerceAtLeast(dst.top))
    return Rect(left, top, left + w, top + h)
}
