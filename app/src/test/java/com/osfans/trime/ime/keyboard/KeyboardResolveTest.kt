/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pure resolvers of the keyboard geometry cascade: conventions A (positive
 * wins, gaps/heights), B (non-negative wins, corners/borders with -1 as the
 * absent sentinel) and C (non-zero wins for offsets) plus the landscape pick.
 * These pin the legacy merge semantics extracted from Keyboard.kt.
 *
 * Also pins [resolveKeyboardForAsciiMode], the 中英 (Chinese ↔ English) pairing
 * derived from the theme's `ascii_keyboard` declarations.
 */
class KeyboardResolveTest :
    BehaviorSpec({
        Given("a positive-wins resolver with a doubling unit") {
            val unit: (Int) -> Int = { it * 2 }

            When("the config value is positive") {
                Then("it wins and the unit is applied once") {
                    resolvePositive(3, 10, unit) shouldBe 6
                }
            }

            When("the config value is zero or null") {
                Then("the style value wins") {
                    resolvePositive(0, 10, unit) shouldBe 20
                    resolvePositive(null, 10, unit) shouldBe 20
                }
            }

            When("both values are zero or null") {
                Then("the result is zero") {
                    resolvePositive(0, 0, unit) shouldBe 0
                    resolvePositive(null, 0) shouldBe 0
                }
            }

            When("negative config values are given") {
                Then("they never win") {
                    resolvePositive(-1, 10, unit) shouldBe 20
                }
            }
        }

        Given("a non-negative-wins resolver (Float)") {
            When("the config value is absent (-1)") {
                Then("the style value is used") {
                    resolveNonNegative(-1f, 8f) shouldBe 8f
                }
            }

            When("the config value is zero") {
                Then("zero is a legal override") {
                    resolveNonNegative(0f, 8f) shouldBe 0f
                }
            }

            When("the config value is positive") {
                Then("it wins") {
                    resolveNonNegative(4f, 8f) shouldBe 4f
                }
            }

            When("the config is null") {
                Then("the style value is used") {
                    resolveNonNegative(null, 8f) shouldBe 8f
                }
            }
        }

        Given("a non-negative-wins resolver (Int)") {
            When("the config value is absent (-1) or null") {
                Then("the style value is used") {
                    resolveNonNegative(-1, 2) shouldBe 2
                    resolveNonNegative(null, 2) shouldBe 2
                }
            }

            When("the config value is zero") {
                Then("zero is a legal override") {
                    resolveNonNegative(0, 2) shouldBe 0
                }
            }
        }

        Given("an offset resolver cascading key, keyboard and style") {
            When("the key offset is non-zero") {
                Then("it wins") {
                    resolveOffset(1.5f, 2f, 3f) shouldBe 1.5f
                }
            }

            When("the key offset is zero but the keyboard offset is set") {
                Then("the keyboard offset wins") {
                    resolveOffset(0f, 2f, 3f) shouldBe 2f
                }
            }

            When("only the style offset is set") {
                Then("the style offset is the fallback") {
                    resolveOffset(0f, 0f, 3f) shouldBe 3f
                }
            }

            When("all are zero") {
                Then("the result is zero") {
                    resolveOffset(0f, 0f, 0f) shouldBe 0f
                }
            }

            When("negative offsets are given") {
                Then("they are kept") {
                    resolveOffset(-1f, 2f, 3f) shouldBe -1f
                    resolveOffset(0f, -2f, 3f) shouldBe -2f
                }
            }
        }

        Given("a landscape picker") {
            When("in portrait mode") {
                Then("the landscape value is ignored") {
                    pickLandscape(250, 200, landscape = false) shouldBe 250
                }
            }

            When("in landscape mode with a landscape value") {
                Then("the landscape value wins") {
                    pickLandscape(250, 200, landscape = true) shouldBe 200
                }
            }

            When("in landscape mode without a landscape value") {
                Then("the portrait value is used") {
                    pickLandscape(250, 0, landscape = true) shouldBe 250
                }
            }
        }

        Given("an ascii-mode keyboard resolver fed by the theme's ascii_keyboard declarations") {
            // 主题现状：my_pinyin 声明了英文盘，其余键盘（含符号/数字/emoji 面板）未声明
            val declared =
                mapOf(
                    "my_pinyin" to "my_english",
                    "my_english" to "",
                    "symbols" to "",
                    "number" to "",
                    "emoji" to "",
                )

            When("切到英文，当前是中文盘") {
                Then("用当前键盘声明的 ascii_keyboard") {
                    resolveKeyboardForAsciiMode(true, "my_pinyin", declared) shouldBe "my_english"
                }
            }

            When("切回中文，当前是英文盘") {
                Then("反查「谁把当前键盘声明为自己的英文盘」") {
                    resolveKeyboardForAsciiMode(false, "my_english", declared) shouldBe "my_pinyin"
                }
            }

            When("反查未命中：当前键盘没有被任何键盘声明为自己的英文盘") {
                Then("不请求切换") {
                    // 中文盘在英文态：反查「谁把 my_pinyin 当自己的英文盘」→ 没有
                    resolveKeyboardForAsciiMode(false, "my_pinyin", declared) shouldBe null
                    // 英文盘在英文态：它自己没声明 ascii_keyboard
                    resolveKeyboardForAsciiMode(true, "my_english", declared) shouldBe null
                }
            }

            When("当前键盘未声明配对（符号/数字/emoji 面板）") {
                Then("两个方向都不换键盘，不把用户从面板里拽走") {
                    for (panel in listOf("symbols", "number", "emoji")) {
                        resolveKeyboardForAsciiMode(true, panel, declared) shouldBe null
                        resolveKeyboardForAsciiMode(false, panel, declared) shouldBe null
                    }
                }
            }

            When("当前键盘名是空串（首次挂载前）") {
                Then("不做任何推断") {
                    // 空串会与「未声明」的空值互相匹配，反查出任意键盘
                    resolveKeyboardForAsciiMode(true, "", declared) shouldBe null
                    resolveKeyboardForAsciiMode(false, "", declared) shouldBe null
                }
            }

            When("配对表是空的") {
                Then("两个方向都解析不出结果") {
                    resolveKeyboardForAsciiMode(true, "my_pinyin", emptyMap()) shouldBe null
                    resolveKeyboardForAsciiMode(false, "my_english", emptyMap()) shouldBe null
                }
            }

            When("声明的值是空白串（不是合法键盘名）") {
                Then("当作未声明，不把空白丢给调用方去兜底") {
                    resolveKeyboardForAsciiMode(true, "my_pinyin", mapOf("my_pinyin" to " ")) shouldBe null
                    resolveKeyboardForAsciiMode(false, "my_english", mapOf("my_pinyin" to " ")) shouldBe null
                }
            }

            When("解析出的目标就是当前键盘（自环）") {
                Then("忽略，避免无谓重挂") {
                    resolveKeyboardForAsciiMode(true, "self", mapOf("self" to "self")) shouldBe null
                    resolveKeyboardForAsciiMode(false, "self", mapOf("self" to "self")) shouldBe null
                }
            }

            When("声明的目标在主题里并不存在") {
                Then("原样返回：本函数只做配对推导，不校验键盘是否存在，兜底归调用方") {
                    resolveKeyboardForAsciiMode(true, "my_pinyin", mapOf("my_pinyin" to "ghost")) shouldBe "ghost"
                }
            }

            When("多个键盘声明了同一个英文盘") {
                Then("取最先声明的那一个（配对表保持主题的声明顺序）") {
                    // 用 linkedMapOf 把「保持插入顺序」这个前提显式钉住
                    val twoClaimants = linkedMapOf("first" to "shared", "second" to "shared")
                    resolveKeyboardForAsciiMode(false, "shared", twoClaimants) shouldBe "first"
                }
            }

            When("当前键盘名不在主题里") {
                Then("两个方向都解析不出结果") {
                    resolveKeyboardForAsciiMode(true, "ghost", declared) shouldBe null
                    resolveKeyboardForAsciiMode(false, "ghost", declared) shouldBe null
                }
            }
        }
    })
