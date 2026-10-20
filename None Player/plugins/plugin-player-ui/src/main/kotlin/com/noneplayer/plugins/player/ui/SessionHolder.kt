package com.noneplayer.plugins.player.ui

import android.annotation.SuppressLint
import android.content.Context
import com.noneplayer.core.data.AppSettings
import com.noneplayer.core.database.HistoryRepository
import com.noneplayer.kernel.api.EventBus
import com.noneplayer.kernel.api.ExtensionRegistry
import com.noneplayer.kernel.api.ServiceLocator
import com.noneplayer.kernel.api.VideoPlayerFactory
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

/**
 * 会话依赖的**显式**解析结果。
 *
 * 每个字段都写明类型（尤其 [history] 用实现类而不是契约接口）：`services.get()`
 * 的 reified 类型推断会跟随目标参数类型——若此处省略类型，`PlaybackSession.history`
 * 的参数类型是契约接口 `PlaybackHistoryStore`，推断就会按**接口键**查
 * ServiceLocator；而内核只注册 `HistoryRepository` 实现 → 引导期直接抛
 * 「内核服务未注册」。2026-10-20 真机（Release/R8）冒烟发现过该装配回归，
 * 回归用例见 `SessionFactoryWiringTest`，依赖增删要同步更新那里的键清单。
 */
internal class SessionDependencies(
    private val context: Context,
    private val factory: VideoPlayerFactory,
    private val registry: ExtensionRegistry,
    private val settings: AppSettings,
    private val events: EventBus,
    private val history: HistoryRepository,
) {
    /** 每次调用新建一份会话（进程级单例由 [SessionHolder] 保证，这里只负责装配）。 */
    fun newSession(): PlaybackSession = PlaybackSession(
        context = context,
        factory = factory,
        registry = registry,
        settings = settings,
        events = events,
        history = history,
    )
}

/**
 * ServiceLocator → 会话工厂的适配（把依赖一次性取齐且类型显式）。
 *
 * 依赖在**工厂创建时**解析而不是推迟到 [KernelServicesView.newSession]：这样装配
 * 问题与真实调用点同时机暴露（`PlayerUiPlugin.onBind` 建会话前就会取齐），也让
 * 回归测试只断言取用键、不必真的构造一个持有协程的 [PlaybackSession]。
 */
internal fun sessionFactoryOf(services: ServiceLocator): KernelServicesView {
    val dependencies = SessionDependencies(
        context = services.get(),
        factory = services.get(),
        registry = services.get(),
        settings = services.get(),
        events = services.get(),
        history = services.get(),
    )
    return object : KernelServicesView {
        override fun newSession(): PlaybackSession = dependencies.newSession()
    }
}
