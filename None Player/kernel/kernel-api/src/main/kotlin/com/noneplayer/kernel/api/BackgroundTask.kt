package com.noneplayer.kernel.api

/**
 * 后台任务扩展点——声明一个可被内核调度的后台任务（扫描、清理、同步）。
 *
 * 纯 Kotlin 契约只描述"做什么"；WorkManager 的 Constraints/WorkRequest
 * 适配由内核实现层（`:kernel`）完成，避免本模块依赖 Android。
 */
interface BackgroundTask : ExtensionPoint {
    /** 任务唯一 id，同时用作 WorkManager 的 unique work name */
    val taskId: String

    /**
     * 执行任务；返回是否成功。
     * 内核实现层负责重试与约束（充电、网络）调度。
     */
    suspend fun execute(): Boolean
}
