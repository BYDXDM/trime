// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.customphrase

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.userdict.CustomPhrase

class CustomPhraseListAdapter(
    items: List<CustomPhrase>,
    val initMoreButton: (ImageButton.(CustomPhrase) -> Unit) = {},
) : BaseQuickAdapter<CustomPhrase, CustomPhraseListAdapter.ViewHolder>(items) {
    class ViewHolder(
        ui: CustomPhraseListEntryUi,
    ) : RecyclerView.ViewHolder(ui.root) {
        val nameText = ui.nameText
        val codeText = ui.codeText
        val moreButton = ui.moreButton
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(CustomPhraseListEntryUi(context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: CustomPhrase?,
    ) {
        val phrase = item ?: return
        holder.nameText.text = phrase.text
        holder.codeText.text = holder.itemView.context.getString(
            com.osfans.trime.R.string.custom_phrase_entry_summary,
            phrase.code,
            phrase.weight,
        )
        initMoreButton(holder.moreButton, phrase)
    }
}
