// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * 输入体验增强的纯逻辑回归：
 *  1. 成对符号自动补全（左引号/括号 → 自动补右半，光标留在中间）；
 *  2. 双击空格补句号的窗口判定；
 *  3. 随包钢琴音效（描述文件 + 音源随包发布）。
 */
class TypingEnhancementsTest :
    StringSpec({
        beforeTest { KeyCode.androidKeyNameToCode = { 0 } }

        "成对符号：快捷符号与符号面板里出现的左半都能找到右半" {
            CommonKeyboardActionListener.autoPairCloserFor("（") shouldBe "）"
            CommonKeyboardActionListener.autoPairCloserFor("“") shouldBe "”"
            CommonKeyboardActionListener.autoPairCloserFor("‘") shouldBe "’"
            CommonKeyboardActionListener.autoPairCloserFor("《") shouldBe "》"
            CommonKeyboardActionListener.autoPairCloserFor("【") shouldBe "】"
            CommonKeyboardActionListener.autoPairCloserFor("「") shouldBe "」"
            CommonKeyboardActionListener.autoPairCloserFor("『") shouldBe "』"
        }

        "成对符号：ASCII 左括号也要能补右半（英文键盘 z 上滑 / o 键下滑）" {
            CommonKeyboardActionListener.autoPairCloserFor("(") shouldBe ")"
            CommonKeyboardActionListener.autoPairCloserFor("[") shouldBe "]"
            CommonKeyboardActionListener.autoPairCloserFor("{") shouldBe "}"
        }

        "成对符号：引号不做 ASCII 配对（同一个键既开又闭，现有实现分不清）" {
            CommonKeyboardActionListener.autoPairCloserFor("\"") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("'") shouldBe null
        }

        "非左半符号不成对（右半、多字一律放行普通上屏）" {
            CommonKeyboardActionListener.autoPairCloserFor("）") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("”") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor(")") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("]") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("}") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("，") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("a") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("1") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("你好") shouldBe null
            CommonKeyboardActionListener.autoPairCloserFor("") shouldBe null
        }

        "ASCII 括号的键码通路：`(` 落在 text 上，`[` `{` 被解析成键码" {
            // 这是 AUTO_PAIR_BY_KEYCODE 存在的理由：`[` `{` 有键码 → KeyAction 的 text 为空，
            // 只有靠键码才认得出它们。
            KeyCode.nameToKeyCode("[") shouldBe KeyEvent.KEYCODE_LEFT_BRACKET
            KeyCode.nameToKeyCode("{") shouldNotBe 0
            KeyCode.nameToKeyCode("(") shouldBe 0
            KeyCode.nameToKeyCode(")") shouldBe 0
        }

        "ASCII 括号的键码通路：按码能还原回字符，非括号键码返回 null" {
            CommonKeyboardActionListener.autoPairTextForCode(KeyEvent.KEYCODE_LEFT_BRACKET) shouldBe "["
            CommonKeyboardActionListener.autoPairTextForCode(KeyCode.nameToKeyCode("{")) shouldBe "{"
            CommonKeyboardActionListener.autoPairTextForCode(KeyEvent.KEYCODE_RIGHT_BRACKET) shouldBe null
            CommonKeyboardActionListener.autoPairTextForCode(KeyEvent.KEYCODE_Q) shouldBe null
            CommonKeyboardActionListener.autoPairTextForCode(0) shouldBe null
        }

        // 「输入框有字、拼音打到一半突然上屏（ 或（）」这个 bug 的回归测试：
        // rime 的 statusCached 由 emitResponse 最后一步刷新，比按键晚一拍，
        // 组词刚建立时它仍是 isComposing = false，所以组词判定必须能独立拦住。
        "成对符号：中文模式也补，补的是全角右半" {
            CommonKeyboardActionListener.resolveAutoPair(
                text = "（",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe "）"
            CommonKeyboardActionListener.resolveAutoPair(
                text = "【",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe "】"
            CommonKeyboardActionListener.resolveAutoPair(
                text = "「",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe "」"
        }

        "成对符号：中文模式下 ASCII 左半仍补 ASCII 右半" {
            CommonKeyboardActionListener.resolveAutoPair(
                text = "(",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe ")"
        }

        "成对符号：组词中不补全（哪怕 statusCached 还停在 isComposing = false）" {
            CommonKeyboardActionListener.resolveAutoPair(
                text = "（",
                isComposing = true,
                hasLocalComposition = false,
            ) shouldBe null
            CommonKeyboardActionListener.resolveAutoPair(
                text = "(",
                isComposing = true,
                hasLocalComposition = false,
            ) shouldBe null
        }

        "成对符号：输入框里有本地预编辑（分词窗口写过 composingText）也不补" {
            CommonKeyboardActionListener.resolveAutoPair(
                text = "(",
                isComposing = false,
                hasLocalComposition = true,
            ) shouldBe null
        }

        "成对符号：不在组词中才补，右半与非左半一律 null" {
            CommonKeyboardActionListener.resolveAutoPair(
                text = "[",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe "]"
            CommonKeyboardActionListener.resolveAutoPair(
                text = "{",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe "}"
            CommonKeyboardActionListener.resolveAutoPair(
                text = "）",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe null
            CommonKeyboardActionListener.resolveAutoPair(
                text = "》",
                isComposing = false,
                hasLocalComposition = false,
            ) shouldBe null
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
