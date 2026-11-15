// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

/**
 * Maps external sync-tree paths onto the local paths an import writes, so that the
 * copy target and the orphan-cleanup keep-set share one rule. Distinct external
 * paths that collapse onto one local path are reported as [Mapping.duplicates]
 * instead of silently overwriting each other.
 */
object SyncPathMap {
    data class Mapping(
        val targets: Map<String, String>,
        val duplicates: List<String>,
    )

    fun map(externalPaths: Collection<String>): Mapping {
        val targets = LinkedHashMap<String, String>()
        val duplicates = mutableListOf<String>()
        val ordered =
            externalPaths
                .distinct()
                .sortedWith(compareBy<String> { isCanonical(it) == false }.thenBy { it })
        ordered.forEach { external ->
            val local =
                runCatching { SyncRelativePath.normalize(external) }.getOrElse {
                    return@forEach
                }
            if (targets.values.any { it == local }) {
                duplicates.add(external)
            } else {
                targets[external] = local
            }
        }
        return Mapping(targets = targets, duplicates = duplicates)
    }

    private fun isCanonical(externalPath: String): Boolean = runCatching { SyncRelativePath.normalize(externalPath) }.getOrNull() == externalPath
}
