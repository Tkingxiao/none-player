package com.noneplayer.kernel.api

import kotlin.reflect.KClass

/**
 * 服务定位器——跨插件共享**内核级**服务的唯一入口。
 *
 * 反模式约束（设计文档 §4.2）：只允许注册内核级服务（< 10 个），
 * 如数据库、调度器、导航器、主题控制器、播放器工厂；
 * 业务能力一律走扩展点，禁止把 ServiceLocator 当全局变量滥用。
 */
interface ServiceLocator {
    fun <T : Any> register(clazz: KClass<T>, service: T)
    fun <T : Any> get(clazz: KClass<T>): T
}

/** 注册一个内核级服务（reified 便捷重载） */
inline fun <reified T : Any> ServiceLocator.register(service: T) = register(T::class, service)

/** 获取一个内核级服务；未注册时抛出异常（reified 便捷重载） */
inline fun <reified T : Any> ServiceLocator.get(): T = get(T::class)
