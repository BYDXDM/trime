// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 输入体验增强的纯逻辑回归：
 *  1. 成对符号自动补全（左引号/括号 → 自动补右半，光标留在中间）；
 *  2. 双击空格补句号的窗口判定；
 *  3. 随包钢琴音效（描述文件 + 音源随包发布）。
 */
class TypingEnhancementsTest :
    StringSpec({
        "成对符号：快捷符号与符号面板里出现的左半都能找到右半" {
            CommonKeyboardActionListener.autoPairCloserFor("（") shouldBe "）"
            CommonKeyboardActionListener.autoPairCloserFor("“") shouldBe "”"
            CommonKeyboardActionListener.autoPairCloserFor("‘") shouldBe "’"
            CommonKeyboardActionListener.autoPairCloserFor("《") shouldBe "》"
            CommonKeyboardActionListener.autoPairCloserFor("【") shouldBe "】"
            CommonKeyboardActionListener.autoPairCloserFor("「") shouldBe "」"
            CommonKeyboardActionListener.autoPairCloserFor("『") shouldBe "』"
        }

        "非左半符号不成对（右半、ASCII、多字一律放行普通上屏）" {
            CommonKeyboardActionListener.autoPairCloserFor("）") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("”") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("(") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("，") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("你好") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("") shouldBe null
        }

        "双击空格：350ms 内的第二次按下才算双击，且必须有前一次记录" {
            CommonKeyboardActionListener.isDoubleSpaceTap(now = 1_000, lastAt = 0) shouldBe false
            CommonKeyboardActionListener.isDoubleSpaceTap(now = 1_000, lastAt = 1_000) shouldBe true
            CommonKeyboardActionListener.isDoubleSpaceTap(now = 1_000, lastAt = 700) shouldBe true
            CommonKeyboardActionListener.isDoubleSpaceTap(now = 1_000, lastAt = 649) shouldBe false
        }

        "钢琴音效随包发布（描述文件 + 五声音阶两八度共 10 个音源）" {
            val dir = File("src/main/assets/shared/soundeffect")
            // 描述文件名必须以 sound.yaml 结尾，否则 SoundEffectManager.listSounds() 扫不到
            val descriptor = File(dir, "pianosound.yaml")
            descriptor.isFile shouldBe true
            val text = descriptor.readText()
            text.contains("name: piano") shouldBe true
            text.contains("folder: piano") shouldBe true
            val sources = File(dir, "piano").listFiles { f -> f.name.endsWith(".ogg") }.orEmpty()
            sources.size shouldBe 10
        }
    })
