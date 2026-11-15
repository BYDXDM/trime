/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

/**
 * Decides whether a delayed ASCII switch tips restore may still be applied.
 *
 * The restore is scheduled one second after the tips were shown, so by the time it runs the
 * engine may have been stopped and restarted, the schema may have switched, or another tips
 * request may have superseded it. Applying a stale composition would overwrite the current
 * preedit with a hint that no longer matches the state.
 */
internal object AsciiTipsGuard {
    fun shouldApply(
        requestedGeneration: Long,
        currentGeneration: Long,
        ready: Boolean,
        requestedTips: String,
        currentTips: String,
    ): Boolean = ready &&
        requestedGeneration == currentGeneration &&
        requestedTips == currentTips
}
