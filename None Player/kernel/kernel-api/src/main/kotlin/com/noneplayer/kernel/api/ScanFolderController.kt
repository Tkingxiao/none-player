package com.noneplayer.kernel.api

/**
 * 可选能力：由来源自己执行「扫描目录」的增删。
 *
 * 背景：扫描目录列表持久化在 `AppSettings`，但增删的**副作用**归来源所有——
 * 释放 SAF 持久化授权、立刻重扫、移除最后一个目录时清空内存列表与缩略图缓存。
 * 媒体库页要提供统一的「文件夹管理」界面（实报"没有统一的文件夹管理界面"），
 * 直接改 DataStore 会绕过这些副作用（旧目录的授权不释放、列表不刷新）。
 *
 * 由 `plugin-scanner-local` 实现并注册；未注册时调用方退化为"只改设置 + 重扫"。
 */
interface ScanFolderController : ExtensionPoint {

    /** 添加一个扫描目录（SAF tree URI 字符串，授权由调用方先行持久化）并立即重扫 */
    suspend fun addScanFolder(uri: String)

    /** 移除一个扫描目录：释放其持久化授权；仍有其他目录时立即重扫，移除最后一个时清空媒体列表 */
    suspend fun removeScanFolder(uri: String)
}
