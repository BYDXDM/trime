// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.customphrase

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.sync.RimeDataSync
import com.osfans.trime.data.userdict.CustomPhrase
import com.osfans.trime.data.userdict.CustomPhraseStore
import com.osfans.trime.util.appContext
import com.osfans.trime.util.item
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 自定义短语（`custom_phrase.txt`）的增删改界面。
 *
 * 短语表是 librime 侧只读的 stabledb，所以每次改动都要写回文件并让
 * Rime 重载一次（`updateConfig` → `startRime(false)`），否则改动不会
 * 反映到候选里。
 */
class CustomPhraseFragment : Fragment() {
    private lateinit var ui: CustomPhraseListUi

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        ui =
            CustomPhraseListUi(
                requireContext(),
                CustomPhraseStore.load(),
            ) { phrase ->
                setOnClickListener { showItemMenu(this, phrase) }
            }
        ui.fab.setOnClickListener {
            CustomPhraseDialog.build(requireContext(), null) { phrase ->
                persist(CustomPhraseStore.upsert(CustomPhraseStore.load(), phrase))
            }.show()
        }
        return ui.root
    }

    private fun showItemMenu(
        anchor: View,
        phrase: CustomPhrase,
    ) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.item(R.string.edit) {
            CustomPhraseDialog.build(requireContext(), phrase) { edited ->
                persist(CustomPhraseStore.upsert(CustomPhraseStore.remove(CustomPhraseStore.load(), phrase), edited))
            }.show()
        }
        popup.menu.item(R.string.delete) {
            confirmDelete(phrase)
        }
        popup.show()
    }

    private fun confirmDelete(phrase: CustomPhrase) {
        AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.custom_phrase_delete_confirm, phrase.text, phrase.code))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                persist(CustomPhraseStore.remove(CustomPhraseStore.load(), phrase))
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 写回文件并重载 Rime。写盘失败时提示且不重载。
     */
    private fun persist(phrases: List<CustomPhrase>) {
        val ctx = requireContext()
        CustomPhraseStore.save(phrases).onFailure {
            ctx.toast(it)
            return
        }
        ui.adapter.submitList(phrases)
        ui.showSnackBar(ctx.getString(R.string.custom_phrase_saved))
        // 挂在 Application 的 scope 上：用户可能在重载完成前就退出这个页面。
        TrimeApplication.getInstance().coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val sessionName = "custom-phrase-persist"
                runCatching {
                    val session = RimeDaemon.createSession(sessionName)
                    try {
                        session.runOnReady { updateConfig() }
                        if (RimeDataSync.usesExternalSync(appContext)) {
                            RimeDataSync.exportConfigFilesToExternal(appContext).getOrThrow()
                        }
                    } finally {
                        RimeDaemon.destroySession(sessionName)
                    }
                }.onFailure { Timber.e(it, "Failed to reload custom phrases") }
            }
        }
    }
}
