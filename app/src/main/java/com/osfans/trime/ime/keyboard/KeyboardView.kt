/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.core.view.children
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate
import kotlin.math.abs

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val keyTextSize = theme.generalStyle.keyTextSize
    internal val keyLongTextSize = theme.generalStyle.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.generalStyle.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress
    internal val hookShiftArrow: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftArrow
    internal val hideKeySymbol: Boolean by AppPrefs.defaultInstance().keyboard.hideKeySymbol
    internal val hideKeyHint: Boolean by AppPrefs.defaultInstance().keyboard.hideKeyHint

    init {
        setWillNotDraw(false)
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, key = key, keyboard = keyboard, keyboardView = this, keyboardActionListener = keyboardActionListener).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            keyboard.verticalGap / 2,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val contentHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        // 可滚动面板：自身高度截到视口高度（= keyboardHeight），超出部分靠滚动查看。
        // 不截的话 InputView 里容器高度会被撑高，整个输入法窗口跟着变高。
        val viewport = keyboard.keyboardHeight + paddingTop + paddingBottom
        val measuredHeight = if (keyboard.scrollable) minOf(contentHeight, viewport) else contentHeight

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)

        // 页签行钉在视口底部，真正可滚动的只有它上面那一段
        pinnedViewTop = (measuredHeight - keyboard.pinnedHeight).toFloat()
        val scrollViewport = viewport - keyboard.pinnedHeight
        scrollRange =
            if (keyboard.scrollable) {
                (keyboard.pinnedTop - scrollViewport).coerceAtLeast(0).toFloat()
            } else {
                0f
            }
        applyScroll(scrollOffset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 惯性滑动每帧推进；不依赖 computeScroll 的回调时机
        if (!scroller.isFinished) computeScroll()

        // 没有钉底行、或还没量出视口高度时按普通绘制走，免得把内容整片裁掉
        if (keyboard.pinnedHeight <= 0 || pinnedViewTop <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        // 页签行钉在底部，内容行滚到视口外不能画到页签行上面去
        val childDrawingTime = drawingTime
        val split = pinnedViewTop
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), split)
        drawChildrenExcept(canvas, childDrawingTime, pinned = false)
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, split, width.toFloat(), height.toFloat())
        drawChildrenExcept(canvas, childDrawingTime, pinned = true)
        canvas.restore()
    }

    private fun drawChildrenExcept(canvas: Canvas, drawingTime: Long, pinned: Boolean) {
        val keys = keyboard.keys
        for (i in 0 until childCount) {
            val key = keys.getOrNull(i) ?: continue
            if (isPinned(key) != pinned) continue
            drawChild(canvas, getChildAt(i), drawingTime)
        }
    }

    /** 该键是否属于钉在底部的页签行。 */
    private fun isPinned(key: Key): Boolean = keyboard.pinnedHeight > 0 && key.y >= keyboard.pinnedTop

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        popup.dismissAll()
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 符号面板允许整块键盘拖动：横向翻分组，纵向滚动（仅 [Keyboard.scrollable] 的面板）。
     *
     * 由 [KeyboardWindow.attachKeyboard] 按键盘名设置：只有 SYMBOLS_PAGE_ORDER 里的
     * 面板才开，主键盘/数字盘照旧逐键手势。
     *
     * 为什么在容器层拦截而不是逐键绑 swipe_*：符号键几乎都带 long_click，手指停满
     * longPressTimeout 后 [GestureFrame] 会置 isLongPressed，此后 ACTION_MOVE 里的
     * 滑动判定整段被跳过 —— 慢速拖动永远翻不了页，只有快甩才行。容器层不参与长按
     * 判定，慢拖也照样跟手。
     */
    var symbolsPaging = false

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragging = false

    /** 拖起来之后锁定的主轴：true = 纵向滚动，false = 横向翻组。 */
    private var draggingVertical = false

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    /** 横向拖动超过这个距离松手才翻页，太短视为手抖回弹。 */
    private val pagingCommitPx by lazy { 32f * resources.displayMetrics.density }

    // ---- 纵向滚动 ----

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    /** 当前滚动位移（内容坐标，0 = 顶部）。 */
    private var scrollOffset = 0f
    private var scrollRange = 0f

    /** 页签行在视口里的顶部 y（= 测量高度 - 页签行高）。 */
    private var pinnedViewTop = 0f

    /** 上一帧触点 y；滚动按增量累加，避免 onMeasure 期间基准漂移。 */
    private var dragLastY = 0f

    private fun applyScroll(offset: Float) {
        scrollOffset = offset.coerceIn(0f, scrollRange.toFloat())
        val keys = keyboard.keys
        for (i in 0 until childCount) {
            val key = keys.getOrNull(i) ?: continue
            // 页签行钉在视口底部，不跟着内容滚
            getChildAt(i).translationY =
                if (key.y >= keyboard.pinnedTop && keyboard.pinnedHeight > 0) {
                    pinnedViewTop
                } else {
                    key.y - scrollOffset
                }
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            applyScroll(scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    /** 换面板时回到顶部：新面板的行数和行高都不一样，沿用旧偏移会滚到奇怪的位置。 */
    fun resetScroll() {
        scroller.forceFinished(true)
        scrollOffset = 0f
        applyScroll(0f)
    }

    private fun beginDrag(ev: MotionEvent) {
        dragLastY = ev.y
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!symbolsPaging && !keyboard.scrollable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                dragging = false
                scroller.forceFinished(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - touchStartX
                    val dy = ev.y - touchStartY
                    // 主轴取位移大的那个，且要比另一轴明显大，避免斜着划时来回跳
                    if (abs(dy) >= abs(dx)) {
                        if (keyboard.scrollable && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f) {
                            draggingVertical = true
                            dragging = true
                            beginDrag(ev)
                            return true
                        }
                    } else if (symbolsPaging && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        draggingVertical = false
                        dragging = true
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (draggingVertical) {
                    velocityTracker?.addMovement(ev)
                    // 1:1 跟手：手指上移多少，内容就上移多少
                    val dy = dragLastY - ev.y
                    dragLastY = ev.y
                    applyScroll(scrollOffset + dy)
                } else {
                    translationX = ev.x - touchStartX
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingVertical) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (ev.actionMasked == MotionEvent.ACTION_UP && abs(vy) > FLING_MIN_VELOCITY) {
                        scroller.fling(0, scrollOffset.toInt(), 0, -vy.toInt(), 0, 0, 0, scrollRange.toInt())
                        postInvalidateOnAnimation()
                    }
                } else {
                    val delta = ev.x - touchStartX
                    translationX = 0f
                    if (ev.actionMasked == MotionEvent.ACTION_UP && abs(delta) > pagingCommitPx) {
                        // 手指左移 = 看下一组
                        keyboardActionListener.onKeyboardPaging(if (delta < 0f) 1 else -1)
                    }
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private companion object {
        const val FLING_MIN_VELOCITY = 120f
    }
}
