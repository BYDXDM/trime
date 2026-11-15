// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.core.RimeLifecycle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * F04 回归：启动中（STARTING）到达的停止请求不能被丢弃，必须在启动完成时按当时的会话情况重新裁决；
 * 启动期间重新连接的新会话不能被旧的停止请求误伤。
 */
class DeferredStopPolicyTest :
    StringSpec({
        "启动中销毁最后会话时挂起停止请求而不是忽略" {
            DeferredStopPolicy.actionFor(RimeLifecycle.State.STARTING) shouldBe DeferredStopPolicy.Action.DEFER
        }

        "就绪时销毁最后会话立即停止" {
            DeferredStopPolicy.actionFor(RimeLifecycle.State.READY) shouldBe DeferredStopPolicy.Action.STOP_NOW
        }

        "停止中与已停止时重复请求不再触发停止" {
            DeferredStopPolicy.actionFor(RimeLifecycle.State.STOPPING) shouldBe DeferredStopPolicy.Action.IGNORE
            DeferredStopPolicy.actionFor(RimeLifecycle.State.STOPPED) shouldBe DeferredStopPolicy.Action.IGNORE
        }

        "启动完成且已无会话时执行挂起的停止" {
            DeferredStopPolicy.shouldStopOnReady(pendingStop = true, sessionCount = 0) shouldBe true
        }

        "启动完成但已重新连接会话时不停，旧停止不误伤新会话" {
            DeferredStopPolicy.shouldStopOnReady(pendingStop = true, sessionCount = 1) shouldBe false
        }

        "没有挂起请求时启动完成不停止" {
            DeferredStopPolicy.shouldStopOnReady(pendingStop = false, sessionCount = 0) shouldBe false
        }

        "等价模型：慢启动中销毁最后会话，旧 guard 丢请求、新流程补停" {
            var state = RimeLifecycle.State.STARTING
            var sessions = 1
            var nativeFinalizes = 0

            // 旧实现：destroySession 直接调 Rime.finalize()，只接受 READY
            fun legacyFinalize(): Boolean {
                if (state != RimeLifecycle.State.READY) return false
                nativeFinalizes++
                state = RimeLifecycle.State.STOPPING
                return true
            }

            sessions--
            legacyFinalize() shouldBe false
            state = RimeLifecycle.State.READY // 慢启动最终完成
            nativeFinalizes shouldBe 0 // 旧实现：引擎无客户端仍在运行
            state shouldBe RimeLifecycle.State.READY

            // 新实现：STARTING 时挂起，READY 时按会话数复核
            state = RimeLifecycle.State.STARTING
            sessions = 1
            nativeFinalizes = 0
            var pendingStop = false
            sessions--
            if (DeferredStopPolicy.actionFor(state) == DeferredStopPolicy.Action.DEFER) pendingStop = true
            pendingStop shouldBe true
            state = RimeLifecycle.State.READY
            if (DeferredStopPolicy.shouldStopOnReady(pendingStop, sessions)) {
                pendingStop = false
                nativeFinalizes++
                state = RimeLifecycle.State.STOPPING
            }
            nativeFinalizes shouldBe 1
        }

        "等价模型：启动中销毁后又重新连接，启动完成时保留新会话" {
            var state = RimeLifecycle.State.STARTING
            var sessions = 1
            var nativeFinalizes = 0
            var pendingStop = false

            sessions-- // 最后一个会话销毁
            if (DeferredStopPolicy.actionFor(state) == DeferredStopPolicy.Action.DEFER) pendingStop = true
            sessions = 1 // 启动尚未完成，新会话重新连接
            state = RimeLifecycle.State.READY
            if (DeferredStopPolicy.shouldStopOnReady(pendingStop, sessions)) {
                nativeFinalizes++
            }
            nativeFinalizes shouldBe 0
        }

        "RimeDaemon 的销毁路径经策略裁决，不再直接 finalize" {
            val source = File("src/main/java/com/osfans/trime/daemon/RimeDaemon.kt").readText()
            val destroyIndex = source.indexOf("fun destroySession(name: String)")
            check(destroyIndex > 0) { "destroySession not found" }
            val body = source.substring(destroyIndex, source.indexOf("private fun requestFinalizeLocked", destroyIndex))
            body shouldContain "requestFinalizeLocked()"
            check(!body.contains("realRime.finalize()")) { "destroySession must not finalize directly" }
            val finalizeIndex = source.indexOf("private fun requestFinalizeLocked")
            val finalizeBody = source.substring(finalizeIndex, source.indexOf("fun getFirstSessionOrNull", finalizeIndex))
            finalizeBody shouldContain "DeferredStopPolicy.actionFor"
        }

        "RimeDaemon 在启动完成时复核挂起的停止请求" {
            val source = File("src/main/java/com/osfans/trime/daemon/RimeDaemon.kt").readText()
            val initIndex = source.indexOf("init {")
            check(initIndex > 0) { "init block not found" }
            val body = source.substring(initIndex, source.indexOf("private inline fun sendNotification", initIndex))
            body shouldContain "stateFlow.collect"
            body shouldContain "DeferredStopPolicy.shouldStopOnReady"
        }

        "finalize 期间没有新会话接入时不重启" {
            DeferredStopPolicy.shouldRestartAfterFinalize(sessionCount = 0) shouldBe false
        }

        "finalize 期间接入新会话时必须把引擎拉回来" {
            DeferredStopPolicy.shouldRestartAfterFinalize(sessionCount = 1) shouldBe true
        }

        "等价模型：停止与 createSession 交错时，旧流程留下「有会话无引擎」" {
            // 旧流程：锁内算出 stopNow，锁外 finalize，结束后不再复核。
            // 新流程：finalize 后在锁内复核 sessions，仍有会话就 tryStartRimeLocked()。
            fun runScenario(recheckAfterFinalize: Boolean): Pair<Boolean, Int> {
                var engineRunning = true
                var sessions = 0
                var restarts = 0
                val stopNow = DeferredStopPolicy.shouldStopOnReady(pendingStop = true, sessionCount = sessions)
                if (stopNow) {
                    engineRunning = false // 锁外 realRime.finalize()
                    sessions++ // 同一窗口内 createSession 成功
                    if (recheckAfterFinalize && DeferredStopPolicy.shouldRestartAfterFinalize(sessions)) {
                        engineRunning = true // tryStartRimeLocked()
                        restarts++
                    }
                }
                return engineRunning to restarts
            }

            runScenario(recheckAfterFinalize = false) shouldBe (false to 0) // 旧流程：会话在、引擎没了
            runScenario(recheckAfterFinalize = true) shouldBe (true to 1) // 新流程：引擎被拉回来
        }

        "RimeDaemon 在 finalize 之后于锁内复核会话并重启" {
            val source = File("src/main/java/com/osfans/trime/daemon/RimeDaemon.kt").readText()
            val readyIndex = source.indexOf("Rime became ready without any session, finalize the deferred stop")
            check(readyIndex > 0) { "deferred stop branch not found" }
            val finalizeIndex = source.indexOf("realRime.finalize()", readyIndex)
            check(finalizeIndex > readyIndex) { "finalize must run in the deferred stop branch" }
            val recheckIndex = source.indexOf("DeferredStopPolicy.shouldRestartAfterFinalize", finalizeIndex)
            check(recheckIndex > finalizeIndex) { "sessions must be rechecked after finalize" }
            val between = source.substring(finalizeIndex, recheckIndex)
            between shouldContain "lock.withLock"
            val tail = source.substring(recheckIndex, source.indexOf("STOPPED ->", recheckIndex))
            tail shouldContain "tryStartRimeLocked()"
            tail shouldContain "scheduleStartupRetry()"
        }
    })
