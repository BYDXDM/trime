/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private var isWindowShowing = false

    private val cachedLocation = intArrayOf(0, 0)

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        // contentView 未 attach 时它没有 window token，showAtLocation 会抛
        // WindowManager.BadTokenException 直接崩掉整个进程。PreeditDelegate 是在 Rime 的
        // composition 消息回调里调进来的，而 InputView 已经 detach 之后消息仍可能在途 ——
        // 真机实测就是这么崩的：
        //   BadTokenException: Unable to add window -- token null is not valid
        //     at PopupWindow.showAtLocation
        //     at PreeditDelegate.onCompositionUpdate
        // 跳过即可：视图重新 attach 后下一次 update 会再调进来。
        if (!contentView.isAttachedToWindow) return
        isWindowShowing = true
        if (window.isShowing) {
            window.update(x, y, w, h)
        } else {
            window.width = w
            window.height = h
            window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
        }
    }

    fun show() {
        val (x, y) = cachedLocation.also { contentView.getLocationInWindow(it) }
        val width = contentView.width
        val height = contentView.height
        showAt(x, y, width, height)
    }

    fun dismiss() {
        if (isWindowShowing) {
            isWindowShowing = false
            window.dismiss()
        }
    }
}
