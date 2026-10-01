// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.charleskorn.kaml.Yaml
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BackgroundPatchTest :
    StringSpec({
        val schemes =
            listOf(
                ColorScheme("default", linkedMapOf("name" to "默认", "key_text_color" to "0xFF000000")),
                ColorScheme("user_light", linkedMapOf("name" to "自定义背景·浅")),
            )
        val ownColors =
            linkedMapOf(
                "name" to "自定义背景",
                "keyboard_back_color" to "girl.gif",
                "key_back_color" to "0x99FFFFFF",
            )

        fun body(image: String = "girl.gif") = BackgroundPatch.buildPatchBody(
            schemes = schemes,
            ownSchemeId = "user_bg",
            ownColors = ownColors + ("keyboard_back_color" to image),
        )

        /** 顶层 `patch:` 只允许出现一次，否则 YAML 解析会失败 */
        fun countTopLevelPatch(text: String) = text.lines().count { it.startsWith("patch:") }

        "配色表被完整带上（顶层键整体替换，漏了就会删掉其它配色）" {
            val body = body()

            body shouldContain "\"default\":"
            body shouldContain "\"user_light\":"
            body shouldContain "\"user_bg\":"
            body shouldContain "\"name\": \"默认\""
        }

        "背景色值是纯键名，不含 librime 路径斜杠 —— 否则会逼出部署并可能让键盘空白" {
            val merged = BackgroundPatch.merge("", body())
            val patch = Yaml.default.parseToYamlNode(merged).mapping!!.pairs["patch"]!!.mapping!!

            // 顶层必须是普通键 `preset_color_schemes`，不能是 `preset_color_schemes/user_bg`
            (patch.pairs["preset_color_schemes"] != null) shouldBe true
            patch.pairs.keys.any { it.contains('/') } shouldBe false

            val scheme = patch.pairs["preset_color_schemes"]!!.mapping!!.pairs["user_bg"]!!.mapping!!
            scheme.pairs["keyboard_back_color"]!!.string shouldBe "girl.gif"
        }

        "空文件时新建 patch 根节点" {
            val merged = BackgroundPatch.merge("", body())

            merged shouldContain "patch:"
            countTopLevelPatch(merged) shouldBe 1
        }

        "用户已有 patch 时插到它下面且不产生第二个 patch 键" {
            val existing =
                """
                # 用户自己写的
                patch:
                  style/color_scheme: user_light
                """.trimIndent()

            val merged = BackgroundPatch.merge(existing, body("cat.gif"))

            countTopLevelPatch(merged) shouldBe 1
            merged shouldContain "style/color_scheme: user_light"

            // 必须仍是合法 YAML：重复的 patch 键会让 kaml 抛异常
            val patch = Yaml.default.parseToYamlNode(merged).mapping!!.pairs["patch"]!!.mapping!!
            patch.pairs["style/color_scheme"]!!.string shouldBe "user_light"
            patch.pairs["preset_color_schemes"]!!.mapping!!.pairs["user_bg"]!!.mapping!!
                .pairs["keyboard_back_color"]!!.string shouldBe "cat.gif"
        }

        "用户文件没有 patch 根节点时追加" {
            val merged = BackgroundPatch.merge("# 只有注释\n", body("a.png"))

            countTopLevelPatch(merged) shouldBe 1
            merged shouldContain "# 只有注释"
            merged shouldContain "\"a.png\""
        }

        "重复应用只保留一个受管区块，旧图片名被替换" {
            val first = BackgroundPatch.merge("", body("old.gif"))
            val second = BackgroundPatch.merge(first, body("new.gif"))

            second shouldContain "new.gif"
            second shouldNotContain "old.gif"
            second.lines().count { it.trim() == BackgroundPatch.MARK_BEGIN } shouldBe 1
            second.lines().count { it.trim() == BackgroundPatch.MARK_END } shouldBe 1
            countTopLevelPatch(second) shouldBe 1
        }

        "已有用户 patch 时重复应用，仍然不动用户内容且只有一个受管区块" {
            val existing =
                """
                patch:
                  style/color_scheme: user_dark
                """.trimIndent()

            val once = BackgroundPatch.merge(existing, body("one.gif"))
            val twice = BackgroundPatch.merge(once, body("two.gif"))

            countTopLevelPatch(twice) shouldBe 1
            twice shouldContain "style/color_scheme: user_dark"
            twice shouldContain "two.gif"
            twice shouldNotContain "one.gif"
            twice.lines().count { it.trim() == BackgroundPatch.MARK_BEGIN } shouldBe 1
        }

        "受管区块夹在用户内容中间时也只删自己那一段" {
            val existing =
                """
                patch:
                  ${BackgroundPatch.MARK_BEGIN}
                  preset_color_schemes:
                    "user_bg":
                      "keyboard_back_color": "gone.gif"
                  ${BackgroundPatch.MARK_END}
                  style/color_scheme: user_light
                """.trimIndent()

            val merged = BackgroundPatch.merge(existing, body("fresh.gif"))

            countTopLevelPatch(merged) shouldBe 1
            merged shouldNotContain "gone.gif"
            merged shouldContain "style/color_scheme: user_light"
            merged shouldContain "fresh.gif"
        }

        "颜色值里的特殊字符不会破坏 YAML" {
            val tricky =
                linkedMapOf(
                    "name" to "带\"引号\"和\\反斜杠",
                    "keyboard_back_color" to "my bg.gif",
                    "key_back_color" to "0xB3FFFFFF",
                )
            val merged =
                BackgroundPatch.merge(
                    "",
                    BackgroundPatch.buildPatchBody(schemes, "user_bg", tricky),
                )

            val scheme =
                Yaml.default.parseToYamlNode(merged).mapping!!
                    .pairs["patch"]!!.mapping!!
                    .pairs["preset_color_schemes"]!!.mapping!!
                    .pairs["user_bg"]!!.mapping!!

            scheme.pairs["name"]!!.string shouldBe "带\"引号\"和\\反斜杠"
            scheme.pairs["keyboard_back_color"]!!.string shouldBe "my bg.gif"
        }
    })
