package com.noneplayer.kernel.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** 挂载外挂字幕文件（file 的 content uri）；传入 null 清除；不支持时为空操作 */
    fun setSubtitle(uri: String?) {}

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
