package com.noneplayer.plugins.scanner.local

import android.content.Context
import android.os.Build

/**
 * 本地扫描所需的媒体权限（Android 13+ 细分媒体权限，旧版本存储权限）。
 *
 * 视频与音频**一起申请**：扫描器同时收录两类媒体，只申请视频权限会让
 * `MediaStore.Audio` 查询静默返回空表（不抛异常，只是没数据）——
 * 表现为"音频区块永远是空的"，很难归因到权限上。
 *
 * REV-13：一起申请，但**分开判定**——用户只拒绝其中一类时，另一类仍然可用
 * （旧实现"全部授予才可用"会连已授权的一类一起封死）。申请清单与可见性
 * 判定都收在 [MediaAccessPolicy] 里，本类只负责向插件系统暴露权限契约。
 */
class LocalMediaPermissions(
    /** 测试可注入；生产默认取当前系统版本 */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : com.noneplayer.kernel.api.PermissionProvider {

    override val permissions: List<String> = MediaAccessPolicy.declaredPermissions(sdkInt)

    override val rationale: String =
        "None Player 需要访问设备上的视频与音频文件，才能在媒体库中展示和播放它们。" +
            "可以只授权视频或音频，媒体库会显示已授权的那部分。"
}

/**
 * 是否至少有一类媒体可见（REV-13 新语义）。
 *
 * 保留签名给 `LocalMediaSource.isAvailable` 等既有调用点；旧语义是
 * "视频+音频全部授予"，用户拒绝音频会连已授权的视频一起判为不可用。
 */
fun hasMediaPermission(context: Context): Boolean =
    MediaAccessPolicy.current(context).hasMediaPermission
