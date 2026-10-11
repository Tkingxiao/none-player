package com.noneplayer.kernel.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * 启动期**可延后**的工作（首次 MediaStore 扫描是典型）：插件把任务安排下去，
 * 内核最多等它一段**有界启动预算**（`Kernel.STARTUP_SCAN_BUDGET_MS`，当前 1200ms）。
 *
 * 与普通 [Plugin.onStart] 的区别就在"要不要等"：
 * - 普通 `onStart()` —— 页面可用性依赖它，内核**同步等完**才置 `Running`；
 * - 本契约 —— 页面可用性**不依赖**它（媒体列表是 StateFlow，扫完自然会到 UI），
 *   所以扫得快就带着内容进首页、扫得慢就先出骨架。
 *
 * 超时**不取消**返回的任务：它不在页面可用的关键路径上，取消等于把媒体列表丢掉。
 * 任务与内核同寿，`Kernel.shutdown()` 统一取消。
 */
interface StartupTaskPlugin : Plugin {
    fun startup(scope: CoroutineScope): Deferred<Unit> = scope.async { onStart() }
}
