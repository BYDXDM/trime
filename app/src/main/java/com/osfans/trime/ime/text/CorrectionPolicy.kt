/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.text

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 英文纠错是否允许作用于当前编辑框。
 *
 * 判据只看 [EditorInfo] 里的两个整型字段，不碰 Android 对象，便于在 JVM 单测里覆盖。
 *
 * 为什么必须单独判一次：密码框会被键盘层强制切到 ASCII 模式
 * （见 KeyboardWindow.onStartInput 的 `.ascii` 分支），而纠错开关原本只跟
 * 「是不是 ASCII 模式」挂钩，于是**密码框里同样会纠错** —— 已上屏的密码被
 * `deleteSurroundingText` 删掉重写，既可能改错内容，也会把密码写进日志。
 *
 * 关闭纠错的输入类型：
 * - 文本/数字密码（含「可见密码」变体）与 Web 密码；
 * - 声明不要建议的字段（`IME_FLAG_NO_SUGGESTIONS` / `TYPE_TEXT_FLAG_NO_SUGGESTIONS`）；
 * - 声明不要个性化学习的字段（`IME_FLAG_NO_PERSONALIZED_LEARNING`，系统据此判断
 *   该字段内容敏感，输入法不应基于它做任何改写）。
 */
internal object CorrectionPolicy {
    fun allowsCorrection(
        inputType: Int,
        imeOptions: Int,
    ): Boolean {
        // 「不要建议」只出现在 inputType 里；EditorInfo 没有对应的 IME_FLAG（已用 javap 核验
        // android-36.1 的 android.jar），所以必须判 inputType 而不是 imeOptions。
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        // 只掩变体位：类别位留着会让 text+password(0x81) 对不上 0x80。
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            -> false

            else -> true
        }
    }
}
