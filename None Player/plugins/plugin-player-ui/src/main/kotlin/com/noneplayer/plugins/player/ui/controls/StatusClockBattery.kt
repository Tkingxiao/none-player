package com.noneplayer.plugins.player.ui.controls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMono
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放页顶栏的自绘时钟电量（§4.2 顶栏 56dp：`16:33 🔋41%`）。
 *
 * 全屏沉浸形态下系统栏被隐藏，时间与电量再无处可看——这正是规格把它画进
 * 播放页顶栏的原因。自绘即"应用自己渲染"，不依赖系统栏存在。
 *
 * 数据源全部本机：时间走 `System.currentTimeMillis()`（对齐到分钟刷新），
 * 电量走 `ACTION_BATTERY_CHANGED` 粘性广播（变化即到，无轮询）。
 */
@Composable
internal fun StatusClockBattery(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var batteryPercent by remember { mutableIntStateOf(readBatteryPercent(context)) }

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
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = timeFormat.format(Date(nowMs)),
            style = NpMono,
            color = tint,
        )
        Icon(
            imageVector = NpIcon.Battery,
            contentDescription = "电量",
            tint = tint,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(14.dp),
        )
        Text(
            text = "$batteryPercent%",
            style = NpMono,
            color = tint,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun readBatteryPercent(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else 100
}
