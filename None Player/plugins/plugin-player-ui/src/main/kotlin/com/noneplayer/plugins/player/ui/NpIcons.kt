package com.noneplayer.plugins.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本插件自带的两个矢量图标（暂停 / 下一集）。
 *
 * **为什么不用 `Icons.Filled.Pause` / `Icons.Filled.SkipNext`**：项目只依赖
 * `material-icons-core`，该构件**只含 5 个基础图标**（Close / PlayArrow / ArrowBack 等），
 * Pause 与 SkipNext 在 `material-icons-extended` 里——那个包有 5MB+ 的图标，为了两个
 * 字形给 APK 增重、同时拖慢构建，不划算。这里按 Material 官方图标的等价路径自绘，
 * 视觉与系统图标一致（24dp 视口、同样的播放/跳过几何）。
 */
internal object NpIcons {

    /** 暂停：两条竖直圆角条 */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpPause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                close()
            }
        }.build()
    }

    /** 下一集：右向三角 + 未端竖条（MediaStyle 里通用的 skip-next 形状） */
    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpSkipNext",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 18f)
                lineTo(14.5f, 12f)
                lineTo(6f, 6f)
                close()
                moveTo(16f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    /** 上一集：SkipNext 的镜像（前端竖条 + 左向三角） */
    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "NpSkipPrevious",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(-2f)
                close()
                moveTo(18f, 6f)
                lineTo(9.5f, 12f)
                lineTo(18f, 18f)
                close()
            }
        }.build()
    }
}
