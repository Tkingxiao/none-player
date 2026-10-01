package com.noneplayer.kernel.api

/**
 * 插件标记注解。
 *
 * 在插件类上标注 `@PluginMarker`，KSP 处理器（`:kernel:kernel-ksp`）会在编译期
 * 校验其实现了 [Plugin]，并生成对应的 `PluginIndex` 贡献类，供宿主装配时收集。
 *
 * 编译期生成、零反射，不受 R8 混淆影响（设计文档 §3.3 方案 B）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PluginMarker

/**
 * 插件——微内核架构中所有具体功能的载体。
 *
 * 插件之间禁止互相依赖，只能通过扩展点与事件总线通信。
 * 生命周期由内核按依赖拓扑排序后依次驱动。
 */
interface Plugin {
    /** 全局唯一 id，使用命名空间前缀（如 `ui.library`、`scanner.local`） */
    val id: String
    val name: String
    val version: String

    /** 依赖的其他插件 id；内核启动时校验存在性并拓扑排序 */
    val dependencies: List<String> get() = emptyList()

    /**
     * 内核在 [onInstall] 之前调用，注入内核级服务定位器。
     * 插件由此获取 Context、DispatcherProvider、EventBus 等内核服务；
     * 无依赖的插件可忽略（默认空实现）。
     */
    fun onBind(services: ServiceLocator) {}

    /**
     * 注册扩展点。此时宿主尚未就绪，禁止访问 Activity 上下文。
     * 权限申请等需要 Activity 的操作只在此登记，实际申请延迟到宿主触发。
     */
    suspend fun onInstall(registry: ExtensionRegistry)

    /** 宿主就绪后调用，可以开始观察数据、触发后台任务 */
    suspend fun onStart()

    /** 卸载清理：释放资源、取消任务，保证插件可重复启停 */
    suspend fun onStop()
}

/**
 * 插件贡献索引——KSP 生成的编译期插件清单。
 *
 * 每个标注了 [PluginMarker] 的插件类，其所在模块会生成一个
 * `<插件类名>PluginIndex` 实现本接口；宿主 `:app` 在装配时收集所有索引，
 * 交给 [KernelCreator] 引导内核。
 */
interface PluginIndex {
    fun plugins(): List<Plugin>
}
