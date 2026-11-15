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
import com.osfans.trime.ime.candidates.CandidateGlyphFilter
import timber.log.Timber

/** 展开面板里的一项：候选本体 + 它在 librime 原始候选列表里的下标。 */
data class IndexedCandidate(
    val index: Int,
    val proto: CandidateProto,
)

private const val OFFSET_SCAN_BATCH_SIZE = 48

/**
 * Convert the compact bar's projected item count to the raw index after its prefix.
 * The same CandidateGlyphFilter projection is used for every scanned batch.
 */
internal fun rawIndexAfterProjectedCount(
    projectedCount: Int,
    blacklist: Set<String>,
    fetch: (startIndex: Int, limit: Int) -> Array<CandidateProto>,
): Int {
    if (projectedCount <= 0) return 0
    var cursor = 0
    var projected = 0
    while (projected < projectedCount) {
        val batch = fetch(cursor, OFFSET_SCAN_BATCH_SIZE)
        if (batch.isEmpty()) return cursor
        val filtered = CandidateGlyphFilter.filter(batch.toList(), blacklist = blacklist)
        val remaining = projectedCount - projected
        if (filtered.indices.size >= remaining) {
            return cursor + filtered.indices[remaining - 1] + 1
        }
        projected += filtered.indices.size
        cursor += batch.size
        if (batch.size < OFFSET_SCAN_BATCH_SIZE) return cursor
    }
    return cursor
}

internal data class ProjectedCandidatePage(
    val candidates: List<IndexedCandidate>,
    val nextKey: Int?,
)

/** All fetch calls must belong to the same synchronous candidate read operation. */
internal fun loadProjectedCandidatePage(
    key: Int?,
    offset: Int,
    pageSize: Int,
    total: Int,
    blacklist: Set<String>,
    fetch: (startIndex: Int, limit: Int) -> Array<CandidateProto>,
): ProjectedCandidatePage {
    // Only the initial compact offset is projected; subsequent keys are raw indices.
    val startIndex = key ?: rawIndexAfterProjectedCount(offset, blacklist, fetch)
    val kept = ArrayList<IndexedCandidate>(pageSize)
    var cursor = startIndex
    var reachedEnd = false
    while (kept.size < pageSize && !reachedEnd) {
        val want = pageSize - kept.size
        val batch = fetch(cursor, want)
        if (batch.isEmpty()) {
            reachedEnd = true
            break
        }
        val filtered = CandidateGlyphFilter.filter(batch.toList(), blacklist = blacklist)
        filtered.indices.forEachIndexed { i, rawIndex ->
            kept += IndexedCandidate(cursor + rawIndex, filtered.candidates[i])
        }
        cursor += batch.size
        if (batch.size < want) reachedEnd = true
    }
    val nextKey = when {
        reachedEnd -> null
        total > 0 && cursor >= total -> null
        else -> cursor
    }
    return ProjectedCandidatePage(kept, nextKey)
}

class CandidatesPagingSource(
    val rime: RimeSession,
    val total: Int,
    val offset: Int,
) : PagingSource<Int, IndexedCandidate>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        val blacklist = BlacklistStore.words()
        val page = rime.runOnReady {
            readCandidatesAtomically { fetch ->
                loadProjectedCandidatePage(params.key, offset, params.loadSize, total, blacklist, fetch)
            }
        }
        Timber.d("getCandidates(key=" + params.key + ", limit=" + params.loadSize + ") -> " + page.candidates.size + ", next=" + page.nextKey)
        // Earlier expanded pages remain loaded; the compact prefix must not be prepended.
        return LoadResult.Page(page.candidates, null, page.nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>) = null
}
