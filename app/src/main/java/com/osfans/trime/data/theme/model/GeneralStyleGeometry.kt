// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.model

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.resolveUserOverride

/**
 * 候选栏高度，应用了「候选栏高度」偏好之后的值。
 *
 * 主题里的 [GeneralStyle.candidateViewHeight] 是 dp；用户把偏好拖离 0 时覆盖它，
 * 0（滑块最左端，摘要显示「跟随主题」）时原样返回主题值。
 *
 * 为什么放在解码之外：偏好要到运行时才可读，而 [GeneralStyle] 在单元测试里也会被
 * 解码（`ThemeTestSupport.decodeBuiltinTheme`），那里没有 [AppPrefs]。
 */
internal val GeneralStyle.effectiveCandidateViewHeight: Int
    get() =
        resolveUserOverride(
            AppPrefs.defaultInstance().keyboard.candidateBarHeightOverride.getValue(),
            candidateViewHeight,
        )

/**
 * 候选栏整体高度 = 候选区 + 注释区，是工具栏、页签按钮等控件用来对齐的基准。
 */
internal val GeneralStyle.effectiveCandidateBarHeight: Int
    get() = effectiveCandidateViewHeight + commentHeight
