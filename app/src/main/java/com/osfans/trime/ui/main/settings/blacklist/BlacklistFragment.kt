// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.blacklist

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.osfans.trime.R
import com.osfans.trime.data.userdict.BlacklistStore
import com.osfans.trime.util.item
import com.osfans.trime.util.toast

/**
 * 词组黑名单的增删界面。
 *
 * 这份名单只影响候选栏显示，不写进 librime 配置，所以改完立刻生效、不用重载 Rime。
 */
class BlacklistFragment : Fragment() {
    private lateinit var ui: BlacklistListUi

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        ui =
            BlacklistListUi(
                requireContext(),
                BlacklistStore.load(),
            ) { word ->
                setOnClickListener { showItemMenu(this, word) }
            }
        ui.fab.setOnClickListener {
            BlacklistDialog.build(requireContext()) { word ->
                persist(
                    (BlacklistStore.load() + word).distinct(),
                    getString(R.string.blacklist_added, word),
                )
            }.show()
        }
        return ui.root
    }

    private fun showItemMenu(
        anchor: View,
        word: String,
    ) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.item(R.string.delete) {
            confirmDelete(word)
        }
        popup.show()
    }

    private fun confirmDelete(word: String) {
        AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.blacklist_delete_confirm, word))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                persist(BlacklistStore.load().filterNot { it == word })
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persist(words: List<String>, message: String? = null) {
        val ctx = requireContext()
        BlacklistStore.save(words).onFailure {
            ctx.toast(it)
            return
        }
        ui.adapter.submitList(words)
        message?.let { ui.showSnackBar(it) }
    }
}
