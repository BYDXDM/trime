// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Rime 启动门槛的真值表。
 *
 * 这里钉死一条**首次安装必现**的回归：默认 `dataStorageMode = EXTERNAL_SYNC`、
 * `externalRimeTreeUri = ""`，所以全新安装时「存储方式」这一步尚未完成
 * （`storageChoiceDone = false`）。若把这种情况当成「存储不可用」而跳过启动，
 * Rime 永远起不来 → ThemeScope 永不就绪 → `onCreateInputView` 无限推迟 →
 * **键盘整块空白、打不出中文**。
 *
 * 真机/模拟器日志实证（release 包，全新安装）：
 * ```
 * W [main]: Skip starting rime: storage not available!
 * I [main]: Scheduling Rime startup retry until storage is available
 * W [main]: Rime startup retry exhausted while sessions remain connected
 * W [main]: ThemeScope is not ready yet; deferring input view creation
 * ```
 */
class RimeStartupGateTest :
    StringSpec({
        "运行目录解析不出来时一律跳过（任何模式都没有东西可加载）" {
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = false,
                usesExternalSync = false,
                hasExternalAccess = false,
                storageChoiceDone = true,
            ) shouldBe true
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = false,
                usesExternalSync = true,
                hasExternalAccess = true,
                storageChoiceDone = true,
            ) shouldBe true
        }

        "★ 全新安装：默认外部同步 + 尚未做过存储选择 + 无授权 → 必须照常启动" {
            // 这正是「首次安装、只启用输入法、从未打开应用」的真实组合
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = true,
                usesExternalSync = true,
                hasExternalAccess = false,
                storageChoiceDone = false,
            ) shouldBe false
        }

        "应用内存储模式：只要运行目录就绪就不跳过" {
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = true,
                usesExternalSync = false,
                hasExternalAccess = false,
                storageChoiceDone = false,
            ) shouldBe false
        }

        "外部同步且授权有效：不跳过" {
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = true,
                usesExternalSync = true,
                hasExternalAccess = true,
                storageChoiceDone = true,
            ) shouldBe false
        }

        "已做过选择但权限失效（被撤销/换机恢复）：继续等待授权" {
            RimeStartupGate.shouldSkipStartup(
                runtimeReady = true,
                usesExternalSync = true,
                hasExternalAccess = false,
                storageChoiceDone = true,
            ) shouldBe true
        }

        "「未做选择」与「已做选择但无权限」必须区分对待" {
            // 二者 hasExternalAccess 都是 false，唯一差别是 storageChoiceDone；
            // 前者要启动（否则首次安装打不出中文），后者要等待。
            val noChoiceYet =
                RimeStartupGate.shouldSkipStartup(true, usesExternalSync = true, hasExternalAccess = false, storageChoiceDone = false)
            val choseButRevoked =
                RimeStartupGate.shouldSkipStartup(true, usesExternalSync = true, hasExternalAccess = false, storageChoiceDone = true)
            noChoiceYet shouldBe false
            choseButRevoked shouldBe true
        }
    })
