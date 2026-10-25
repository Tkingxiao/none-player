package com.noneplayer.plugins.player.ui

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.noneplayer.kernel.api.SessionKeyProvider

/**
 * 会话级键盘快捷键的**唯一映射表**（E99 / 设计路线 §8.4）。
 *
 * 两个消费面共用这一张表，避免"同一颗键在两个形态里做两件事"：
 *  - 独占播放页（`PlayerScreen` 根节点的 `onPreviewKeyEvent`）——页面级动作
 *    （`F` 全屏、`L` 锁定、`Ctrl+O` 打开文件、`Esc` 的弹层链）由页面自己附加；
 *  - 类 PC 三栏浏览态 / 任意非播放页（[BrowseSessionKeys]，经内核
 *    `SessionKeyProvider` 在窗口层兜底派发）。
 *
 * 步进值也住在这里：以前散在播放页的私有常量，右栏要用就得再抄一份——
 * 两份数字迟早会漂。
 */
internal object SessionKeySteps {
    /** `←/→` 单次跳转 */
    const val SEEK_MS = 5_000L

    /** `Shift+←/→` 大步跳转 */
    const val SEEK_LARGE_MS = 30_000L

    /**
     * 媒体快进/快退键与 `J` 的步进（E228）。键盘惯例取 10 秒：方向键 5 秒 / Shift 30 秒
     * 之间，单键直达又不至于一按飞过四分之一短片。
     */
    const val SEEK_SKIP_MS = 10_000L

    /** `↑/↓` 音量步进（百分点） */
    const val VOLUME_STEP = 5

    /** `M` 静音后没有记住过可听档位时的兜底音量（还 0 会让"静音"变成"永久失声"） */
    const val VOLUME_RESTORE_PERCENT = 30
}

/**
 * 百分比 → 系统音量档位：**四舍五入**（E101 观察②）。
 *
 * 截断会让 ±5% 的按键/手势步进在档位少的设备上偶发无效果：15 档时一档 ≈6.7%，
 * `33% + 5% = 38%` 换算回来 `15 × 38 / 100 = 5.7` 截成 5，与原来同档（"按了没反应"）；
 * 静音还原同理掉一档。播放页手势/按键与右栏兜底派发共用这一条算式。
 */
internal fun volumeIndexFor(max: Int, percent: Int): Int =
    if (max <= 0) 0 else ((max * percent.coerceIn(0, 100) + 50) / 100).coerceIn(0, max)

/**
 * 独占播放页是否在场（E102）。
 *
 * 方向键在播放页里应当归**页面自己**——那边的手势/按键路径带"快进 5 秒"提示与边界
 * 拒绝触觉（批次 2/5 的既定反馈），而会话兜底是"静默执行"。所以窗口层抢下方向键后，
 * 由 [BrowseSessionKeys] 在页内主动让位（返回 false → 键回到视图树 → 页面自己的
 * `onPreviewKeyEvent` 接手）。
 */
internal object PlayerPagePresence {
    @Volatile
    var active: Boolean = false
}

/** 与界面无关的会话指令——映射与执行分离，映射才能被单测覆盖。 */
internal sealed interface SessionKeyCommand {
    data object PlayPause : SessionKeyCommand

    /** 媒体 PLAY 键：只播不切（停在末尾时先归零，见 [PlaybackSession.play]）。 */
    data object Play : SessionKeyCommand

    /** 媒体 PAUSE 键：只停不放。 */
    data object Pause : SessionKeyCommand

    data class Seek(val deltaMs: Long) : SessionKeyCommand
    data class Volume(val deltaPercent: Int) : SessionKeyCommand
    data object Mute : SessionKeyCommand

    /** 媒体 NEXT 键：下一集（无下一集时空操作）。 */
    data object NextTrack : SessionKeyCommand

    /** 媒体 PREVIOUS 键：上一集（无上一集时空操作）。 */
    data object PreviousTrack : SessionKeyCommand
}

/**
 * 键码 → 会话指令。不认识的键返回 null（**绝不吞键**：未消费的键还要继续
 * 走系统的默认路径，比如音量键该由系统出条）。
 *
 * `Shift` 只改步进、不改语义。媒体键（E228）：蓝牙键盘/遥控的媒体键不会都进
 * MediaSession——不少外设把 PLAY/FF/NEXT 当普通键事件投给焦点窗口，这里认领后
 * 两个消费面（播放页 / 三栏浏览态兜底）同口径。表里**没有** `Esc` / `F` / `Ctrl+O`：
 *  - `Esc` 在窗口层就被系统当成"返回"投递掉了（`super.dispatchKeyEvent` 吃掉，
 *    兜底派发根本收不到），而浏览态的退出点本来就是返回键与右栏顶栏那颗返回钮；
 *    播放页要的又是"逐层关弹层"，属页面级，留在 `PlayerScreen` 里。
 *  - `F`（全屏）与 `Ctrl+O`（打开文件）需要导航与文件选择器，只有播放页做得到。
 *
 * `L` 也不进表（E228 裁定）：J/K/L 惯例里的 `+10s` 与 §8.4 设计表的 `L=锁定`
 * 撞键——锁定是设计规格且"排在锁定吞键之前"的生命线（锁死后的唯一键盘出口），
 * `+10s` 由 `→`/媒体 FF 承担，不抢。
 */
internal fun routeSessionKey(keyCode: Int, shiftPressed: Boolean): SessionKeyCommand? = when (keyCode) {
    KeyEvent.KEYCODE_SPACE -> SessionKeyCommand.PlayPause
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> SessionKeyCommand.PlayPause
    KeyEvent.KEYCODE_MEDIA_PLAY -> SessionKeyCommand.Play
    KeyEvent.KEYCODE_MEDIA_PAUSE -> SessionKeyCommand.Pause
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
        SessionKeyCommand.Seek(SessionKeySteps.SEEK_SKIP_MS)
    KeyEvent.KEYCODE_MEDIA_REWIND ->
        SessionKeyCommand.Seek(-SessionKeySteps.SEEK_SKIP_MS)
    KeyEvent.KEYCODE_MEDIA_NEXT -> SessionKeyCommand.NextTrack
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> SessionKeyCommand.PreviousTrack
    KeyEvent.KEYCODE_J -> SessionKeyCommand.Seek(-SessionKeySteps.SEEK_SKIP_MS)
    KeyEvent.KEYCODE_K -> SessionKeyCommand.PlayPause
    KeyEvent.KEYCODE_DPAD_LEFT ->
        SessionKeyCommand.Seek(-(if (shiftPressed) SessionKeySteps.SEEK_LARGE_MS else SessionKeySteps.SEEK_MS))
    KeyEvent.KEYCODE_DPAD_RIGHT ->
        SessionKeyCommand.Seek(if (shiftPressed) SessionKeySteps.SEEK_LARGE_MS else SessionKeySteps.SEEK_MS)
    KeyEvent.KEYCODE_DPAD_UP -> SessionKeyCommand.Volume(SessionKeySteps.VOLUME_STEP)
    KeyEvent.KEYCODE_DPAD_DOWN -> SessionKeyCommand.Volume(-SessionKeySteps.VOLUME_STEP)
    KeyEvent.KEYCODE_M -> SessionKeyCommand.Mute
    else -> null
}

/**
 * 数字键 0-9（主键盘 + 小键盘）→ 数字，其它键码返回 null（E228 数字跳转）。
 * 跳转语义（片长 × n/10）是页面级的（要时长与 seek），但键位识别放这里，
 * 与共用表同址维护，避免播放页再开一份自己的键码表。
 */
internal fun digitForKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
    KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
    KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
    KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
    KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
    KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
    KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
    else -> null
}

/**
 * 数字跳转目标位置：片长 × n/10。时长未知（≤0，装载中/直播型）返回 null——
 * 没有目标可去就不吞键，与「未知时长双击拒绝」同口径。
 */
internal fun digitJumpTargetMs(durationMs: Long, digit: Int): Long? =
    if (durationMs <= 0L) null else durationMs * digit / 10L

/**
 * 非播放页时的会话快捷键载体（E99）。
 *
 * 注册在 `SessionKeyProvider` 上，由 `MainActivity` 在 **Compose 树没消费这个键**
 * 之后调用——所以播放页在场时它一行都不会执行（播放页的 `onPreviewKeyEvent`
 * 先把键吃掉），搜索框里敲空格也不会误触暂停。
 *
 * 音量走系统 `STREAM_MUSIC`，与播放页右侧竖滑手势、`↑/↓` 同一条流；
 * 每次读实时值再算，不持有副本——否则页面改了音量这边就对不上。
 */
internal class BrowseSessionKeys(
    context: Context,
    private val sessionOf: () -> PlaybackSession,
) : SessionKeyProvider {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var lastAudiblePercent = 0

    override fun onSessionKey(keyCode: Int, shiftPressed: Boolean, ctrlPressed: Boolean): Boolean {
        val command = routeSessionKey(keyCode, shiftPressed) ?: return false
        // E102：播放页在场时方向键让位给页面（那边带"快进 5 秒"提示与边界拒绝触觉）
        if (PlayerPagePresence.active &&
            (command is SessionKeyCommand.Seek || command is SessionKeyCommand.Volume)
        ) return false
        val session = sessionOf()
        // 没有会话可操作就别把键吞掉：从未播过、引擎还没起来（含 E88 的无引擎体面失败）
        val player = session.player ?: return false
        if (session.nowPlaying.value == null) return false
        when (command) {
            SessionKeyCommand.PlayPause -> session.toggle()
            SessionKeyCommand.Play -> session.play()
            SessionKeyCommand.Pause -> session.pause()
            is SessionKeyCommand.Seek -> {
                val duration = player.durationMs.value.coerceAtLeast(0L)
                player.seekTo((player.positionMs.value + command.deltaMs).coerceIn(0L, duration))
            }
            is SessionKeyCommand.Volume -> setVolumePercent(currentVolumePercent() + command.deltaPercent)
            SessionKeyCommand.Mute -> {
                val current = currentVolumePercent()
                if (current > 0) {
                    lastAudiblePercent = current
                    setVolumePercent(0)
                } else {
                    setVolumePercent(if (lastAudiblePercent > 0) lastAudiblePercent else SessionKeySteps.VOLUME_RESTORE_PERCENT)
                }
            }
            // 媒体 NEXT/PREVIOUS：切集带断点落史，与会话两侧（选集面板/迷你条）同一条路
            SessionKeyCommand.NextTrack -> session.next()
            SessionKeyCommand.PreviousTrack -> session.previous()
        }
        return true
    }

    private fun maxVolume(): Int =
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    private fun currentVolumePercent(): Int {
        val max = maxVolume()
        return if (max <= 0) 0
        else audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setVolumePercent(value: Int) {
        val max = maxVolume()
        if (max <= 0) return
        audioManager?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volumeIndexFor(max, value),
            0,
        )
    }
}
