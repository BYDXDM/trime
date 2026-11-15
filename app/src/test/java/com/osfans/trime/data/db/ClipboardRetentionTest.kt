// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class ClipboardRetentionTest :
    StringSpec({
        "keeps the newest rows by time instead of by id" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 100),
                    ClipboardRetention.Row(2, 300),
                    ClipboardRetention.Row(3, 200),
                    ClipboardRetention.Row(4, 400),
                )
            ClipboardRetention.expiredIds(rows, limit = 3) shouldBe listOf(1)
        }
        "re-copied old row survives cleanup" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 500),
                    ClipboardRetention.Row(2, 100),
                    ClipboardRetention.Row(3, 200),
                    ClipboardRetention.Row(4, 300),
                )
            ClipboardRetention.expiredIds(rows, limit = 3) shouldBe listOf(2)
        }
        "equal timestamps keep the higher id" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 100),
                    ClipboardRetention.Row(2, 200),
                    ClipboardRetention.Row(3, 200),
                )
            ClipboardRetention.expiredIds(rows, limit = 2) shouldBe listOf(1)
        }
        "zero limit expires every unpinned row" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 100),
                    ClipboardRetention.Row(2, 200, pinned = true),
                    ClipboardRetention.Row(3, 300),
                )
            ClipboardRetention.expiredIds(rows, limit = 0).sorted() shouldBe listOf(1, 3)
        }
        "pinned rows never expire and do not consume capacity" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 999, pinned = true),
                    ClipboardRetention.Row(2, 100),
                    ClipboardRetention.Row(3, 200),
                    ClipboardRetention.Row(4, 300),
                )
            ClipboardRetention.expiredIds(rows, limit = 2) shouldBe listOf(2)
        }
        "exactly at the limit expires nothing" {
            val rows =
                listOf(
                    ClipboardRetention.Row(1, 100),
                    ClipboardRetention.Row(2, 200),
                    ClipboardRetention.Row(3, 300),
                )
            ClipboardRetention.expiredIds(rows, limit = 3).shouldBeEmpty()
        }
    })
