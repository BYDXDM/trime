/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.window

import android.view.ContextThemeWrapper
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.osfans.trime.R
import com.osfans.trime.ime.broadcast.InputBroadcaster
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class BoardWindowManager(override val di: DI) : DIAware {

    companion object {
        /** 键盘/窗口切换的过渡时长。整块键盘滑入，太短会像"跳"，太长会拖沓。 */
        private const val WINDOW_TRANSITION_DURATION_MS = 180L
    }
    private val context by instance<ContextThemeWrapper>()
    private val broadcaster by instance<InputBroadcaster>()

    private val cachedResidentWindows = mutableMapOf<ResidentWindow.Key, Pair<BoardWindow, View?>>()

    private var currentWindow: BoardWindow? = null
    private var currentView: View? = null

    private fun prepareAnimation(
        exitAnimation: Transition?,
        enterAnimation: Transition?,
        remove: View,
        add: View,
    ) {
        enterAnimation?.addTarget(add)
        exitAnimation?.addTarget(remove)
        TransitionManager.beginDelayedTransition(
            view,
            TransitionSet().apply {
                enterAnimation?.let { addTransition(it) }
                exitAnimation?.let { addTransition(it) }
                // 原本是 100ms 且没设插值器：整块键盘全宽滑入，100ms 太短，
                // 观感是"跳"而不是"衔接"。放到 180ms 并显式给 Material 标准缓动
                // （fast-out-slow-in，与 PathInterpolator 的 (0.4, 0, 0.2, 1) 等价），
                // 起步快、收尾稳，切换才有连续感。想更快/更慢只改这一个常量。
                duration = WINDOW_TRANSITION_DURATION_MS
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            },
        )
    }

    fun <R> cacheResidentWindow(
        window: R,
        createView: Boolean = false,
    ) where R : BoardWindow, R : ResidentWindow {
        if (window.key in cachedResidentWindows) {
            if (cachedResidentWindows[window.key]!!.first === window) {
                Timber.d("Skip adding resident window $window")
            } else {
                throw IllegalStateException("${window.key} is already occupied")
            }
        }
        broadcaster.addReceiver(window)
        val view = if (createView) window.onCreateView() else null
        cachedResidentWindows[window.key] = window to view
    }

    fun attachWindow(windowKey: ResidentWindow.Key) {
        cachedResidentWindows[windowKey]?.let { (window, _) ->
            attachWindow(window)
        } ?: throw IllegalStateException("$windowKey is not a known resident window key")
    }

    fun attachWindow(window: BoardWindow) {
        if (window === currentWindow) {
            Timber.d("Skip attaching $window")
        }
        val newView =
            if (window is ResidentWindow) {
                cachedResidentWindows[window.key]?.second ?: window
                    .onCreateView()
                    .also { cachedResidentWindows[window.key] = window to it }
            } else {
                broadcaster.addReceiver(window)
                window.onCreateView()
            }
        if (currentWindow != null) {
            val oldWindow = currentWindow!!
            val oldView = currentView!!
            prepareAnimation(
                oldWindow.exitAnimation(window),
                window.enterAnimation(oldWindow),
                oldView,
                newView,
            )
            oldWindow.onDetached()
            view.removeView(oldView)
            broadcaster.onWindowDetached(oldWindow)
            Timber.d("Detach $oldWindow")
            if (oldWindow !is ResidentWindow) {
                broadcaster.removeReceiver(oldWindow)
            }
        }
        if (window is ResidentWindow) {
            window.beforeAttached()
        }
        view.apply { add(newView, lParams(matchParent, matchParent)) }
        currentView = newView
        Timber.d("Attach $window")
        window.onAttached()
        currentWindow = window
        broadcaster.onWindowAttached(window)
    }

    /** Restyles the window currently on screen after a scheme switch. */
    fun refreshColors() {
        currentWindow?.refreshColors()
    }

    val view: FrameLayout by lazy { context.frameLayout(R.id.input_window) }

    fun isAttached(window: BoardWindow) = currentWindow === window
}
