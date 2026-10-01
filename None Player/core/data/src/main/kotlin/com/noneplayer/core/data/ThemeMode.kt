package com.noneplayer.core.data

/**
 * 外观模式。
 *
 * 注意：Apple HIG（dark-mode.md）建议不提供应用内外观切换；
 * None Player 作为视频播放器，用户经常需要在亮环境下看深色界面，
 * 故保留三模式选择，默认跟随系统。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("亮色"),
    DARK("暗色"),
}

/** 媒体库主页视图模式 */
enum class LibraryView(val label: String) {
    VIDEOS("全部视频"),
    FOLDERS("按文件夹"),
}
