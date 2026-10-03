package com.noneplayer.plugins.player.ui

import android.annotation.SuppressLint
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.get

/**
 * [PlaybackSession] 的进程级持有者。
 *
 * **为什么要有这一层**：迷你条与播放页是两个互不相识的组合（迷你条在 Tab Scaffold 的
 * 底栏槽位里常驻，播放页在 NavHost 的二级路由里），但它们必须**操作同一个播放会话**——
 * 否则"退页后迷你条接着播"根本无从谈起。
 *
 * 插件间禁止互相依赖（架构铁律），因此共享点只能落在插件内部：
 * 会话由本插件在 [ServiceLocator] 上登记，读的一方通过 [of] 取用；
 * 播放页与迷你条都只依赖这一处，谁先创建都拿到同一个实例。
 *
 * 生命周期与会话一致（进程级）：只创建一次，插件停用时**不**销毁——
 * 播放是后台服务的事，停用 UI 插件不该掐断正在播的音频（L0 的另一半）。
 */
internal object SessionHolder {

    // 进程级单例是设计使然（见类注释）：会话寿命 = 进程寿命，随播放服务驻留，
    // 非意外持有的 Activity Context——抑制误报。
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var instance: PlaybackSession? = null

    /** 取用会话；未创建时按需构建（幂等，双检锁避免并发建两份） */
    fun of(kernel: KernelServicesView): PlaybackSession {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: kernel.newSession().also { instance = it }
        }
    }
}

/**
 * 构建会话所需的四件内核实力的只读视图。
 *
 * 抽出来是为了让 [SessionHolder] 不去碰 Compose 的 `LocalKernel`
 * （那是 UI 层的东西，对象本身不该依赖组合局部变量）。
 */
internal interface KernelServicesView {
    fun newSession(): PlaybackSession
}

/** ServiceLocator → 会话工厂的适配（把四个依赖一次性取齐） */
internal fun sessionFactoryOf(services: ServiceLocator): KernelServicesView =
    object : KernelServicesView {
        override fun newSession(): PlaybackSession = PlaybackSession(
            context = services.get(),
            factory = services.get(),
            registry = services.get<ExtensionRegistry>(),
            settings = services.get(),
            events = services.get(),
        )
    }
