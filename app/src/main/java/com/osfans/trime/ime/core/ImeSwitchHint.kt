/*
 * SPDX-FileCopyrightText: 2026 myime
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.content.ComponentName
import android.content.Context

/**
 * 记录「用户切去了别的输入法」，供再次回到本输入法时给出切换引导提示。
 *
 * 为什么不用 onFinishInput/onStartInput：那两个回调只反映**焦点变化**，
 * 用户从微信切到桌面再切回来同样会触发，无法区分「换了输入法」。
 * 真正可靠的信号是系统广播 `ACTION_INPUT_METHOD_CHANGED`：
 * 它只在**当前输入法发生变化**时发出，且携带新输入法的 id。
 *
 * 用户明确要求「天天弹」——他要把本输入法当主力，需要反复被提醒怎么切回来，
 * 所以这里**不做节流**，每次切走再回来都提示。
 */
object ImeSwitchHint {
    /** 切走的时刻；0 表示没有待提示的切换。 */
    @Volatile
    private var switchedAwayAt = 0L

    /** 自身输入法的包名，用来判断广播里的新输入法是不是自己。 */
    private fun selfPackage(context: Context): String = ComponentName(context, TrimeInputMethodService::class.java).packageName

    /**
     * 收到 `ACTION_INPUT_METHOD_CHANGED` 时调用。
     * 新输入法不是自己 → 记一笔「切走了」。
     */
    fun onImeChanged(context: Context, newImeId: String?) {
        if (newImeId.isNullOrEmpty()) return
        if (newImeId.startsWith(selfPackage(context))) return
        switchedAwayAt = System.currentTimeMillis()
    }

    /**
     * 回到本输入法时取一次「是否刚从别的输入法切回来」。
     * 取完即清，保证每次切换只提示一次。
     */
    fun consumeSwitchedAway(): Boolean {
        if (switchedAwayAt == 0L) return false
        switchedAwayAt = 0L
        return true
    }

    /**
     * 广播 action。`InputMethodManager.ACTION_INPUT_METHOD_CHANGED` 与
     * `EXTRA_INPUT_METHOD_ID` 都是 @hide，不在公开 SDK 里，编译期取不到，
     * 只能按 AOSP 定义写字面量（值多年未变）。
     */
    const val ACTION_IME_CHANGED = "android.intent.action.INPUT_METHOD_CHANGED"

    /** 见上，同样是 @hide 常量。 */
    const val EXTRA_IME_ID = "input_method_id"
}
