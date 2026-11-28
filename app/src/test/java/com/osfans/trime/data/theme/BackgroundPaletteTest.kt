/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * The palette is derived from the picture's average luminance: the key overlay
 * and the text colour have to end up on opposite sides of that luminance, and
 * the candidate bar must stay a solid colour instead of the picture itself.
 */
class BackgroundPaletteTest :
    BehaviorSpec({
        Given("a light picture") {
            val p = BackgroundPalette.forImage("wall.jpg", 200.0)
            Then("the key overlay is white and the text is dark") {
                p[BackgroundPalette.KEY_BACK] shouldBe "0x33FFFFFF"
                p["key_text_color"] shouldBe "0xFF1A1A1A"
                p["candidate_text_color"] shouldBe "0xFF1A1A1A"
            }
            Then("both the keyboard and the root use the picture") {
                p[BackgroundPalette.KEYBOARD_BACK] shouldBe "wall.jpg"
                p[BackgroundPalette.ROOT_BACKGROUND] shouldBe "wall.jpg"
            }
        }

        Given("a dark picture") {
            val p = BackgroundPalette.forImage("wall.jpg", 40.0)
            Then("the key overlay is black and the text is light") {
                p[BackgroundPalette.KEY_BACK] shouldBe "0x33000000"
                p["key_text_color"] shouldBe "0xFFF5F5F5"
                p["candidate_text_color"] shouldBe "0xFFF5F5F5"
            }
        }

        Given("any picture") {
            Then("the candidate bar is a solid colour, never the picture") {
                for (lum in listOf(0.0, 139.9, 140.1, 255.0)) {
                    val candidate = BackgroundPalette.forImage("wall.jpg", lum)[BackgroundPalette.CANDIDATE_BACK]!!
                    candidate shouldStartWith "0xE6"
                    (candidate == "wall.jpg") shouldBe false
                }
            }
        }

        Given("a luminance right at the threshold") {
            Then("the threshold belongs to the dark side") {
                BackgroundPalette.forImage("w.jpg", BackgroundPalette.LUM_THRESHOLD)["key_text_color"] shouldBe "0xFFF5F5F5"
                BackgroundPalette.forImage("w.jpg", BackgroundPalette.LUM_THRESHOLD + 0.1)["key_text_color"] shouldBe "0xFF1A1A1A"
            }
        }

        Given("the produced key set") {
            Then("every key is one the landscape suffix can be appended to") {
                BackgroundPalette.forImage("w.jpg", 200.0).keys.toList() shouldBe
                    listOf(
                        "keyboard_back_color",
                        "candidate_background",
                        "root_background",
                        "key_back_color",
                        "key_text_color",
                        "key_symbol_color",
                        "label_color",
                        "candidate_text_color",
                        "comment_text_color",
                        "shadow_color",
                    )
            }
        }
    })
