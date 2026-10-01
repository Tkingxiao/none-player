package com.noneplayer.kernel

import com.noneplayer.kernel.api.EventBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.reflect.KClass

/**
 * 事件总线默认实现：共享 [MutableSharedFlow]。
 *
 * 通知语义（设计文档 §4.3）：即发即弃、不重放；缓冲区满时丢弃最旧事件，
 * 发布永不阻塞、永不抛错。慢消费者只丢通知，不丢数据——
 * 数据永远通过扩展点查询获得，事件只作为"发生了变化"的提示。
 */
class FlowEventBus : EventBus {

    private val events = MutableSharedFlow<Any>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun publish(event: Any) {
        events.tryEmit(event)
    }

    override fun <T : Any> subscribe(clazz: KClass<T>): Flow<T> = events.filterIsInstance(clazz)
}
