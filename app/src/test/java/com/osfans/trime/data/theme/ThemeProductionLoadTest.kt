// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * 用**真实的生产加载路径**读随包发布的主题。
 *
 * 与 ThemeTestSupport 的区别：那里直接调 ThemeDslExpander 并跳过自动补丁注入，
 * 这里走 [ThemeLoader.loadFromSource]，也就是应用真正用的那条路
 * （读源码 → 注入 `<id>.custom.yaml` 的 patch → 展开 → decode）。
 *
 * 键盘空白如果源于主题加载失败，这个测试会直接红。
 */
class ThemeProductionLoadTest :
    StringSpec({
        val assetDir = File("src/main/assets/shared")

        fun loaderFor(dir: File) = ThemeLoader.SourceLoader { id -> File(dir, "$id.yaml").takeIf { it.isFile } }

        "随包的 trime.yaml 能走通生产加载路径" {
            val result = ThemeLoader.loadFromSource("trime", null, loaderFor(assetDir))

            (result != null) shouldBe true
            (result is ThemeLoader.ThemeLoadResult.Success) shouldBe true

            val theme = (result as ThemeLoader.ThemeLoadResult.Success).theme
            // fork 的 5 个键盘必须都在
            theme.presetKeyboards.keys.containsAll(
                listOf("my_pinyin", "my_english", "symbols", "number", "emoji"),
            ) shouldBe true
            // 配色表必须非空（否则 ThemeLoader 会拒绝）
            (theme.colorSchemes.isNotEmpty()) shouldBe true
        }

        "本次补回的预设都能在加载结果里查到" {
            val theme =
                (
                    ThemeLoader.loadFromSource("trime", null, loaderFor(assetDir))
                        as ThemeLoader.ThemeLoadResult.Success
                    ).theme

            val needed =
                listOf(
                    "select_all", "Clear", "BackToPreviousSyllable", "copy", "cut", "paste",
                    "undo", "redo", "CommitComment", "Return", "Return1", "BackSpace",
                    "Shift_L", "space", "Delete", "Insert", "Escape", "Keyboard_emoji",
                    "space_cursor", "backspace_clear",
                )
            val missing = needed.filterNot { theme.presetKeys.containsKey(it) }
            missing shouldBe emptyList()
        }

        "用户补丁里的斜杠键会让源码路径放弃（此时必须靠兜底，不能空白）" {
            val dir = Files.createTempDirectory("trime-theme").toFile()
            try {
                File(assetDir, "trime.yaml").copyTo(File(dir, "trime.yaml"), overwrite = true)
                // 旧版本写过的那种 librime 路径键
                File(dir, "trime.custom.yaml").writeText(
                    """
                    patch:
                      "preset_color_schemes/user_bg":
                        name: 自定义背景
                        key_back_color: 0x99FFFFFF
                    """.trimIndent() + "\n",
                )

                // 现状：源码路径返回 null（走入部署兜底）
                ThemeLoader.loadFromSource("trime", null, loaderFor(dir)) shouldBe null

                // 但兜底用的「忽略用户补丁」必须能读出主题，否则键盘就白了
                val recovered = ThemeLoader.loadSourceIgnoringCustomPatch("trime", loaderFor(dir))
                (recovered is ThemeLoader.ThemeLoadResult.Success) shouldBe true
            } finally {
                dir.deleteRecursively()
            }
        }
    })
