// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

import java.io.File

object SyncRelativePath {
    class PathEscapeException(
        relativePath: String,
    ) : RuntimeException("Relative path escapes sync root: $relativePath")

    /**
     * Canonical form of a sync-relative path. Leading/trailing spaces belong to the
     * file name and are preserved; only separators and "./" prefixes are rewritten.
     */
    fun normalize(relativePath: String): String {
        if (relativePath.isBlank()) {
            throw PathEscapeException(relativePath)
        }
        var normalized = relativePath.replace('\\', '/')
        while (normalized.startsWith('/')) normalized = normalized.removePrefix("/")
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized.isEmpty()) {
            throw PathEscapeException(relativePath)
        }
        val segments = normalized.split('/')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) {
            throw PathEscapeException(relativePath)
        }
        return segments.joinToString("/")
    }

    fun resolveContained(
        root: File,
        relativePath: String,
    ): File {
        val normalized = normalize(relativePath)
        val rootCanonical = root.canonicalFile
        val resolved = File(rootCanonical, normalized).canonicalFile
        if (!resolved.path.startsWith(rootCanonical.path + File.separator) && resolved != rootCanonical) {
            throw PathEscapeException(relativePath)
        }
        return resolved
    }
}
