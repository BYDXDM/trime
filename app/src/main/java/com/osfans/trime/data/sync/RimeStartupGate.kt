// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

/**
 * Decides whether [com.osfans.trime.core.Rime.startup] may bring the native
 * dispatcher up.
 *
 * The native side cannot initialise without usable user/shared data
 * directories, and bringing it up while the storage-mode setup step is still
 * pending would leave the user in a half-configured state that only a full
 * restart resolves. Keeping the decision here (rather than inline in `Rime`)
 * makes both rules independently testable.
 */
internal object RimeStartupGate {
    /**
     * @param runtimeReady both user and shared data dirs resolved.
     * @param usesExternalSync the profile is configured for external sync.
     * @param hasExternalAccess the persisted tree URI grants read+write.
     * @param storageChoiceDone the user finished the storage-mode setup step.
     * @return true when startup must be skipped.
     */
    fun shouldSkipStartup(
        runtimeReady: Boolean,
        usesExternalSync: Boolean,
        hasExternalAccess: Boolean,
        storageChoiceDone: Boolean,
    ): Boolean {
        // Runtime dirs are required in every mode: without them nothing to load.
        if (!runtimeReady) return true
        // The setup wizard has not been completed yet; starting now would
        // resolve the wrong data dir and then have to be redone.
        if (!storageChoiceDone) return true
        // External sync is selected but the persisted permission is missing
        // (revoked, or restored on another device): defer until it is granted.
        if (usesExternalSync && !hasExternalAccess) return true
        return false
    }
}
