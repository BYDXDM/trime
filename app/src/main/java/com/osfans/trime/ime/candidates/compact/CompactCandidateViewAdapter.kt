/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.userdict.BlacklistStore
import com.osfans.trime.ime.candidates.CandidateGlyphFilter
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

open class CompactCandidateViewAdapter(
    val scope: ThemeScope,
) : BaseQuickAdapter<CandidateProto, CandidateViewHolder>() {
    init {
        setHasStableIds(true)
    }

    /**
     * 用位置当 id，而不是 `CandidateProto.hashCode()`：同一次候选里出现两条一模一样的
     * 候选（text/comment/label 全等）时 hash 相同，RecyclerView 会把它们当成同一个 item
     * 丢掉其中一个，那一格就空了。
     */
    override fun getItemId(position: Int): Long = position.toLong()

    var total: Int = -1
        private set

    /** 过滤掉画不出来的候选之后，实际显示的条数。同步可得，不依赖 submitList 的异步 diff。 */
    var visibleCount: Int = 0
        private set

    /** 本轮被过滤掉的条数。展开按钮据此判断「栏里是不是已经全显示了」。 */
    var filteredOut: Int = 0
        private set

    var highlightedIdx: Int = -1
        private set

    /** 过滤后的第 i 项在 librime 原始候选列表里的下标。 */
    private var originalIndices: IntArray = IntArray(0)

    fun originalIndex(position: Int): Int = originalIndices.getOrNull(position) ?: position

    var layoutMinWidth: Int = 0
        private set

    var layoutFlexGrow: Float = 0f
        private set

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    fun updateCandidates(
        data: Array<CandidateProto>,
        total: Int,
        highlightedIndex: Int,
    ) {
        val filtered = CandidateGlyphFilter.filter(data.toList(), CandidateGlyphFilter::canRender)
        // 黑名单串在字形过滤之后：两层都只减少条目，下标映射必须一路带着走，
        // 否则上屏/删除会作用到别的候选上。
        val blacklist = BlacklistStore.words()
        val kept = ArrayList<CandidateProto>(filtered.candidates.size)
        val keptIndices = ArrayList<Int>(filtered.candidates.size)
        filtered.candidates.forEachIndexed { i, candidate ->
            if (candidate.text in blacklist) return@forEachIndexed
            kept += candidate
            keptIndices += filtered.indices[i]
        }
        originalIndices = keptIndices.toIntArray()
        visibleCount = kept.size
        filteredOut = data.size - kept.size
        // total 保持 librime 的原始条数：展开面板的 CandidatesPagingSource 按下标取页，
        // 把它改成过滤后的条数会让末尾的候选永远翻不到。
        this.total = total
        this.highlightedIdx = highlightedIndex
        super.submitList(kept, null)
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder {
        val ui = CandidateItemUi(context, scope)
        ui.root.apply {
            minimumWidth = dp(40)
            layoutParams =
                FlexboxLayoutManager.LayoutParams(wrapContent, matchParent).apply {
                    // ★ Never let Flexbox shrink a candidate below its text width.
                    //   With flexShrink > 0 the item gets squeezed (in NOWRAP mode down to
                    //   its minimumWidth alone) and the glyphs no longer fit the cell —
                    //   which was one half of the "candidate row renders completely blank"
                    //   defect. Overflow is fine: the layout manager scrolls horizontally.
                    //   Set here on the LayoutParams instance so onBindViewHolder's
                    //   updateLayoutParams{minWidth; flexGrow} cannot reset it.
                    flexShrink = 0f
                }
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
        item: CandidateProto?,
    ) {
        item ?: return
        val originalIndex = originalIndex(position)
        val isHighlighted = originalIndex == highlightedIdx
        holder.ui.update(item, isHighlighted)
        holder.text = item.text
        holder.comment = item.comment
        holder.idx = originalIndex
        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
            minWidth = this@CompactCandidateViewAdapter.layoutMinWidth
            flexGrow = this@CompactCandidateViewAdapter.layoutFlexGrow
        }
    }
}
