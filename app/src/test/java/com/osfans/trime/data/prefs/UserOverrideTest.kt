// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.prefs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins [resolveUserOverride], the cascade that lets the geometry preferences
 * (keyboard height, key spacing, candidate bar height) override the theme while
 * 0 keeps meaning "follow the theme".
 */
class UserOverrideTest :
    BehaviorSpec({
        Given("a positive-wins user override with a doubling unit") {
            val unit: (Int) -> Int = { it * 2 }

            When("the user set a positive value") {
                Then("it wins and the unit is applied once") {
                    resolveUserOverride(30, 500, unit) shouldBe 60
                }
            }

            When("the user left it at 0 (follow the theme)") {
                Then("the themed value is returned untouched, unit included") {
                    // 主题值已经过 dp 换算，不能再乘一次 unit
                    resolveUserOverride(0, 500, unit) shouldBe 500
                }
            }

            When("the stored value is somehow negative") {
                Then("it is treated as unset rather than shrinking the keyboard") {
                    resolveUserOverride(-5, 500, unit) shouldBe 500
                }
            }
        }

        Given("a user override without a unit converter") {
            When("the user set a value") {
                Then("it wins verbatim") {
                    resolveUserOverride(7, 3) shouldBe 7
                }
            }

            When("the user set 0") {
                Then("the themed value wins verbatim") {
                    resolveUserOverride(0, 3) shouldBe 3
                }
            }
        }
    })
