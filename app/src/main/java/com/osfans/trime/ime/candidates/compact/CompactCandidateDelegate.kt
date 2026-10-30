/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.ContextThemeWrapper
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.InputView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

class CompactCandidateDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()

    private val theme: Theme
        get() = scope.theme

    private val fillStyle by AppPrefs.defaultInstance().keyboard.horizontalCandidateMode

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(childCount)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                // 栏里少了几条被过滤掉的缺字形候选，这些不会显示，算「已全显示」时要把它们补回来。
                // total 为 -1 表示 librime 的菜单还没数完（bulk 被截断），此时必须算「还有更多」，
                // 否则展开按钮恒被判空隐藏——用户报「无法展开其余候选词」的根因。
                (adapter.total >= 0 && childCount + adapter.filteredOut >= adapter.total),
        )
    }

    /** Restyles the compact list after a scheme switch; visible rows re-bind. */
    fun refreshColors() {
        separatorDrawable.paint.color = scope.colors.candidateSeparatorColor
        adapter.notifyDataSetChanged()
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(scope).apply {
            setOnItemClickListener { _, _, position ->
                // 候选栏过滤掉了缺字形的条目，显示位置不再等于 librime 下标。
                // 这里用 apply 的接收者（适配器本身），不能写 `adapter.`——那会递归引用本属性。
                val index = originalIndex(position)
                rime.launchOnReady { it.selectCandidate(index, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                val index = originalIndex(position)
                val text = items.getOrNull(position)?.text ?: return@setOnItemLongClickListener false
                inputView.showCandidateActionMenu(index, text, view, global = true)
                true
            }
        }
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            init {
                // ★ 单行 + 横向滚动。
                //   FlexWrap.WRAP 会在候选多于列数时换到第二行，而候选栏高度固定成一行 →
                //   第二行整行被裁掉；又因为 canScrollHorizontally() 原本被写死 false，
                //   用户永远够不着那些候选。NOWRAP 后多出来的候选改为横向溢出。
                //
                // ⚠ NOWRAP 下绝不能允许 flexShrink > 0：
                //   FlexboxLayoutManager 在单行模式下按「所有子项的最小宽度之和」是否
                //   超过容器来决定是否把 items 们 shrink。候选项的最小宽度等于
                //   CandidateItemUi.root 的 minimumWidth（40dp = 120px），于是
                //   6 个候选 × 120px = 720px < 996px → 判定「放得下」，不 shrink，
                //   但随后 Flexbox 仍按 flexBasis（= 测量宽度）之和去分配空间，
                //   flexGrow 又是 0 → 每个 item 都被压到 40dp。
                //   而 40dp 的格子装不下候选字号 → AutoScaleTextView(Proportional)
                //   的缩放系数 min(格宽/字宽, 格高/字高) 没有下限，一路缩到近 0，
                //   于是画布上什么都没画。**候选栏因此整行空白。**
                //   修法是两端一起改：这里放开单行，Adapter 里 flexShrink = 0，
                //   CandidateItemUi 里 scaleMode = Mode.None（不再缩放文字）。
                flexWrap = FlexWrap.NOWRAP
            }

            // 放开横向滚动（用户要求「右滑查看更多候选词」）；
            // 纵向仍禁止 —— NOWRAP 下不会有纵向溢出，留着只会让 bar 上下晃。
            override fun canScrollHorizontally(): Boolean = true

            override fun canScrollVertically(): Boolean = false

            // ★ 自己接管横向滚动，不走父类的 handleScrollingMainOrientation()。
            //   父类那条路径最终落到 fill()，而 fill() 的
            //     while ((remainingSpace > 0 || mInfinite) && hasMore(...))
            //   在 NOWRAP 下只可能转一圈（mFlexLines 里就一行），每圈扣的是
            //   flexLine.getCrossSize()（行高）而不是主轴溢出量，所以
            //   fill() 返回的 consumed 恒在「行高」量级，位移被钳死。
            //   实测：第一次右滑刚好走 1066px，之后无论滑多少次、滑多快，
            //   候选栏像素逐列完全一致 —— 第 13 个之后的候选永远够不着。
            //   候选栏本来就在一次 layout 里把整行候选项全部 measure+addView 完，
            //   所以这里直接平移子项，边界按「内容总宽 − 视口宽」裁剪即可。
            override fun scrollHorizontallyBy(
                dx: Int,
                recycler: RecyclerView.Recycler?,
                state: RecyclerView.State?,
            ): Int {
                if (dx == 0 || childCount == 0) return 0
                val viewport = width - paddingLeft - paddingRight
                val maxScroll = (contentExtent() - viewport).coerceAtLeast(0)
                if (maxScroll <= 0) return 0
                val current = (paddingLeft - decoratedLeftEdge()).coerceAtLeast(0)
                val target = (current + dx).coerceIn(0, maxScroll)
                val real = target - current
                if (real == 0) return 0
                offsetChildrenHorizontal(-real)
                return real
            }

            /** 已排布子项占用的主轴总宽度（含分隔线 ItemDecoration）。 */
            private fun contentExtent(): Int {
                var minLeft = Int.MAX_VALUE
                var maxRight = Int.MIN_VALUE
                for (i in 0 until childCount) {
                    val child = getChildAt(i) ?: continue
                    minLeft = minOf(minLeft, getDecoratedLeft(child))
                    maxRight = maxOf(maxRight, getDecoratedRight(child))
                }
                return if (maxRight > minLeft) maxRight - minLeft else 0
            }

            /** 最左子项的装饰后左边界；未滚动时应等于 paddingLeft。 */
            private fun decoratedLeftEdge(): Int {
                var minLeft = Int.MAX_VALUE
                for (i in 0 until childCount) {
                    val child = getChildAt(i) ?: continue
                    minLeft = minOf(minLeft, getDecoratedLeft(child))
                }
                return if (minLeft == Int.MAX_VALUE) paddingLeft else minLeft
            }

            // 滚动条虽然已关掉（isHorizontalScrollBarEnabled = false），但这两个值
            // 还被 RecyclerView 用来判断边缘发光/无障碍滚动，一并给准数。
            override fun computeHorizontalScrollRange(state: RecyclerView.State): Int = contentExtent()

            override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int = (paddingLeft - decoratedLeftEdge()).coerceAtLeast(0)

            override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int = width - paddingLeft - paddingRight

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshUnrolled(cnt)
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = scope.colors.candidateSeparatorColor
        }
    }

    val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == CompactCandidateMode.AUTO_FILL) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - separatorDrawable.intrinsicWidth
                }
            }
        }
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            // ★ 关掉横向滚动条。放开 canScrollHorizontally() 之后，候选槽会默认在
            //   底部画一条灰色滚动条（实测 y≈1580、x=9..395、rgb(144,144,156)）——
            //   它贴着候选栏下沿，看着就是一道「边框」。滑动照旧可用，只是不再画这条。
            isHorizontalScrollBarEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                defaultFocusHighlightEnabled = false
            }
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    /** 最后一次候选快照。黑名单改动后要拿它重新过一遍过滤，不必等下一次按键。 */
    private var lastBulk: Candidates.Bulk? = null

    /** 黑名单变动后重刷候选栏。 */
    fun refreshCandidates() {
        lastBulk?.let { onCandidateListUpdate(it) }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        lastBulk = data
        val (total, highlighted, candidates) = data

        val maxSpanCount = maxSpanCountPref.getValue()

        adapter.updateCandidates(candidates, total, highlighted)
        // 填充策略要按「实际能显示的条数」算：缺字形被过滤掉的候选不占格子。
        val visibleCount = adapter.visibleCount

        when (fillStyle) {
            CompactCandidateMode.NEVER_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }

            CompactCandidateMode.AUTO_FILL -> {
                layoutMinWidth = view.width / maxSpanCount - separatorDrawable.intrinsicWidth
                layoutFlexGrow = if (visibleCount < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = visibleCount < maxSpanCount
                secondLayoutPassDone = false
            }

            CompactCandidateMode.ALWAYS_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }

        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (visibleCount == 0) {
            refreshUnrolled(0)
        }
    }
}
