// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.userdict

import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File

/**
 * 词组黑名单：一行一个词，命中后不再出现在候选栏里。
 *
 * 与 [CustomPhraseStore] 不同，这份文件 librime 并不认识，纯属输入法侧的显示过滤，
 * 所以放在用户目录下由本应用自己维护，改动后也不需要重载 Rime。
 */
object BlacklistStore {
    /** 文件首行注释，解析时按 `#` 开头跳过。 */
    private const val HEADER = "# 词组黑名单：一行一个词，命中的词不再出现在候选栏"

    /** 内存缓存，过滤发生在候选刷新的热路径上，不能每次都读文件。 */
    @Volatile
    private var cached: Set<String>? = null

    val file: File
        get() = File(DataManager.userDataDir, DataManager.BLACKLIST_FILE_NAME)

    /**
     * 解析文件内容。跳过空行与注释行，行首尾空白会被去掉。
     */
    fun parse(content: String): List<String> = content
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .distinct()
        .toList()

    fun serialize(words: List<String>): String = buildString {
        append(HEADER).append('\n')
        words.forEach { append(it).append('\n') }
    }

    /**
     * 读取黑名单。文件不存在或读取失败时返回空列表。
     */
    fun load(): List<String> {
        val target = runCatching { file }.getOrElse {
            Timber.w(it, "Blacklist file is not available")
            return emptyList()
        }
        if (!target.isFile) return emptyList()
        return runCatching { parse(target.readText(Charsets.UTF_8)) }
            .onFailure { Timber.w(it, "Failed to read blacklist file") }
            .getOrDefault(emptyList())
    }

    /**
     * 覆盖写入黑名单。空列表时删除文件，回到「没有黑名单」的状态。
     */
    fun save(words: List<String>): Result<Unit> = runCatching {
        val target = file
        if (words.isEmpty()) {
            if (target.exists()) target.delete()
            cached = emptySet()
            return@runCatching
        }
        target.parentFile?.mkdirs()
        target.writeText(serialize(words), Charsets.UTF_8)
        cached = words.toHashSet()
    }.onFailure { Timber.w(it, "Failed to write blacklist file") }

    /** 当前黑名单集合，首次访问时从文件读入。 */
    fun words(): Set<String> = cached ?: load().toHashSet().also { cached = it }

    /** 该候选是否被拉黑。过滤在 UI 线程同步调用，必须走缓存。 */
    fun contains(text: String): Boolean = text.isNotEmpty() && words().contains(text)

    fun add(word: String): Result<Unit> {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return Result.success(Unit)
        val current = words()
        if (current.contains(trimmed)) return Result.success(Unit)
        return save((current + trimmed).toList())
    }

    fun remove(word: String): Result<Unit> {
        val current = words()
        if (!current.contains(word)) return Result.success(Unit)
        return save(current.filterNot { it == word })
    }

    /** 丢弃缓存，下次访问重新读盘。外部直接改文件后调用。 */
    fun invalidate() {
        cached = null
    }
}
