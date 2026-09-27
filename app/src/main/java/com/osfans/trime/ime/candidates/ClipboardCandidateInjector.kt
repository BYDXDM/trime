package com.osfans.trime.ime.candidates

import android.text.TextUtils
import com.osfans.trime.core.Rime
import splitties.systemservices.clipboardManager
import timber.log.Timber

/**
 * 剪贴板候选注入器。
 *
 * 目标：输入法首次被唤起（且候选栏为空）时，把剪贴板内容作为**第一个候选词**展示，
 * 点一下就上屏。解决「复制一段文字 → 切到输入法 → 还要长按粘贴」的繁琐。
 *
 * 为什么需要这个类：Rime 引擎的候选流由 librime 产生，剪贴板内容不在其中。
 * Trime 的候选栏直接读 Rime.candidatesWithoutCommit，所以必须在
 * 「引擎候选」和「UI 候选」之间插一层转换。本类只做注入，不改任何 Rime 状态，
 * 因此不会污染用户词库、不会影响用户词频学习。
 *
 * 触发时机（三条全部满足才注入）：
 *   1. 本次输入会话是**首次**唤起（onStartInputView 且 !restarting）
 *   2. 当前 Rime 编码为空（用户还没开始打字）
 *   3. 剪贴板内容与上次已注入的不同（避免每次切输入法都弹同一条）
 *
 * 安全：不读剪贴板敏感标记（isSensitive），不记录剪贴板原文到日志。
 */
object ClipboardCandidateInjector {

    /** 允许注入的最大字符数。太长的文本塞进候选栏既难看也容易误触。 */
    private const val MAX_LENGTH = 36

    /** 候选栏显示用的截断长度 */
    private const val DISPLAY_LENGTH = 14

    /** 记录已注入过的内容，防止重复弹同一条 */
    private var lastInjected: String? = null

    /** 本次会话是否已经用过注入（每次 onStartInputView 重置） */
    private var consumedInSession = false

    /**
     * 新的输入会话开始。在 TrimeInputMethodService.onStartInputView() 里调用。
     */
    fun onNewSession() {
        consumedInSession = false
    }

    /**
     * 读取剪贴板，判断此刻是否应该注入。
     *
     * @return 可上屏的完整文本；不需要注入时返回 null
     */
    fun peekForInjection(): String? {
        if (consumedInSession) return null

        // 用户已经开始打字了，别插队
        if (Rime.statusCached.isComposing) return null

        val text = readClipboard() ?: return null

        // 同一条内容只提示一次，避免骚扰
        if (text == lastInjected) return null

        return text
    }

    /** 真正把该候选上屏，并标记已消费 */
    fun commit(text: String) {
        lastInjected = text
        consumedInSession = true
    }

    /** 用户做了别的操作（打字、切键盘），本会话不再注入 */
    fun dismiss() {
        consumedInSession = true
    }

    /**
     * 返回候选栏要显示的短文本。多行和超长都截断，
     * 并把换行替换成可见符号，否则候选栏高度会炸。
     */
    fun displayText(text: String): String {
        val oneLine = text.replace('\n', '↵').replace('\r', ' ')
        val shown = if (oneLine.length > DISPLAY_LENGTH) {
            oneLine.substring(0, DISPLAY_LENGTH) + "…"
        } else {
            oneLine
        }
        return "📋 $shown"
    }

    private fun readClipboard(): String? {
        // Android 10+ 只有前台应用能读剪贴板，输入法自身是前台，可以读。
        val clip = try {
            clipboardManager.primaryClip
        } catch (e: SecurityException) {
            Timber.w(e, "读取剪贴板被拒绝")
            return null
        } ?: return null

        if (clip.itemCount == 0) return null

        // 敏感内容（密码管理器标记的）一律不碰
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
            return null
        }

        val item = clip.getItemAt(0)
        val text = try {
            item.coerceToText(null)?.toString()
        } catch (e: Exception) {
            Timber.w(e, "剪贴板内容转换失败")
            null
        }

        if (TextUtils.isEmpty(text)) return null
        // 图片、文件之类的非纯文本不适合当候选（content:// URI 会 paste 出乱码）
        if (item.uri != null && item.text.isNullOrEmpty()) return null

        return text!!.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_LENGTH
        }
    }
}
