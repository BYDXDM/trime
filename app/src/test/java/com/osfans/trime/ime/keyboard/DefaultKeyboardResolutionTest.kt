// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.ThemeLoader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 覆盖实际生产代码中的键盘选择兜底，防止未知方案解析成零按键键盘。
 *
 * fork 只保留 my_pinyin / my_english / symbols / number / emoji，主题里没有
 * 上游的 default / qwerty 别名；因此必须由 [pickFallbackKeyboard] 选出可绘制键盘。
 */
class DefaultKeyboardResolutionTest :
    StringSpec({
        val theme =
            (
                ThemeLoader.loadFromSource(
                    "trime",
                    null,
                    ThemeLoader.SourceLoader { id ->
                        File("src/main/assets/shared/$id.yaml").takeIf { it.isFile }
                    },
                ) as ThemeLoader.ThemeLoadResult.Success
                ).theme
        val ids = theme.presetKeyboards.keys.toList()

        "fork 的 12 个键盘都在（符号按搜狗习惯拆成 7 组 + 颜文字）" {
            ids shouldBe listOf("my_pinyin", "my_english", "symbols", "symbols_cn", "symbols_en", "symbols_net", "symbols_math", "symbols_arrow", "symbols_num", "kaomoji", "number", "emoji")
        }

        "方案 id 优先匹配同名键盘" {
            layoutNameForAlphabet("abc", ids, "my_pinyin") shouldBe "my_pinyin"
        }

        "上游 qwerty 名称不存在时返回空候选并交给兜底" {
            layoutNameForAlphabet("abcdefghijklmnopqrstuvwxyz", ids, "luna_pinyin_simp") shouldBe ""
            val fallback = pickFallbackKeyboard(ids) { theme.presetKeyboards[it]?.keys?.size ?: 0 }
            fallback shouldBe "my_pinyin"
        }

        "空 alphabet 不会因 all 谓词真值而误选 qwerty" {
            layoutNameForAlphabet("", ids, "unknown_schema") shouldBe ""
        }

        "没有非空键盘时仍返回已定义 id" {
            pickFallbackKeyboard(listOf("empty", "other")) { 0 } shouldBe "empty"
        }
    })
