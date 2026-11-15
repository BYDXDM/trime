// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.content.res.Configuration
import android.content.res.Resources

fun Configuration.isNightMode() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun Configuration.isLandscape() = orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 屏幕当前是不是横着的，按像素宽高比判断。
 *
 * 不用 [Configuration.isLandscape]：模拟器与分屏场景下 display 会转成 1600x900，
 * 而进程拿到的 `orientation` 仍是 portrait，于是「屏幕明明横着」被判成竖屏，
 * 横屏壁纸永远不生效（2026-11-15 MuMu 上实测）。宽高比才是用户眼里的方向。
 */
fun Resources.isLandscapeBySize(): Boolean = displayMetrics.widthPixels > displayMetrics.heightPixels
