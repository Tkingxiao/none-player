package com.noneplayer.kernel.api

/**
 * 所有扩展点的标记接口。
 *
 * 插件实现 [ExtensionPoint] 的具体子接口（如 [MediaSourceProvider]、[ScreenProvider]）
 * 并在 [Plugin.onInstall] 中注册到 [ExtensionRegistry]，即可被内核与其他插件发现。
 *
 * 约束：扩展点只定义契约，不携带实现；Android/Compose 相关扩展点
 * 定义在 `kernel-ui-api`，保持本模块纯 Kotlin。
 */
interface ExtensionPoint
