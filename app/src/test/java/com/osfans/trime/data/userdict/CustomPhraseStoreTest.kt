// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.userdict

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class CustomPhraseStoreTest :
    StringSpec({
        "解析：跳过注释行、空行与字段不足的行" {
            val content =
                """
                # Rime table
                # 这是注释

                崩坏：星穹铁道	bt
                只有一列
                	缺短语
                缺编码	
                GitHub	github	100
                """.trimIndent()

            CustomPhraseStore.parse(content) shouldContainExactly
                listOf(
                    CustomPhrase("崩坏：星穹铁道", "bt"),
                    CustomPhrase("GitHub", "github", 100),
                )
        }

        "解析：权重列非数字时退回 0" {
            CustomPhraseStore.parse("词\tci\tabc") shouldContainExactly listOf(CustomPhrase("词", "ci", 0))
        }

        "解析：编码列两侧空白被裁掉，短语列保持原样" {
            val phrase = CustomPhraseStore.parse("带 空格\t  code  ").single()
            phrase.text shouldBe "带 空格"
            phrase.code shouldBe "code"
        }

        "解析：CRLF 行尾不残留回车" {
            CustomPhraseStore.parse("词\tci\r\n词二\tci2\r\n") shouldContainExactly
                listOf(
                    CustomPhrase("词", "ci"),
                    CustomPhrase("词二", "ci2"),
                )
        }

        "序列化：带表头、权重为 0 时省略第三列" {
            val content =
                CustomPhraseStore.serialize(
                    listOf(
                        CustomPhrase("崩坏：星穹铁道", "bt"),
                        CustomPhrase("GitHub", "github", 100),
                    ),
                )

            content shouldStartWith "# Rime table\n"
            content shouldContain "崩坏：星穹铁道\tbt\n"
            content shouldContain "GitHub\tgithub\t100\n"
        }

        "序列化：绝不写入会关闭注释解析的哨兵行" {
            CustomPhraseStore
                .serialize(listOf(CustomPhrase("词", "ci")))
                .lineSequence()
                .none { it == "# no comment" } shouldBe true
        }

        "往返：serialize 后 parse 得到等价列表" {
            val phrases =
                listOf(
                    CustomPhrase("崩坏：星穹铁道", "bt"),
                    CustomPhrase("GitHub", "github", 100),
                    CustomPhrase("带\"引号\"", "quote", -5),
                )

            CustomPhraseStore.parse(CustomPhraseStore.serialize(phrases)) shouldBe phrases
        }

        "往返：空列表序列化后解析回空列表" {
            CustomPhraseStore.parse(CustomPhraseStore.serialize(emptyList())) shouldBe emptyList()
        }

        "upsert：同编码同短语覆盖权重，不新增行" {
            val phrases = listOf(CustomPhrase("词", "ci", 1))

            CustomPhraseStore.upsert(phrases, CustomPhrase("词", "ci", 9)) shouldContainExactly
                listOf(CustomPhrase("词", "ci", 9))
        }

        "upsert：编码相同但短语不同则追加" {
            val phrases = listOf(CustomPhrase("词", "ci", 1))

            CustomPhraseStore.upsert(phrases, CustomPhrase("词二", "ci", 9)) shouldContainExactly
                listOf(
                    CustomPhrase("词", "ci", 1),
                    CustomPhrase("词二", "ci", 9),
                )
        }

        "remove：按 (编码, 短语) 精确删除" {
            val phrases =
                listOf(
                    CustomPhrase("词", "ci", 1),
                    CustomPhrase("词", "ci2", 1),
                )

            CustomPhraseStore.remove(phrases, CustomPhrase("词", "ci")) shouldContainExactly
                listOf(CustomPhrase("词", "ci2", 1))
        }

        "remove：目标不存在时列表不变" {
            val phrases = listOf(CustomPhrase("词", "ci"))

            CustomPhraseStore.remove(phrases, CustomPhrase("别的", "ci")) shouldBe phrases
        }
    })
