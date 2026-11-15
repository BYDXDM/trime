/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.text

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * F14 回归：纠错许可必须按编辑框判定，且英文缓冲只认这一个判据。
 *
 * 旧实现里纠错开关只跟「是不是 ASCII 模式」挂钩，而键盘层会把密码框强制切成
 * ASCII 模式，于是密码框里同样会走 `deleteSurroundingText` + 重新提交的改写路径。
 */
class CorrectionPolicyTest :
    BehaviorSpec({
        Given("当前编辑框是否允许英文纠错") {
            When("普通文本框") {
                Then("允许") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
                        0,
                    ) shouldBe true
                }
            }

            When("密码框（TYPE_TEXT_VARIATION_PASSWORD）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        0,
                    ) shouldBe false
                }
            }

            When("可见密码框（TYPE_TEXT_VARIATION_VISIBLE_PASSWORD）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        0,
                    ) shouldBe false
                }
            }

            When("网页密码框（TYPE_TEXT_VARIATION_WEB_PASSWORD）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        0,
                    ) shouldBe false
                }
            }

            When("数字密码框（TYPE_NUMBER_VARIATION_PASSWORD）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                        0,
                    ) shouldBe false
                }
            }

            When("编辑框声明不要个性化学习（IME_FLAG_NO_PERSONALIZED_LEARNING）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT,
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    ) shouldBe false
                }
            }

            When("输入类型自带不要建议标志（TYPE_TEXT_FLAG_NO_SUGGESTIONS）") {
                Then("禁止") {
                    CorrectionPolicy.allowsCorrection(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                        0,
                    ) shouldBe false
                }
            }

            When("没有输入类型（TYPE_NULL，例如只读焦点）") {
                Then("不额外关闭，交由其它判据决定") {
                    CorrectionPolicy.allowsCorrection(InputType.TYPE_NULL, 0) shouldBe true
                }
            }
        }

        Given("英文缓冲读取纠错许可的方式") {
            fun withCorrector(
                asciiMode: Boolean,
                allowed: Boolean,
                body: () -> Unit,
            ) {
                val oldEnabled = EnglishCorrector.enabled
                val oldAllowed = EnglishCorrector.inputAllowsCorrection
                try {
                    EnglishCorrector.enabled = asciiMode
                    EnglishCorrector.inputAllowsCorrection = allowed
                    body()
                } finally {
                    EnglishCorrector.enabled = oldEnabled
                    EnglishCorrector.inputAllowsCorrection = oldAllowed
                }
            }

            When("英文模式 + 敏感框（不允许纠错）") {
                Then("字母根本不进缓冲，flush 不产生改写") {
                    withCorrector(asciiMode = true, allowed = false) {
                        val buffer = EnglishInputBuffer()
                        buffer.onCommit("t")
                        buffer.onCommit("e")
                        buffer.onCommit("h")
                        buffer.pendingLength shouldBe 0
                        buffer.onCommit(" ") shouldBe null
                        buffer.flush() shouldBe null
                    }
                }
            }

            When("英文模式 + 普通框") {
                Then("照常纠错，teh + 空格 → the") {
                    withCorrector(asciiMode = true, allowed = true) {
                        val buffer = EnglishInputBuffer()
                        buffer.onCommit("t")
                        buffer.onCommit("e")
                        buffer.onCommit("h")
                        buffer.pendingLength shouldBe 3
                        val result = buffer.onCommit(" ")
                        result?.original shouldBe "teh"
                        result?.replacement shouldBe "the"
                        result?.backspaces shouldBe 3
                    }
                }
            }

            When("中文模式（未开 ASCII）") {
                Then("不纠错") {
                    withCorrector(asciiMode = false, allowed = true) {
                        val buffer = EnglishInputBuffer()
                        buffer.onCommit("t")
                        buffer.onCommit("e")
                        buffer.onCommit("h")
                        buffer.pendingLength shouldBe 0
                        buffer.flush() shouldBe null
                    }
                }
            }
        }
    })
