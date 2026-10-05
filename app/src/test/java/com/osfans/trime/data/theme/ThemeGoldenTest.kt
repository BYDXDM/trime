/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyBehavior
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Golden tests for the shipped themes: parse + decode, key fields compared against the
 * source files value by value.
 *
 * - tongwenfeng.trime.yaml: no librime DSL; decodes as-is, covering anchors/aliases
 *   (style values via `*hgap`/`*jpgd4`/...) and flow mappings (`preset_keys`/`keys`).
 * - trime.yaml: two `__include` entries (librime DSL), expanded by [ThemeDslExpander] before
 *   decoding: `letter` inherits /preset_keyboards/default, `scj6` is a copy of cangjie5.
 */
class ThemeGoldenTest :
    BehaviorSpec({
        Given("the built-in tongwenfeng.trime.yaml") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("tongwenfeng.trime.yaml")

            When("the whole file is decoded") {
                Then("theme header and style scalars are preserved") {
                    theme.name shouldBe "标准"
                    val style = theme.generalStyle
                    style.autoCaps shouldBe false
                    style.candidateTextSize shouldBe 18f
                    style.keyTextSize shouldBe 24f
                    style.keyWidth shouldBe 10f
                    style.keyboardHeight shouldBe 250
                    style.keyboardHeightLand shouldBe 200
                }

                Then("style values referenced through anchors/aliases resolve to the anchored values") {
                    // File defines height: {4: &jpgd4 48}, 6: &hgap 4, 7: &sgap 12, 1: &round1 6;
                    // style references them via *jpgd4 / *hgap / *sgap / *round1.
                    val style = theme.generalStyle
                    style.keyHeight shouldBe 48
                    style.horizontalGap shouldBe 4
                    style.verticalGap shouldBe 12
                    style.roundCorner shouldBe 6f
                }

                Then("enter labels are decoded") {
                    val enterLabel = theme.generalStyle.enterLabel
                    enterLabel.go shouldBe "前往"
                    enterLabel.done shouldBe "完成"
                    enterLabel.default shouldBe "Enter"
                }

                Then("all 50 preset keyboards are decoded") {
                    theme.presetKeyboards.size shouldBe 50
                    theme.presetKeyboards shouldContainKey "default"
                    theme.presetKeyboards shouldContainKey "letter"
                    theme.presetKeyboards shouldContainKey "number"
                    theme.presetKeyboards shouldContainKey "bqrw1"
                }

                Then("the default keyboard decodes keys incl. inline flow mappings and per-key colors") {
                    val keyboard = theme.presetKeyboards.getValue("default")
                    keyboard.name shouldBe "26键默认布局"
                    keyboard.author shouldBe "暖暖"
                    keyboard.width shouldBe 10f
                    keyboard.asciiMode shouldBe false
                    keyboard.keys.size shouldBe 37
                    val firstKey = keyboard.keys.first()
                    firstKey.behaviors[KeyBehavior.CLICK] shouldBe KeyActionToken.Plain("q")
                    firstKey.behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain("1")
                    firstKey.keyBackColor shouldBe "bh1"
                    firstKey.keyTextColor shouldBe "th1"
                }

                Then("all 46 color schemes are decoded, with the default scheme intact") {
                    theme.colorSchemes.size shouldBe 46
                    val defaultScheme = theme.colorSchemes.first { it.id == "default" }
                    defaultScheme.colors["name"] shouldBe "标准配色！"
                    defaultScheme.colors["dark_scheme"] shouldBe "steam"
                }

                Then("fallback colors override table is decoded") {
                    theme.fallbackColors shouldBe mapOf("candidate_text_color" to "text_color")
                }

                Then("preset keys with inline maps are decoded") {
                    theme.presetKeys shouldContainKey "BRIGHTNESS_DOWN"
                    val brightnessDown = theme.presetKeys.getValue("BRIGHTNESS_DOWN")
                    brightnessDown.label shouldBe "亮度-"
                    brightnessDown.send shouldBe "BRIGHTNESS_DOWN"
                }
            }
        }

        // 本段原本以上游 trime.yaml 为基准（預設 / 18 个键盘 / letter / scj6 / cangjie5）。
        // fork 把主题重写成「5 个键盘 + myime」，那些断言永远不可能满足，
        // 所以这里换成 **fork 自己的基准**，并保留「键盘一定能画出来」这条真正
        // 有价值的约束。
        Given("the built-in trime.yaml (fork theme)") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")

            When("the whole file is decoded") {
                Then("theme header and style scalars are preserved") {
                    theme.name shouldBe "myime"
                    val style = theme.generalStyle
                    style.candidateTextSize shouldBe 22f
                    style.keyHeight shouldBe 52
                    style.horizontalGap shouldBe 1
                }

                Then("color schemes and preset keys are decoded") {
                    // fork 只保留 4 个配色：default / dark / user_light / user_dark
                    theme.colorSchemes.size shouldBe 4
                    theme.colorSchemes.map { it.id } shouldBe
                        listOf("default", "dark", "user_light", "user_dark")
                    // fork 把 preset_keys 从 16 个补回到 34 个（见 README-FORK 坑 5）
                    // 34 → 41：符号面板按搜狗习惯拆成 7 组后，preset_keys 多了
                    // 7 条分组页签（SymbolsTab_*）。
                    theme.presetKeys.size shouldBe 41
                    // 补回的预设里 copy/paste 曾经是哑键，这里守住它们
                    theme.presetKeys.getValue("copy").send shouldBe "Control+c"
                    theme.presetKeys.getValue("paste").send shouldBe "Control+v"
                    theme.presetKeys.getValue("BackToPreviousSyllable").send shouldBe "Control+BackSpace"
                }

                // 5 → 11：符号面板按搜狗习惯拆成 7 组（常用/中文/英文/网络/数学/箭头/序号），
                // 原来的 symbols 变成「常用」，另外多出 6 个分组键盘。
                Then("the 11 fork keyboards are decoded with their keys") {
                    theme.presetKeyboards.size shouldBe 11
                    theme.presetKeyboards.keys shouldBe
                        setOf(
                            "my_pinyin",
                            "my_english",
                            "symbols",
                            "symbols_cn",
                            "symbols_en",
                            "symbols_net",
                            "symbols_math",
                            "symbols_arrow",
                            "symbols_num",
                            "number",
                            "emoji",
                        )

                    val pinyin = theme.presetKeyboards.getValue("my_pinyin")
                    pinyin.name shouldBe "拼音26键"
                    pinyin.width shouldBe 10f
                    pinyin.height shouldBe 52f
                    pinyin.lock shouldBe true
                    pinyin.asciiMode shouldBe false
                    // 37 键：Z 行原本 9 键（含一个与 A 行重复的 l/@），删掉后为 8 键，
                    // 并把该行字母键宽度 8.75 → 10 补足行宽（15 + 7×10 + 15 = 100）。
                    pinyin.keys.size shouldBe 37
                    pinyin.labelTransform shouldBe TextKeyboard.LabelTransform.UPPERCASE
                    // 首键：click=q，上滑=1（键顶角标那个符号），下滑=!（备选符号）。
                    // 角标画在键顶部，用户自然朝角标方向（向上）滑，所以上滑必须出角标
                    // 那个符号。早前是反的（上滑=!、下滑=1）：用户上滑拿到的是「另一个」
                    // 符号，问号键 l 上滑会得到倒问号 ¿，看起来像 bug（用户实测报障）。
                    pinyin.keys.first().behaviors[KeyBehavior.CLICK] shouldBe
                        KeyActionToken.Plain("q")
                    pinyin.keys.first().behaviors[KeyBehavior.SWIPE_UP] shouldBe
                        KeyActionToken.Plain("1")
                    pinyin.keys.first().behaviors[KeyBehavior.SWIPE_DOWN] shouldBe
                        KeyActionToken.Plain("!")

                    // Z 行必须恰好 8 键（Shift + 7 字母 + BackSpace）。
                    // 删键忘了补宽度会让行宽不足 100 → 整行错位，这里守住它。
                    val zRow = pinyin.keys.filter { k ->
                        k.behaviors[KeyBehavior.CLICK]?.let { token ->
                            token is KeyActionToken.Plain && token.token == "z"
                        } ?: false
                    }
                    zRow.size shouldBe 1

                    // 角标走 labelSymbol 字段（画在键顶部）。
                    // 先前误用 label（会顶掉字母本身）与 hint（画在键底部、
                    // 与 labelSymbol 同值时上下各出一排重复符号），故断言二者为空。
                    pinyin.keys.first().labelSymbol shouldBe "1"
                    pinyin.keys.first().hint shouldBe ""

                    // 英文键盘是 ascii_mode 的载体（英文不是 Rime 方案）
                    val english = theme.presetKeyboards.getValue("my_english")
                    english.asciiMode shouldBe true
                    english.labelTransform shouldBe TextKeyboard.LabelTransform.NONE
                }

                Then("every keyboard decodes a non-empty key set") {
                    // 这条是防止「键盘区域整块空白」的关键约束：
                    // KeyboardWindow 拿到零按键的键盘就画不出任何东西。
                    theme.presetKeyboards.forEach { (id, keyboard) ->
                        withClue("键盘 " + id + " 必须至少有一个按键") {
                            keyboard.keys shouldNotBe emptyList<TextKeyboard.TextKey>()
                        }
                    }
                }
            }
        }
    })
