// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toDrawable
import androidx.core.math.MathUtils
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.NinePatchBitmapFactory
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.isNightMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Global entry point for the colors and drawables of the active theme.
 *
 * The activation state itself lives in the current [ThemeScope], which
 * [attachTheme] replaces on every theme switch; scheme switches update the
 * same scope in place. String-keyed lookups below ([getColor], [getDrawable])
 * resolve through that scope so that keys only a theme can define (per-key
 * keyboard colors, image-valued entries) keep working, while [activeColorScheme]
 * and the scope's [ThemeScope.colors] expose the typed view for UI code.
 */
object ColorManager {
    private var scope: ThemeScope? = null
    private val prefs = ThemeManager.prefs

    private var isNightMode = false

    val activeColorScheme: ColorScheme
        get() = requireNotNull(requireScope().activeColorScheme) { "ColorManager is not initialized" }

    private var bitmapCache: LruCache<String, Bitmap>? = null

    private var generation = 0L

    /**
     * Bumped whenever the active scheme changes. Scheme-dependent caches
     * (e.g. per-key colors in Key) re-resolve when it moves.
     */
    val colorGeneration: Long
        get() = generation

    fun interface OnColorChangeListener {
        fun onColorChange(theme: Theme)
    }

    private val onChangeListeners = WeakHashSet<OnColorChangeListener>()

    fun addOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onColorChange(requireScope().theme) }
    }

    fun init(configuration: Configuration) {
        isNightMode = configuration.isNightMode()
        activateScheme(notify = false)

        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val cacheSize = maxMemory / 8
        bitmapCache =
            object : LruCache<String, Bitmap>(cacheSize.toInt()) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.byteCount / 1024
            }
    }

    fun onSystemNightModeChange(isNight: Boolean) {
        isNightMode = isNight
        activateScheme(notify = true)
    }

    /** The current theme scope, or null before the first theme is attached. */
    fun currentScope(): ThemeScope? = scope

    /**
     * Attaches a theme, replacing the current scope. Theme switches notify
     * through ThemeManager, so no color listener fires here.
     */
    fun attachTheme(theme: Theme) {
        bitmapCache?.evictAll()
        scope = ThemeScope(theme)
        activateScheme(notify = false)
    }

    fun setColorScheme(scheme: ColorScheme) {
        activateScheme(scheme, notify = true)
        prefs.normalModeColor.setValue(scheme.id)
    }

    private fun requireScope(): ThemeScope = requireNotNull(scope) { "ColorManager is not initialized" }

    private fun resolveActiveScheme(theme: Theme): ColorScheme = ColorSchemeResolver.resolve(
        schemes = theme.colorSchemes,
        selectedSchemeId = prefs.normalModeColor.getValue(),
        followSystemDayNight = prefs.followSystemDayNight.getValue(),
        isNightMode = isNightMode,
    )

    /**
     * Re-resolves the active scheme from the prefs and current theme, or
     * activates the given one. Listener notification fires only when the
     * scheme actually changed.
     */
    private fun activateScheme(
        scheme: ColorScheme? = null,
        notify: Boolean,
    ) {
        val activeScope = scope ?: return
        val target = scheme ?: resolveActiveScheme(activeScope.theme)
        if (activeScope.activeColorScheme == target) return
        activeScope.updateScheme(target)
        generation++
        if (notify) fireChange()
    }

    private fun backgroundFolder(scope: ThemeScope) = scope.theme.generalStyle.backgroundFolder

    /**
     * Resolves a color key against the given scope. Exposed so UI code can
     * look up keys only a theme can define through an injected scope.
     */
    @ColorInt
    internal fun resolveColor(
        scope: ThemeScope,
        key: String,
    ): Int {
        val tableEntry = ColorKey.from(key)?.let { scope.colorTable?.get(it) }
        if (tableEntry is ColorTable.Value.Color) return tableEntry.argb
        // Keys defined only by a theme resolve through the same chain rules.
        val scheme = requireNotNull(scope.activeColorScheme)
        val raw = ColorTable.resolveRaw(key, scheme.colors, scope.theme.fallbackColors)
        return try {
            if (raw == null) throw IllegalArgumentException("$key not found")
            ColorUtils.parseColor(raw)
        } catch (_: IllegalArgumentException) {
            ColorUtils.parseColor(key)
        }
    }

    /** Resolves a drawable key (color or image asset) against the given scope. */
    internal fun resolveDrawable(
        scope: ThemeScope,
        key: String,
    ): Drawable? {
        val tableEntry = ColorKey.from(key)?.let { scope.colorTable?.get(it) }
        if (tableEntry != null) {
            return when (tableEntry) {
                is ColorTable.Value.Color -> GradientDrawable().apply { setColor(tableEntry.argb) }
                is ColorTable.Value.Image -> imageDrawable(scope, tableEntry.path)
                ColorTable.Value.None -> parseDrawable(scope, key)
            }
        }
        // Keys defined only by a theme resolve through the same chain rules.
        val scheme = requireNotNull(scope.activeColorScheme)
        val raw = ColorTable.resolveRaw(key, scheme.colors, scope.theme.fallbackColors)
        return parseDrawable(scope, raw ?: key)
    }

    private fun parseDrawable(
        scope: ThemeScope,
        value: String,
    ): Drawable? {
        if (value.isEmpty()) return null
        if (ColorTable.isImageValue(value)) return imageDrawable(scope, value)
        val color = runCatching { ColorUtils.parseColor(value) }.getOrDefault(Color.TRANSPARENT)
        return GradientDrawable().apply { setColor(color) }
    }

    private fun imageDrawable(
        scope: ThemeScope,
        value: String,
    ): Drawable? {
        val path = resolveImageFilePath(scope, value)

        // GIF 必须走 AnimatedImageDrawable：下面的 BitmapFactory 只会解出第一帧，
        // 动图背景会变成静态图。仅支持 Android 9+，低版本自动落到静态分支。
        if (value.endsWith(".gif", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            createAnimatedGif(path)?.let { return it }
        }

        val bitmap =
            bitmapCache?.get(path)
                ?: BitmapFactory.decodeFile(path)?.also {
                    bitmapCache?.put(path, it)
                } ?: return null
        if (path.endsWith(".9.png")) {
            val chunk = bitmap.ninePatchChunk
            return if (NinePatch.isNinePatchChunk(chunk)) {
                // for compiled nine patch image
                NinePatchDrawable(Resources.getSystem(), bitmap, chunk, Rect(), null)
            } else {
                // for source nine patch image
                NinePatchBitmapFactory.createNinePatchDrawable(Resources.getSystem(), bitmap)
            }
        }
        return bitmap.toDrawable(Resources.getSystem())
    }

    /**
     * 把 GIF 解码成自动播放的 [AnimatedImageDrawable]；失败返回 null，
     * 由调用方回落到静态解码。
     *
     * 用 [ImageDecoder]（API 28+）而不是 `Drawable.createFromPath`，
     * 唯一原因是前者支持 [ImageDecoder.setTargetSize] —— 动图每帧都要重解码，
     * 像素数直接决定 CPU 与内存带宽开销。这里按
     * [KeyboardBackground.animatedDecodeScale] 把帧尺寸压到预算内，
     * 于是「1920×1080 的大图」也能正常当动图播放，而不是被硬性拒绝。
     */
    private fun createAnimatedGif(path: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val file = File(path)
        if (!KeyboardBackground.gifWithinSizeLimit(file)) return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            val decoded =
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val scale = KeyboardBackground.animatedDecodeScale(width, height)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            (decoded as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        }.getOrNull()
    }

    private fun resolveImageFilePath(
        scope: ThemeScope,
        value: String,
    ): String {
        val default = DataManager.userDataDir.resolve("backgrounds/${backgroundFolder(scope)}/$value")
        if (!default.exists()) {
            val fallback = DataManager.userDataDir.resolve("backgrounds/$value")
            if (fallback.exists()) return fallback.absolutePath
        }
        return default.absolutePath
    }

    @ColorInt
    fun getColor(key: String): Int = resolveColor(requireScope(), key)

    fun getDrawable(key: String): Drawable? = resolveDrawable(requireScope(), key)

    internal fun resolveDecorDrawable(
        scope: ThemeScope,
        colorKey: String,
        borderColorKey: String?,
        borderPx: Int,
        cornerRadius: Float,
        alpha: Int,
    ): Drawable? = when (val drawable = resolveDrawable(scope, colorKey)) {
        is GradientDrawable ->
            drawable.also {
                it.cornerRadius = cornerRadius
                it.alpha = MathUtils.clamp(alpha, 0, 255)
                if (!borderColorKey.isNullOrEmpty()) {
                    try {
                        val borderColor = resolveColor(scope, borderColorKey)
                        it.setStroke(borderPx, borderColor)
                    } catch (_: Exception) {
                    }
                }
            }

        else -> drawable?.also { it.alpha = MathUtils.clamp(alpha, 0, 255) }
    }

    fun getDecorDrawable(
        colorKey: String,
        borderColorKey: String? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = resolveDecorDrawable(requireScope(), colorKey, borderColorKey, borderPx, cornerRadius, alpha)
}
