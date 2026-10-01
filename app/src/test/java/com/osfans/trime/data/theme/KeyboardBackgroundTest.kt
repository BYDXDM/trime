// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.roundToInt

class KeyboardBackgroundTest :
    StringSpec({
        // 与 KeyboardBackground.ANIMATED_MAX_PIXELS 保持一致（≈1 MP）
        val budgetPixels = (1024 * 1024).toLong()

        /** 按缩放系数算出目标尺寸后的实际像素数 */
        fun targetPixels(
            width: Int,
            height: Int,
        ): Long {
            val scale = KeyboardBackground.animatedDecodeScale(width, height)
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            return w.toLong() * h.toLong()
        }

        "小图不缩放" {
            KeyboardBackground.animatedDecodeScale(192, 192) shouldBe 1f
            KeyboardBackground.animatedDecodeScale(1080, 640) shouldBe 1f
        }

        "刚好等于预算时不缩放" {
            KeyboardBackground.animatedDecodeScale(1024, 1024) shouldBe 1f
        }

        "1080p 会被缩到预算内，但仍然保留动图而不是被拒绝" {
            val scale = KeyboardBackground.animatedDecodeScale(1920, 1080)

            (scale in 0.5f..1f) shouldBe true
            (targetPixels(1920, 1080) <= budgetPixels) shouldBe true
            // 不能被压成缩略图，仍应接近预算
            (targetPixels(1920, 1080) >= budgetPixels / 2) shouldBe true
        }

        "超大图同样被压到预算内" {
            (targetPixels(4000, 4000) <= budgetPixels) shouldBe true
            (targetPixels(4000, 4000) >= budgetPixels / 2) shouldBe true
        }

        "各种尺寸都不会明显超过预算" {
            val sizes =
                listOf(
                    192 to 192,
                    1080 to 1080,
                    1920 to 1080,
                    1080 to 1920,
                    2560 to 1440,
                    4096 to 4096,
                    800 to 4800,
                    1 to 1,
                )
            sizes.forEach { (w, h) ->
                // 允许四舍五入带来的少量溢出
                val limit = budgetPixels + (w + h).toLong()
                (targetPixels(w, h) <= limit) shouldBe true
            }
        }

        "非法尺寸安全返回 1" {
            KeyboardBackground.animatedDecodeScale(0, 100) shouldBe 1f
            KeyboardBackground.animatedDecodeScale(100, 0) shouldBe 1f
            KeyboardBackground.animatedDecodeScale(-5, -5) shouldBe 1f
        }

        "缩放在两个方向等比，不会拉伸变形" {
            val scale = KeyboardBackground.animatedDecodeScale(1920, 1080)
            val w = (1920 * scale).roundToInt()
            val h = (1080 * scale).roundToInt()
            val aspect = w.toDouble() / h.toDouble()
            (abs(aspect - 1920.0 / 1080.0) < 0.02) shouldBe true
        }
    })
