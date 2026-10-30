// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.blacklist

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter

class BlacklistListAdapter(
    items: List<String>,
    val initMoreButton: (ImageButton.(String) -> Unit) = {},
) : BaseQuickAdapter<String, BlacklistListAdapter.ViewHolder>(items) {
    class ViewHolder(
        ui: BlacklistListEntryUi,
    ) : RecyclerView.ViewHolder(ui.root) {
        val nameText = ui.nameText
        val moreButton = ui.moreButton
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(BlacklistListEntryUi(context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: String?,
    ) {
        val word = item ?: return
        holder.nameText.text = word
        initMoreButton(holder.moreButton, word)
    }
}
