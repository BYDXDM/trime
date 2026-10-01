/*
 * SPDX-FileCopyrightText: 2025 myime
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 键盘背景图支持。
 *
 * 接入现状：
 *   - [gifWithinSizeLimit] 已被 ColorManager 的 GIF 分支使用，GIF 动图背景生效。
 *   - [overlayColorFor] / [textColorFor] / [isImageValue] 目前**尚未接入**。
 *     它们需要 KeyboardView.onDraw 先画一层蒙层，属于会改变渲染结果和
 *     每帧开销的改动，未在此次修复中启用。使用前请先决定是否接受该视觉变化。
 */

package com.osfans.trime.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.sqrt

/**
 * 键盘背景图工具。
 *
 * ⚠ 其中 [overlayColorFor] / [textColorFor] 是「自动对比度蒙层」方案，
 * 需要 KeyboardView.onDraw 配合绘制；当前重心是 GIF 支持，蒙层未启用。
 */
object KeyboardBackground {

    /** ColorTable.kt 里已有的判定，保持一致 */
    private val IMAGE_SUFFIXES = arrayOf(".png", ".webp", ".jpg", ".jpeg", ".gif")

    fun isImageValue(value: String?): Boolean = value != null && IMAGE_SUFFIXES.any { value.endsWith(it, ignoreCase = true) }

    /**
     * 动图是否在尺寸上限内。只读头部（inJustDecodeBounds），不解码像素。
     * 量不出尺寸时保守放行，交给系统解码。
     *
     * 供 [ColorManager] 的 GIF 分支复用，因此在类外可见（internal）。
     *
     * 这里只管**源文件**不要大到离谱（解码器输入上限）。
     * 真正的内存控制靠 [animatedDecodeScale]：解码时用
     * `ImageDecoder.setTargetSize` 把帧尺寸压到 [ANIMATED_MAX_PIXELS] 以内。
     * 之所以能这么做，是因为 GIF 不能用 `inSampleSize`（`Drawable.createFromPath`
     * 不接受 `BitmapFactory.Options`），只能靠 ImageDecoder 的目标尺寸缩放。
     */
    internal fun gifWithinSizeLimit(file: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val longest = maxOf(opts.outWidth, opts.outHeight)
        return longest <= 0 || longest <= MAX_GIF_SOURCE_DIM
    }

    /**
     * 动图解码缩放系数：把总像素压到 [ANIMATED_MAX_PIXELS] 以内，不超过 1。
     *
     * 每帧占用的内存 = 宽 × 高 × 4 字节，而动画是**按帧重解码**的，
     * 所以像素数直接决定 CPU 与内存带宽开销。举例（12.5 fps）：
     * - 192×192   → 0.14 MB/帧 → 约 1.8 MB/s
     * - 1080×1080 → 4.7 MB/帧  → 约 58 MB/s（可接受）
     * - 1920×1080 → 8.3 MB/帧  → 约 104 MB/s（明显偏重）
     *
     * 纯函数，便于单元测试。
     */
    internal fun animatedDecodeScale(
        width: Int,
        height: Int,
    ): Float {
        if (width <= 0 || height <= 0) return 1f
        val pixels = width.toLong() * height.toLong()
        if (pixels <= ANIMATED_MAX_PIXELS) return 1f
        return sqrt(ANIMATED_MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
    }

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
            Color.argb(OVERLAY_ALPHA, 0, 0, 0) // 亮背景压暗
        } else {
            Color.argb(OVERLAY_ALPHA, 255, 255, 255) // 暗背景提亮
        }
    }

    /**
     * 自动决定按键文字颜色（黑 / 白）。
     *
     * 必须与 [overlayColorFor] 的方向一致：
     *   亮背景 → 蒙层压暗 → 用浅色字
     *   暗背景 → 蒙层提亮 → 用深色字
     * 早期版本这里与蒙层反着配（亮背景压暗却用深色字），
     * 蒙层叠加后文字会糊在背景里。
     */
    fun textColorFor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.WHITE
        return if (averageLuminance(bitmap) > LUM_THRESHOLD) {
            Color.parseColor("#FFF2F2F2") // 亮背景经蒙层压暗 → 浅色字
        } else {
            Color.parseColor("#FF1A1A1A") // 暗背景经蒙层提亮 → 深色字
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
    private const val OVERLAY_ALPHA = 0x8C // 约 55% 不透明

    /**
     * 动图**源文件**允许的最大边长（px）。超过就退回静态解码路径。
     *
     * 这只是一道「别把离谱尺寸喂给解码器」的保险；真正的内存控制是
     * 解码时按 [ANIMATED_MAX_PIXELS] 做目标尺寸缩放（见 [animatedDecodeScale]），
     * 所以这里可以放得比较宽 —— 1080p（1920×1080）能正常当动图播放。
     */
    private const val MAX_GIF_SOURCE_DIM = 4096

    /**
     * 动图**解码后**允许的最大总像素数（≈1 MP，约 1024×1024）。
     *
     * 每帧内存 = 像素数 × 4 字节 ≈ 4 MB；按 12.5 fps 计约 50 MB/s 的解码与上传量，
     * 对输入法属于可接受量级。想更省电可以调小（观感会更糊），
     * 想更清晰可以调到 2 MP 左右 —— 再大就等着掉帧和发热。
     */
    private const val ANIMATED_MAX_PIXELS = 1024 * 1024
}
