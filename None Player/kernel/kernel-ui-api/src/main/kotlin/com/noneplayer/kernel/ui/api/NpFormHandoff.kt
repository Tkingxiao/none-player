package com.noneplayer.kernel.ui.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "这一次换页是**转屏交接**，不是用户点出来的"。
 *
 * 背景（2026-10-14 用户实报"横竖屏切换动画会闪屏，很难看"）：窗口形态换了画面的归属者
 * （竖屏播放页 ↔ 类 PC 右栏），代码为此自动导航一次。而导航转场是给"用户推进/退回一页"
 * 设计的整页淡入淡出 + 横向位移，叠在系统转屏动画上就成了两层同时动的错位。
 *
 * 交接的目标形态本身就是新落点，没有"从哪一页来"的叙事要表达，所以这里一律不淡不滑。
 * 横屏右栏 → 竖屏还必须在**第一帧竖屏画出来之前**就把播放页推进栈：晚一帧，系统转屏
 * 截到的就是首页/剧集封面，右栏 Surface 和播放页 Surface 还会叠成一大一小两层画面。
 * 反向（竖屏 → 横屏）同理：退页要在第一帧横屏画出来之前发生，否则那一帧就是"播放内容
 * 全屏放大"——用户实报"生硬且奇怪"。
 *
 * **时限而不是消费即清**：一次交接会被转场的多个 lambda 读到（enter + exit，或 popEnter +
 * popExit），第一个读的人清掉会让后半截照旧动画。终点以飞行层还在不在为准（见 [isSilent]），
 * [WINDOW_MS] 秒表只在没有飞行可观测时兜底。
 *
 * **标记要盖住"没有导航的形态变化"**（第二轮真机取证）：右栏没在播、横屏停在剧集详情页时
 * 压根没有路线可交接，但那一帧详情页照样要在竖屏收成首页——`LibraryScreen` 的 stage
 * 转场照旧交叉淡入，撞上系统转屏动画就是"两层同时半透明"。所以**类 PC 档一变就 mark**，
 * 不管这次有没有导航。
 *
 * **画面本身也要连着**（第三轮：用户实报"没有应有的动效，只有黑色背景及播放内容的全屏
 * 生硬转换"）：交接只做到"不淡不滑"还不够——画面的归属者换了地方，那一帧得**从它原来的
 * 位置移到它要去的位置——整屏 morph（[NpMorph]）：新版式的画面、标题、按钮、选集行
 * 各自从上一形态里同名的位置飞到位。转屏回调在**第一帧之前**冻结那批矩形
 * （[noteForm] 里的 [NpMorph.begin]），随后才导航/退页。
 */
object NpFormHandoff {

    private const val WINDOW_MS = 600L
    private const val PLAYER_HOLD_MS = 500L
    private const val PANE_TAKEOVER_MS = 600L

    /** 飞行状态能给转场的静音上限：异常留住的 flight 不许把整站转场永久静音（见 [isSilent]）。 */
    private const val CEILING_MS = 2_500L

    /** [keepsDesktopChrome] 等几何的天花板：驱动者没跑起来时左栏也不能无限期留在类 PC 版。 */
    private const val CHROME_HOLD_MS = 800L

    private var silentUntil = 0L
    private var silentStartedAt = 0L

    /**
     * 这一次 `mark()` 是不是替某轮 morph 打的。绑定的 morph 一走就放开静音——
     * 否则秒表会把手工翻页的转场一起吞掉，最长 [WINDOW_MS]（E132 的误触发路径）。
     */
    private var silentMorphSeen = false
    private var playerHoldUntil = 0L
    private var paneTakeoverUntil = 0L

    /**
     * 档位翻转状态。判据与归约都在 [NpFormDecision]：两条上报路径（Activity 的
     * `onConfigurationChanged` 与外壳的 `SideEffect`）对同一份窗口配置只会翻转一次，
     * 第二次由归约拒掉——旧写法两边各算一套阈值，同一次物理旋转被判成两次档位变化。
     */
    private var flipState = NpFormFlipState(desktop = false)

    /** 上一次因为"显示真的转过"而起过 morph 的旋转角；防同一次转屏被多次上报重复起飞。 */
    private var rotationMorphAt = -1

    /**
     * 档位变化的轮次号。**同一轮只允许起飞一次**（第四轮真机取证）：
     * 转屏会被两条路径各报一次，第二次进来时手里既没有帧也没有源矩形（那一侧的画面已经
     * 交出去了），它把带帧的真实飞行顶掉之后，飞行层只剩"整屏黑"这一种画法：真机录屏
     * 逐帧核对到的正是"黑屏 1.35s + 播放内容生硬切换"，用户读到的是"没有动效"。
     *
     * 归约层已经拒掉"同一份配置的第二次翻转"（E132），这里的判重继续管
     * "同一轮里两个方向都来一次"的历史路径，两道闸各自成立。
     */
    private val formGeneration: Int get() = flipState.generation
    private var paneHandoffGen = -1
    private var playerPageHandoffGen = -1
    private var paneRoute: String? = null
    private var detailRoute: String? = null
    private var navigatorToken: Any? = null
    private var navigateSlot: ((String) -> Unit)? = null

    /** 独占播放页在不在栈上。在的话形态变化不归本对象管——那一页自己会适配。 */
    private var playerPageAlive = false

    /**
     * 独占播放页正按用户的**「全屏」意图**占着整屏（`orientationLock == Landscape`）。
     *
     * 平板竖屏点全屏 = 请求横向，而横向在类 PC 档正是"右栏 + 左栏剧集"那一版式。
     * 不做区分的话这一下会被判成"进类 PC 档"，播放页当场退页、画面缩进右栏，
     * 用户读到的就是"点全屏没反应/全屏不起来"（2026-10-15 真机实报）。
     * 用户明确点了全屏，这一档就归播放页自己占，交接不接管。
     */
    private var stageFullscreen = false

    /** 竖屏播放页登记的退页动作（见 [bindStageRetire]）。单槽：同一时刻只有一个独占页。 */
    private var stageToken: Any? = null
    private var stageRetire: (() -> Unit)? = null

    /**
     * 用例之间复位。
     *
     * 本对象是进程级单例，档位翻转历史（[flipState]）与三道秒表都会跨用例残留；
     * Robolectric 在同一个 SDK 沙箱里复用静态状态，行为级回归必须从这里回到干净的起点。
     */
    internal fun resetForTests() {
        silentUntil = 0L
        silentStartedAt = 0L
        silentMorphSeen = false
        playerHoldUntil = 0L
        paneTakeoverUntil = 0L
        flipState = NpFormFlipState(desktop = false)
        paneHandoffGen = -1
        playerPageHandoffGen = -1
        paneRoute = null
        detailRoute = null
        navigateSlot = null
        navigatorToken = null
        playerPageAlive = false
        stageFullscreen = false
        stageToken = null
        stageRetire = null
    }

    /** 转屏驱动的交接（播放页 ↔ 右栏）在发起导航之前打点。
     *
     * [boundToMorph] 为真表示这次静音是**替这一轮 morph** 打的：morph 一结束静音就放开，
     * 秒表不许再吞掉用户下一次真实翻页的转场（E132）。
     */
    fun mark(boundToMorph: Boolean = false) {
        val now = NpHandoffTime.now()
        silentStartedAt = now
        silentUntil = now + WINDOW_MS
        silentMorphSeen = boundToMorph || NpMorph.isFlying
    }

    /**
     * 转场读它：true 时整页不淡不滑，直接落位。
     *
     * **以 morph 状态为准，秒表只兜"没有版式要飞"的形态变化**：整屏 morph 是 480ms 补间
     * 加落位后的重排，秒表先到期的话，右栏宽度动画与整页淡移会被放回"部件正在飞"的那几百
     * 毫秒里，读感又回到"跳一下"。反向的误触发也一并堵掉：morph 已经结束而秒表还没到期时
     * 不再静音。判据本体是纯函数 [npHandoffSilent]，输入见 [gate]。
     */
    val isSilent: Boolean get() = NpMorph.isFlying || npHandoffSilent(gate())

    /**
     * 形态交接正在演（含"已起飞、新形态还没量出来"的那几帧）。
     *
     * 外壳靠它把**左栏留在类 PC 那一版**直到交接落位：横转竖时 `isDesktopLike` 一翻，
     * `LibraryScreen` 的 `detailOpen` 立刻为假、左栏当场收成媒体库首页，而竖屏播放页还在
     * 下一帧才组合出来——用户实报的"背景里闪过首页和剧集页"就是这一帧。留住它，播放页
     * 盖上来之后外壳再收，那一帧就看不见了。
     */
    val keepsDesktopChrome: Boolean get() =
        NpMorph.generation > 0 && (
            NpMorph.isFlying ||
                (!NpMorph.geometryReady && NpHandoffTime.now() - NpMorph.startedAtMs < CHROME_HOLD_MS)
            )

    /**
     * 静音窗的输入快照。morph 状态在这里**可观测**（读的是 `NpMorph.isFlying`/`progress`
     * 快照状态，组合期读它会随起飞/落位重组），行为级单测也从这里取证。
     */
    internal fun gate(): NpHandoffGate {
        val flying = NpMorph.isFlying
        val now = NpHandoffTime.now()
        // mark 之后才起飞的调用点（PlayerScreen 的「交还浏览态」先 mark 再 begin）在这里补认。
        if (NpMorph.startedAtMs >= silentStartedAt) silentMorphSeen = true
        return NpHandoffGate(
            flightActive = flying,
            flightAgeMs = if (flying) now - NpMorph.startedAtMs else Long.MAX_VALUE,
            silentRemainingMs = silentUntil - now,
            silentBoundToFlight = silentMorphSeen,
            ceilingMs = CEILING_MS,
        )
    }

    /**
     * 刚从右栏交接到播放页的短窗口。这段里播放页不要因为尺寸回跳再 `pop` 回首页。
     */
    fun holdsPlayer(): Boolean = NpHandoffTime.now() < playerHoldUntil

    /**
     * 进入类 PC 档的那一轮交接有没有被外壳接手（连退页一起发起了）。独占播放页的兜底
     * 退页要据此让路：接手过之后再退一次就是把媒体库首页也弹掉。
     */
    fun tookOverToPane(): Boolean = NpHandoffTime.now() < paneTakeoverUntil

    /**
     * 绑定导航器。**令牌而不是置空**（与 [bindPaneHandoff] 同一教训）：
     * 转屏交接要在 `onConfigurationChanged` 里用这个槽导航，而槽一旦被"后离开的挂载者"
     * 置空，交接就只剩路线没有出口——真机上表现为横转竖不打开播放页、直接停在首页。
     */
    fun bindNavigator(token: Any, navigate: (String) -> Unit) {
        navigatorToken = token
        navigateSlot = navigate
    }

    fun releaseNavigator(token: Any) {
        if (navigatorToken === token) {
            navigatorToken = null
            navigateSlot = null
        }
    }

    /** 交接用的导航出口。读它而不是直接调，便于把"没有出口"这条失败路径打进日志。 */
    private fun navigator(): ((String) -> Unit)? = navigateSlot

    /** 独占播放页挂载/卸载时上报（见 [playerPageAlive]）。 */
    fun notePlayerPage(alive: Boolean) {
        playerPageAlive = alive
    }

    /** 独占播放页上报"现在是用户点出来的全屏"（见 [stageFullscreen]）。 */
    fun noteStageFullscreen(fullscreen: Boolean) {
        stageFullscreen = fullscreen
    }

    /**
     * 竖屏播放页登记"退页"动作。窗口转横（进类 PC 档）时画面归右栏，外壳要在**第一帧
     * 横屏画出来之前**调它退页——不这样做，那一帧就是播放内容被全屏放大成横屏版式
     * （用户实报"生硬且奇怪"）。
     *
     * 用令牌而不是置空：播放页与外壳的挂载顺序在不同路径上不一样，
     * 谁是后离开的谁就把别人的登记清掉了（旧实现在真机上丢过出口）。
     */
    fun bindStageRetire(token: Any, retire: () -> Unit) {
        stageToken = token
        stageRetire = retire
    }

    fun releaseStageRetire(token: Any) {
        if (stageToken === token) {
            stageToken = null
            stageRetire = null
        }
    }

    /** 右栏正在播的那一条：离开类 PC 时优先交给它。 */
    fun offerPaneRoute(route: String?) {
        paneRoute = route?.takeIf { it.isNotBlank() }
    }

    /**
     * 横屏详情页开着的那一条：右栏没在播时的落点。
     *
     * 竖屏没有详情页这一档（§13.2 的形态矩阵里，竖屏点卡片是直接进播放页），所以
     * 详情页在竖屏的对应物就是播放页——不接管的话转屏会把人丢回首页。
     */
    fun offerDetailRoute(route: String?) {
        detailRoute = route?.takeIf { it.isNotBlank() }
    }

    /**
     * 各来源**只清自己那一条**，而且要趁还在类 PC 档时清。
     *
     * 为什么不能一把全清：右栏收起（✕ 停播、点全屏）比媒体库那侧的登记**晚**——
     * 右栏要先把宽度动画收完才离开组合，那一刻详情页还开着、路线刚被登记上。
     * 真机上"详情页停播再转竖屏掉回首页"就是这么来的：清路线的人顺手把别人的也清了，
     * 而媒体库在那之后没有新的失效点，再也没人把详情页的路线登记回来。
     */
    fun clearPaneRouteWhileDesktop() {
        if (flipState.desktop) paneRoute = null
    }

    fun clearDetailRouteWhileDesktop() {
        if (flipState.desktop) detailRoute = null
    }

    /** 转屏回调用：在第一帧组合之前按新窗口尺寸决定要不要打开播放页。 */
    fun handoffForWindowSize(screenWidthDp: Int, screenHeightDp: Int) {
        noteForm(NpFormConfig(screenWidthDp, screenHeightDp, NpDevicePose.displayRotation))
    }

    /**
     * 外壳记档类 PC 档；带上**这一份窗口配置**而不是只带布尔。
     *
     * 外壳的 `SideEffect` 与 Activity 的转屏回调会对同一次旋转各报一次，归约只认第一次，
     * 第二次直接拒——旧写法两边各自判档（一套 WindowSizeClass、一套硬编码 dp），
     * 判据不等价时同一次旋转被判成两次档位变化（E132）。
     */
    fun noteDesktop(config: NpFormConfig) = noteForm(config)

    /**
     * 窗口类 PC 档变化时调用，且必须发生在这次配置下的第一帧组合之前。
     * 离开类 PC 时按「右栏在播 → 详情页开着」的顺序挑一条路线打开竖屏播放页；
     * 进入类 PC 时把独占播放页的画面与退页一并交出去。
     */
    fun noteForm(config: NpFormConfig) {
        val (next, changed) = npReduceFormFlip(flipState, config)
        flipState = next
        // 档位没变 = 不是交接，同一份配置的第二次上报也在这里被拒。
        if (!changed) {
            // E247：手机两向都落在紧凑档，档位不翻 → 从前永远不起飞，转屏读起来就是硬闪
            //（用户实报"手机横竖屏切换是直接闪现，没有切换动画"）。播放页在场时，只要显示
            // 真的转了角度就补一轮同形 morph：画面与按钮从旧窗口的矩形飞进新窗口，版式不变。
            beginRotationMorph(next.desktop)
            return
        }
        rotationMorphAt = NpDevicePose.displayRotation
        val leaving = !next.desktop
        // **换版式之前**冻结这一批部件矩形：morph 的起点就是"上一形态里它们所在的位置"，
        // 晚一步登记上来的已经是新形态的矩形，起点就没了（E133）。
        // 带上这一下显示转过的角度：起点要按刚体旋转折进新窗口，而不是按「窗口归一化位置」
        // 搬——后者会把竖屏顶部满宽的画面搬到横屏窗口正中央（用户实报的"先全屏再跳右栏"）。
        NpMorph.begin(desktop = next.desktop, turn = NpDevicePose.turnDegrees)
        if (leaving) handOverToPlayerPage() else handOverToPane()
    }

    /**
     * 手机档的转屏补间：档位没翻，但显示真的转了 90°/180° 时也起一轮 morph（E247）。
     *
     * 只给播放页补：库页没有跨形态要搬位置的部件，补了只是让整块列表抖一下。
     */
    private fun beginRotationMorph(desktop: Boolean) {
        val rotation = NpDevicePose.displayRotation
        if (rotation == rotationMorphAt) return
        rotationMorphAt = rotation
        if (!playerPageAlive) return
        if (NpDevicePose.turnDegrees == 0) return
        if (NpMorph.isFlying) return
        NpMorph.begin(desktop = desktop, turn = NpDevicePose.turnDegrees)
    }

    /**
     * 离开类 PC 档：画面归属者从右栏换到竖屏播放页。两种起始形态的处置不一样：
     *
     * ① 独占播放页占着整屏（`playerPageAlive`）：页面留在栈上自己适配新形态
     *    （见 `PlayerScreen` 的形态分派）。整屏 morph 已经在 [noteForm] 里起飞，这里无事可做；
     * ② 浏览态右栏在播：本页还没挂载——导航过去，让播放页里的画面与按钮节点
     *    从右栏那批矩形的位置飞进竖屏版式。
     */
    private fun handOverToPlayerPage() {
        if (playerPageHandoffGen == formGeneration) return
        if (playerPageAlive) {
            playerPageHandoffGen = formGeneration
            return
        }
        val go = navigator()
        val route = formHandoffRoute(
            leavingDesktop = true,
            playerPageAlive = false,
            paneRoute = paneRoute,
            detailRoute = detailRoute,
        )
        if (route == null || go == null) return
        paneRoute = null
        detailRoute = null
        playerPageHandoffGen = formGeneration
        mark(boundToMorph = true)
        playerHoldUntil = NpHandoffTime.now() + PLAYER_HOLD_MS
        go(route)
    }

    /** 进入类 PC 档：画面归右栏，独占播放页退页。morph 的起点已在 [noteForm] 里冻结。 */
    private fun handOverToPane() {
        if (!playerPageAlive) return
        // 这一下横向是用户点「全屏」要来的：整屏归播放页，不交还右栏（见 [stageFullscreen]）。
        if (stageFullscreen) return
        // 右栏刚把画面交过来的那几百毫秒里，窗口尺寸可能回跳一帧。那一帧不能退页，
        // 否则竖屏播放页会立刻被拆掉，用户看见的是首页。
        if (holdsPlayer()) return
        // 本轮档位变化已经交接过一次了（两条路径各报一次，见 [formGeneration]）。
        // 这里只挡"同一轮里的重复"，不挡"用户真的又转了一次"。
        if (paneHandoffGen == formGeneration) return
        val retire = stageRetire ?: return
        paneHandoffGen = formGeneration
        mark(boundToMorph = true)
        paneTakeoverUntil = NpHandoffTime.now() + PANE_TAKEOVER_MS
        retire()
    }
}

/**
 * 离开类 PC 那一次的落点：右栏在播优先，其次是开着的详情页。
 *
 * 两条否决：`leavingDesktop` 之外没有"交接"这回事；独占播放页已经在栈上时再推一个
 * 就是叠两层播放器（形态变化由那一页自己适配）。
 */
internal fun formHandoffRoute(
    leavingDesktop: Boolean,
    playerPageAlive: Boolean,
    paneRoute: String?,
    detailRoute: String?,
): String? {
    if (!leavingDesktop || playerPageAlive) return null
    return paneRoute?.takeIf { it.isNotBlank() } ?: detailRoute?.takeIf { it.isNotBlank() }
}
