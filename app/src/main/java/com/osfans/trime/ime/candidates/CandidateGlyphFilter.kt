/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto

/**
 * 候选栏里的「空白词条」其实是缺字形：候选文本是补充平面汉字（CJK 扩展 B/C/D，
 * U+20000 以上），而 `candidate_font` 覆盖不到这些码位，[AutoScaleTextView] 自绘时
 * 只能画出 `.notdef` 豆腐块（窄长的方框打叉）。
 *
 * 实测：`luna_pinyin.dict.yaml` 70803 条词条里有 15822 条含补充平面码位；编码 `a` 的
 * 13 条候选里有 3 条是这种，滑到第二屏就能看到 3 个豆腐块。`stroke.dict.yaml` 更严重
 * （111510 行里 68937 行）。词库本身是合法的，删数据会连生僻字一起删掉，所以在显示层过滤。
 */
object CandidateGlyphFilter {
    /**
     * 判据用「码位是否在补充平面」而不是 `Paint.hasGlyph`：`hasGlyph` 只查当前 Typeface
     * 自己的字形表、**不查系统 fallback 链**，而候选字体在用户没放 `han.ttf` 时就是
     * `Typeface.DEFAULT`——那样连「你」都会被判成 false，整栏中文候选全被滤掉。
     * 按码位判断没有这个风险：BMP 汉字一律放行，只拦 U+FFFF 以上（本机必然画成豆腐的那批）。
     */
    fun canRender(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint > 0xFFFF) return false
            i += Character.charCount(codePoint)
        }
        return true
    }

    /**
     * 过滤掉画不出来的候选，同时记下「保留下来的候选在原始列表里的下标」。
     *
     * 下标必须保留：[com.osfans.trime.core.RimeApi.selectCandidate] 收的是 librime 的候选
     * 下标，过滤后列表位置和它不再相同，直接用位置会选中错误的词。
     */
    fun filter(
        candidates: List<CandidateProto>,
        canRender: (String) -> Boolean,
    ): Filtered {
        val kept = ArrayList<CandidateProto>(candidates.size)
        val indices = ArrayList<Int>(candidates.size)
        candidates.forEachIndexed { index, candidate ->
            if (canRender(candidate.text)) {
                kept += candidate
                indices += index
            }
        }
        return Filtered(kept, indices.toIntArray())
    }

    class Filtered(
        val candidates: List<CandidateProto>,
        /** `candidates[i]` 在原始列表里的下标。 */
        val indices: IntArray,
    )
}
