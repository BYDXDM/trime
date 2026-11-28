/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.broadcast

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.osfans.trime.data.theme.Theme
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import timber.log.Timber

class EnterKeyDisplayDelegate(override val di: DI) : DIAware {
    private val broadcaster: InputBroadcaster by instance()
    private val theme: Theme by instance()

    companion object {
        const val DEFAULT_LABEL = "Enter"
    }

    enum class Mode {
        ACTION_LABEL_NEVER,
        ACTION_LABEL_ONLY,
        ACTION_LABEL_PREFERRED,
        CUSTOM_PREFERRED,
    }

    val mode: Mode = runCatching { Mode.entries[theme.generalStyle.enterLabelMode] }.getOrDefault(Mode.ACTION_LABEL_NEVER)

    var keyLabel: String = DEFAULT_LABEL
        private set

    private var actionLabel: String = DEFAULT_LABEL

    private fun labelFromEditorInfo(info: EditorInfo): String {
        val actionLabel = info.actionLabel
        // 自定义动作优先：App 用 NO_ENTER_ACTION + actionId/actionLabel 自定义「发送」按钮时，
        // 唯一可靠的文字就是它自己给的 actionLabel。走下面的 NO_ENTER_ACTION 分支只会落回
        // enter_labels.default（本主题是「换行」），正是「发送键被显示成换行」的来源。
        if (info.actionId != EditorInfo.IME_ACTION_UNSPECIFIED && !actionLabel.isNullOrEmpty()) {
            return actionLabel.toString()
        }
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val actionText =
            when (action) {
                EditorInfo.IME_ACTION_DONE -> theme.generalStyle.enterLabel.done
                EditorInfo.IME_ACTION_GO -> theme.generalStyle.enterLabel.go
                EditorInfo.IME_ACTION_NEXT -> theme.generalStyle.enterLabel.next
                EditorInfo.IME_ACTION_PREVIOUS -> theme.generalStyle.enterLabel.pre
                EditorInfo.IME_ACTION_SEARCH -> theme.generalStyle.enterLabel.search
                EditorInfo.IME_ACTION_SEND -> theme.generalStyle.enterLabel.send
                else -> null
            }
        // 有动作就按动作显示，**不再看 IME_FLAG_NO_ENTER_ACTION**：
        // 多行输入框会被框架自动补上这个标志，而 App 自己声明的 SEND/DONE/SEARCH
        // 依然有意义。真机实测 com.xjs.ehviewer 的编辑框 imeOptions=0x44000006
        // （IME_ACTION_DONE | IME_FLAG_NO_ENTER_ACTION | IME_FLAG_NO_EXTRACT_UI），
        // 旧逻辑一律显示成 enter_labels.default（本主题是「换行」），回车键于是
        // 既显示成换行、按下也真插一个换行，发送不出去。
        val preferCustom = mode == Mode.ACTION_LABEL_ONLY || mode == Mode.ACTION_LABEL_PREFERRED
        if (preferCustom && !actionLabel.isNullOrEmpty()) return actionLabel.toString()
        if (actionText != null) return actionText
        if (mode != Mode.ACTION_LABEL_NEVER && !actionLabel.isNullOrEmpty()) return actionLabel.toString()
        // 多行文本框且 App 没声明任何动作：框架会自动补 NO_ENTER_ACTION，落到这里就是
        // 主题的 default（「换行」）。聊天类 App 的输入框正是这种形态（真机实测
        // com.openai.chatgpt 的 inputType=0x2c001、imeOptions=0x42000000、actionId=0），
        // 用户期待的是「发送」。这里统一按「发送」呈现，行为端见 handleReturnKey。
        val multiLineText =
            info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (multiLineText && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return theme.generalStyle.enterLabel.send
        }
        return theme.generalStyle.enterLabel.default
    }

    fun updateLabelOnEditorInfo(info: EditorInfo) {
        val computed = labelFromEditorInfo(info)
        // 诊断：回车键显示成「换行/发送」时，这一行能直接指出是哪个 EditorInfo 字段决定的。
        // release 版也输出（Timber 的 release tree 只丢弃 VERBOSE/DEBUG）。
        Timber.i(
            "enterLabel: inputType=0x${info.inputType.toString(16)} imeOptions=0x${info.imeOptions.toString(16)}" +
                " actionId=${info.actionId} actionLabel=${info.actionLabel} mode=$mode -> $computed",
        )
        actionLabel = computed
        if (keyLabel == actionLabel) return
        keyLabel = actionLabel
        broadcaster.onEnterKeyLabelUpdate(keyLabel)
    }
}
