/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val rime get() = RimeDaemon.getFirstSessionOrNull()!!

    private val deletedTextBuffer = ArrayDeque<String>()

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val shiftPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shiftPath = Path()

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(KeyActionManager.getAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT -> {
                        // 键上没有左右滑动绑定时，交给键盘级翻页（符号面板左右滑换页）
                        if (key.hasAction(behavior)) {
                            key.getAction(behavior)?.let { processKeyAction(it, behavior) }
                        } else {
                            val direction = if (behavior == KeyBehavior.SWIPE_LEFT) 1 else -1
                            keyboardActionListener.onKeyboardPaging(direction)
                        }
                    }

                    // 上下滑不再兜底翻分组：符号面板改成整块纵向滚动（KeyboardView 负责），
                    // 没被容器拦下的竖向手势直接忽略，避免「想滚一点却整组跳走」。
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(KeyActionManager.getAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(KeyActionManager.getAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (action.isModifierKey) {
            if (action.modifierKeyOnMask == KeyEvent.META_SHIFT_ON) {
                // Shift 走三态循环：默认 → 单次大写 → 大写锁定 → 默认
                keyboard.cycleShiftState()
            } else {
                keyboard.clickModifierKey(
                    action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),
                    action.modifierKeyOnMask,
                )
            }
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        if (key.isShift) {
            // Shift 键自带三态字形（空心/实心/实心+下横线），主题里的静态 label 与角标不再绘制
            drawShiftGlyph(canvas)
        } else {
            val label = key.getLabel().let {
                if (it == "enter_labels") keyboardView.labelEnter else it
            }

            if (label.isNotEmpty()) {
                drawLabel(canvas, label)
            }

            val symbol = key.symbolLabel
            if (symbol.isNotEmpty()) {
                drawSymbol(canvas, symbol)
            }
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return

        if (bg is GradientDrawable) {
            k.roundCorner.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        bg.draw(canvas)
    }

    /**
     * Shift 键的三态字形（对照搜狗）：
     *  - 默认：空心描边箭头
     *  - 单次大写：实心箭头
     *  - 大写锁定：实心箭头 + 箭头下方一条短横线
     *
     * 状态来源与 [Keyboard.isShifted] 一致，无需额外字段：
     * [Keyboard.mShiftKey] 的 isOn 即锁定态，修饰位 META_SHIFT_ON 即单次态。
     */
    private fun drawShiftGlyph(canvas: Canvas) {
        val capsLock = keyboard.mShiftKey?.isOn == true
        val oneShot = !capsLock && keyboard.modifier.hasFlag(KeyEvent.META_SHIFT_ON)

        val color = key.getTextColor()
        val glyphHeight = (height - paddingTop - paddingBottom) * SHIFT_GLYPH_HEIGHT_RATIO
        val glyphWidth = glyphHeight * SHIFT_GLYPH_ASPECT
        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
        // 锁定态要为下方横线让出位置，整体略微上移
        val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop -
            if (capsLock) sp(SHIFT_LOCK_LINE_GAP) / 2f else 0f

        shiftPath.reset()
        // 箭头：顶部尖端 → 两侧翼 → 两侧竖边 → 底部
        val tipY = centerY - glyphHeight / 2f
        val shoulderY = centerY - glyphHeight / 2f + glyphHeight * SHIFT_HEAD_RATIO
        val stemHalf = glyphWidth * SHIFT_STEM_HALF_RATIO
        val bottomY = centerY + glyphHeight / 2f
        shiftPath.moveTo(centerX, tipY)
        shiftPath.lineTo(centerX + glyphWidth / 2f, shoulderY)
        shiftPath.lineTo(centerX + stemHalf, shoulderY)
        shiftPath.lineTo(centerX + stemHalf, bottomY)
        shiftPath.lineTo(centerX - stemHalf, bottomY)
        shiftPath.lineTo(centerX - stemHalf, shoulderY)
        shiftPath.lineTo(centerX - glyphWidth / 2f, shoulderY)
        shiftPath.close()

        shiftPaint.apply {
            this.color = color
            style = if (capsLock || oneShot) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = sp(SHIFT_STROKE_WIDTH)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawPath(shiftPath, shiftPaint)

        if (capsLock) {
            val lineHalf = glyphWidth * SHIFT_LOCK_LINE_HALF_RATIO
            val lineWidth = sp(SHIFT_LOCK_LINE_WIDTH)
            // 线的中心要抬到「箭头底 + 线宽一半 + 间隙」，否则 Paint 的 stroke 会吃掉一半间隙
            val lineY = bottomY + lineWidth / 2f + sp(SHIFT_LOCK_LINE_GAP)
            canvas.drawLine(
                centerX - lineHalf,
                lineY,
                centerX + lineHalf,
                lineY,
                shiftPaint.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = lineWidth
                },
            )
        }
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize)

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = FontManager.getTypeface("key_font")
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX), centerY + adjustmentY + sp(key.keyTextOffsetY), textPaint)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = FontManager.getTypeface("symbol_font")
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }

    private companion object {
        /** Shift 字形高度占键高的比例 */
        const val SHIFT_GLYPH_HEIGHT_RATIO = 0.34f

        /** 箭头宽度 / 高度（对齐参考图 1.08） */
        const val SHIFT_GLYPH_ASPECT = 1.08f

        /** 箭头肩线位置（自箭头顶部起，占箭头高度的比例，参考图约 0.6） */
        const val SHIFT_HEAD_RATIO = 0.6f

        /** 箭杆半宽 / 箭头宽度（参考图箭杆宽 = 箭头宽的一半） */
        const val SHIFT_STEM_HALF_RATIO = 0.25f

        /** 空心态描边宽度（dp） */
        const val SHIFT_STROKE_WIDTH = 3.0f

        /** 锁定横线与箭头底部的间距（dp） */
        const val SHIFT_LOCK_LINE_GAP = 1.5f

        /** 锁定横线粗细（dp），比空心态描边细一档，避免压过箭头 */
        const val SHIFT_LOCK_LINE_WIDTH = 2.0f

        /** 锁定横线半宽 / 箭头宽度（扣掉圆头线帽后与箭杆等宽） */
        const val SHIFT_LOCK_LINE_HALF_RATIO = 0.22f
    }
}
