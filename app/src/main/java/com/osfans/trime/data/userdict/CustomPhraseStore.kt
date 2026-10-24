// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.userdict

import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File

/**
 * 一条自定义短语，对应 `custom_phrase.txt` 的一行。
 *
 * 文件格式由 librime 的 `rime_table_entry_parser` 决定（见
 * `app/src/main/jni/librime/src/rime/dict/table_db.cc`）：
 * `短语 <Tab> 编码 [ <Tab> 权重 ]`。
 * 权重是整数 commits，缺省为 0；数值越大候选排序越靠前。
 */
data class CustomPhrase(
    val text: String,
    val code: String,
    val weight: Int = 0,
)

/**
 * `custom_phrase.txt` 的读写。该文件由 `mydomain.schema.yaml` 里
 * `table_translator@custom_phrase` 的 `db_class: stabledb` 消费，
 * 属于用户目录（`DataManager.userDataDir`）下的纯文本，由用户自行维护。
 *
 * librime 侧 `StableDb` 是只读的（`OpenReadOnly()`），且扩展名固定 `.txt`，
 * 所以这里只负责生成文件，改动后需要重载 Rime 才会生效。
 */
object CustomPhraseStore {
    /** 文件首行注释；`TsvReader` 会把 `#` 开头的行当注释跳过。 */
    private const val HEADER = "# Rime table"

    /** `TsvReader` 遇到这一行会关闭注释解析，绝对不能写进文件。 */
    private const val COMMENT_DISABLED = "# no comment"

    val file: File
        get() = File(DataManager.userDataDir, DataManager.CUSTOM_PHRASE_FILE_NAME)

    /**
     * 解析文件内容。跳过空行与注释行，忽略字段不足或短语/编码为空的行。
     */
    fun parse(content: String): List<CustomPhrase> = content
        .lineSequence()
        .mapNotNull { parseLine(it) }
        .toList()

    private fun parseLine(rawLine: String): CustomPhrase? {
        val line = rawLine.trimEnd()
        if (line.isEmpty()) return null
        if (line.startsWith("#")) return null
        val row = line.split('\t')
        if (row.size < 2) return null
        val text = row[0]
        val code = row[1].trim()
        if (text.isEmpty() || code.isEmpty()) return null
        val weight = row.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return CustomPhrase(text = text, code = code, weight = weight)
    }

    /**
     * 序列化。权重为 0 时省略第三列，保持文件干净。
     */
    fun serialize(phrases: List<CustomPhrase>): String = buildString {
        append(HEADER).append('\n')
        phrases.forEach { phrase ->
            append(phrase.text).append('\t').append(phrase.code)
            if (phrase.weight != 0) append('\t').append(phrase.weight)
            append('\n')
        }
    }

    /**
     * 读取用户目录下的短语表。文件不存在或读取失败时返回空列表。
     */
    fun load(): List<CustomPhrase> {
        val target = runCatching { file }.getOrElse {
            Timber.w(it, "Custom phrase file is not available")
            return emptyList()
        }
        if (!target.isFile) return emptyList()
        return runCatching { parse(target.readText(Charsets.UTF_8)) }
            .onFailure { Timber.w(it, "Failed to read custom phrase file") }
            .getOrDefault(emptyList())
    }

    /**
     * 覆盖写入短语表。空列表时删除文件，让 librime 回到「无自定义短语」的状态。
     */
    fun save(phrases: List<CustomPhrase>): Result<Unit> = runCatching {
        val target = file
        if (phrases.isEmpty()) {
            if (target.exists()) target.delete()
            return@runCatching
        }
        target.parentFile?.mkdirs()
        target.writeText(serialize(phrases), Charsets.UTF_8)
    }.onFailure { Timber.w(it, "Failed to write custom phrase file") }

    /**
     * 追加一条并返回新列表；同编码同短语已存在时覆盖权重。
     */
    fun upsert(
        phrases: List<CustomPhrase>,
        phrase: CustomPhrase,
    ): List<CustomPhrase> {
        val index = phrases.indexOfFirst { it.code == phrase.code && it.text == phrase.text }
        if (index < 0) return phrases + phrase
        return phrases.toMutableList().apply { this[index] = phrase }
    }

    /**
     * 按 (编码, 短语) 删除一条。
     */
    fun remove(
        phrases: List<CustomPhrase>,
        phrase: CustomPhrase,
    ): List<CustomPhrase> = phrases.filterNot { it.code == phrase.code && it.text == phrase.text }
}
