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
    ): Boolean = when {
        // Runtime dirs are required in every mode: without them nothing to load.
        !runtimeReady -> true

        // App-internal storage: nothing else to wait for.
        !usesExternalSync -> false

        // External sync with a live grant: good to go.
        hasExternalAccess -> false

        // ★ 关键：**「从未做过存储选择」不能当作「存储不可用」**。
        //   存储方式是应用内向导里的一步；用户只启用输入法、从未打开过应用时，
        //   该选择尚未完成。此时若跳过启动，Rime 永远起不来 → ThemeScope 永不就绪
        //   → onCreateInputView 无限推迟 → **键盘整块空白、打不出中文**
        //   （首次安装即启用输入法时必现）。
        //   从未选择时按应用内存储直接启动，与 DataManager.deploy 的
        //   ExternalSyncFallback 语义一致；只有「已做过选择却拿不到目录权限」
        //   （权限被撤销、或从别的设备恢复）才继续等待。
        else -> storageChoiceDone
    }
}
