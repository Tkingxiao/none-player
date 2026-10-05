package com.noneplayer.kernel

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import com.noneplayer.kernel.api.ProcessLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ProcessLifecycle] 默认实现：以 ActivityLifecycleCallbacks 对 STARTED 的
 * Activity 计数（≥1 即前台）。
 *
 * 构造时机是 [Kernel.create]（即 Application.onCreate，主线程、任何 Activity
 * 存在之前），因此计数不会漏掉首个 Activity 的 onStart。初值仍用
 * [ActivityManager.getMyMemoryState] 播种一道保险：即使宿主改变了装配时机
 * （如内核在首个 Activity 已 STARTED 后才创建），状态也不会卡死在"后台"。
 *
 * 全量覆写回调而不是依赖接口 default 方法：default 是 API 29 才加的，
 * minSdk 26 上未覆写的方法会在 API 26–28 设备抛 AbstractMethodError。
 */
internal class DefaultProcessLifecycle(application: Application?) : ProcessLifecycle {

    private val _isForeground = MutableStateFlow(seedForeground())
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var startedActivities = 0

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) _isForeground.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            // coerceAtLeast(0)：播种为前台但注册晚于 onStart 的场景里，
            // 首个 onActivityStopped 不能把计数打成 -1（否则下次启动置不回前台）
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) _isForeground.value = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        application?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun seedForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
