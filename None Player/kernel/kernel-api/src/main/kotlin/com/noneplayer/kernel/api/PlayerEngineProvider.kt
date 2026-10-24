package com.noneplayer.kernel.api

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 循环模式（设计稿 fs-panel / more sheet 的四态）。
 *
 * 映射到 media3：OFF=顺序播放、ONE=单集循环、ALL=列表循环、
 * SHUFFLE=随机播放（列表循环 + shuffle 队列）。
 */
enum class NpLoopMode {
    /** 顺序播放：播完队列即停 */
    OFF,

    /** 单集循环 */
    ONE,

    /** 列表循环 */
    ALL,

    /** 随机播放 */
    SHUFFLE,
}

/**
 * 视频播放器抽象——内核对播放能力的统一契约。
 *
 * 具体实现由 PlayerEngineProvider 扩展点提供（ExoPlayer 插件、FFmpeg 插件），
 * 播放页插件只面向本接口编程。实现必须可在任意线程调用，并在 [release] 后不可复用。
 */
interface VideoPlayer {
    /** 播放状态 */
    val state: StateFlow<PlaybackState>

    /** 当前播放位置（毫秒） */
    val positionMs: StateFlow<Long>

    /** 总时长（毫秒），未知为 0 */
    val durationMs: StateFlow<Long>

    /**
     * 当前缓冲前沿（毫秒，§11.3 S5 缓冲段可视化）：进度条三段式
     * 「已播 / 已缓冲 / 未缓冲」的中段数据源。未知或引擎不支持时为 0——
     * UI 据此把缓冲段画成与未缓冲同色（零命中时不显示一段假缓冲）。
     * 默认实现恒为 0（引擎未接入缓冲信息）。
     */
    val bufferedPositionMs: StateFlow<Long>
        get() = PlayerDefaults.bufferedPositionMs

    /**
     * 视频宽高比（宽/高，已考虑旋转），用于 UI 按比例渲染画面；
     * 未知为 0。默认实现返回常量 0 流（无画面信息的能力）。
     */
    val videoAspectRatio: StateFlow<Float>
        get() = PlayerDefaults.videoAspectRatio

    /** 当前实际播放倍速（UI 应以此为准显示，而非本地记忆）。默认恒为 1 */
    val speed: StateFlow<Float>
        get() = PlayerDefaults.speed

    /**
     * 会话中当前已装载的媒体地址；未装载为 null。
     * UI 重进播放页时据此判断"会话仍在播同一视频"，避免用过期断点覆盖新进度。
     */
    val currentUri: StateFlow<String?>
        get() = PlayerDefaults.currentUri

    /**
     * 引擎「已真正装载并生效」的媒体键（`sourceId:mediaId`）。
     *
     * REV-04：播放页落盘进度时必须核对身份——慢探测/装载窗口内引擎位置仍属于上一条
     * 媒体，只按"当前队列下标"写历史会把 A 的进度写进 B 的键。null = 引擎没有装载
     * 身份概念（legacy 引擎）或当前没有已生效的媒体；调用方此时退回旧行为。
     */
    val appliedMediaKey: String? get() = null

    /**
     * [appliedMediaKey] 对应媒体的最近已知位置（毫秒）；无身份信息时为 null。
     *
     * 与 [positionMs] 的区别：后者是"控制器当前位置"，在切换装载窗口内可能仍属于上一条
     * 媒体；本值只随已生效媒体更新，切换后立即归零。
     */
    val appliedPositionMs: Long? get() = null

    /** [appliedMediaKey] 对应媒体的总时长（毫秒）；未知/无身份信息为 0 */
    val appliedDurationMs: Long get() = 0L

    /**
     * 当前应显示的字幕 Cue（外挂与内嵌轨道的统一出口）；
     * 无字幕时为空列表。默认实现恒为空（引擎未接入字幕输出）。
     */
    val cues: StateFlow<List<SubtitleCue>>
        get() = PlayerDefaults.cues

    /**
     * 当前媒体的可选轨道（内嵌音频/字幕，批次 3 轨道面板）。
     * 空列表 = 无内嵌轨道或引擎未接入轨道枚举。默认实现恒为空。
     */
    val tracks: StateFlow<List<NpTrack>>
        get() = PlayerDefaults.tracks

    /**
     * 章节列表（§12.5，1.11.0+ MP4/Matroska 章节经 metadata 通道）。
     * 空列表 = 无章节数据，UI 隐藏入口。默认实现恒为空。
     */
    val chapters: StateFlow<List<NpChapter>>
        get() = PlayerDefaults.chapters

    /**
     * 起播探测给出的"提示胶囊"文案（C21）：如"该视频仅有软件解码器，可能发热/掉帧"。
     * null = 无提示。默认实现恒为 null（引擎不做前置探测）。
     * UI 只负责展示，判定与措辞归引擎（它是唯一知道解码器实际选择的一方）。
     */
    val decodeNotice: StateFlow<String?>
        get() = PlayerDefaults.decodeNotice

    /**
     * 当前媒体的 HDR 概况（起播探测自容器声明的 colorTransfer）：
     * "HDR10" / "HLG"；SDR、未知或未探测为 null。默认恒为 null。
     * UI 据此在信息行/详情里给 HDR 标识。
     */
    val hdrLabel: StateFlow<String?>
        get() = PlayerDefaults.hdrLabel

    /**
     * 当前播放队列（同文件夹连播的来源）。默认空——不支持队列的引擎退化为单曲播放。
     *
     * 队列放在引擎契约而非 UI 侧，是因为"自动连播/通知栏 ⏮⏭"只有引擎（会话侧）
     * 才知道：media3 在 `setMediaItems` 之后由框架负责切集与通知按钮，
     * UI 侧另存一份队列反而会出现两份真相（§8.2）。
     */
    val queue: StateFlow<List<com.noneplayer.core.media.MediaItem>>
        get() = PlayerDefaults.queue

    /** 当前队列下标；无队列或未播放为 -1 */
    val currentIndex: StateFlow<Int>
        get() = PlayerDefaults.currentIndex

    /**
     * 装载整个队列并从 [startIndex] 开始播放（队列连播、通知 ⏮⏭ 由此免费获得）。
     * 不支持队列的引擎可退化为只 [prepare] [startIndex] 那一项。
     *
     * [playOnLoad] = false 表示**只装载不起播**：装载落地后停在就绪态，等用户按播放
     * （竖屏播放页的"未播放态"落点）。
     *
     * 为什么要进契约，而不是"装载完让调用方再 pause()"：装载是**异步**的
     * （起播前探测 + 建表），`setQueue` 返回时媒体还没进播放器，调用方那一记
     * `pause()` 落在空处，随后装载落地里的 `play()` 会把它原样覆盖——"只装载"
     * 于是变成"播起来"（真机实报：横屏剧集详情转竖屏，未播放的落点自己开始播）。
     * 意图必须跟着这一次装载一起下发给引擎。
     */
    fun setQueue(
        items: List<com.noneplayer.core.media.MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        playOnLoad: Boolean = true,
    ) {
        items.getOrNull(startIndex)?.let { prepare(it.uri, it.mimeType, it.title, it.coverUri) }
        if (!playOnLoad) pause()
    }

    /** 跳到队列中的指定项（选集面板）；越界忽略 */
    fun selectQueueIndex(index: Int) {}

    /**
     * 清空队列与已装载媒体（迷你条 ✕、"后台播放"关闭时退页）。
     *
     * 除了停播与清会话侧队列，实现还必须清掉底层播放器的 media items——系统媒体
     * 通知与后台服务的生命周期都由它决定。只 pause 会留下"迷你条没了、通知还挂着"
     * 的两份真相（L8）。不支持队列的引擎可退化为空操作。
     */
    fun clearQueue() {}

    /** 下一集（无下一集时为空操作） */
    fun playNext() {}

    /** 上一集（无上一集时为空操作） */
    fun playPrevious() {}

    /**
     * 一颗媒体键按钮的 `PendingIntent`（A2）：小窗（PiP）上的暂停 / 下一集由**引擎**
     * 给出通路，UI 只负责图标、文案与顺序。
     *
     * 为什么不让 UI 自己 `PendingIntent.getService(...)`：那种 Intent 只有 action +
     * package、没有组件，本包内没有 service 接得住它（后台服务只声明了
     * `MediaSessionService` 的 intent-filter），`send()` 静默无操作——按钮在、点了没反应。
     * media3 的通路是**广播到 `MediaButtonReceiver`**，由它把媒体键交给会话服务，
     * 顺带补上"服务被系统杀掉后，耳机/蓝牙媒体键能把它叫回来"这条链路。
     *
     * 返回值实际是 `android.app.PendingIntent`——内核契约是纯 JVM 模块，Android 类型
     * 只能以 `Any` 出现（与 [attachDisplay] 同一姿势）。null = 引擎没有这条通路，
     * UI 不要画一颗点了没反应的钮。
     */
    fun mediaButtonIntent(action: NpMediaButtonAction): Any? = null

    /**
     * 准备播放指定地址。
     *
     * [title] / [artworkUri] 供媒体通知与锁屏展示（E4）；实现不支持时忽略。
     * 两者带默认值，保持既有调用方兼容。
     */
    fun prepare(uri: String, mimeType: String, title: String = "", artworkUri: String? = null)

    /**
     * 挂载视频显示表面（如 Android SurfaceView/TextureView）。
     * 传入 null 表示解除挂载；实现不支持外部表面时为空操作。
     */
    fun attachDisplay(view: Any?) {}

    /** Detach only this view; an outgoing screen must not clear its replacement's surface. */
    fun detachDisplay(view: Any) {}

    /** Audio-only playback disables video decoding without stopping the audio session. */
    fun setVideoTracksEnabled(enabled: Boolean) {}

    /** 设置播放倍速（0.25–4.0）；实现不支持时为空操作 */
    fun setSpeed(speed: Float) {}

    /**
     * 当前循环模式；不支持的引擎默认 [NpLoopMode.OFF]（顺序播放）。
     */
    val loopMode: StateFlow<NpLoopMode>
        get() = PlayerDefaults.loopMode

    /** 设置循环模式（顺序/单集循环/列表循环/随机）；不支持时为空操作 */
    fun setLoopMode(mode: NpLoopMode) {}

    /**
     * 自动连播下一集（设置页「播放」组）。
     *
     * 关闭时，当前条目播完应**停在末帧**而不是自动推进到队列下一项——引擎侧对应
     * media3 的 `ExoPlayer.setPauseAtEndOfMediaItems(true)`（会在末帧暂停并把
     * `playWhenReady` 置 false，理由为 `PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM`）。
     * 不支持队列语义的引擎可退化为空操作。
     */
    fun setAutoplayNext(enabled: Boolean) {}

    /**
     * 挂载外挂字幕文件（file 的 content uri）；传入 null 清除；不支持时为空操作。
     *
     * [format] = 该字幕文件的**容器格式**，由挂载方从真实文件名判出后显式传进来（A3）。
     * 为什么不能只给 uri：MediaStore 的 `content://media/external/file/1234` 末段是数字 id、
     * SAF 的是编码过的文档 id，引擎从 uri 串上取扩展名会一律落到 SubRip——于是自动匹配到的
     * `.ass` 一条 Cue 都不出。null = 挂载方判不出来，引擎退回按 uri 末段猜（旧行为）。
     */
    fun setSubtitle(uri: String?, format: NpSubtitleFormat? = null) {}

    /**
     * 选择内嵌轨道（批次 3 轨道面板）。[id] 来自 [tracks] 的 [NpTrack.id]；
     * 不支持的引擎为空操作。选字幕轨时应同时恢复文本轨启用态。
     */
    fun selectTrack(type: NpTrackType, id: String) {}

    /**
     * 启用/禁用内嵌文本轨（字幕）。关闭 = 不显示任何内嵌字幕，
     * 外挂挂载也经同一开关复原（E32）。不支持时为空操作。
     */
    fun setTextTracksEnabled(enabled: Boolean) {}

    /**
     * 字幕偏移（E43 / §12.5）：正数 = 字幕延后显示、负数 = 提前，
     * 单位毫秒。引擎据此重排 Cue 出口的时间窗（media3 的 Cue 流不带
     * 逐 Cue 时刻，偏移只能由引擎侧缓冲窗口实现）。不支持时为空操作。
     */
    fun setSubtitleOffsetMs(offsetMs: Long) {}

    /**
     * 画面裁剪模式（§2.4「画面」）：true = 满窗裁剪（cover），
     * false = 按比例适配（fit）。映射 ExoPlayer 的 videoScalingMode；
     * 布局比例/镜像/旋转由 UI 层 graphicsLayer 承担，不经引擎。
     * 不支持时为空操作。
     */
    fun setVideoCrop(crop: Boolean) {}

    /**
     * 进入/退出"擦洗"（进度条拖动）模式：进入时引擎应放宽 seek 精度换取响应速度，
     * 退出时恢复精确定位。不支持时为空操作。
     */
    fun setScrubbing(active: Boolean) {}

    /**
     * 结束一次擦洗：退出擦洗模式、落到 [targetMs]，并按 [resume] 恢复播放。
     *
     * 默认实现拆成三个标准调用；Exo 实现会经**一条** `np.scrub` 私有命令在会话侧
     * 原子完成（setSeekParameters + seek + play），避免三次跨进程往返和中间态。
     */
    fun finishScrub(targetMs: Long, resume: Boolean) {
        setScrubbing(false)
        seekTo(targetMs)
        if (resume) play()
    }

    /**
     * 与承载播放服务的连接状态（REV-10）。
     *
     * ExoPlayer 实现里播放器驻留后台 MediaSessionService，经 MediaController 通信；
     * 服务被系统回收时连接会断，命令与位置轮询静默变成空操作。legacy 引擎没有独立
     * 服务，恒为 [PlayerConnection.CONNECTED]。
     */
    val connection: StateFlow<PlayerConnection>
        get() = PlayerDefaults.connection

    /**
     * 请求重连（REV-10）：仅在 [connection] 为 DISCONNECTED/FAILED 时有意义；
     * 实现应做有界重试并在成功/失败时更新 [connection]。不支持时为空操作。
     */
    fun reconnect() {}

    /**
     * 等待播放器就绪（如后台播放会话建立完成）。
     * 立即就绪的实现立即返回；在调用 prepare/play 前先 await。
     */
    suspend fun awaitReady() {}

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 释放底层解码器等资源；释放后实例不可复用 */
    fun release()
}

/**
 * 播放器与承载服务之间的连接状态（REV-10）。
 *
 * 断连不是播放错误（不归 [PlaybackState.Error]）：服务被系统回收后可以重连恢复，
 * UI 应显示"已断开/重连中"并提供重试，而不是"无法播放"。
 */
enum class PlayerConnection {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    FAILED,
}

/**
 * 播放状态机。
 *
 * **没有"就绪"这一态**（A5）：引擎侧 media3 的 `STATE_READY` 按 `playWhenReady`
 * 落成 [PlaybackState.Playing] 或 [PlaybackState.Paused]。单独发一个 `Ready`
 * 只会成为死端——离开它只靠 `onIsPlayingChanged`，而"暂停后拖进度条"
 * （BUFFERING → READY，`isPlaying` 全程 false）永远不会再回调，
 * 于是常亮、降档、弹幕、播放钮一起停在错的状态上。
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Ended : PlaybackState

    /**
     * 播放失败。[failure] 为结构化失败信息（E41 体面失败：容器/编码/原因/转码建议），
     * 引擎拿不到结构信息时为 null，仅展示 [message]。
     */
    data class Error(
        val message: String,
        val failure: com.noneplayer.core.media.PlaybackFailure? = null,
    ) : PlaybackState
}

/**
 * 播放内核扩展点——提供一路播放引擎。
 *
 * 多引擎并存时，[VideoPlayerFactory] 按 [priority] 降序选择第一个
 * [supports] 目标格式的引擎；切换引擎时由工厂负责销毁旧实例，防止解码器泄漏。
 */
interface PlayerEngineProvider : ExtensionPoint {
    val engineId: String

    /** 优先级，越大越优先 */
    val priority: Int

    /** 创建播放器实例；`context` 由 UI 层解释（Android 上为 Context） */
    fun create(context: Any): VideoPlayer

    /** 是否支持目标 mimeType */
    fun supports(mimeType: String): Boolean
}

/** 轨道类型（批次 3 轨道面板） */
enum class NpTrackType { AUDIO, TEXT }

/**
 * 媒体键动作（[VideoPlayer.mediaButtonIntent] 的词汇表）。
 *
 * 只列引擎需要对外发的那几颗；UI 侧的图标与文案不归这里。
 */
enum class NpMediaButtonAction { PLAY, PAUSE, NEXT, PREVIOUS }

/**
 * 外挂字幕的容器格式（A3）。
 *
 * [extension] 是转码副本沿用的扩展名——副本必须与源同格式，否则引擎按 uri 末段猜 mime
 * 时会把 `.ass` 当 SubRip 解，`[Script Info]` / `Dialogue:` 对不上时间轴，一条 Cue 都不出。
 */
enum class NpSubtitleFormat(val extension: String) {
    SUBRIP("srt"),
    WEBVTT("vtt"),
    SSA("ass"),
    TTML("ttml"),
}

/**
 * 从文件名（优先）或 uri 串判字幕格式；判不出来返回 null，由引擎退回旧口径。
 *
 * 调用方应传**真实文件名**而不是 uri：MediaStore 的 `content://media/external/file/1234`
 * 与 SAF 的文档 uri 末段都是 id，从那里取不到扩展名（A3 的另一半根因）。
 */
fun npSubtitleFormatOf(nameOrUri: String): NpSubtitleFormat? =
    when (
        nameOrUri.substringAfterLast('.', "").substringBefore('/').substringBefore('?').lowercase()
    ) {
        "srt", "sub" -> NpSubtitleFormat.SUBRIP
        "vtt", "webvtt" -> NpSubtitleFormat.WEBVTT
        "ass", "ssa" -> NpSubtitleFormat.SSA
        "ttml", "xml", "dfxp" -> NpSubtitleFormat.TTML
        else -> null
    }

/**
 * 一条可选轨道（内嵌音频/字幕轨）。[label] 是人类可读展示名
 * （语言 / 标题 / 声道数 / 角色标签的组合），由引擎拼装；[id] 是选择键。
 */
@Immutable
data class NpTrack(
    val id: String,
    val type: NpTrackType,
    val label: String,
    val selected: Boolean,
)

/** 章节（§12.5：MP4/Matroska 章节经 metadata 通道暴露） */
@Immutable
data class NpChapter(
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

/** VideoPlayer 默认 getter 的共享容器：getter 不再每次新建 Flow（E209）。 */
private object PlayerDefaults {
    val bufferedPositionMs = MutableStateFlow(0L)
    val videoAspectRatio = MutableStateFlow(0f)
    val speed = MutableStateFlow(1f)
    val currentUri = MutableStateFlow<String?>(null)
    val cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val tracks = MutableStateFlow<List<NpTrack>>(emptyList())
    val chapters = MutableStateFlow<List<NpChapter>>(emptyList())
    val decodeNotice = MutableStateFlow<String?>(null)
    val hdrLabel = MutableStateFlow<String?>(null)
    val queue = MutableStateFlow<List<com.noneplayer.core.media.MediaItem>>(emptyList())
    val currentIndex = MutableStateFlow(-1)
    val loopMode = MutableStateFlow(NpLoopMode.OFF)
    val connection = MutableStateFlow(PlayerConnection.CONNECTED)
}
