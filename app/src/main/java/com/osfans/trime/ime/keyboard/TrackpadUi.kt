/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.osfans.trime.data.theme.ThemeScope

/**
 * iOS 式触摸板覆盖层：长按空格拖动光标时盖住整个键盘，
 * 键帽全部隐去，视觉上键盘变成一块纯色触摸板。
 *
 * 不消费触摸事件（普通 View 默认 onDraw/onTouchEvent 返回 false），
 * 手势照常落到下层 [KeyboardView]。
 */
class TrackpadUi(
    context: Context,
    private val scope: ThemeScope,
) : View(context) {
    private val paint = Paint()

    // 用时再取：换肤即换色。
    private val trackpadColor: Int get() = scope.colors.backColor

    init {
        // 不加这句默认 VISIBLE，一唤出键盘就把整个输入法窗口铺成纯色。
        visibility = View.GONE
    }

    fun show() {
        visibility = View.VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = trackpadColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
