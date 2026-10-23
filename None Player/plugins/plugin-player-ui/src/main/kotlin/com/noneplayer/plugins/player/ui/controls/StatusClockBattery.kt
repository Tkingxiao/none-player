package com.noneplayer.plugins.player.ui.controls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.noneplayer.core.ui.LocalNpInk
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import kotlinx.coroutines.delay
import java.util.Date

/**
 * 播放页顶栏的自绘时钟电量（§4.2 顶栏 56dp：`16:33 🔋41%`）。
 *
 * 全屏沉浸形态下系统栏被隐藏，时间与电量再无处可看——这正是规格把它画进
 * 播放页顶栏的原因。自绘即"应用自己渲染"，不依赖系统栏存在。
 *
 * 时间用 `DateFormat.getTimeFormat`（跟随系统 12/24 制、Locale、时区），
 * 监听时间/时区变化后重建；电量走 `ACTION_BATTERY_CHANGED` 粘性广播，
 * 初值也由注册后的第一次回调给出，不在组合期读 binder。
 */
@Composable
internal fun StatusClockBattery(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ink = LocalNpInk.current
    // getTimeFormat 同时带 Locale 与 12/24 制；写死 "HH:mm" 会在 12 小时制设备上
    // 和系统栏差 12 小时，"h:mm" 又会把下午 1 点和凌晨 1 点画成一样。
    var timeFormat by remember(context) {
        mutableStateOf(android.text.format.DateFormat.getTimeFormat(context))
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // null = 粘性广播还没回来/读数不可用：显示「—」，不要兜底成 100%。
    var battery by remember { mutableStateOf(BatteryInfo(percent = null, charging = false)) }

    // 对齐到下一个整分钟刷新（30s 节拍也能接受，但对齐分钟在"55→00"这类
    // 进位上不会出现半分钟的错误时间）
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMs = now
            delay(60_000 - now % 60_000)
        }
    }

    DisposableEffect(context) {
        val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                timeFormat = android.text.format.DateFormat.getTimeFormat(context)
                nowMs = System.currentTimeMillis()
            }
        }
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                battery = batteryInfoOf(intent)
            }
        }
        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            // Locale 影响 getTimeFormat 的上午/下午写法；不重建会把旧语言一直用下去。
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            timeReceiver,
            timeFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 改「12/24 小时制」不会发 ACTION_TIME_CHANGED，只会改系统设置：
        // 必须盯 TIME_12_24，否则要离开播放页再回来才变。
        val clockObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                timeFormat = android.text.format.DateFormat.getTimeFormat(context)
                nowMs = System.currentTimeMillis()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24),
            false,
            clockObserver,
        )
        ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(timeReceiver)
            context.unregisterReceiver(batteryReceiver)
            context.contentResolver.unregisterContentObserver(clockObserver)
        }
    }

    val percent = battery.percent
    val low = isLowBattery(percent)
    val batteryTint = if (low) ink.danger else tint
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = timeFormat.format(Date(nowMs)),
            style = NpMono,
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Icon(
            imageVector = NpIcon.Battery,
            contentDescription = batteryContentDescription(battery),
            tint = batteryTint,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(14.dp),
        )
        Text(
            text = percent?.let { "$it%" } ?: "—",
            style = NpMono,
            color = batteryTint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

internal data class BatteryInfo(val percent: Int?, val charging: Boolean)

/** level/scale 任一无效 → null（界面显示「—」），不再兜底成满电。 */
internal fun batteryPercentOf(level: Int, scale: Int): Int? =
    if (level >= 0 && scale > 0) (level * 100L / scale).toInt().coerceIn(0, 100) else null

internal fun batteryInfoOf(intent: Intent): BatteryInfo {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return BatteryInfo(
        percent = batteryPercentOf(level, scale),
        charging = isChargingStatus(status),
    )
}

internal fun isChargingStatus(status: Int): Boolean =
    status == BatteryManager.BATTERY_STATUS_CHARGING

internal fun isLowBattery(percent: Int?): Boolean = percent != null && percent < LOW_BATTERY_PERCENT

/** 读屏至少要知道在充电；电量未知时也不要报出一个假百分比。 */
internal fun batteryContentDescription(info: BatteryInfo): String = when {
    info.charging -> "电量，正在充电"
    info.percent == null -> "电量未知"
    else -> "电量"
}

internal const val LOW_BATTERY_PERCENT = 15
