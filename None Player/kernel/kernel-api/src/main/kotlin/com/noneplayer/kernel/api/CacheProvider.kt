package com.noneplayer.kernel.api

/**
 * 插件自愿暴露的可清理缓存（批次 7「插件清缓存」）。
 *
 * 内核不知道插件有没有缓存、缓存在哪——那是插件的私有实现细节，内核扫目录
 * 就是往内核写插件专属逻辑。因此做成这个**可选扩展点**：谁有缓存谁注册，
 * 插件管理页聚合展示占用并提供清理；没注册就没有对应行，页面不编造数字。
 *
 * 两个方法都由调用方保证在后台线程执行（探测/删除都是磁盘 I/O）。
 */
interface CacheProvider : ExtensionPoint {

    /** 展示名（如「缩略图缓存」） */
    val cacheLabel: String

    /** 估算当前占用（字节） */
    fun cacheSizeBytes(): Long

    /** 清理并返回清理前的占用（字节） */
    fun clearCache(): Long
}
