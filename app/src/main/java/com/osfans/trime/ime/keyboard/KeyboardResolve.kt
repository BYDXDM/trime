// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

/**
 * Pure resolvers for the keyboard geometry cascade (key -> keyboard config ->
 * theme style). The three legacy merge conventions are kept explicit so the
 * semantics stay testable without Android dependencies.
 *
 * Convention A: positive-wins for gaps and heights. A value > 0 overrides the
 * style default; 0 or null means "unset" and falls through to the style value
 * (which may itself be 0). [unit] converts the selected raw value exactly once.
 */
internal fun resolvePositive(
    config: Int?,
    style: Int,
    unit: (Int) -> Int = { it },
): Int = if (config != null && config > 0) unit(config) else unit(style)

/**
 * Convention B: non-negative-wins for corners and borders. The config uses -1
 * as the "absent" sentinel, while 0 is a legal value that overrides the style.
 */
internal fun resolveNonNegative(
    config: Float?,
    style: Float,
): Float = if (config != null && config >= 0f) config else style

/** Int overload of [resolveNonNegative]. */
internal fun resolveNonNegative(
    config: Int?,
    style: Int,
): Int = if (config != null && config >= 0) config else style

/**
 * Convention C: non-zero-wins for the text/hint/press offsets, cascading
 * key -> keyboard -> style. Negative values are legal and kept.
 */
internal fun resolveOffset(
    key: Float,
    keyboard: Float,
    style: Float,
): Float = if (key != 0f) {
    key
} else if (keyboard != 0f) {
    keyboard
} else {
    style
}

/**
 * Picks the landscape value when the keyboard is in landscape mode and the
 * theme provides one; the portrait value is the fallback in every other case.
 */
internal fun pickLandscape(
    value: Int,
    landscapeValue: Int,
    landscape: Boolean,
): Int = if (landscape && landscapeValue > 0) landscapeValue else value

/**
 * Decides which keyboard name a schema maps to, before the theme is consulted.
 *
 * [presetKeyboardIds] are the keyboard ids the theme actually defines. The
 * returned value is the first candidate that exists in the theme; when none
 * does, the last fallback is returned so the caller can still recover.
 *
 * The `qwerty*` names come from upstream: upstream ships keyboards literally
 * named `qwerty` / `qwerty_` / `qwerty0`. A theme that renamed its keyboards
 * (this fork keeps only `my_pinyin` / `my_english` / …) never matches them, so
 * callers must fall back — see [pickFallbackKeyboard].
 *
 * Passing an empty [alphabet] is treated as "unknown": every `all { }` check on
 * an empty string is vacuously true, which would otherwise pick `qwerty` for a
 * schema whose alphabet could not be read.
 */
internal fun layoutNameForAlphabet(
    alphabet: String,
    presetKeyboardIds: Collection<String>,
    schemaId: String,
): String {
    // The theme may name a keyboard exactly after the schema; that wins.
    if (presetKeyboardIds.contains(schemaId)) return schemaId
    if (alphabet.isEmpty()) return ""
    if (alphabet.all { it.isLetter() }) {
        return "qwerty".takeIf(presetKeyboardIds::contains) ?: ""
    }
    if (alphabet.all { it.isLetter() || it in ",./;" }) {
        return "qwerty_".takeIf(presetKeyboardIds::contains) ?: ""
    }
    if (alphabet.all { it.isLetterOrDigit() }) {
        return "qwerty0".takeIf(presetKeyboardIds::contains) ?: ""
    }
    return "default".takeIf(presetKeyboardIds::contains) ?: ""
}

/**
 * Picks the keyboard to show after Rime's `ascii_mode` changed.
 *
 * The theme declares the Chinese ↔ English pairing with `ascii_keyboard`
 * (`my_pinyin.ascii_keyboard: my_english`). Both directions are derived from
 * that single declaration, so no keyboard id is hard-coded:
 *  - switching to English: the current keyboard's own `ascii_keyboard`;
 *  - switching back to Chinese: whichever keyboard declares the current one as
 *    its `ascii_keyboard` (a reverse lookup).
 *
 * A keyboard that declares no pairing — the `symbols` / `number` / `emoji`
 * panels — yields `null` in both directions, so the 中英 key there leaves the
 * panel alone instead of yanking the user out of it. To make those panels
 * follow the mode as well, declare an `ascii_keyboard` on them in the theme;
 * no code change is needed.
 *
 * @param asciiKeyboardOf keyboard id -> its declared `ascii_keyboard`
 *   (empty string when the keyboard declares none)
 * @return the keyboard id to switch to, or `null` when nothing should change
 */
internal fun resolveKeyboardForAsciiMode(
    asciiMode: Boolean,
    currentKeyboardId: String,
    asciiKeyboardOf: Map<String, String>,
): String? {
    // 首次挂载前 currentKeyboardId 为空串；空串会与「未声明」的空值互相匹配，
    // 反查出任意键盘。没有当前键盘就没有配对可言。
    if (currentKeyboardId.isEmpty()) return null
    val target =
        if (asciiMode) {
            asciiKeyboardOf[currentKeyboardId]
        } else {
            asciiKeyboardOf.entries.firstOrNull { it.value == currentKeyboardId }?.key
        }
    // isNotBlank：空白串不是合法的键盘名，别把它当目标丢给调用方去兜底
    return target?.takeIf { it.isNotBlank() && it != currentKeyboardId }
}

/**
 * Last-resort keyboard pick that is guaranteed to be drawable.
 *
 * `Keyboard(context, theme, width, null)` builds a keyboard with **no keys**,
 * which renders as a blank input area — the failure this guards against. So
 * prefer a keyboard that really has keys, then any defined id, then the
 * literal `default` (which the caller resolves again).
 */
internal fun pickFallbackKeyboard(
    ids: List<String>,
    keyCountOf: (String) -> Int,
): String? = ids.firstOrNull { keyCountOf(it) > 0 } ?: ids.firstOrNull()
