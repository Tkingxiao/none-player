package com.noneplayer.plugins.library

import android.Manifest
import android.annotation.SuppressLint

/**
 * 媒体权限 → 中文类别的标签表（REV-13）。
 *
 * 只映射本应用会申请的权限；未知权限原样显示，方便诊断而不是悄悄丢掉。
 */
// 权限名是 API 33/34 才有的 String 常量（会被内联）；这里只做字符串等值比较，
// 旧系统上这些权限根本不会出现在 declared/granted 里，运行时安全。
@SuppressLint("InlinedApi")
private val PERMISSION_LABELS: Map<String, String> = mapOf(
    Manifest.permission.READ_MEDIA_VIDEO to "视频",
    Manifest.permission.READ_MEDIA_AUDIO to "音频",
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to "部分照片与视频",
    Manifest.permission.READ_EXTERNAL_STORAGE to "存储",
)

/**
 * 部分授权提示（纯函数，可 JVM 单测）。
 *
 * 返回 null 的三种情形都有明确归属，UI 不该再用同一句提示盖住：
 *  - [declared] 为空：没有权限可申请；
 *  - 全部授予：无需提示；
 *  - 全部未授予：整页「需要媒体权限」空态负责（见 `LibraryStage.NoPermission`）。
 *
 * 只有"一部分已授予、一部分没有"才返回一句列出缺失类别的提示——用户既知道
 * 媒体库现在为什么只显示一部分内容，也知道补授权能补哪一类。
 *
 * Android 14 的 `READ_MEDIA_VISUAL_USER_SELECTED` 是「仅选中的照片/视频」这种
 * **替代**授权：完整视频或音频已经到手时，系统不会同时给这条权限，它的缺失
 * 不是缺陷（否则完整授权反而会误报"部分权限"），所以此时从缺失列表里剔除。
 */
@SuppressLint("InlinedApi")
internal fun partialPermissionHint(declared: List<String>, granted: Set<String>): String? {
    if (declared.isEmpty()) return null
    val declaredDistinct = declared.distinct()
    val hasFullMediaAccess = Manifest.permission.READ_MEDIA_VIDEO in granted ||
        Manifest.permission.READ_MEDIA_AUDIO in granted
    val missing = declaredDistinct.filterNot { it in granted }
        .filterNot {
            it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && hasFullMediaAccess
        }
    if (missing.isEmpty() || missing.size == declaredDistinct.size) return null
    val labels = missing.map { PERMISSION_LABELS[it] ?: it }
    return "仅授予了部分媒体权限（缺少${labels.joinToString("、")}），媒体库只显示已授权的部分。"
}
