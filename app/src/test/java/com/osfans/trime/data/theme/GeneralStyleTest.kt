/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.yamlMapOf
import com.osfans.trime.util.yamlScalarOf
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * GeneralStyle decode baseline: field-level values from the built-in trime.yaml
 * (golden values taken from the file itself), and graceful fallback to defaults
 * for malformed values (empty / wrong types / unknown enums) in incorrect.yaml.
 */
class GeneralStyleTest :
    BehaviorSpec({
        Given("the built-in trime.yaml") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")

            When("its style section is decoded") {
                val style = theme.generalStyle

                Then("plain scalar values from the file are preserved") {
                    style shouldNotBe null
                    style.autoCaps shouldBe false
                    // 这两个原本是默认 0（主题没设），候选之间因此既无内边距也无间隔；
                    // 用户反馈「没有间隔」后显式补上。
                    style.candidatePadding shouldBe 6
                    style.candidateSpacing shouldBe 6f
                    // 字号/格子高度必须配套：候选文字不缩放（scaleMode = Mode.None），
                    // 框比字矮就会把字上下裁掉。18sp 约 78px，配 32dp(96px) 的框。
                    style.candidateTextSize shouldBe 18f
                    style.candidateTextVerticalBias shouldBe 1f
                    style.candidateViewHeight shouldBe 32
                    style.commentHeight shouldBe 12
                    style.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
                    style.commentTextSize shouldBe 12f
                    style.horizontalGap shouldBe 1
                    style.keyHeight shouldBe 52
                    style.keyLongTextSize shouldBe 14f
                    style.keyTextSize shouldBe 20f
                    style.keyWidth shouldBe 10f
                    style.labelTextSize shouldBe 13f
                    style.keyboardHeight shouldBe 250
                    style.keyboardHeightLand shouldBe 200
                    style.keyboardPaddingRight shouldBe 0
                    style.keyboardPaddingLand shouldBe 40
                }

                Then("fonts declared as a single scalar decode to a single-element list") {
                    // 2bcdf382 曾把标量字体名解析成空列表（等于内置主题的字体配置
                    // 全部失效）。上游主题与社区主题（tongwenfeng 等）的惯例都是
                    // 标量写法 `key_font: latin.ttf`，标量必须按单元素列表解析；
                    // 列表写法 `candidate_font: [han.ttf]` 也继续支持。
                    style.candidateFont shouldBe listOf("han.ttf")
                    style.keyFont shouldBe listOf("latin.ttf")
                }

                Then("theme header is decoded") {
                    // fork 把主题名改成了 myime（上游是「預設」）
                    theme.name shouldBe "myime"
                }
            }
        }

        Given("a theme with empty/incorrect style values") {
            val theme = ThemeTestSupport.decodeThemeFile("src/test/assets/incorrect.yaml")

            When("its style section is decoded") {
                val style = theme.generalStyle

                Then("malformed values fall back to defaults without exception") {
                    style.autoCaps shouldBe false
                    style.candidateTextSize shouldBe 15f
                    style.candidateBorder shouldBe 0
                    style.candidateFont shouldBe emptyList()
                    style.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
                    style.enterLabel shouldNotBe null
                    style.enterLabel.go shouldBe "go"
                    style.enterLabel.default shouldBe "default"
                }
            }
        }

        Given("an empty style section") {
            val style = GeneralStyle.decode(yamlMapOf())

            Then("decode equals the constructor defaults") {
                style shouldBe GeneralStyle.DEFAULTS
            }
            Then("decode fills explicit keys but keeps the defaults for the rest") {
                val style =
                    GeneralStyle.decode(
                        yamlMapOf(
                            "candidate_text_size" to yamlScalarOf("20"),
                        ),
                    )
                style.candidateTextSize shouldBe 20f
                style.keyHeight shouldBe 0
            }
        }
    })
