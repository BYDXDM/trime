// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.customphrase

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import com.osfans.trime.R
import com.osfans.trime.data.userdict.CustomPhrase
import com.osfans.trime.util.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * 新增 / 编辑一条自定义短语的对话框。
 *
 * 字段与 `custom_phrase.txt` 的三列一一对应（短语、编码、权重），
 * 权重留空按 0 处理。
 */
object CustomPhraseDialog {
    fun build(
        context: Context,
        initial: CustomPhrase?,
        onConfirm: (CustomPhrase) -> Unit,
    ): AlertDialog {
        val textField =
            context.editText {
                hint = context.getString(R.string.custom_phrase_text)
                setText(initial?.text.orEmpty())
            }
        val codeField =
            context.editText {
                hint = context.getString(R.string.custom_phrase_code)
                setText(initial?.code.orEmpty())
            }
        val weightField =
            context.editText {
                hint = context.getString(R.string.custom_phrase_weight)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText((initial?.weight ?: 0).toString())
            }

        val content =
            context.verticalLayout {
                setPadding(dp(24), dp(8), dp(24), dp(8))
                add(
                    textField,
                    lParams {
                        width = matchParent
                        height = wrapContent
                    },
                )
                add(
                    codeField,
                    lParams {
                        width = matchParent
                        height = wrapContent
                    },
                )
                add(
                    weightField,
                    lParams {
                        width = matchParent
                        height = wrapContent
                    },
                )
            }

        return AlertDialog
            .Builder(context)
            .setTitle(if (initial == null) R.string.custom_phrase_add else R.string.custom_phrase_edit)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = textField.text.toString().trim()
                val code = codeField.text.toString().trim()
                if (text.isEmpty() || code.isEmpty()) {
                    context.toast(R.string.custom_phrase_invalid)
                    return@setPositiveButton
                }
                onConfirm(
                    CustomPhrase(
                        text = text,
                        code = code,
                        weight = weightField.text.toString().trim().toIntOrNull() ?: 0,
                    ),
                )
            }.setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
