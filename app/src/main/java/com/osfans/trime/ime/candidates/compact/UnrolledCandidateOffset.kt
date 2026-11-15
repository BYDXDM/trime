package com.osfans.trime.ime.candidates.compact

/** Counts the ordered candidate prefix fully inside the unscrolled viewport. */
internal fun fullyVisibleCandidatePrefix(
    decoratedBounds: List<Pair<Int, Int>>,
    viewportStart: Int,
    viewportEnd: Int,
    scrollOffset: Int = 0,
): Int {
    var count = 0
    for ((left, right) in decoratedBounds) {
        if (left + scrollOffset < viewportStart || right + scrollOffset > viewportEnd) break
        count++
    }
    return count
}

internal fun shouldShowUnrollButton(
    total: Int,
    visibleCount: Int,
    visiblePrefix: Int,
    filteredOut: Int,
): Boolean = visibleCount > 0 && (total < 0 || visiblePrefix + filteredOut < total)

internal fun unrolledCandidateOffset(visiblePrefix: Int, visibleCount: Int): Int =
    if (visibleCount == 0) -1 else visiblePrefix
