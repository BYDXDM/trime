// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

fun String.removeRegexSet(regexSet: Set<Regex>): String {
    var result = this
    regexSet.forEach { result = result.replace(it, String.EMPTY) }
    return result
}

fun String.matchesAny(regexSet: Set<Regex>): Boolean = regexSet.any { it.matches(this) }
