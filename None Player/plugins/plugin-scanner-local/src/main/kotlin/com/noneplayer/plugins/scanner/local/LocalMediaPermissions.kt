package com.noneplayer.plugins.scanner.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 本地扫描所需的媒体权限（Android 13+ 细分媒体权限，旧版本存储权限） */
class LocalMediaPermissions : com.noneplayer.kernel.api.PermissionProvider {

    override val permissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    override val rationale: String =
        "None Player 需要访问设备上的视频文件，才能在媒体库中展示和播放它们。"
}

/** 权限是否已全部授予 */
fun hasMediaPermission(context: Context): Boolean =
    LocalMediaPermissions().permissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
