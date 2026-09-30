package com.osfans.trime.ime.text

import timber.log.Timber

/**
 * 英文单词缓冲 —— 把逐字符提交的字母攒成一个完整单词，交给
 * [EnglishCorrector] 判断后决定是否回改。
 *
 * ## 为什么需要缓冲
 *
 * Rime 的 `ascii_composer` 在英文模式下对每个按键执行
 * `engine_->CommitText(string(1, ch))`（见 ascii_composer.cc:179），
 * 即**一个字母一次 commit**。所以 `commitText()` 每次只拿到一个字符，
 * 在那一层无法判断"用户打完了没有"。
 *
 * 这个类负责攒词：字母不断累积，遇到词边界（空格/标点/回车/多字符提交）
 * 就结算，返回需要替换成的内容。
 *
 * ## 回改是怎么实现的
 *
 * 单词已上屏后要纠错，只能"删掉已上屏的 + 重新输入正确的"。
 * 所以 [flush] 返回一个 [Result]：
 * - `backspaces` 要删几个字符（= 已上屏的原词长度）
 * - `replacement` 要重新输入的文本
 *
 * 由调用方执行实际的删除/输入。这样本类不碰 InputConnection，
 * 纯逻辑，好测。
 */
class EnglishInputBuffer {

    /** 已累积的字母。只装纯字母，遇非字母立刻结算。 */
    private val letters = StringBuilder()

    /**
     * 收到一个已提交的字符。返回需要执行的纠错动作，或 null 表示无需动作。
     */
    fun onCommit(text: String): Result? {
        // 多字符提交（非英文模式、或候选词上屏）：先结算掉缓冲
        if (text.length != 1) {
            return flush()
        }

        val ch = text[0]
        return if (ch.isLetter()) {
            letters.append(ch)
            null
        } else {
            // 词边界：结算
            flush()
        }
    }

    /**
     * 结算当前缓冲。返回纠错动作，或 null。
     *
     * 注意：无论纠不纠，缓冲都会被清空 —— 这个单词已经"结束"了。
     */
    fun flush(): Result? {
        if (letters.isEmpty()) return null
        val word = letters.toString()
        letters.setLength(0)

        if (!EnglishCorrector.enabled) return null

        val fixed = EnglishCorrector.correct(word) ?: return null
        Timber.d("English corrector: %s -> %s", word, fixed)

        return Result(
            original = word,
            backspaces = word.length,
            replacement = fixed,
        )
    }

    /** 清空状态（切换输入目标、切模式时调用）。 */
    fun clear() {
        letters.setLength(0)
    }

    /** 当前缓冲的单词长度，调试用。 */
    val pendingLength: Int get() = letters.length

    /**
     * 一次纠错动作。
     *
     * @param original 用户原本打的词（用于回改前校验光标位置）
     * @param backspaces 需要先删除的字符数
     * @param replacement 需要重新输入的文本
     */
    data class Result(
        val original: String,
        val backspaces: Int,
        val replacement: String,
    )
}
