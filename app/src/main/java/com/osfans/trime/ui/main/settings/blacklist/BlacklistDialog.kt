// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.blacklist

import android.app.AlertDialog
import android.content.Context
import com.osfans.trime.R
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * 新增一条黑名单词组的对话框。
 */
object BlacklistDialog {
    fun build(
        context: Context,
        onConfirm: (String) -> Unit,
    ): AlertDialog {
        val wordField =
            context.editText {
                hint = context.getString(R.string.blacklist_word)
            }

        val content =
            context.verticalLayout {
                setPadding(dp(24), dp(8), dp(24), dp(8))
                add(
                    wordField,
                    lParams {
                        width = matchParent
                        height = wrapContent
                    },
                )
            }

        return AlertDialog
            .Builder(context)
            .setTitle(R.string.blacklist_add)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val word = wordField.text.toString().trim()
                if (word.isEmpty()) return@setPositiveButton
                onConfirm(word)
            }.setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
