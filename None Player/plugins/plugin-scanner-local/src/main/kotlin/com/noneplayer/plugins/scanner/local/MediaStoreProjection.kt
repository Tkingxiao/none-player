package com.noneplayer.plugins.scanner.local

import android.os.Build
import android.provider.MediaStore

/**
 * 按 SDK 构造 MediaStore 投影（REV-12）。
 *
 * 旧实现无条件把 `RELATIVE_PATH` 放进投影，SDK 判断只保护"取列"（`getColumnIndex`）——
 * 但旧 Provider 在收到未知列时可能直接拒绝查询，于是 API 26–28 上视频/音频**整个查询失败**。
 * 投影本身必须按 SDK 裁剪：Q+ 含 `RELATIVE_PATH`（分组与同目录连播依赖它），
 * pre-Q 不含。
 *
 * 纯逻辑，不读 [Build.VERSION.SDK_INT]：SDK 由调用方传入，便于 26/28/29/34 矩阵单测。
 * 每次调用返回**新数组**，调用方可以安全持有/修改。
 */
internal object MediaStoreProjection {

    /** Q（29）起 MediaStore 提供 `RELATIVE_PATH` 列。 */
    private const val SDK_Q = Build.VERSION_CODES.Q

    /** 投影是否应包含 `RELATIVE_PATH`。 */
    fun hasRelativePath(sdkInt: Int): Boolean = sdkInt >= SDK_Q

    /**
     * 视频集合投影。
     *
     * Q+：`_ID, DISPLAY_NAME, DURATION, SIZE, DATE_MODIFIED, DATE_ADDED, MIME_TYPE, DATA,
     * RELATIVE_PATH, WIDTH, HEIGHT`；pre-Q：同一顺序去掉 `RELATIVE_PATH`。
     */
    fun video(sdkInt: Int): Array<String> = if (hasRelativePath(sdkInt)) {
        arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
    } else {
        arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
    }

    /**
     * 音频集合投影。
     *
     * Q+：`_ID, DISPLAY_NAME, DURATION, SIZE, DATE_MODIFIED, DATE_ADDED, MIME_TYPE, DATA,
     * RELATIVE_PATH, ALBUM_ID, ARTIST, TITLE, IS_MUSIC`；pre-Q：同一顺序去掉 `RELATIVE_PATH`。
     *
     * `ALBUM_ID` 用于相册封面，`TITLE` 才是"歌名"（DISPLAY_NAME 是文件名），
     * `ARTIST`/`IS_MUSIC` 备查但不用于过滤（铃声/录音同样落在音频集合里）。
     */
    fun audio(sdkInt: Int): Array<String> = if (hasRelativePath(sdkInt)) {
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.IS_MUSIC,
        )
    } else {
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.IS_MUSIC,
        )
    }
}
