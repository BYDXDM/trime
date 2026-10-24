/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CandidateGlyphFilterTest :
    StringSpec({
        fun candidate(text: String) = CandidateProto(text = text, comment = "", label = "")

        "BMP 汉字与 ASCII 一律放行" {
            CandidateGlyphFilter.canRender("") shouldBe true
            CandidateGlyphFilter.canRender("a") shouldBe true
            CandidateGlyphFilter.canRender("你") shouldBe true
            CandidateGlyphFilter.canRender("崩坏：星穹铁道") shouldBe true
            // BMP 边界：U+FFFF 仍属 BMP
            CandidateGlyphFilter.canRender("\uFFFF") shouldBe true
        }

        "补充平面码位判为画不出来" {
            // U+10000 是补充平面起点，U+20F1E 是实测里出现过的一个 Ext-B 字
            CandidateGlyphFilter.canRender("\uD800\uDC00") shouldBe false
            CandidateGlyphFilter.canRender("\uD843\uDF1E") shouldBe false
            CandidateGlyphFilter.canRender("你\uD843\uDF1E") shouldBe false
        }

        "过滤保留原下标，供 selectCandidate 使用" {
            val list = listOf(candidate("你"), candidate("\uD843\uDF1E"), candidate("好"))
            val filtered = CandidateGlyphFilter.filter(list, CandidateGlyphFilter::canRender)
            filtered.candidates.map { it.text } shouldContainExactly listOf("你", "好")
            filtered.indices.toList() shouldContainExactly listOf(0, 2)
        }

        "全部可画时下标是恒等映射" {
            val list = listOf(candidate("a"), candidate("b"))
            val filtered = CandidateGlyphFilter.filter(list, CandidateGlyphFilter::canRender)
            filtered.indices.toList() shouldContainExactly listOf(0, 1)
        }

        "全部被过滤时结果为空" {
            val list = listOf(candidate("\uD843\uDF1E"), candidate("\uD800\uDC01"))
            val filtered = CandidateGlyphFilter.filter(list, CandidateGlyphFilter::canRender)
            filtered.candidates shouldBe emptyList()
            filtered.indices.toList() shouldBe emptyList()
        }
    })
