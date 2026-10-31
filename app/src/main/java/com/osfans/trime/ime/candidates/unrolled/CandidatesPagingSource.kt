/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.userdict.BlacklistStore
import timber.log.Timber

/** 展开面板里的一项：候选本体 + 它在 librime 原始候选列表里的下标。 */
data class IndexedCandidate(
    val index: Int,
    val proto: CandidateProto,
)

class CandidatesPagingSource(
    val rime: RimeSession,
    val total: Int,
    val offset: Int,
) : PagingSource<Int, IndexedCandidate>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        // use candidate index for key, null means load from beginning (including offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        val blacklist = BlacklistStore.words()
        val kept = ArrayList<IndexedCandidate>(pageSize)
        // 黑名单命中的候选不下发，一页往往要跨好几批原始候选才凑够。
        // key 始终是 librime 的原始下标，所以过滤不会让翻页错位。
        var cursor = startIndex
        var reachedEnd = false
        while (kept.size < pageSize && !reachedEnd) {
            val want = pageSize - kept.size
            val batch = rime.runOnReady { getCandidates(cursor, want) }
            if (batch.isEmpty()) {
                reachedEnd = true
                break
            }
            batch.forEachIndexed { i, proto ->
                if (kept.size < pageSize && proto.text !in blacklist) {
                    kept += IndexedCandidate(cursor + i, proto)
                }
            }
            cursor += batch.size
            if (batch.size < want) reachedEnd = true
        }
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize) -> ${kept.size}, next=$cursor")
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        val nextKey = when {
            reachedEnd -> null
            total > 0 -> if (cursor >= total) null else cursor
            else -> cursor
        }
        return LoadResult.Page(kept, prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>) = null
}
