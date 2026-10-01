package com.noneplayer.kernel

import com.noneplayer.kernel.api.ServiceLocator
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * 服务定位器默认实现：类型到实例的并发映射。
 *
 * 只承载内核级服务（设计文档 §4.2 约束，< 10 个）：
 * DispatcherProvider、VideoPlayerFactory、AppDatabase（后续）等。
 * get 未命中即抛错——服务缺失是装配错误，必须尽早暴露。
 */
class DefaultServiceLocator : ServiceLocator {

    private val services = ConcurrentHashMap<KClass<*>, Any>()

    override fun <T : Any> register(clazz: KClass<T>, service: T) {
        // E61 修复：put 无条件覆盖、check 在覆盖之后才做，是 check-then-act 竞态——
        // 两个线程并发注册同一 clazz 时底层 map 已被破坏。putIfAbsent 是原子的
        // "若不存在才写入"，返回值即为已存在的旧值，判重与写入一步完成。
        val old = services.putIfAbsent(clazz, service)
        check(old == null) { "内核服务重复注册: ${clazz.qualifiedName}" }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: KClass<T>): T =
        requireNotNull(services[clazz]) {
            "内核服务未注册: ${clazz.qualifiedName}。请在 Kernel.create 装配时注册。"
        } as T
}
