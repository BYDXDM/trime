/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.monitorCursorAnchor
import splitties.dimensions.dp
import kotlin.math.roundToInt

/**
 * iOS 式光标放大镜：长按空格拖动光标时，在**正文光标处**浮一个放大气泡，
 * 里面是光标前后的文字（放大显示）加一根竖线，气泡跟着光标走。
 *
 * 与旧版「键盘上方的横条」的区别：气泡贴在插入点上方（空间不足时翻到下方），
 * 视觉上就在正文里，不在键盘上。
 *
 * 挂在 [com.osfans.trime.ime.core.InputView] 根布局，默认 GONE 且 alpha=0，
 * 只在拖动光标期间可见，进出场各有一段淡入淡出。
 */
class CursorLoupeUi(
    context: Context,
    private val scope: ThemeScope,
    private val service: TrimeInputMethodService,
) : View(context) {
    companion object {
        /** 光标两侧各取多少字符。 */
        private const val CONTEXT_CHARS = 16

        /** 放大镜竖线宽（px）。 */
        private const val CARET_STROKE = 6f

        /** 淡入淡出时长（ms）。 */
        private const val FADE_MS = 130L
    }

    // 用时再取：换肤即换色。
    // 底色必须不透明：popup_back_color 缺省回退到 key_back_color，本主题那里是
    // 0x00000000（按键无底框），气泡会变成透明、底下的正文直接透出来。
    private val backColor: Int get() = scope.colors.backColor
    private val foreColor: Int get() = scope.colors.popupTextColor
    private val accentColor: Int get() = scope.colors.hilitedTextColor

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f).toFloat()
        }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(26f)
        }
    private val ellipsizePaint = TextPaint(textPaint)
    private val bubbleRect = RectF()

    /** 气泡宽度：只容得下光标前后几个字，像放大镜而不是第二根横条。 */
    private val bubbleWidth = dp(168)

    private var beforeText = ""
    private var afterText = ""

    /** 插入点在本 View 坐标系里的位置；[anchorValid] 为 false 时气泡不显示。 */
    private var anchorX = 0f
    private var anchorTop = 0f
    private var anchorBottom = 0f
    private var anchorValid = false

    /** 处于拖动模式（show 之后、hide 之前）；锚点晚到时据此补显示。 */
    private var wanted = false

    init {
        // 不加这句默认 VISIBLE，一唤出键盘就会在左上角画出一个气泡。
        visibility = View.GONE
        alpha = 0f
    }

    /** 宿主上报光标位置（本 View 坐标系）。 */
    fun updateAnchor(x: Float, top: Float, bottom: Float) {
        anchorX = x
        anchorTop = top
        anchorBottom = bottom
        anchorValid = true
        if (!wanted) return
        // 拖动刚开始时锚点可能还没送到，送到这一帧再淡入。
        if (visibility == View.VISIBLE) requestLayoutPosition() else appear()
    }

    fun invalidateAnchor() {
        anchorValid = false
    }

    /** 进入拖动模式：读一次正文，有锚点就淡入。 */
    fun show() {
        wanted = true
        // 候选浮窗没开时输入法并没有在监控 CursorAnchorInfo，
        // 这里补开一次，否则气泡永远等不到锚点。
        service.currentInputConnection?.monitorCursorAnchor(true)
        refreshText()
        if (anchorValid) appear()
    }

    private fun appear() {
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
        }
        animate().cancel()
        animate().alpha(1f).setDuration(FADE_MS).start()
        requestLayoutPosition()
    }

    /** 光标动了：重读正文并重新定位，不重放淡入。 */
    fun refresh() {
        if (visibility != View.VISIBLE) return
        refreshText()
        requestLayoutPosition()
    }

    fun hide() {
        wanted = false
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction { visibility = View.GONE }
            .start()
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    private fun refreshText(): Boolean {
        val ic = service.currentInputConnection ?: return false
        beforeText = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
        afterText = ic.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
        return true
    }

    /** 把气泡挪到光标处：默认在上方，顶部放不下就翻到下方，左右夹在父容器内。 */
    private fun requestLayoutPosition() {
        // 锚点还没到：先不摆，等 updateAnchor 再显示。
        if (!anchorValid) return
        val w = width.toFloat()
        val h = height.toFloat()
        val parentView = parent as? View ?: return
        val parentW = parentView.width.toFloat()
        if (w <= 0f || h <= 0f || parentW <= 0f) {
            // 还没量过：下一帧再摆。
            post { requestLayoutPosition() }
            return
        }
        val gap = dp(10f).toFloat()
        val margin = dp(6f).toFloat()
        var x = anchorX - w / 2f
        x = x.coerceIn(margin, (parentW - w - margin).coerceAtLeast(margin))
        var y = anchorTop - h - gap
        if (y < margin) {
            // 上方空间不足：翻到光标下方。
            y = anchorBottom + gap
        }
        translationX = x
        translationY = y
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        setMeasuredDimension(bubbleWidth, (textHeight + dp(12f) * 2).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != View.VISIBLE) return

        val w = width.toFloat()
        val h = height.toFloat()
        val radius = dp(14f).toFloat()
        bubbleRect.set(0f, 0f, w, h)

        bubblePaint.color = backColor
        canvas.drawRoundRect(bubbleRect, radius, radius, bubblePaint)
        borderPaint.color = accentColor
        canvas.drawRoundRect(bubbleRect, radius, radius, borderPaint)

        caretPaint.color = accentColor
        textPaint.color = foreColor

        val pad = dp(12f).toFloat()
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val baseline = h / 2f - textHeight / 2f - textPaint.fontMetrics.ascent

        val half = w / 2f
        var beforeShown = beforeText
        while (beforeShown.isNotEmpty() && textPaint.measureText(beforeShown) > half - pad * 2) {
            beforeShown = beforeShown.substring(1)
        }
        val afterLimit = w - half - pad * 2
        var afterShown = afterText
        if (textPaint.measureText(afterShown) > afterLimit) {
            afterShown = TextUtils.ellipsize(afterText, ellipsizePaint, afterLimit, TextUtils.TruncateAt.END).toString()
        }
        val beforeWidth = textPaint.measureText(beforeShown)

        canvas.drawText(beforeShown, half - CARET_STROKE / 2 - pad - beforeWidth, baseline, textPaint)
        val caretX = half - CARET_STROKE / 2
        val caretTop = h / 2f - textHeight / 2f - dp(2f)
        val caretBottom = h / 2f + textHeight / 2f + dp(2f)
        canvas.drawRect(caretX, caretTop, caretX + CARET_STROKE, caretBottom, caretPaint)
        canvas.drawText(afterShown, caretX + CARET_STROKE / 2 + pad, baseline, textPaint)
    }
}
