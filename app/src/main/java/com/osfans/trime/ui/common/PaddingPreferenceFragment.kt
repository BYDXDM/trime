/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import com.osfans.trime.util.applyNavBarInsetsBottomPadding

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.applyNavBarInsetsBottomPadding()
        // 设置列表默认用 ?android:attr/colorBackground 的不透明底色，会把
        // activity_main 的插画背景整块盖住。置为透明后背景图才透得出来。
        listView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
}
