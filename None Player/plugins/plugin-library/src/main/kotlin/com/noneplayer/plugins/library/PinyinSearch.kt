package com.noneplayer.plugins.library

import android.icu.text.Transliterator
import android.os.Build
import android.util.LruCache
import com.noneplayer.core.media.MediaItem
import com.noneplayer.core.media.cardTitle

/**
 * 中文标题的拼音搜索（§12.6，批次 8）——搜 "ai" 命中「爱…」，搜 "aq" 命中首字母。
 *
 * 转写用 **android.icu**（`Han-Latin; Latin-ASCII`，API 24 起内置）：零新依赖、
 * 离线、全字表覆盖——这是不引 pinyin4j 的原因（体积 + 停更 + 字表还得自己带上）。
 *
 * 失败路径全部退化：ICU 不可用（极旧 ROM / JVM 单测环境）或转写抛异常时，
 * [toPinyin] 返回 null，[matches] 退回普通包含匹配——拼音是**增益**不是门槛。
 */
internal object PinyinSearch {

    /** 拼音源抽象（JVM 单测注入假表用；生产走 ICU） */
    fun interface Source {
        fun transliterate(text: String): String?
    }

    // android.icu.text.Transliterator 需要 API 29；26–28 与单测环境退化为普通包含
    // （拼音是增益不是门槛，见类注释）
    private val icuSource: Source? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Source { text ->
            // Han-Latin 带声调 → Latin-ASCII 剥调；"爱情" → "ài qíng" → "ai qing"
            Transliterator.getInstance("Han-Latin; Latin-ASCII").transliterate(text)
        }
    } else {
        null
    }

    /** 转写结果缓存：扫描库越大同一标题被反复匹配的次数越多 */
    private val cache = LruCache<String, String?>(512)

    /** 拼音串归一用的两条模式：提到常量，键入路径上每次 cache miss 都各编译一次太亏（E121） */
    private val NON_ALPHA = Regex("[^a-z ]")
    private val MULTI_SPACE = Regex(" +")

    /** 标题 → "ai qing" 形态拼音；不可转写（无 ICU / 异常）返回 null */
    fun toPinyin(text: String): String? {
        val source = icuSource ?: return null
        cache.get(text)?.let { return it }
        val result = runCatching { source.transliterate(text) }.getOrNull()
            ?.lowercase()
            ?.replace(NON_ALPHA, "")
            ?.replace(MULTI_SPACE, " ")
            ?.trim()
            ?.ifEmpty { null }
        cache.put(text, result)
        return result
    }

    /**
     * 查询词是否命中 [text]：全拼包含（"aiqing" ∋ "ai"）或首字母包含（"aq" ∋ "a"）。
     * [pinyin] 为 null（不可转写）时只在 [text] 上做普通包含。
     */
    fun hitsQuery(text: String, pinyin: String?, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        if (pinyin == null) return text.contains(q, ignoreCase = true)
        val joined = pinyin.replace(" ", "")
        if (joined.contains(q)) return true
        // 首字母串："ai qing" → "aq"
        val initials = pinyin.split(' ').mapNotNull { it.firstOrNull() }.joinToString("")
        return initials.isNotEmpty() && initials.contains(q)
    }

    /**
     * 搜索域判定：标题/短标题/文件夹任一命中（普通包含 **或** 拼音命中）。
     * 搜索页与 `LocalSearchProvider` 共用这一份口径。
     */
    fun matches(item: MediaItem, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        // 纯 ASCII 查询词才走拼音路；中文查询词普通包含就够了（转写是单向的）
        fun plain(s: String?) = s?.contains(q, ignoreCase = true) == true
        if (plain(item.title) || plain(item.cardTitle) || plain(item.folder)) return true
        if (!q.all { it.code < 0x80 }) return false
        val targets = sequenceOf(item.title, item.cardTitle, item.folder.orEmpty()).distinct()
        return targets.any { target -> hitsQuery(target, toPinyin(target), q) }
    }
}
