// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

/**
 * F06: run one symbol input with the Chinese punctuation conversion temporarily enabled.
 *
 * A SYMBOL panel entry may be a Chinese punctuation mark that only comes out of the punctuator
 * when `ascii_punct` is off. The option has to be switched off before the simulated key and put
 * back afterwards, even when the simulated input throws, otherwise the user is left with
 * half-width punctuation for the rest of the session.
 *
 * The option name is the one librime reads (`ctx->get_option("ascii_punct")` in
 * app/src/main/jni/librime/src/rime_api_impl.h:357); `ascii_mode` keeps its previous behaviour of
 * being switched off for the symbol without being restored.
 */
internal object SymbolInputRunner {
    const val OPTION_ASCII_MODE = "ascii_mode"
    const val OPTION_ASCII_PUNCT = "ascii_punct"

    suspend fun run(
        isAsciiMode: Boolean,
        isAsciiPunct: Boolean,
        setOption: suspend (String, Boolean) -> Unit,
        clearComposition: suspend () -> Unit,
        simulateSymbol: suspend () -> Unit,
    ) {
        try {
            if (isAsciiMode) setOption(OPTION_ASCII_MODE, false)
            if (isAsciiPunct) setOption(OPTION_ASCII_PUNCT, false)
            clearComposition()
            simulateSymbol()
        } finally {
            if (isAsciiPunct) setOption(OPTION_ASCII_PUNCT, true)
        }
    }
}
