package com.noneplayer.kernel.ui.api

import android.graphics.Bitmap
import android.os.SystemClock
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
 * popExit），第一个读的人清掉会让后半截照旧动画。600ms 后自动失效。
 *
 * **标记要盖住"没有导航的形态变化"**（第二轮真机取证）：右栏没在播、横屏停在剧集详情页时
 * 压根没有路线可交接，但那一帧详情页照样要在竖屏收成首页——`LibraryScreen` 的 stage
 * 转场照旧交叉淡入，撞上系统转屏动画就是"两层同时半透明"。所以**类 PC 档一变就 mark**，
 * 不管这次有没有导航。
 *
 * **画面本身也要连着**（第三轮：用户实报"没有应有的动效，只有黑色背景及播放内容的全屏
 * 生硬转换"）：交接只做到"不淡不滑"还不够——画面的归属者换了地方，那一帧得**从它原来的
 * 位置移到它要去的位置**（[NpPlayerPaneMotion] 的飞行帧）。两侧各自登记抓帧口：
 * [bindPaneHandoff] 给右栏（拆栏之前抓帧）、[bindStageHandoff] 给竖屏播放页（退页之前
 * 抓帧并退页）。转屏回调在**第一帧之前**调用它们，抓到的帧与矩形随交接一起飞。
 */
object NpFormHandoff {

    private const val WINDOW_MS = 600L
    private const val PLAYER_HOLD_MS = 500L
    private const val PANE_TAKEOVER_MS = 600L

    private var silentUntil = 0L
    private var playerHoldUntil = 0L
    private var paneTakeoverUntil = 0L
    private var wasDesktop = false

    /**
     * 档位变化的轮次号。**同一轮只允许起飞一次**（第四轮真机取证）：
     * 转屏会被两条路径各报一次——Activity 的 [handoffForWindowSize] 与外壳的 [noteDesktop]
     * ——第二次进来时 `wasDesktop` 已经更新过，若还照旧判定就会落到 [handOverToPane] 上，
     * 而那一侧只认 `playerPageAlive`，转屏后播放页刚挂上就为真，于是**又起飞一次**。
     * 第二次起飞手里没有帧也没有源矩形（那一侧的画面已经交出去了），它把带帧的真实飞行
     * 顶掉之后，飞行层只剩"整屏黑"这一种画法：真机录屏逐帧核对到的正是
     * "黑屏 1.35s + 播放内容生硬切换"，用户读到的是"没有动效"。
     */
    private var formGeneration = 0
    private var paneHandoffGen = -1
    private var playerPageHandoffGen = -1
    private var paneRoute: String? = null
    private var detailRoute: String? = null
    private var navigate: ((String) -> Unit)? = null

    /** 独占播放页在不在栈上。在的话形态变化不归本对象管——那一页自己会适配。 */
    private var playerPageAlive = false

    /** 右栏侧登记的抓帧口（见 [bindPaneHandoff]）。单槽：一台播放器只有一个输出 Surface。 */
    private var paneToken: Any? = null
    private var paneCapture: (() -> NpPicture?)? = null

    /** 竖屏播放页侧登记的抓帧口 + 退页动作（见 [bindStageHandoff]）。 */
    private var stageToken: Any? = null
    private var stageCapture: (() -> NpPicture?)? = null
    private var stageRetire: (() -> Unit)? = null

    /** 交接带过来的那一帧（按媒体 uri + 落点取，见 [posterFor]）。只在主线程读写。 */
    private var posterUri: String? = null
    private var posterFrame: Bitmap? = null
    private var posterTarget: NpHandoffTarget? = null

    /** 转屏驱动的交接（播放页 ↔ 右栏）在发起导航之前打点 */
    fun mark() {
        silentUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    /** 转场读它：true 时整页不淡不滑，直接落位 */
    val isSilent: Boolean get() = SystemClock.elapsedRealtime() < silentUntil

    /**
     * 刚从右栏交接到播放页的短窗口。这段里播放页不要因为尺寸回跳再 `pop` 回首页。
     */
    fun holdsPlayer(): Boolean = SystemClock.elapsedRealtime() < playerHoldUntil

    /**
     * 进入类 PC 档的那一轮交接有没有被外壳接手（连退页一起发起了）。独占播放页的兜底
     * 退页要据此让路：接手过之后再退一次就是把媒体库首页也弹掉。
     */
    fun tookOverToPane(): Boolean = SystemClock.elapsedRealtime() < paneTakeoverUntil

    fun bindNavigator(navigate: ((String) -> Unit)?) {
        this.navigate = navigate
    }

    /** 独占播放页挂载/卸载时上报（见 [playerPageAlive]）。 */
    fun notePlayerPage(alive: Boolean) {
        playerPageAlive = alive
    }

    /**
     * 右栏侧登记"抓帧"口：转屏回调在拆栏**之前**调它，取走当前画面本体与它此刻的矩形。
     * 抓不到（没在播、被 FLAG_SECURE 拒了、Surface 还没出画）返回 null，交接就退回纯黑。
     *
     * 用令牌而不是"置空"：右栏与详情页的内联画面会在同一次组合里先后挂载，
     * 谁是后离开的谁就把别人的登记清掉了（旧实现在真机上丢过帧）。
     */
    fun bindPaneHandoff(token: Any, capture: () -> NpPicture?) {
        paneToken = token
        paneCapture = capture
    }

    fun releasePaneHandoff(token: Any) {
        if (paneToken === token) {
            paneToken = null
            paneCapture = null
        }
    }

    /**
     * 竖屏播放页侧登记抓帧口与退页动作。窗口转横（进类 PC 档）时画面归右栏，由外壳在
     * 退页**之前**调 [capture] 取走最后一帧，再调 [retire] 退页——不这样做，那一帧就是
     * 播放内容被全屏放大成横屏版式（用户实报"生硬且奇怪"）。
     */
    fun bindStageHandoff(token: Any, capture: () -> NpPicture?, retire: () -> Unit) {
        stageToken = token
        stageCapture = capture
        stageRetire = retire
    }

    fun releaseStageHandoff(token: Any) {
        if (stageToken === token) {
            stageToken = null
            stageCapture = null
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
        if (wasDesktop) paneRoute = null
    }

    fun clearDetailRouteWhileDesktop() {
        if (wasDesktop) detailRoute = null
    }

    fun stashPoster(picture: NpPicture, target: NpHandoffTarget) {
        val frame = picture.frame ?: return
        if (frame.isRecycled) return
        posterUri = picture.uri
        posterFrame = frame
        posterTarget = target
    }

    /**
     * 交接落地那一帧的静止封面（按 uri 与**落点**认领，取走即清）。落点的新 Surface
     * 出画前用它顶着，否则飞行帧落位淡出之后会露出一段黑舞台。
     *
     * 按落点认领是必需的：交接的两个方向里，**离开**的那一侧还在画最后几帧，
     * 它若顺手把封面取走，正在进场的落点这边就什么也捞不着。
     */
    fun posterFor(uri: String, target: NpHandoffTarget): Bitmap? {
        val hit = posterFrame?.takeIf {
            !it.isRecycled && posterUri == uri && posterTarget == target
        }
        if (hit != null) {
            posterFrame = null
            posterUri = null
        }
        return hit
    }

    /** 转屏回调用：在第一帧组合之前按新窗口尺寸决定要不要打开播放页。 */
    fun handoffForWindowSize(screenWidthDp: Int, screenHeightDp: Int) {
        noteForm(isDesktopLikeWindow(screenWidthDp, screenHeightDp))
    }

    /** 外壳记档类 PC 档；档位变了就走同一条交接判定。 */
    fun noteDesktop(nowDesktop: Boolean) = noteForm(nowDesktop)

    /**
     * 窗口类 PC 档变化时调用，且必须发生在这次配置下的第一帧组合之前。
     * 离开类 PC 时按「右栏在播 → 详情页开着」的顺序挑一条路线打开竖屏播放页；
     * 进入类 PC 时把独占播放页的画面与退页一并交出去。
     */
    fun noteForm(nowDesktop: Boolean) {
        // 档位没变 = 不是交接。这一条**必须**先判：同一个档位会被反复上报
        // （Activity 的转屏回调 + 外壳每次记档），不判就会在下面误触发一轮新的交接。
        if (wasDesktop == nowDesktop) return
        val leaving = wasDesktop && !nowDesktop
        wasDesktop = nowDesktop
        formGeneration++
        android.util.Log.d(
            "NpRot",
            "handoff.noteForm nowDesktop=$nowDesktop gen=$formGeneration leaving=$leaving playerPageAlive=$playerPageAlive",
        )
        if (leaving) handOverToPlayerPage() else handOverToPane()
    }

    /**
     * 起飞。**抓不到画面就不起飞、也不 [mark]**——飞行层没有帧可画，那它就只剩一层铺满
     * 整窗的黑底（真机实报的"只有黑色背景及播放内容的全屏生硬转换"正是它）。这种情况
     * 让页面路由的正常转场去演，至少是一段看得见的位移，好过一屏黑。
     */
    private fun beginFlight(picture: NpPicture?, towardsPane: Boolean): Boolean {
        val frame = picture?.frame ?: return false
        val from = picture.rect ?: return false
        mark()
        NpPlayerPaneMotion.begin(frame, from, towardsPane)
        return true
    }

    /**
     * 离开类 PC 档：画面归属者从右栏换到竖屏播放页。两种起始形态的源不一样：
     *
     * ① 独占播放页占着整屏（`playerPageAlive`）：源在本页自己的舞台上，页面留在栈上
     *    适配新形态（见 `PlayerScreen` 的形态分派），所以只起飞、不导航；
     * ② 浏览态右栏在播：源在右栏的画面里，本页还没挂载——起飞**并且**导航过去。
     */
    private fun handOverToPlayerPage() {
        if (playerPageHandoffGen == formGeneration) return
        if (playerPageAlive) {
            // ①：抓帧必须在版式重排之前（旧形态的矩形才是"从哪来"）
            val picture = stageCapture?.invoke() ?: return
            android.util.Log.d("NpRot", "handoff.case1 stage picture=$picture rect=${picture.rect}")
            playerPageHandoffGen = formGeneration
            beginFlight(picture, towardsPane = false)
            return
        }
        // ②：抓帧必须在拆栏之前——先清上一轮没被取走的封面，免得旧帧冒充这一轮画面
        val route = formHandoffRoute(
            leavingDesktop = true,
            playerPageAlive = false,
            paneRoute = paneRoute,
            detailRoute = detailRoute,
        ) ?: return
        val go = navigate ?: return
        paneRoute = null
        detailRoute = null
        posterFrame = null
        posterUri = null
        val picture = paneCapture?.invoke()
        android.util.Log.d("NpRot", "handoff.case2 pane picture=$picture rect=${picture?.rect} route=$route")
        picture?.let { stashPoster(it, NpHandoffTarget.PLAYER_PAGE) }
        playerPageHandoffGen = formGeneration
        beginFlight(picture, towardsPane = false)
        playerHoldUntil = SystemClock.elapsedRealtime() + PLAYER_HOLD_MS
        go(route)
    }

    /** 进入类 PC 档：画面归右栏，独占播放页交出最后一帧后退页。 */
    private fun handOverToPane() {
        if (!playerPageAlive) return
        // 右栏刚把画面交过来的那几百毫秒里，窗口尺寸可能回跳一帧。那一帧不能退页，
        // 否则竖屏播放页会立刻被拆掉，用户看见的是首页。
        if (holdsPlayer()) return
        // 本轮档位变化已经交接过一次了（两条路径各报一次，见 [formGeneration]）。
        // 这里只挡"同一轮里的重复"，不挡"用户真的又转了一次"。
        if (paneHandoffGen == formGeneration) return
        val retire = stageRetire ?: return
        // 抓不到画面（FLAG_SECURE、Surface 还没出画）也要照走：这一段是纯黑收尾，
        // 停在横屏的独占播放页才是真的难看。
        val picture = stageCapture?.invoke()
        android.util.Log.d("NpRot", "handoff.case3 ->pane picture=$picture rect=${picture?.rect}")
        picture?.let { stashPoster(it, NpHandoffTarget.PANE) }
        paneHandoffGen = formGeneration
        beginFlight(picture, towardsPane = true)
        paneTakeoverUntil = SystemClock.elapsedRealtime() + PANE_TAKEOVER_MS
        retire()
    }
}

/** 交接的落点：静止封面要交给谁顶着（见 [NpFormHandoff.posterFor]）。 */
enum class NpHandoffTarget { PANE, PLAYER_PAGE }

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
