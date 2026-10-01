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
                    theme.presetKeys.size shouldBe 34
                    // 补回的预设里 copy/paste 曾经是哑键，这里守住它们
                    theme.presetKeys.getValue("copy").send shouldBe "Control+c"
                    theme.presetKeys.getValue("paste").send shouldBe "Control+v"
                    theme.presetKeys.getValue("BackToPreviousSyllable").send shouldBe "Control+BackSpace"
                }

                Then("the 5 fork keyboards are decoded with their keys") {
                    theme.presetKeyboards.size shouldBe 5
                    theme.presetKeyboards.keys shouldBe
                        setOf("my_pinyin", "my_english", "symbols", "number", "emoji")

                    val pinyin = theme.presetKeyboards.getValue("my_pinyin")
                    pinyin.name shouldBe "拼音26键"
                    pinyin.width shouldBe 10f
                    pinyin.height shouldBe 52f
                    pinyin.lock shouldBe true
                    pinyin.asciiMode shouldBe false
                    pinyin.keys.size shouldBe 38
                    pinyin.labelTransform shouldBe TextKeyboard.LabelTransform.UPPERCASE
                    // 首键：click=q，上滑=!（键面上的「1」是 label/hint，不是上滑内容）
                    pinyin.keys.first().behaviors[KeyBehavior.CLICK] shouldBe
                        KeyActionToken.Plain("q")
                    pinyin.keys.first().behaviors[KeyBehavior.SWIPE_UP] shouldBe
                        KeyActionToken.Plain("!")

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
