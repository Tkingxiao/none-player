package com.noneplayer.kernel.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 进程前台/后台状态（E25）。
 *
 * 内核级服务：宿主以 Activity 计数实现，插件经 ServiceLocator 取用。
 * 动机——E25 的实害不是"没人调 shutdown()"本身，而是它导致资源型回调
 * （如扫描插件的 ContentObserver）在进程存活期内**永不注销**。
 * 与其把 `Kernel.shutdown()` 接到 Activity.onDestroy（会话/插件寿命 = 进程寿命，
 * shutdown 置 Idle 后内核不可 re-boot，裁定见《审查文档.md》§1 批次 7），
 * 不如把"进程是否在前台"变成一条可订阅的状态，让资源持有方自行跟随：
 * 进后台 → 注销观察；回前台 → 重挂并补一次刷新。
 */
interface ProcessLifecycle {

    /** true = 至少一个 Activity 处于 STARTED（进程对用户可见） */
    val isForeground: StateFlow<Boolean>
}
