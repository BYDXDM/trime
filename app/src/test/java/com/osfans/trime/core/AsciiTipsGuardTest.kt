// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * F02 回归：ASCII 切换提示的延迟恢复必须回到 rime-main 读取原生 context，且只在该请求仍是当前请求、
 * 引擎仍处于 READY 时应用；停止后不得再用旧提示覆盖新状态。
 */
class AsciiTipsGuardTest :
    StringSpec({
        "请求仍是当前代际、引擎就绪、提示未变化时才应用" {
            AsciiTipsGuard.shouldApply(
                requestedGeneration = 7L,
                currentGeneration = 7L,
                ready = true,
                requestedTips = "En",
                currentTips = "En",
            ) shouldBe true
        }

        "被更新的提示请求取代后旧任务不再应用" {
            AsciiTipsGuard.shouldApply(
                requestedGeneration = 6L,
                currentGeneration = 7L,
                ready = true,
                requestedTips = "En",
                currentTips = "En",
            ) shouldBe false
        }

        "引擎停止后旧提示不覆盖新状态" {
            AsciiTipsGuard.shouldApply(
                requestedGeneration = 3L,
                currentGeneration = 3L,
                ready = false,
                requestedTips = "En",
                currentTips = "En",
            ) shouldBe false
        }

        "提示文本已被切换（如换 schema）时不回写旧文本" {
            AsciiTipsGuard.shouldApply(
                requestedGeneration = 3L,
                currentGeneration = 3L,
                ready = true,
                requestedTips = "En",
                currentTips = "明月",
            ) shouldBe false
        }

        "停止重启后代际递增，重启前的旧任务作废" {
            var generation = 0L
            generation++ // 旧会话的提示请求
            val requested = generation
            generation++ // finalize() 使旧任务作废
            generation++ // 重启后的新请求
            AsciiTipsGuard.shouldApply(
                requestedGeneration = requested,
                currentGeneration = generation,
                ready = true,
                requestedTips = "En",
                currentTips = "En",
            ) shouldBe false
        }

        "延迟恢复在 rime-main 上读取原生 context" {
            val source = File("src/main/java/com/osfans/trime/core/Rime.kt").readText()
            val launchIndex = source.indexOf("asciiSwitchTipsJob = lifecycleScope.launch {")
            check(launchIndex > 0) { "tips job launch not found" }
            val delayIndex = source.indexOf("delay(1000L)", launchIndex)
            check(delayIndex > launchIndex) { "delay(1000L) not found after the tips job launch" }
            val hopIndex = source.indexOf("withRimeContext", delayIndex)
            check(hopIndex > delayIndex) { "delayed tips restore must go through withRimeContext" }
            val readIndex = source.indexOf("getRimeContext()", delayIndex)
            check(readIndex > hopIndex) { "getRimeContext() must run inside withRimeContext" }
            source.substring(delayIndex, hopIndex).contains("getRimeContext()") shouldBe false
        }

        "finalize 使待恢复的提示任务作废" {
            val source = File("src/main/java/com/osfans/trime/core/Rime.kt").readText()
            val finalizeIndex = source.indexOf("fun finalize()")
            check(finalizeIndex > 0) { "finalize() not found" }
            val body = source.substring(finalizeIndex, source.indexOf("companion object", finalizeIndex))
            body shouldContain "asciiTipsGeneration++"
            body shouldContain "asciiSwitchTipsJob?.cancel()"
        }

        "R1：代际计数器必须 volatile，finalize 会在非 rime-main 线程自增" {
            // finalize() 可能由 STOPPED 状态回调或服务销毁路径触发，不在 rime-main 上；
            // 普通 Long 字段在跨线程可见性上没有保证，延迟任务可能读到过期代际而误判「仍是当前请求」。
            val field = Rime::class.java.getDeclaredField("asciiTipsGeneration")
            ((field.modifiers and java.lang.reflect.Modifier.VOLATILE) != 0) shouldBe true
        }
    })
