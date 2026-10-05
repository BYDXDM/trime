// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        lateinit var currentKeyboard: Keyboard

        /** `import_preset` 的最大展开层数，防止主题写出环导致无限递归 */
        private const val MAX_IMPORT_PRESET_DEPTH = 8
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()

    /**
     * 键盘名 → 它声明的 `ascii_keyboard`（未声明为空串）。
     * 中英切换靠这一份声明推导「中文盘 ↔ 英文盘」的配对，见
     * [resolveKeyboardForAsciiMode]。
     */
    private val asciiKeyboardOf = theme.presetKeyboards.mapValues { it.value.asciiKeyboard }

    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val activeKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)
        attachKeyboard(evalKeyboard(".default"))
        return keyboardView
    }

    /**
     * 卸载当前键盘。
     *
     * @param recordAsciiMode 是否把「离开这一刻的 ascii_mode」记进旧键盘的
     *   `lastAsciiMode`。**由 `ascii_mode` 变化驱动的换键盘必须传 false**：这次模式
     *   变化不是这个键盘造成的，记下来会污染它的记忆值；它之后在别处被重新挂载
     *   （[refreshKeyboards]、`onStartInput`）时会按这个假记忆把模式写回去。
     */
    private fun detachCurrentView(recordAsciiMode: Boolean = true) {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        if (recordAsciiMode) {
            activeKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
        }
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        val padding = theme.generalStyle.run {
            if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
        }

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = safeWidth - 2 * context.dp(padding)
        allowedWidth = width
        return width
    }

    /**
     * 解析键盘名到实际的键盘配置。
     *
     * ★ 绝不能返回 null：调用方会用 `Keyboard(context, theme, width, null)` 构造键盘，
     *   那样没有任何按键、键盘区域整块空白（本 fork 的 `default` 键缺失就是这样翻车的）。
     *   所以这里多了一层「随便挑一个能画的键盘」兜底。
     *
     * [depth] 防止主题把 `import_preset` 写成环（a→b→a）时无限递归爆栈。
     */
    private fun selectKeyboardConfig(
        name: String,
        depth: Int = 0,
    ): TextKeyboard? {
        if (depth > MAX_IMPORT_PRESET_DEPTH) return null
        val config =
            theme.presetKeyboards[name]
                ?: theme.presetKeyboards["default"]
                ?: theme.presetKeyboards.values.firstOrNull { it.keys.isNotEmpty() }
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset, depth + 1)
        }
        return config
    }

    /**
     * 挂载键盘。
     *
     * @param syncAsciiMode 是否按目标键盘的 `reset_ascii_mode` / 记忆值回写
     *   `ascii_mode`。**由 `ascii_mode` 变化驱动的换键盘必须传 false** —— 模式是那次
     *   切换的「因」，再被键盘的记忆值回写就会把用户刚切到的状态打回去，中英切换键
     *   表现为「按了没反应」。
     */
    private fun attachKeyboard(
        target: String,
        syncAsciiMode: Boolean = true,
    ) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = activeKeyboard ?: Keyboard(context, theme, computeAllowedWidth(), config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            runBlocking { _currentKeyboardHeight.emit(it.keyboardHeight) }
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode

            if (syncAsciiMode) {
                val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                if (currentMode != targetMode) {
                    service.postRimeJob {
                        commitComposition()
                        setRuntimeOption("ascii_mode", targetMode)
                    }
                }
            }

            currentKeyboard = it
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    /**
     * 选出与当前 Rime 方案匹配的键盘名。
     *
     * ★ 末尾的兜底会返回**主题里真实存在的**键盘名，而不是字面量 `"default"`。
     *   原因：fork 把键盘改名为 my_pinyin / my_english 等，主题里并没有
     *   `default` / `qwerty` 这些通用名。以前这里直接 `else "default"`，
     *   再往下 `selectKeyboardConfig` 查不到就返回 null，调用方用
     *   `Keyboard(context, theme, width, null)` 构造出**零按键**的键盘 ——
     *   表现就是输入法界面整块空白（首次进输入法必现）。
     */
    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout = layoutNameForAlphabet(alphabet, presetKeyboardIds, currentSchema)
        if (layout.isNotEmpty()) return layout

        // 兜底：挑一个真实存在且真的有按键的键盘，让键盘一定能画出来
        return pickFallbackKeyboard(presetKeyboardIds) { id ->
            theme.presetKeyboards[id]?.keys?.size ?: 0
        } ?: "default"
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()

                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId

                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId

                ".last" -> lastKeyboardId

                ".last_lock" -> lastLockKeyboardId

                ".ascii" -> {
                    var ascii = activeKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }

                else -> {
                    id.ifEmpty {
                        if (activeKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    /**
     * 切换键盘。
     *
     * @param syncAsciiMode 见 [attachKeyboard]；由 `ascii_mode` 变化驱动的切换
     *   必须传 false。
     */
    fun switchKeyboard(
        to: String,
        syncAsciiMode: Boolean = true,
    ) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) return@execute
            }
            detachCurrentView(recordAsciiMode = syncAsciiMode)
            attachKeyboard(target, syncAsciiMode)
        }
        Timber.d("Switched to keyboard: $target")
    }

    /**
     * 由 `ascii_mode` 变化驱动的换键盘（中英切换）。
     *
     * 与 [switchKeyboard] 的两点关键差别：
     *  1. **解析放进同一个主线程任务里**。[switchKeyboard] 的 detach/attach 是投递给
     *     主队列的，`currentKeyboardId` 到那时才更新。若在通知回调里先解析再投递，
     *     连续两次通知（快速连按中英键）会让第二次读到过期的 `currentKeyboardId`，
     *     解析出错切 / 漏切 —— 又变成「按了没反应」。
     *  2. **`syncAsciiMode = false`**：模式是本次切换的「因」，不能再被键盘的记忆值
     *     回写；同时把目标键盘的记忆值对齐到新模式，免得它之后被 [refreshKeyboards]
     *     等路径重新挂载时按旧记忆把模式写回去。
     */
    private fun switchKeyboardForAsciiMode(asciiMode: Boolean) {
        ContextCompat.getMainExecutor(service).execute {
            val target = resolveKeyboardForAsciiMode(asciiMode, currentKeyboardId, asciiKeyboardOf)
            if (target == null || target == currentKeyboardId) return@execute
            detachCurrentView(recordAsciiMode = false)
            attachKeyboard(target, syncAsciiMode = false)
            cachedKeyboards[target]?.first?.lastAsciiMode = asciiMode
            Timber.d("Switched to keyboard for ascii_mode=$asciiMode: $target")
        }
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() {
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onStartInput(info: EditorInfo) {
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        switchKeyboard(targetKeyboard)
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: activeKeyboard?.let {
                if (theme.generalStyle.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.generalStyle.autoCaps && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            activeKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            activeKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            // 中英切换：Rime 的 ascii_mode 变了，键盘要跟着换。
            // 主题用 `my_pinyin.ascii_keyboard: my_english` 声明配对，两个方向都
            // 由这份声明推导（见 resolveKeyboardForAsciiMode），代码里不写死键盘名。
            // 这条 OptionMessage 是唯一可靠的同步点 —— 用户按中英键时 Rime 必发它。
            option == "ascii_mode" -> switchKeyboardForAsciiMode(value.value)

            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(KeyActionManager.getAction(what))
                }
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
    }
}
