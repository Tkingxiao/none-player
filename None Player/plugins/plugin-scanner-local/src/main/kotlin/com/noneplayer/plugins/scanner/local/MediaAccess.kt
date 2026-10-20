package com.noneplayer.plugins.scanner.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * 本地媒体的可见性快照（REV-13）。
 *
 * 旧模型把视频与音频当成**一个**权限：两者必须全部授予才算可用
 * （见旧版 `LocalMediaPermissions.hasMediaPermission`），用户只拒绝音频，
 * 已经授权的视频也会一起被判成不可用。这里把可见性拆成四份分别建模：
 * 完整视频、完整音频、Android 14 的「仅选中的照片/视频」、以及
 * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。
 *
 * 纯数据类，不碰系统 API —— 判定逻辑全在 [MediaAccessPolicy] 里，可 JVM 单测。
 */
internal data class MediaAccess(
    val sdkInt: Int,
    val videoGranted: Boolean,
    val audioGranted: Boolean,
    /** Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`：只可见用户勾选的那部分照片/视频 */
    val selectedVisualGranted: Boolean = false,
    /** Android 12L 及以下：`READ_EXTERNAL_STORAGE` 一条同时覆盖视频与音频 */
    val legacyStorageGranted: Boolean = false,
    /** 已持久化授权的 SAF 目录数：没有媒体权限时，SAF 目录也算来源可用 */
    val safFolderCount: Int = 0,
    /** API 30+ 的「所有文件访问」：绕开媒体权限的可见性 */
    val allFilesAccess: Boolean = false,
) {
    /** 能否查询到视频（完整授权 / 仅选中授权 / 所有文件访问，任一成立即可） */
    val canQueryVideo: Boolean
        get() = videoGranted || selectedVisualGranted || allFilesAccess

    /** 能否查询到音频 */
    val canQueryAudio: Boolean
        get() = audioGranted || allFilesAccess

    /** 至少有一类媒体可见 —— REV-13 的可用性下限，不再要求两类全给 */
    val hasMediaPermission: Boolean
        get() = canQueryVideo || canQueryAudio

    /** 来源是否可用：有任一类媒体可见，或已有 SAF 目录授权 */
    val isAvailable: Boolean
        get() = hasMediaPermission || safFolderCount > 0

    /** 有媒体权限但缺另一类 —— UI 该提示「部分权限」，而不是整页授权引导 */
    val isPartial: Boolean
        get() = hasMediaPermission && (!canQueryVideo || !canQueryAudio)

    /** 当前不可见的媒体类别（中文标签；全部可见时为空表） */
    fun deniedCategories(): List<String> = buildList {
        if (!canQueryVideo) add("视频")
        if (!canQueryAudio) add("音频")
    }

    /**
     * 部分权限时给用户看的一句话；完整授权或完全没有权限时为 null
     * （完全没有权限由「需要媒体权限」整页空态负责，不叠加局部提示）。
     */
    fun note(): String? = when {
        canQueryVideo && !canQueryAudio -> "未授予音频权限，媒体库仅显示视频"
        canQueryAudio && !canQueryVideo -> "未授予视频权限，媒体库仅显示音频"
        else -> null
    }
}

/**
 * 媒体权限的申请与判定策略。
 *
 * 只有 [current] 读系统状态；[declaredPermissions] / [evaluate] 是纯函数，
 * 单测不依赖 Robolectric。
 */
internal object MediaAccessPolicy {

    // 权限名都是 API 33/34 才有的 String 常量（会被内联）；[declaredPermissions] 按
    // sdkInt 分支只申请/判定该版本存在的权限，旧系统不会执行到这些常量，运行时安全。
    @SuppressLint("InlinedApi")
    private const val READ_MEDIA_VIDEO = Manifest.permission.READ_MEDIA_VIDEO

    @SuppressLint("InlinedApi")
    private const val READ_MEDIA_AUDIO = Manifest.permission.READ_MEDIA_AUDIO

    @SuppressLint("InlinedApi")
    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

    private const val READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * 申请用的权限列表：Android 13+ 视频/音频分开申请（一起申请，但分开判定）；
     * Android 14+ 追加「仅选中的照片/视频」，让用户可以选择部分授权而不是一刀切；
     * Android 12L 及以下只有一条存储权限。
     */
    fun declaredPermissions(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            READ_MEDIA_VIDEO,
            READ_MEDIA_AUDIO,
            READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(READ_MEDIA_VIDEO, READ_MEDIA_AUDIO)
        else -> listOf(READ_EXTERNAL_STORAGE)
    }

    /**
     * 纯函数判定：给定 SDK 版本、已授予权限集合、SAF 目录数与所有文件访问状态，
     * 算出各类媒体的可见性。
     *
     * @param granted 已授予的权限（其余声明了但未授予的都不在里面）
     * @param safFolderCount 已持久化授权的 SAF 目录数（由调用方从上下文或设置里取）
     * @param allFilesAccess API 30+ `Environment.isExternalStorageManager()` 的结果
     */
    fun evaluate(
        sdkInt: Int,
        granted: Set<String>,
        safFolderCount: Int,
        allFilesAccess: Boolean,
    ): MediaAccess {
        val legacy = sdkInt < Build.VERSION_CODES.TIRAMISU
        val video = if (legacy) READ_EXTERNAL_STORAGE in granted else READ_MEDIA_VIDEO in granted
        val audio = if (legacy) READ_EXTERNAL_STORAGE in granted else READ_MEDIA_AUDIO in granted
        val selected = !legacy && READ_MEDIA_VISUAL_USER_SELECTED in granted
        return MediaAccess(
            sdkInt = sdkInt,
            videoGranted = video,
            audioGranted = audio,
            selectedVisualGranted = selected,
            legacyStorageGranted = legacy && READ_EXTERNAL_STORAGE in granted,
            safFolderCount = safFolderCount,
            allFilesAccess = allFilesAccess,
        )
    }

    /**
     * 读当前进程的真实状态。
     *
     * `safFolderCount` 用 ContentResolver 里持久化的读授权数作为「已有 SAF 目录」的
     * 上下文内代理：扫描目录的真相在 `AppSettings.scanFolderUris`，但可用性判定
     * （`PermissionProvider` / `LocalMediaSource.isAvailable`）拿不到它，而且这里
     * 只关心「是否至少授权过一处」，不关心具体是哪几个目录。
     */
    fun current(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): MediaAccess {
        val granted = declaredPermissions(sdkInt).filterTo(mutableSetOf()) {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val allFilesAccess = sdkInt >= Build.VERSION_CODES.R && allFilesAccessGranted()
        return evaluate(
            sdkInt = sdkInt,
            granted = granted,
            safFolderCount = context.contentResolver.persistedUriPermissions.count {
                it.isReadPermission
            },
            allFilesAccess = allFilesAccess,
        )
    }

    /**
     * 「所有文件访问」是否已授予：API 30 以下没有这个能力。
     *
     * 单独抽出来用 [Build.VERSION.SDK_INT] 守卫，是因为 [current] 的 `sdkInt` 是
     * 可注入参数（REV-12 测试要模拟旧系统），lint 的 NewApi 数据流只认
     * `Build.VERSION.SDK_INT`。
     */
    private fun allFilesAccessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            false
        }
}
