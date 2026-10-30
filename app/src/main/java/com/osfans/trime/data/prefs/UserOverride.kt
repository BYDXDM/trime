// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.prefs

/**
 * Convention D: a user preference overrides whatever the theme resolved.
 *
 * The geometry preferences (keyboard height, key spacing, candidate bar height)
 * use 0 as "follow the theme" — the left end of their slider — so this is a
 * positive-wins cascade, the same shape as `resolvePositive` in
 * `ime/keyboard/KeyboardResolve.kt`, with the two ends being "user preference"
 * and "theme result" instead of "keyboard config" and "theme style".
 *
 * [unit] converts the chosen value exactly once, so a preference stored in dp
 * is not converted twice when it wins.
 */
internal fun resolveUserOverride(
    override: Int,
    themed: Int,
    unit: (Int) -> Int = { it },
): Int = if (override > 0) unit(override) else themed
