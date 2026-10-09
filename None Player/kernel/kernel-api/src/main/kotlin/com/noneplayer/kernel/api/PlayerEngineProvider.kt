package com.noneplayer.kernel.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

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
 * 均衡器能力快照（A2-11）：段数 + 每段可用增益区间（mB，毫贝尔）。
 *
 * 由引擎侧从 framework `Equalizer` 实测得到，UI 只按它画滑杆与映射归一值，
 * 不再猜机型——「5 段 ±1500」只是最常见的一档，不是普遍事实。
 */
data class EqualizerCapabilities(
    val bands: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int,
) {
    /** 量程跨度；<=0 表示设备报了个无效区间，UI 应按不支持处理 */
    val spanMb: Int get() = maxLevelMb - minLevelMb
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
        get() = MutableStateFlow(0L)

    /**
     * 视频宽高比（宽/高，已考虑旋转），用于 UI 按比例渲染画面；
     * 未知为 0。默认实现返回常量 0 流（无画面信息的能力）。
     */
    val videoAspectRatio: StateFlow<Float>
        get() = MutableStateFlow(0f)

    /** 当前实际播放倍速（UI 应以此为准显示，而非本地记忆）。默认恒为 1 */
    val speed: StateFlow<Float>
        get() = MutableStateFlow(1f)

    /**
     * 会话中当前已装载的媒体地址；未装载为 null。
     * UI 重进播放页时据此判断"会话仍在播同一视频"，避免用过期断点覆盖新进度。
     */
    val currentUri: StateFlow<String?>
        get() = MutableStateFlow(null)

    /**
     * 当前应显示的字幕 Cue（外挂与内嵌轨道的统一出口）；
     * 无字幕时为空列表。默认实现恒为空（引擎未接入字幕输出）。
     */
    val cues: StateFlow<List<SubtitleCue>>
        get() = MutableStateFlow(emptyList())

    /**
     * 当前媒体的可选轨道（内嵌音频/字幕，批次 3 轨道面板）。
     * 空列表 = 无内嵌轨道或引擎未接入轨道枚举。默认实现恒为空。
     */
    val tracks: StateFlow<List<NpTrack>>
        get() = MutableStateFlow(emptyList())

    /**
     * 章节列表（§12.5，1.11.0+ MP4/Matroska 章节经 metadata 通道）。
     * 空列表 = 无章节数据，UI 隐藏入口。默认实现恒为空。
     */
    val chapters: StateFlow<List<NpChapter>>
        get() = MutableStateFlow(emptyList())

    /**
     * 起播探测给出的"提示胶囊"文案（C21）：如"该视频仅有软件解码器，可能发热/掉帧"。
     * null = 无提示。默认实现恒为 null（引擎不做前置探测）。
     * UI 只负责展示，判定与措辞归引擎（它是唯一知道解码器实际选择的一方）。
     */
    val decodeNotice: StateFlow<String?>
        get() = MutableStateFlow(null)

    /**
     * 当前播放队列（同文件夹连播的来源）。默认空——不支持队列的引擎退化为单曲播放。
     *
     * 队列放在引擎契约而非 UI 侧，是因为"自动连播/通知栏 ⏮⏭"只有引擎（会话侧）
     * 才知道：media3 在 `setMediaItems` 之后由框架负责切集与通知按钮，
     * UI 侧另存一份队列反而会出现两份真相（§8.2）。
     */
    val queue: StateFlow<List<com.noneplayer.core.media.MediaItem>>
        get() = MutableStateFlow(emptyList())

    /** 当前队列下标；无队列或未播放为 -1 */
    val currentIndex: StateFlow<Int>
        get() = MutableStateFlow(-1)

    /**
     * 装载整个队列并从 [startIndex] 开始播放（队列连播、通知 ⏮⏭ 由此免费获得）。
     * 不支持队列的引擎可退化为只 [prepare] [startIndex] 那一项。
     */
    fun setQueue(
        items: List<com.noneplayer.core.media.MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
    ) {
        items.getOrNull(startIndex)?.let { prepare(it.uri, it.mimeType, it.title, it.coverUri) }
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

    /** 设置播放倍速（0.25–4.0）；实现不支持时为空操作 */
    fun setSpeed(speed: Float) {}

    /**
     * 当前循环模式；不支持的引擎默认 [NpLoopMode.OFF]（顺序播放）。
     */
    val loopMode: StateFlow<NpLoopMode>
        get() = MutableStateFlow(NpLoopMode.OFF)

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

    /** 挂载外挂字幕文件（file 的 content uri）；传入 null 清除；不支持时为空操作 */
    fun setSubtitle(uri: String?) {}

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
     * 均衡器能力（A2-11）：段数与每段量程（**mB，毫贝尔**）。
     *
     * 必须由引擎**实测上报**——framework `Equalizer` 的 `numberOfBands` 与
     * `bandLevelRange` 各机型不同（5 段 ±1500 只是最常见的一档，也有 ±600 与 3 段）。
     * `null` = 本引擎没有均衡器，UI 据此**隐藏入口**，不做"滑杆拨了没反应"的假动作。
     */
    val equalizerCapabilities: Flow<EqualizerCapabilities?> get() = flowOf(null)

    /**
     * 设置均衡器各段增益（A2-11）。[levels] 按段序归一到 `-1f..1f`；
     * **映射到真实量程在引擎侧做**——客户端不知道设备量程，跨进程传 mB 绝对值必然越界。
     * 长度不足按 0f（平）补齐，超出部分忽略。
     */
    fun setEqualizerLevels(levels: List<Float>) {}

    /** 均衡器总开关（false = 直通；不销毁效果实例，再开时保留上次增益） */
    fun setEqualizerEnabled(enabled: Boolean) {}

    /**
     * 探测均衡器能力（A2-11）：UI 打开面板时发一发——服务侧据此建效果实例，
     * 并把段数/量程经 `SessionExtras` 回传（能力只能实测得到，见 [equalizerCapabilities]）。
     * **只探不改**：不带开关与增益参数时不得改动当前声音状态。
     */
    fun probeEqualizer() {}

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

/** 播放状态机 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data object Ready : PlaybackState
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
 * 一条可选轨道（内嵌音频/字幕轨）。[label] 是人类可读展示名
 * （语言 / 标题 / 声道数 / 角色标签的组合），由引擎拼装；[id] 是选择键。
 */
data class NpTrack(
    val id: String,
    val type: NpTrackType,
    val label: String,
    val selected: Boolean,
)

/** 章节（§12.5：MP4/Matroska 章节经 metadata 通道暴露） */
data class NpChapter(
    val startMs: Long,
    val endMs: Long,
    val title: String,
)
