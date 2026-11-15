// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException

/**
 * F06 回归：SYMBOL 模拟输入必须临时关闭真实选项 ascii_punct（不是拼错的 ascii_punch），
 * 并在输入结束（含异常）后恢复原来的标点状态。
 */
class SymbolInputRunnerTest :
    StringSpec({
        "临时关闭 ascii_punct，模拟输入后再恢复" {
            val calls = mutableListOf<String>()
            SymbolInputRunner.run(
                isAsciiMode = false,
                isAsciiPunct = true,
                setOption = { name, value -> calls += "$name=$value" },
                clearComposition = { calls += "clear" },
                simulateSymbol = { calls += "simulate" },
            )
            calls shouldContainExactly
                listOf(
                    "ascii_punct=false",
                    "clear",
                    "simulate",
                    "ascii_punct=true",
                )
        }

        "初始未开中文标点时不动该选项" {
            val calls = mutableListOf<String>()
            SymbolInputRunner.run(
                isAsciiMode = false,
                isAsciiPunct = false,
                setOption = { name, value -> calls += "$name=$value" },
                clearComposition = { calls += "clear" },
                simulateSymbol = { calls += "simulate" },
            )
            calls shouldContainExactly listOf("clear", "simulate")
        }

        "模拟输入抛异常时仍恢复原标点状态" {
            val calls = mutableListOf<String>()
            shouldThrow<IOException> {
                SymbolInputRunner.run(
                    isAsciiMode = false,
                    isAsciiPunct = true,
                    setOption = { name, value -> calls += "$name=$value" },
                    clearComposition = { calls += "clear" },
                    simulateSymbol = { throw IOException("simulate failed") },
                )
            }
            calls shouldContainExactly
                listOf(
                    "ascii_punct=false",
                    "clear",
                    "ascii_punct=true",
                )
        }

        "ASCII 模式下临时关闭 ascii_mode，保留原有不恢复的语义" {
            val calls = mutableListOf<String>()
            SymbolInputRunner.run(
                isAsciiMode = true,
                isAsciiPunct = false,
                setOption = { name, value -> calls += "$name=$value" },
                clearComposition = { calls += "clear" },
                simulateSymbol = { calls += "simulate" },
            )
            calls shouldContainExactly
                listOf(
                    "ascii_mode=false",
                    "clear",
                    "simulate",
                )
        }

        "两种初始标点状态组合都恢复到初值" {
            listOf(true, false).forEach { initialPunct ->
                listOf(true, false).forEach { asciiMode ->
                    val options = mutableMapOf(SymbolInputRunner.OPTION_ASCII_PUNCT to initialPunct)
                    SymbolInputRunner.run(
                        isAsciiMode = asciiMode,
                        isAsciiPunct = initialPunct,
                        setOption = { name, value -> options[name] = value },
                        clearComposition = {},
                        simulateSymbol = {},
                    )
                    options[SymbolInputRunner.OPTION_ASCII_PUNCT] shouldBe initialPunct
                }
            }
        }

        "选项名与 librime 一致，且不再出现拼错的 ascii_punch" {
            SymbolInputRunner.OPTION_ASCII_PUNCT shouldBe "ascii_punct"
            SymbolInputRunner.OPTION_ASCII_MODE shouldBe "ascii_mode"
            val source = File("src/main/java/com/osfans/trime/ime/symbol/LiquidWindow.kt").readText()
            source.contains("ascii_punch") shouldBe false
            source.contains("SymbolInputRunner.run") shouldBe true
        }
    })
