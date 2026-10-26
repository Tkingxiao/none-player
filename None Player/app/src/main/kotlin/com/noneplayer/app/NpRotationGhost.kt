package com.noneplayer.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 转屏「上一形态快照」幽灵层（E247）。
 *
 * **为什么需要**：换方向的系统动画放完、应用第一帧新布局画出来之前，Compose 还没组合完新形态
 * ——真机实测横→竖 395ms 就量到新窗口，播放页却 552ms 才进组合，于是这 150ms 级空窗里屏幕上是
 * **竖屏外壳（首页）**，用户读成「先切到首页再跳回播放页」。快照盖住这段，画面从旧形态直接淡到新形态。
 *
 * **为什么是快照而不是黑幕**：E133 曾用黑底幽灵层被用户判为「黑屏闪一下」而删除。这里必须是
 * 转屏前那一帧的真实像素（PixelCopy 连视频画面一起抓），否则等于换一种闪法。
 *
 * 只在配置变化那一刻抓一帧，淡出后自摘；不吃触摸、不进无障碍树。
 */
internal object NpRotationGhost {
    private var overlay: ImageView? = null

    /**
     * 必须在 super.onConfigurationChanged **之前**调用：那时窗口还是旧尺寸，抓到的才是旧形态。
     *
     * @param turnDegrees 这次转过的角度（NpDevicePose.turnDegrees）：快照按它摆正，落点才和
     *   系统转屏动画一致。
     */
    fun captureAndShow(activity: Activity, turnDegrees: Int) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return
        }
        @Suppress("DEPRECATION")
        val listener = PixelCopy.OnPixelCopyFinishedListener { result: Int ->
            if (result == PixelCopy.SUCCESS) show(activity, bitmap, turnDegrees) else bitmap.recycle()
        }
        try {
            PixelCopy.request(activity.window, bitmap, listener, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            bitmap.recycle()
        }
    }

    private fun show(activity: Activity, bitmap: Bitmap, turnDegrees: Int) {
        dismiss()
        val decor = activity.window?.decorView as? ViewGroup ?: return bitmap.recycle()
        val view = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            // `turnDegrees` 是**画面要转过去的角**（morph 折矩形用），而快照要摆成
            // 「系统转屏动画演完那一刻」的样子：显示旋转 δ 意味着新内容按 δ 转正，旧画面
            // 就必须反向转 −δ 才接得上。两者符号相反——第一版照 turnDegrees 直接转，
            // 真机上是「先把当前界面倒转 180° 再切换」（用户实报），差的就是这个符号。
            rotation = (-turnDegrees).toFloat()
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        // 尺寸取快照自己的像素尺寸（旧窗口），居中后按 turnDegrees 旋转正好铺满新窗口
        // ——设备只是转了方向、物理像素没变，旋转后铺满是精确的，不需要额外缩放。
        val lp = FrameLayout.LayoutParams(bitmap.width, bitmap.height, Gravity.CENTER)
        decor.addView(view, lp)
        overlay = view
        view.animate()
            .alpha(0f)
            .setStartDelay(FADE_DELAY_MS)
            .setDuration(FADE_MS)
            .withEndAction { dismiss() }
            .start()
    }

    fun dismiss() {
        val view = overlay ?: return
        overlay = null
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** 淡出前的停留：盖住「新窗口已画出、播放页还没组合」那段（实测 250ms 级）。 */
    private const val FADE_DELAY_MS = 240L
    private const val FADE_MS = 300L
}
