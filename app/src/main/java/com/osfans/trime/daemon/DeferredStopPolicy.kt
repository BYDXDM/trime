// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.core.RimeLifecycle

/**
 * F04: what to do with a finalize request that arrives while the engine is still starting.
 *
 * [com.osfans.trime.core.Rime.finalize] only accepts the READY state, so a request issued during
 * STARTING used to be dropped and the native engine kept running without any session. The request
 * is therefore deferred and re-evaluated once startup completes, against the sessions that exist
 * at that moment.
 */
internal object DeferredStopPolicy {
    enum class Action {
        STOP_NOW,
        DEFER,
        IGNORE,
    }

    fun actionFor(state: RimeLifecycle.State): Action = when (state) {
        RimeLifecycle.State.READY -> Action.STOP_NOW
        RimeLifecycle.State.STARTING -> Action.DEFER
        RimeLifecycle.State.STOPPING, RimeLifecycle.State.STOPPED -> Action.IGNORE
    }

    /** Startup just became ready: stop only if the deferred request survived and nothing reconnected. */
    fun shouldStopOnReady(pendingStop: Boolean, sessionCount: Int): Boolean = pendingStop && sessionCount == 0

    /**
     * R2: [com.osfans.trime.core.Rime.finalize] 在锁外执行，期间 [RimeDaemon.createSession] 可能已接入新会话。
     * 停止完成后必须复核一次会话数：仍有会话就说明引擎被误停，需要立刻拉回来。
     */
    fun shouldRestartAfterFinalize(sessionCount: Int): Boolean = sessionCount > 0
}
