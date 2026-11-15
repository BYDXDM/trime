/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto

/**
 * 候选显示层采用保守的码位策略：BMP 文本放行，补充平面码位过滤。
 * 这不是设备字体覆盖检测；它只保证紧凑栏和展开分页使用同一稳定投影规则。
 */
object CandidateGlyphFilter {
    /**
     * 保留现有保守码位政策：文本含 U+FFFF 以上码位即过滤，其他文本放行。
     * 此方法不检查 Typeface、系统 fallback 或实际字形，不能保证设备上的字体覆盖。
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
     * 按保守码位策略过滤候选，同时记下「保留下来的候选在原始列表里的下标」。
     *
     * 下标必须保留：[com.osfans.trime.core.RimeApi.selectCandidate] 收的是 librime 的候选
     * 下标，过滤后列表位置和它不再相同，直接用位置会选中错误的词。
     *
     * 按同一稳定规则生成显示投影；[Filtered.indices] 始终指向传入列表的原始下标。
     * 黑名单和字形过滤都只删除条目，不改变其余候选的相对顺序。
     */
    fun filter(
        candidates: List<CandidateProto>,
        canRender: (String) -> Boolean = ::canRender,
        blacklist: Set<String> = emptySet(),
    ): Filtered {
        val kept = ArrayList<CandidateProto>(candidates.size)
        val indices = ArrayList<Int>(candidates.size)
        candidates.forEachIndexed { index, candidate ->
            if (candidate.text !in blacklist && canRender(candidate.text)) {
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
