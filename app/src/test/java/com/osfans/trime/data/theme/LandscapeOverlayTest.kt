/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Landscape overrides are a per-key merge: only the keys a scheme actually
 * spells out with the `_land` suffix change, everything else must survive
 * untouched so a scheme can describe both orientations at once.
 */
class LandscapeOverlayTest :
    BehaviorSpec({
        Given("a scheme without landscape keys") {
            val colors =
                mapOf(
                    "keyboard_back_color" to "portrait.jpg",
                    "key_text_color" to "0xFF000000",
                )
            Then("it is returned unchanged") {
                LandscapeOverlay.apply(colors) shouldBe colors
            }
        }

        Given("a scheme with a landscape variant of one key") {
            val colors =
                mapOf(
                    "keyboard_back_color" to "portrait.jpg",
                    "keyboard_back_color_land" to "landscape.jpg",
                    "key_text_color" to "0xFF000000",
                )
            Then("the variant overrides its base key and is dropped from the result") {
                LandscapeOverlay.apply(colors) shouldBe
                    mapOf(
                        "keyboard_back_color" to "landscape.jpg",
                        "key_text_color" to "0xFF000000",
                    )
            }
        }

        Given("a scheme that defines only a landscape key") {
            val colors = mapOf("root_background_land" to "landscape.jpg")
            Then("the base key is created") {
                LandscapeOverlay.apply(colors) shouldBe mapOf("root_background" to "landscape.jpg")
            }
        }

        Given("a scheme with a bare suffix key") {
            val colors =
                mapOf(
                    "_land" to "orphan.jpg",
                    "key_text_color" to "0xFF000000",
                )
            Then("the orphan entry is dropped instead of creating an empty key") {
                LandscapeOverlay.apply(colors) shouldBe mapOf("key_text_color" to "0xFF000000")
            }
        }

        Given("a scheme with several landscape keys") {
            val colors =
                mapOf(
                    "keyboard_back_color" to "portrait.jpg",
                    "keyboard_back_color_land" to "landscape.jpg",
                    "key_text_color" to "0xFF000000",
                    "key_text_color_land" to "0xFFFFFFFF",
                    "candidate_text_color" to "0xFF111111",
                )
            Then("every landscape key overrides its own base key") {
                LandscapeOverlay.apply(colors) shouldBe
                    mapOf(
                        "keyboard_back_color" to "landscape.jpg",
                        "key_text_color" to "0xFFFFFFFF",
                        "candidate_text_color" to "0xFF111111",
                    )
            }
        }
    })
