package com.noneplayer.kernel.ui.api

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.ui.NpDimens
import com.noneplayer.core.ui.NpIcon
import com.noneplayer.core.ui.NpMonoSmall
import com.noneplayer.core.ui.NpSwitch
import com.noneplayer.core.ui.NpTypography
import com.noneplayer.core.ui.npToggleable
import com.noneplayer.kernel.api.ExtensionPoint
import com.noneplayer.kernel.api.VideoPlayer

/**
 * 播放器「播放选项」菜单的**行贡献扩展点**（2026-10-12 用户裁定落地）。
 *
 * 背景：弹幕插件此前把自己的开关做成视频区右上角的一枚小 chip（点击开/关、
 * 长按弹来源菜单），用户实报"不要做成右上角的小圆点，把他加入右下角的选项表里，
 * 做好开关UI的适配"——开/关必须以**正经开关行**出现在播放器自己的选项表里。
 *
 * 为什么是契约：选项表（全屏 ⋯ 下拉 / 竖屏「播放选项」sheet）属于 `plugin-player-ui`，
 * 而弹幕开关与其来源管理（手动文件 / 内嵌轨）属于 `plugin-danmaku`，插件间禁止
 * 互相依赖——贡献方实现本接口注册，播放器把每一行渲染进菜单即可，双方只认这一纸契约。
 *
 * 上下文随行给：[media]/[player] 是当前会话的媒体与引擎（可能为 null = 尚未装载），
 * 贡献行据此渲染 per-media 状态（如"这一条当前有没有弹幕来源"）。
 */
interface PlaybackMenuContributor : ExtensionPoint {

    /** 唯一 id（日志/去重），以插件 id 为前缀 */
    val contributorId: String

    /** 排序（小的在前） */
    val order: Int get() = 100

    /**
     * 渲染若干行（整行自持：图标/文案/开关/点击都在实现里）。
     *
     * @param dark true = 宿主是**暗色菜单**（全屏 ⋯ 下拉，黑底白字语言）；
     *             false = 浅色面板（竖屏「播放选项」sheet，跟随应用主题）。
     * @param media 当前会话媒体（null = 无）
     * @param player 当前会话引擎（null = 无）
     */
    @Composable
    fun RenderRows(dark: Boolean, media: MediaItem?, player: VideoPlayer?)
}

/**
 * 贡献行的小标题（与宿主菜单分区标题同一字号/留白）。
 *
 * @param dark 见 [PlaybackMenuContributor.RenderRows]
 */
@Composable
fun npPlaybackMenuTitle(text: String, dark: Boolean) {
    Text(
        text = text,
        style = NpMonoSmall.copy(fontWeight = FontWeight.Medium),
        color = npPlaybackMenuMuted(dark),
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 贡献行：整行动作（图标 + 标题 + 右侧值/箭头）。
 *
 * 语言由宿主菜单给出（[dark]），几何与播放器自己的 [FsMenuRow](plugin-player-ui)
 * 对齐——44dp 命中下界（§14 #13）、圆角 10、横向留白 10dp，
 * 这样插件行与播放器行在同一列里读起来是同一套东西。
 */
@Composable
fun npPlaybackMenuRow(
    label: String,
    onClick: () -> Unit,
    dark: Boolean,
    icon: ImageVector? = null,
    value: String? = null,
    active: Boolean = false,
    chevron: Boolean = false,
) {
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val tint = if (active) MaterialTheme.colorScheme.primary else fg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = NpTypography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = NpMonoSmall,
                color = if (active) MaterialTheme.colorScheme.primary else npPlaybackMenuMuted(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Icon(
                imageVector = NpIcon.ChevronRight,
                contentDescription = null,
                tint = npPlaybackMenuMuted(dark),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 贡献行：开关（整行可点 = 拨动）。开/关是**唯一动作**，点击不弹任何二级面板 */
@Composable
fun npPlaybackMenuSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    dark: Boolean,
    icon: ImageVector? = null,
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NpDimens.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            // R4-17/REV-14：整行就是一个开关（On/Off + Role.Switch），读屏只报一个节点。
            .npToggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else npPlaybackMenuMuted(dark),
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = NpTypography.bodyMedium,
                color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = NpMonoSmall,
                    color = npPlaybackMenuMuted(dark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 语义已挂在整行上：里层开关只负责画图形，清空语义避免读屏重复报两个开关。
        NpSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun npPlaybackMenuMuted(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
