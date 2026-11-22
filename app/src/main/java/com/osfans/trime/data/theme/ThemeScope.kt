/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.graphics.drawable.Drawable
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.ColorUtils
import timber.log.Timber

/**
 * The runtime state of an activated theme: its config plus the currently
 * active color scheme, the pre-compiled [ColorTable] and the typed [colors]
 * view over it.
 *
 * A scope outlives scheme switches — switching schemes only rebuilds the
 * table and [colors] — but not theme switches: activating another theme
 * replaces the scope (see [ColorManager.attachTheme]). Views must therefore
 * re-read colors from [colors] (or through [ColorManager]) every time they
 * bind or draw; values read once at construction go stale on scheme changes.
 */
class ThemeScope internal constructor(
    val theme: Theme,
    private val parseColor: (String) -> Int? = { value ->
        runCatching { ColorUtils.parseColor(value) }.getOrNull()
    },
) {
    /** The currently active color scheme, or null before the first activation. */
    var activeColorScheme: ColorScheme? = null
        private set

    private var colorsValue: ThemeColors? = null

    internal var colorTable: ColorTable? = null
        private set

    /** Typed view over the colors of the active scheme. */
    val colors: ThemeColors
        get() = requireNotNull(colorsValue) { "ThemeScope is not initialized" }

    /**
     * Activates [scheme], rebuilding the color table and [colors].
     *
     * When [landscape] is true the scheme's `_land` keys override their base
     * keys first (see [LandscapeOverlay]), so the table reflects the current
     * orientation.
     */
    internal fun updateScheme(
        scheme: ColorScheme,
        landscape: Boolean = false,
    ) {
        activeColorScheme = scheme
        val own = if (landscape) LandscapeOverlay.apply(scheme.colors) else scheme.colors
        val effective = scheme.copy(colors = inheritUnresolved(own))
        val table = ColorTable.resolve(effective, theme.fallbackColors, parseColor)
        colorTable = table
        colorsValue = ThemeColors(table)
        // Values the theme file cannot resolve are reported once at load, per
        // scheme (see ThemeDiagnostics); this is the demand-driven remainder.
        if (table.unresolvedKeys.isNotEmpty()) {
            Timber.w("Unknown color key: %s", table.unresolvedKeys.joinToString { it.key })
        }
    }

    /**
     * 以主题的 default 配色补齐 [colors] 里解析不到的键。
     *
     * 只写少量键的自建配色（例如「键盘背景」生成器产出的 user_bg）常缺
     * `text_color` / `back_color` 这类链终点；缺了它们 [ThemeColors] 一读就抛
     * IllegalArgumentException，输入法在创建输入视图时直接崩溃。这里只补「本来就
     * 解析不到」的键——能解析的键一个都不动，因此不会改变任何已有外观。
     */
    private fun inheritUnresolved(colors: Map<String, String>): Map<String, String> {
        val base =
            (theme.colorSchemes.find { it.id == DEFAULT_SCHEME } ?: theme.colorSchemes.firstOrNull())
                ?.colors
                ?: return colors
        val missing = ColorKey.entries.filter { ColorTable.resolveRaw(it.key, colors, theme.fallbackColors) == null }
        if (missing.isEmpty()) return colors
        val merged = LinkedHashMap(colors)
        for (key in missing) {
            val value = base[key.key]
            if (!value.isNullOrEmpty()) merged[key.key] = value
        }
        return merged
    }

    /** Resolves a color key through this scope; theme-only keys resolve via the fallback chain. */
    fun color(key: String): Int = ColorManager.resolveColor(this, key)

    /** Resolves a drawable key (a color or an image asset) through this scope. */
    fun drawable(key: String): Drawable? = ColorManager.resolveDrawable(this, key)

    /**
     * Builds a decorated drawable (rounded corners, optional border) from the
     * color or image asset resolved for [colorKey] in this scope.
     */
    fun decorDrawable(
        colorKey: String,
        borderColorKey: String? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = ColorManager.resolveDecorDrawable(this, colorKey, borderColorKey, borderPx, cornerRadius, alpha)
}

private const val DEFAULT_SCHEME = "default"
