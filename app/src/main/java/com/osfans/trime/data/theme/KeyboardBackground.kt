/*
 * SPDX-FileCopyrightText: 2025 myime
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 键盘背景图支持 —— ColorManager 改造
 *
 * 目的：
 *   1. 支持把本地 .jpg / .gif / .png / .webp 当键盘背景
 *   2. GIF 真正动起来（Trime 原生只取第一帧）
 *   3. 自动半透明蒙层 + 自动文字颜色，保证按键字母不被背景吃掉
 *
 * 集成方式：把下面三个方法合并进
 *   app/src/main/java/com/osfans/trime/data/theme/ColorManager.kt
 */

package com.osfans.trime.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import com.osfans.trime.data.base.DataManager
import java.io.File

object KeyboardBackground {

    /** ColorTable.kt 里已有的判定，保持一致 */
    private val IMAGE_SUFFIXES = arrayOf(".png", ".webp", ".jpg", ".jpeg", ".gif")

    fun isImageValue(value: String?): Boolean =
        value != null && IMAGE_SUFFIXES.any { value.endsWith(it, ignoreCase = true) }

    /**
     * 解析背景图路径。对应 ColorManager.kt 原有的查找顺序：
     *   1) <userDataDir>/backgrounds/<backgroundFolder>/<value>
     *   2) <userDataDir>/backgrounds/<value>          ← 降级
     *   3) <userDataDir>/backgrounds/<value> 的父目录兜底
     *
     * 注意 backgroundFolder 来自主题 general_style/background_folder，
     * 用户上传的图必须放到这个子目录下，否则第 1 步找不到。
     */
    fun resolveImageFile(scope: ThemeScope, value: String): File? {
        val folder = scope.theme.generalStyle.backgroundFolder
        val base = DataManager.userDataDir.resolve("backgrounds")
        val candidates = listOf(
            base.resolve("$folder/$value"),
            base.resolve(value),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    /**
     * 加载背景为 Drawable。
     *
     * ★ 关键修复：Trime 原生用 BitmapFactory.decodeFile()，
     *   对 GIF 只会解出第一帧 —— 动图背景变成静态图。
     *   Android 9 (P) 起系统原生支持 AnimatedImageDrawable，直接用它。
     *
     * 为避免大图导致键盘掉帧，超过 MAX_DIM 的图做降采样。
     */
    fun loadDrawable(context: Context, scope: ThemeScope, value: String): Drawable? {
        val file = resolveImageFile(scope, value) ?: return null

        // GIF：用系统动图支持，不过 BitmapFactory（会丢帧）
        if (file.extension.equals("gif", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            Drawable.createFromPath(file.absolutePath)?.let { d ->
                (d as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
                return d
            }
        }

        // 静态图：降采样，避免大图拖慢键盘
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val longest = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (longest / sample > MAX_DIM) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565   // 背景不需要全彩，省内存
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        return bmp.toDrawable(context.resources)
    }

    /** 背景图最长边上限（px），超过就降采样 */
    private const val MAX_DIM = 1080

    // ================================================================
    //  半透明蒙层：让按键字母不被背景遮挡
    // ================================================================

    /**
     * 方案 A（零改代码）：在主题配色里把按键色写成带 alpha 的 ARGB。
     *   此时按键自带半透明底，字母对比度由 key_text_color 保证。
     *   缺点：按键【缝隙】没有蒙层，背景花纹会从缝里透出（horizontal_gap）。
     *   → 见 trime.yaml 的 user_light / user_dark 配色。
     *
     * 方案 B（推荐，本方法）：整个键盘区加一层统一蒙层。
     *   在 KeyboardView.onDraw 里先画这层，再画键位。
     *   这样缝隙也被压住，观感统一。
     */
    fun overlayColorFor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.argb(0x66, 0, 0, 0)
        val lum = averageLuminance(bitmap)
        // 亮背景 → 盖深色蒙层；暗背景 → 盖浅色蒙层。
        // 目标是让按键上的文字始终有足够对比度。
        return if (lum > LUM_THRESHOLD) {
            Color.argb(OVERLAY_ALPHA, 0, 0, 0)          // 亮背景压暗
        } else {
            Color.argb(OVERLAY_ALPHA, 255, 255, 255)    // 暗背景提亮
        }
    }

    /**
     * 自动决定按键文字颜色（黑 / 白）。
     * 配合 overlayColorFor 一起用，能覆盖绝大多数背景图。
     */
    fun textColorFor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.BLACK
        return if (averageLuminance(bitmap) > LUM_THRESHOLD) {
            Color.parseColor("#FF1A1A1A")   // 亮背景 → 深色字
        } else {
            Color.parseColor("#FFF2F2F2")   // 暗背景 → 浅色字
        }
    }

    /** 平均亮度 0..255。按 4x4 网格采样，大图也能秒算。 */
    private fun averageLuminance(bmp: Bitmap): Double {
        var sum = 0.0
        var n = 0
        val stepX = maxOf(1, bmp.width / 16)
        val stepY = maxOf(1, bmp.height / 16)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                // ITU-R BT.601 亮度公式
                sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 128.0 else sum / n
    }

    private const val LUM_THRESHOLD = 140.0
    private const val OVERLAY_ALPHA = 0x8C   // 约 55% 不透明
}
