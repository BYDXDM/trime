/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

/**
 * Landscape overrides for a color scheme.
 *
 * A scheme may carry a landscape variant of any key by suffixing the key with
 * [SUFFIX]: `keyboard_back_color_land` overrides `keyboard_back_color` while
 * the screen is in landscape orientation. One scheme therefore describes both
 * orientations, which keeps a scheme switch (and the user's manual pick) out of
 * the rotation path.
 */
internal object LandscapeOverlay {
    /** Suffix that marks a landscape-only key. */
    const val SUFFIX = "_land"

    /**
     * Returns [colors] as they should read in landscape: every `xxx_land` entry
     * overrides `xxx`.
     *
     * Overrides are per key, so a scheme that only sets
     * `keyboard_back_color_land` keeps every other key at its base value.
     * The `_land` entries themselves are dropped from the result.
     */
    fun apply(colors: Map<String, String>): Map<String, String> {
        if (colors.none { it.key.endsWith(SUFFIX) }) return colors
        val merged = LinkedHashMap<String, String>(colors.size)
        colors.forEach { (key, value) ->
            if (!key.endsWith(SUFFIX)) merged[key] = value
        }
        colors.forEach { (key, value) ->
            if (!key.endsWith(SUFFIX)) return@forEach
            val base = key.removeSuffix(SUFFIX)
            if (base.isNotEmpty()) merged[base] = value
        }
        return merged
    }
}
