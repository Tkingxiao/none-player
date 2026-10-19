package com.noneplayer.plugins.player.ui.brightness

import android.app.Activity
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 把 Activity 窗口亮度与系统亮度接到纯策略 [BrightnessPolicy] 上。
 *
 * 只做平台接线：读 = `Window.attributes.screenBrightness`（无覆盖为 -1f）/
 * `Settings.System.SCREEN_BRIGHTNESS`（0..255）；写 = 窗口属性覆盖。
 */
internal class WindowBrightnessController(private val activity: Activity?) {
    private val policy = BrightnessPolicy(
        target = object : WindowBrightnessTarget {
            override fun readWindowBrightness(): Float =
                activity?.window?.attributes?.screenBrightness ?: BRIGHTNESS_NONE

            override fun writeWindowBrightness(value: Float) {
                val window = activity?.window ?: return
                window.attributes = window.attributes.apply { screenBrightness = value }
            }
        },
        system = SystemBrightnessSource {
            activity?.let { ctx ->
                runCatching {
                    Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull()
            }
        },
    )

    fun capture(): Float = policy.capture()

    fun restore(original: Float) = policy.restore(original)

    fun currentPercent(): Int = policy.currentPercent()

    fun setPercent(percent: Int): Int = policy.setPercent(percent)
}

/**
 * 播放页/内嵌播放器共用的窗口亮度状态（A8）。
 *
 * 生命周期约定：
 * - **进页**捕获窗口原值（含 [BRIGHTNESS_NONE]），**离页**精确写回；
 * - 配置变化（`isChangingConfigurations`）不写回，避免旋转/分屏/开关截屏时把
 *   用户滑过的亮度"撤销"；
 * - PiP 不触发写回：`DisposableEffect` 的 key 是 controller（Activity 实例），
 *   `inPipMode` 变化不会走 `onDispose`。
 *
 * 与音量竖滑对齐：起手读一次平台真实值（[WindowBrightnessHandle.refreshFromPlatform]），
 * 不再用进页时的快照从假值起算。
 */
@Composable
internal fun rememberWindowBrightness(activity: Activity?): WindowBrightnessHandle {
    val controller = remember(activity) { WindowBrightnessController(activity) }
    // rememberSaveable：配置变化重建后，本次滑过的值与原值都还在（不回到平台默认）。
    val percent = rememberSaveable { mutableIntStateOf(controller.currentPercent()) }
    val touched = rememberSaveable { mutableStateOf(false) }
    val original = rememberSaveable { mutableFloatStateOf(Float.NaN) }
    DisposableEffect(controller) {
        if (original.value.isNaN()) original.value = controller.capture()
        // 重建出的新窗口默认没有覆盖：把本次已滑过的值重新应用，观感不跳变。
        if (touched.value) controller.setPercent(percent.value)
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                controller.restore(original.value)
            }
        }
    }
    return remember(controller) { WindowBrightnessHandle(controller, percent, touched) }
}

/** [rememberWindowBrightness] 暴露给 UI 的句柄：读数 + 写百分比。 */
internal class WindowBrightnessHandle(
    private val controller: WindowBrightnessController,
    val percent: MutableState<Int>,
    private val touched: MutableState<Boolean>,
) {
    /**
     * 写亮度百分比（clamp 1..100、平台现值相同不写），并把 clamp 后的值回写 UI 状态。
     *
     * 一旦写过就记 [touched]：配置变化重建后需要把该值重新应用到新窗口。
     */
    fun setPercent(value: Int) {
        touched.value = true
        percent.value = controller.setPercent(value)
    }

    /** 重读平台真实亮度（竖滑起手/回到页面时用，避免从过期快照起算）。 */
    fun refreshFromPlatform() {
        percent.value = controller.currentPercent()
    }
}
