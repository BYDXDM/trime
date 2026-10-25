/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.LiquidData
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.buildIntentFromAction
import com.osfans.trime.util.buildIntentFromArgument
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import com.osfans.trime.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** 删除键上滑「清空」：清完预编辑串后，等 Rime 把结果推给输入框再全选删除（毫秒）。 */
private const val CLEAR_SETTLE_MS = 80L

/**
 * 删除键上滑「清空」时，一次向 `deleteSurroundingText` 请求删除的字符数。
 * 取一个大到足以覆盖任何正常输入框、又不至于溢出的值。
 */
private const val CLEAR_MAX_CHARS = 10_000

class CommonKeyboardActionListener(override val di: DI) : DIAware {

    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val prefs = AppPrefs.defaultInstance()

    private fun showDialog(dialog: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(dialog(api))
            }
        }
    }

    private fun showThemePicker() {
        showDialog { api ->
            ThemePickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showColorPicker() {
        showDialog { api ->
            ColorPickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showSoundEffectPicker() {
        showDialog {
            SoundEffectPickerDialog.build(service.lifecycleScope, context)
        }
    }

    private fun showEnabledSchemaPicker() {
        showDialog { api ->
            EnabledSchemaPickerDialog.build(api, service.lifecycleScope, context) {
                setNegativeButton(R.string.enable_schemata) { _, _ ->
                    AppUtils.launchMainToSchemaList(context)
                }
            }
        }
    }

    private fun expandActiveText(input: String): String = if (input.matches(PLACEHOLDER_PATTERN)) {
        input.format(
            service.getActiveText(1),
            service.getActiveText(2),
            service.getActiveText(3),
            service.getActiveText(4),
        )
    } else {
        input
    }

    val listener by lazy {
        object : KeyboardActionListener {
            override fun onPress(keyEventCode: Int) {
                InputFeedbackManager.run {
                    keyPressSound(keyEventCode)
                    keyPressSpeak(keyEventCode)
                }
            }

            override fun onAction(action: KeyAction) = handleAction(action, allowAutoPair = true)

            override fun onKeyboardPaging(direction: Int): Boolean = keyboardWindow.swipeSymbolsPage(direction)

            /**
             * @param allowAutoPair `{xxx}` 文本序列展开出来的按键要传 false：那是主题里
             *   写死的「模拟这个键」（如 `{braceleft}`），不能被成对补全改写成别的字符序列。
             */
            private fun handleAction(
                action: KeyAction,
                allowAutoPair: Boolean,
            ) {
                val text = action.getText(KeyboardWindow.currentKeyboard)
                val shouldHandle = when {
                    action.commit.isNotEmpty() -> {
                        service.commitText(action.commit)
                        false
                    }

                    text.isNotEmpty() -> {
                        // 成对符号：光标后无内容时自动补右半（“ → “”，（ → （）），
                        // 其余情况按普通文本上屏
                        if (!commitAutoPair(text)) onText(text)
                        false
                    }

                    else -> true
                }

                if (shouldHandle) {
                    // `[` `{` 在 KeyAction 初始化时就被解析成了 keycode（KEYCODE_LEFT_BRACKET /
                    // 符号码 10011），text 为空，走不到上面 text.isNotEmpty() 那条成对分支；
                    // 这里按码还原字符再判一次。不是成对左半、或光标后已有内容时直接落下去，
                    // 照常走下面的按键分发。
                    if (allowAutoPair) {
                        val open = autoPairTextForCode(action.code)
                        if (open != null && commitAutoPair(open)) return
                    }

                    when (action.code) {
                        KeyEvent.KEYCODE_SWITCH_CHARSET -> handleSwitchCharset(action)

                        KeyEvent.KEYCODE_EISU -> keyboardWindow.switchKeyboard(action.select)

                        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> handleLanguageSwitch(action)

                        KeyEvent.KEYCODE_FUNCTION -> handleFunctionCommand(action)

                        KeyEvent.KEYCODE_SETTINGS -> handleSettings(action)

                        KeyEvent.KEYCODE_PROG_RED -> showColorPicker()

                        KeyEvent.KEYCODE_MENU -> showEnabledSchemaPicker()

                        KeyEvent.KEYCODE_VOICE_ASSIST -> switchToVoiceInputMethod()

                        // 退格（单击）：逐字删除。
                        // ⚠ 这里**故意**不做「有预编辑就整体清空」：
                        //   service.hasComposition() 读的是 composingText，而它的赋值有两条路 ——
                        //   ① 嵌入式预编辑模式（inline_preedit_mode 非 DISABLE）会持续写它；
                        //   ② 分词窗口 SegmentsWindow 不看清开关直接写它、且 detach 时不复位。
                        //   于是那个判定在默认配置下恒假（退化成逐字删），一旦用户开了嵌入式预编辑
                        //   或开过分词窗口就恒真（单击变成整体清空）—— 同一手势两种语义。
                        //   按用户确认：单击恒为逐字删；整体清空由删除键**上滑**
                        //   （trime.yaml 里删除键 swipe_up: ClearAll → command clear_all）承担，
                        //   见下面的 clearInputAndComposition()。
                        KeyEvent.KEYCODE_DEL -> handleDefaultKeyAction(action)

                        else -> handleDefaultKeyAction(action)
                    }
                }
            }

            /**
             * 删除键上滑「清空」：清掉待选拼音 + 清空输入框正文。
             *
             * 两步都不能省（真机实测）：
             * - 只发 `{Control+a}{BackSpace}`（旧的 Clear 预设）**清不掉预编辑串** ——
             *   上滑后拼音仍挂在输入栏上，只是正文被删了。
             * - 只 clearComposition() 又清不掉已上屏的正文。
             *
             * ⚠ 两个踩过的坑：
             * 1. 一开始把 `onText("{Control+a}{BackSpace}")` 放在 `handleFunctionCommand`
             *    里调用 —— **完全不生效**（正文一个字都没删）。同样的字符串走主题的
             *    `text:` 分支是好的，说明问题出在调用点，不在字符串本身。
             *    结论：别在这条路径上依赖组合键回放。
             * 2. 所以改成直接操作 `InputConnection`：光标移到末尾 + 删掉光标前的全部内容。
             *    这个语义不依赖输入框对 Ctrl+A 的支持，各输入框行为一致。
             */
            private fun clearInputAndComposition() {
                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        // 1) 清 Rime 的预编辑串
                        api.clearComposition()
                        // 2) 等清空结果落到输入框，再清正文 —— 否则拼音会混在待删内容里
                        delay(CLEAR_SETTLE_MS)
                        clearInputBox()
                    }
                }
            }

            /** 把输入框里的内容整体删掉（光标移到末尾 → 删除光标前全部）。 */
            private fun clearInputBox() {
                val ic = service.currentInputConnection ?: return
                ic.beginBatchEdit()
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
                ic.deleteSurroundingText(CLEAR_MAX_CHARS, 0)
                ic.endBatchEdit()
            }

            private fun handleSwitchCharset(action: KeyAction) {
                val option = action.toggle.ifEmpty { return }

                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        val isEnabled = api.getRuntimeOption(option)
                        val isComposing = api.statusCached.isComposing
                        api.setRuntimeOption(option, !isEnabled)
                        if (option == "ascii_mode" && isComposing) {
                            api.getRawInput().takeIf { it.isNotEmpty() }?.let {
                                service.commitText(it)
                                api.clearComposition()
                            }
                        }
                    }
                }
            }

            private fun handleLanguageSwitch(action: KeyAction) {
                when {
                    action.select == ".next" -> service.switchToNextIme()
                    action.select.isNotEmpty() -> service.switchToPrevIme()
                    else -> inputMethodManager.showInputMethodPicker()
                }
            }

            private fun handleFunctionCommand(action: KeyAction) {
                val arg = expandActiveText(action.option)

                when (action.command) {
                    "liquid_keyboard" -> handleLiquidKeyboard(arg)

                    "menu_keyboard" -> windowManager.attachWindow(SwitchOptionWindow(di))

                    "clipboard_window" -> handleClipboardWindow(arg)

                    "set_color_scheme" -> handleColorScheme(arg)

                    "set_theme" -> handleTheme(arg)

                    "broadcast" -> service.sendBroadcast(Intent(arg))

                    "clipboard" -> handleClipboard()

                    "commit" -> service.commitText(arg)

                    "date" -> service.commitText(customFormatDateTime(arg))

                    "run" -> handleRunCommand(arg)

                    "apply" -> handleApplyCommand(arg)

                    "share_text" -> service.shareText()

                    "select_candidate" -> handleSelectCandidate(arg)

                    "switch_hide_key_symbol" -> switchHideKeySymbol()

                    "switch_hide_key_hint" -> switchHideKeyHint()

                    // 删除键**上滑**：清空（先清待选拼音，再删输入框正文）。
                    // 与单击区分：单击走 KEYCODE_DEL 分支逐字删。
                    "clear_all" -> clearInputAndComposition()

                    else -> handleIntentAction(action.command, arg)
                }
            }

            private fun handleLiquidKeyboard(arg: String) {
                // for compatibility
                if (arg == "剪贴" || arg == "clipboard") {
                    windowManager.attachWindow(ClipboardWindow(di))
                    return
                }
                val liquidTagList = LiquidData.getTagList()
                val index = liquidTagList.indexOfFirst { tag ->
                    tag.label == arg || runCatching {
                        LiquidData.Type.valueOf(arg.uppercase())
                    }.getOrNull() == tag.type
                }

                if (index >= 0) {
                    windowManager.attachWindow(LiquidWindow)
                    liquidWindow.setDataByIndex(index)
                } else {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            private fun handleClipboardWindow(arg: String) {
                val tabIndex = arg.toIntOrNull()?.coerceIn(0, 1) ?: 0
                windowManager.attachWindow(ClipboardWindow(di, tabIndex))
            }

            private fun handleColorScheme(arg: String) {
                ThemeManager.activeTheme.colorSchemes
                    .find { it.id == arg }
                    ?.let { ColorManager.setColorScheme(it) }
            }

            private fun handleTheme(arg: String) {
                if (arg.isEmpty()) {
                    // 参数为空时，刷新当前主题
                    val themeId = ThemeManager.prefs.selectedTheme.getValue()
                    service.lifecycleScope.launch { ThemeManager.selectTheme(themeId) }
                } else {
                    // 通过主题名称查找对应的配置ID并切换主题
                    ThemeManager.getAllThemes()
                        .find { it.name.equals(arg, ignoreCase = true) }?.let { item ->
                            service.lifecycleScope.launch { ThemeManager.selectTheme(item.configId) }
                        }
                }
            }

            private fun handleClipboard() {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(service)
                    ?.let { service.commitText(it.toString()) }
            }

            private fun handleRunCommand(arg: String) {
                buildIntentFromArgument(arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleApplyCommand(arg: String) {
                when (arg) {
                    "DEPLOY" -> {
                        Timber.i("try to start maintenance via command ...")
                        rime.launchOnReady { api -> api.deploy() }
                    }

                    "SYNC_USER_DATA" -> {
                        Timber.i("try to sync rime user data via command ...")
                        rime.launchOnReady { api -> api.syncUserData() }
                    }

                    "UPDATE_CONFIG" -> {
                        Timber.i("try to update rime config via command ...")
                        rime.launchOnReady { api ->
                            api.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    else -> Timber.w("Unknown apply method: $arg")
                }
            }

            private fun handleIntentAction(command: String, arg: String) {
                buildIntentFromAction(command, arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleSelectCandidate(arg: String) {
                val index = arg.toIntOrNull() ?: return
                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        api.selectCandidate(index, false)
                    }
                }
            }

            private fun switchHideKeySymbol() {
                val preference = prefs.keyboard.hideKeySymbol
                preference.setValue(!preference.getValue())
            }

            private fun switchHideKeyHint() {
                val preference = prefs.keyboard.hideKeyHint
                preference.setValue(!preference.getValue())
            }

            private fun handleSettings(action: KeyAction) {
                when (action.option) {
                    "theme" -> showThemePicker()
                    "color" -> showColorPicker()
                    "schema" -> AppUtils.launchMainToSchemaList(context)
                    "sound" -> showSoundEffectPicker()
                    else -> AppUtils.launchMainActivity(service)
                }
            }

            private fun switchToVoiceInputMethod() {
                val pkgName = prefs.general.preferredVoiceInput.getValue()
                val voiceInputSubType = if (pkgName.isNotEmpty()) {
                    InputMethodUtils.voiceInputMethods().find {
                        it.first.packageName == pkgName
                    }?.let {
                        it.first.id to it.second
                    } ?: InputMethodUtils.firstVoiceInput()
                } else {
                    InputMethodUtils.firstVoiceInput()
                }
                if (voiceInputSubType != null) {
                    val (id, subType) = voiceInputSubType
                    InputMethodUtils.switchInputMethod(service, id, subType)
                } else {
                    service.toast(R.string.no_voice_input_installed)
                }
            }

            private fun handleDefaultKeyAction(action: KeyAction) {
                val shouldHookShiftKey = when {
                    prefs.keyboard.hookShiftSpace.getValue() && action.code == KeyEvent.KEYCODE_SPACE -> true
                    prefs.keyboard.hookShiftNum.getValue() && action.code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in setOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD) -> true
                    else -> false
                }

                // 双击空格补句号：非组词态下连按两次空格，第二次直接上屏“。”
                // （组词态的空格是选候选，同样会计时，方便上屏后紧接着出句号）
                if (action.code == KeyEvent.KEYCODE_SPACE && action.modifier == 0) {
                    val now = SystemClock.uptimeMillis()
                    val composing = rime.run { statusCached }.isComposing
                    if (!composing && isDoubleSpaceTap(now, lastSpaceTapAt)) {
                        lastSpaceTapAt = 0L
                        service.commitText("。")
                        return
                    }
                    lastSpaceTapAt = now
                }

                if (action.modifier == 0 && KeyboardWindow.currentKeyboard.isOnlyShiftOn && shouldHookShiftKey) {
                    onKey(action.code, 0)
                    return
                }

                val modifier = when {
                    action.modifier == 0 -> KeyboardWindow.currentKeyboard.modifier

                    (action.modifier and KeyEvent.META_CTRL_ON) != 0 && isNavigationKey(action.code) ->
                        action.modifier or KeyboardWindow.currentKeyboard.modifier

                    else -> action.modifier
                }

                onKey(action.code, modifier)
            }

            private fun isNavigationKey(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                keyCode == KeyEvent.KEYCODE_MOVE_END

            override fun onKey(
                keyEventCode: Int,
                metaState: Int,
            ) {
                // An uppercase letter key (e.g. from `{x: A}`) is passed to
                // rime as the uppercase keysym with Shift, matching what a
                // physical keyboard reports via the unicode char, so that
                // rime commits the uppercase letter in ascii mode as well.
                // The generated reverse mapping would otherwise resolve e.g.
                // KEYCODE_A to the lowercase name "a" (XK_a).
                val value =
                    if (metaState and KeyEvent.META_SHIFT_ON != 0 &&
                        keyEventCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
                    ) {
                        'A'.code + (keyEventCode - KeyEvent.KEYCODE_A) // XK_A..XK_Z
                    } else {
                        val name = KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol"
                        RimeKeyEvent.getKeycodeByName(name)
                    }
                val m = if (keyEventCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS) {
                    metaState or KeyEvent.META_NUM_LOCK_ON
                } else {
                    metaState
                }
                val modifiers = KeyModifiers.fromMetaState(m).modifiers
                service.postRimeJob {
                    if (service.hookKeyboard(keyEventCode, m)) {
                        Timber.d("handleKey: hook")
                        return@postRimeJob
                    }
                    if (processKey(value, modifiers)) {
                        Timber.d("handleKey: processKey")
                        return@postRimeJob
                    }
                    if (AppUtils.launchKeyCategory(service, keyEventCode)) {
                        Timber.d("handleKey: openCategory")
                        return@postRimeJob
                    }
                    // other special cases
                    if (keyEventCode == KeyEvent.KEYCODE_BACK) {
                        service.requestHideSelf(0)
                    }
                }
            }

            override fun onText(input: String) {
                if (input.isEmpty()) return
                Timber.d("onText: $input")
                val status = rime.run { statusCached }
                if (!input[0].isAsciiPrintable() && status.isComposing) {
                    service.postRimeJob { commitComposition() }
                }

                val escaped = input.replace("{}", "{braceleft}{braceright}")
                var i = 0
                while (i < escaped.length) {
                    val value = when (val match = TEXT_INPUT_PATTERN.matchEntire(escaped.substring(i))) {
                        match if (match != null) -> match.groupValues[1]
                        else -> escaped[i].toString()
                    }

                    service.postRimeJob {
                        if (value.run { startsWith('{') && endsWith('}') }) {
                            val token = value.removeSurrounding("{", "}")
                            // 文本序列里的 `{xxx}` 是「模拟这个键」，不是用户真的按了那个键：
                            // 不做成对补全，否则 `{braceleft}` 会被改写成 `{}` 或 `{`。
                            handleAction(KeyActionManager.getAction(token), allowAutoPair = false)
                        } else if (!value[0].isAsciiPrintable()) {
                            service.commitText(value)
                        } else {
                            simulateKeySequence(value)
                        }
                    }

                    i += value.length
                }
            }
        }
    }

    private var lastSpaceTapAt = 0L

    /**
     * 输入 [text] 若是成对符号的左半且光标后没有内容，则自动补上右半并把
     * 光标留在中间；组词态不处理（交给 onText 的先上屏再输入逻辑）。
     * @return true 表示已按成对处理，调用方不必再普通上屏。
     */
    private fun commitAutoPair(text: String): Boolean {
        val status = rime.run { statusCached }
        val close =
            resolveAutoPair(
                text = text,
                isComposing = status.isComposing,
                hasLocalComposition = service.hasComposition(),
            ) ?: return false
        return service.commitPairedText(text, close)
    }

    companion object {
        /** 双击空格判定窗口：两次按下间隔小于该值视为双击。 */
        private const val SPACE_DOUBLE_TAP_WINDOW_MS = 350L

        /** 左半符号 → 右半符号。快捷符号与符号面板里出现的成对符号都在列（含 ASCII）。 */
        private val AUTO_PAIR_CLOSERS: Map<String, String> =
            mapOf(
                "（" to "）",
                "“" to "”",
                "‘" to "’",
                "《" to "》",
                "【" to "】",
                "「" to "」",
                "『" to "』",
                "(" to ")",
                "[" to "]",
                "{" to "}",
            )

        /**
         * 查不到键码的字符（如 `(`）才会被 [KeyAction] 填进 text，走上面的字符表就够了；
         * 而 `[` `{` 会被 [KeyCode] 解析成键码（KEYCODE_LEFT_BRACKET / 符号码 10011），
         * text 为空，根本走不到那张表。这里按「字符 → 它会被解析成的键码」再补一张。
         */
        private val AUTO_PAIR_BY_KEYCODE: Map<Int, String> =
            mapOf(
                KeyCode.nameToKeyCode("[") to "[",
                KeyCode.nameToKeyCode("{") to "{",
            )

        internal fun autoPairCloserFor(text: String): String? = AUTO_PAIR_CLOSERS[text]

        /** @return [code] 对应的成对左半符号；该键码不是左半符号时为 null。 */
        internal fun autoPairTextForCode(code: Int): String? = AUTO_PAIR_BY_KEYCODE[code]

        /**
         * 判定 [text] 要不要自动补全，返回该补的右半符号；不该补时返回 null。
         *
         * 两道守卫，顺序即优先级：
         * 1. 组词中不补。rime 的 statusCached 由 emitResponse 在**最后一步**刷新，
         *    比按键晚一拍；连打时组词刚建立它还是 isComposing = false，光看它会在
         *    拼音打一半时误补括号。本地 composingText 一并看（分词窗口会写它）。
         *    这条是「打字打到一半冒出括号」那个 bug 的根治点，不能删。
         * 2. 非左半符号不补。
         *
         * 中英文**都补**（用户 2026-10-18 拍板「中文也要」）：按字符查表天然分流 ——
         * 中文键盘上 `(` 经 rime 标点表出来是 `（`，查到 `）`；英文模式下是 `(`，
         * 查到 `)`。所以这里不需要再看 isAsciiMode。
         */
        internal fun resolveAutoPair(
            text: String,
            isComposing: Boolean,
            hasLocalComposition: Boolean,
        ): String? {
            if (isComposing || hasLocalComposition) return null
            return autoPairCloserFor(text)
        }

        internal fun isDoubleSpaceTap(
            now: Long,
            lastAt: Long,
        ): Boolean = lastAt > 0 && now - lastAt < SPACE_DOUBLE_TAP_WINDOW_MS

        /**
         * Regex for combined key events.
         * group(1) captures either:
         *   - a plain prefix (optionally preceded by {Escape}) from the left branch,
         *   - or a standalone {xxx} block from the right branch.
         * The trailing .* consumes the rest of the input without affecting group(1).
         */
        private val TEXT_INPUT_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        private val PLACEHOLDER_PATTERN = Regex(".*(%([1-4]\\$)?s).*")
    }
}
