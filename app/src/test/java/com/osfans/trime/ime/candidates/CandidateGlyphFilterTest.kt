/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.candidates.unrolled.loadProjectedCandidatePage
import com.osfans.trime.ime.candidates.unrolled.rawIndexAfterProjectedCount
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

        "补充平面码位按保守策略过滤" {
            // U+10000 是补充平面起点，U+20F1E 是补充平面样例；这里只验证代码策略
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

        "共享投影保留 BMP 生僻字和重复候选的顺序及原始下标" {
            val list = listOf("风", "\uD843\uDF1E", "飌", "黑", "风", "feng", "风筝").map(::candidate)
            val filtered = CandidateGlyphFilter.filter(list, blacklist = setOf("黑"))
            filtered.candidates.map { it.text } shouldContainExactly listOf("风", "飌", "风", "feng", "风筝")
            filtered.indices.toList() shouldContainExactly listOf(0, 2, 4, 5, 6)
        }

        "紧凑投影前缀与展开后缀拼接无重复无遗漏且保留原始索引" {
            val list = listOf("风", "\uD843\uDF1E", "飌", "黑", "风", "feng", "风筝").map(::candidate)
            val blacklist = setOf("黑")
            val compact = CandidateGlyphFilter.filter(list, blacklist = blacklist)
            for (displayed in 0..compact.candidates.size) {
                val rawStart = rawIndexAfterProjectedCount(displayed, blacklist) { start, limit ->
                    list.drop(start).take(limit).toTypedArray()
                }
                val expanded = CandidateGlyphFilter.filter(list.drop(rawStart), blacklist = blacklist)
                val indices = compact.indices.take(displayed) + expanded.indices.map { it + rawStart }
                indices shouldContainExactly compact.indices.toList()
                indices.map { list[it] } shouldContainExactly compact.candidates
            }
        }

        "跨批次过滤不会把展开起点误当作显示位置" {
            val list = List(52) { candidate(if (it < 49) "\uD843\uDF1E" else "风") }
            val rawStart = rawIndexAfterProjectedCount(2, emptySet()) { start, limit ->
                list.drop(start).take(limit).toTypedArray()
            }
            rawStart shouldBe 51
            val suffix = CandidateGlyphFilter.filter(list.drop(rawStart))
            suffix.indices.map { it + rawStart } shouldContainExactly listOf(51)
        }

        "空列表或全部过滤后不足投影数量时在原始末尾结束" {
            rawIndexAfterProjectedCount(0, emptySet()) { _, _ -> error("zero offset must not fetch") } shouldBe 0
            rawIndexAfterProjectedCount(1, emptySet()) { _, _ -> emptyArray() } shouldBe 0
            val list = List(49) { candidate("\uD843\uDF1E") }
            rawIndexAfterProjectedCount(1, emptySet()) { start, limit ->
                list.drop(start).take(limit).toTypedArray()
            } shouldBe list.size
        }

        "未知总数时后续页沿用 raw key 不重复映射紧凑前缀" {
            val list = listOf("黑", "风", "\uD843\uDF1E", "飌", "黑", "风", "feng", "风筝").map(::candidate)
            val blacklist = setOf("黑")
            val fetch: (Int, Int) -> Array<CandidateProto> = { start, limit ->
                list.drop(start).take(limit).toTypedArray()
            }
            val first = loadProjectedCandidatePage(null, 1, 2, -1, blacklist, fetch)
            first.candidates.map { it.index } shouldContainExactly listOf(3, 5)
            first.nextKey shouldBe 6
            val starts = mutableListOf<Int>()
            val second = loadProjectedCandidatePage(first.nextKey, 999, 2, -1, blacklist) { start, limit ->
                starts += start
                fetch(start, limit)
            }
            starts shouldContainExactly listOf(6)
            second.candidates.map { it.index } shouldContainExactly listOf(6, 7)
            second.nextKey shouldBe 8
            val end = loadProjectedCandidatePage(second.nextKey, 999, 2, -1, blacklist, fetch)
            end.candidates shouldBe emptyList()
            end.nextKey shouldBe null
        }

        "连续全过滤批次后仍取得可见候选并在原始末尾终止" {
            val list = (List(100) { candidate("\uD843\uDF1E") } + candidate("风"))
            val starts = mutableListOf<Int>()
            val page = loadProjectedCandidatePage(null, 0, 2, -1, emptySet()) { start, limit ->
                starts += start
                list.drop(start).take(limit).toTypedArray()
            }
            starts.first() shouldBe 0
            starts.last() shouldBe 100
            page.candidates.map { it.index } shouldContainExactly listOf(100)
            page.nextKey shouldBe null
            val filtered = loadProjectedCandidatePage(null, 0, 2, -1, setOf("风")) { start, limit ->
                list.drop(start).take(limit).toTypedArray()
            }
            filtered.candidates shouldBe emptyList()
            filtered.nextKey shouldBe null
        }

        "已知总数在最后一个 raw key 结束并保持重复候选原始身份" {
            val list = listOf("风", "黑", "风", "飌").map(::candidate)
            val page = loadProjectedCandidatePage(null, 1, 2, list.size, setOf("黑")) { start, limit ->
                list.drop(start).take(limit).toTypedArray()
            }
            page.candidates.map { it.index } shouldContainExactly listOf(2, 3)
            page.candidates.map { it.proto.text } shouldContainExactly listOf("风", "飌")
            page.nextKey shouldBe null
        }
    })
