package com.noneplayer.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器提供者。
 *
 * 内核级服务之一，通过 [com.noneplayer.kernel.api.ServiceLocator] 共享，
 * 让插件可以在测试中替换调度器，避免硬编码 `Dispatchers.IO`。
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

/** 生产环境默认实现，直接委托给 `Dispatchers`。 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
}
