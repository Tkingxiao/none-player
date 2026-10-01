package com.noneplayer.kernel.api

/**
 * 权限扩展点——插件声明所需权限，由内核统一申请。
 *
 * 时机约束（设计文档 §9 踩坑）：权限申请必须在 Activity 上下文，
 * 插件在 [Plugin.onInstall] 只通过本扩展点登记，实际申请由内核在
 * 宿主 Activity 就绪后触发。
 */
interface PermissionProvider : ExtensionPoint {
    /** 所需权限列表（Android 权限字符串或自定义语义标识） */
    val permissions: List<String>

    /** 申请理由，用于权限说明 UI */
    val rationale: String
}
