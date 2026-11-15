// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

/**
 * Capacity rule for the clipboard history: keep the [limit] newest unpinned rows,
 * ordered by time and then by id, and expire exactly the remaining ids.
 */
internal object ClipboardRetention {
    data class Row(
        val id: Int,
        val time: Long,
        val pinned: Boolean = false,
    )

    fun expiredIds(
        rows: List<Row>,
        limit: Int,
    ): List<Int> {
        val unpinned =
            rows
                .filterNot { it.pinned }
                .sortedWith(compareByDescending<Row> { it.time }.thenByDescending { it.id })
        return unpinned.drop(limit.coerceAtLeast(0)).map { it.id }
    }
}
