/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 延迟动作的目标复核。
 *
 * 回归对象：删除键上滑「清空」在 `delay` 之后重新读 `currentInputConnection` 直接删字符，
 * 焦点在延迟窗口内切到另一个编辑框时会删掉**新编辑框**的内容。
 * [isSameInputTarget] 是这条路径上唯一的判据，所以这里把它的四种组合钉死。
 */
class InputTargetGuardTest :
    BehaviorSpec({
        Given("发起清空时记录的输入目标") {
            val connection = Any()

            When("代际与连接都没变（用户没切换编辑框）") {
                Then("允许继续清空") {
                    isSameInputTarget(7L, 7L, connection, connection) shouldBe true
                }
            }

            When("代际变了（已切到另一个编辑框）") {
                Then("即使连接对象相同也必须放弃") {
                    isSameInputTarget(7L, 8L, connection, connection) shouldBe false
                }
            }

            When("代际没变但 InputConnection 被换掉") {
                Then("同样放弃，避免写到新连接上") {
                    isSameInputTarget(7L, 7L, connection, Any()) shouldBe false
                }
            }

            When("发起时没有连接（没有编辑框），之后有了连接") {
                Then("放弃，不能把后来出现的编辑框当成原目标") {
                    isSameInputTarget(7L, 7L, null, connection) shouldBe false
                }
            }

            When("前后都没有连接") {
                Then("视为同一目标（调用方随后会因 ic 为空而直接返回）") {
                    isSameInputTarget(0L, 0L, null, null) shouldBe true
                }
            }
        }
    })
