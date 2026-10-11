/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.data.stats.TypedCharCounter
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, icon = icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    /**
     * 把每日字数画成一行迷你柱状图（Unicode 方块字符 ▁▂▃▄▅▆▇█）。
     *
     * 全 0 时返回空串 —— 用户还没输入过时不该显示一条没有意义的平地线。
     * 0 也占一格（画成最矮的 ▁），否则柱子数对不上天数、看不出是哪天缺。
     */
    private fun sparkline(counts: List<Int>): String {
        if (counts.isEmpty() || counts.all { it == 0 }) return ""
        val bars = "▁▂▃▄▅▆▇█"
        val max = counts.max()
        return counts.joinToString("") { c ->
            val level = if (max <= 0) 0 else (c.toFloat() / max * (bars.length - 1)).toInt()
            bars[level.coerceIn(0, bars.length - 1)].toString()
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // 今日输入字数：只读展示，不设 onClick。
            // 计数在 IME 侧由 TrimeInputMethodService.commitText 累加，
            // 每次回到本页重新构建 preference 时取一次最新值即可。
            // 副标题附一条最近 7 天的迷你走势（Unicode 方块字符），一眼看出趋势。
            val ctx = requireContext()
            val week = TypedCharCounter.history(ctx, 7)
            addPreference(
                ctx.getString(R.string.typed_today, TypedCharCounter.todayCount(ctx)),
                summary =
                sparkline(week)
                    .takeIf { it.isNotEmpty() }
                    ?.let { ctx.getString(R.string.typed_week_spark, it, week.sum()) },
                icon = R.drawable.ic_baseline_edit_24,
            )
            addDestinationPreference(
                R.string.schemata,
                R.drawable.ic_round_view_list_24,
                NavigationRoute.SchemaList,
            )
            addDestinationPreference(
                R.string.user_dictionary,
                R.drawable.ic_baseline_book_24,
                NavigationRoute.UserDict,
            )
            addDestinationPreference(
                R.string.profile,
                R.drawable.ic_baseline_snippet_folder_24,
                NavigationRoute.Profile,
            )
            addCategory("") {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.general,
                    R.drawable.ic_baseline_tune_24,
                    NavigationRoute.General,
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    NavigationRoute.VirtualKeyboard,
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    NavigationRoute.CandidatesWindow,
                )
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_color_lens_24,
                    NavigationRoute.Theme,
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard_24,
                    NavigationRoute.Clipboard,
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    NavigationRoute.Advanced,
                )
            }
        }
    }
}
