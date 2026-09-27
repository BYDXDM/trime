package com.osfans.trime.ime.candidates

import android.content.Context
import android.text.TextUtils
import com.osfans.trime.daemon.RimeSession
import splitties.systemservices.clipboardManager
import timber.log.Timber

/**
 * 剪贴板候选注入器。
 *
 * 目标：输入法被唤起、且 Rime 没有正在组合的输入时，把剪贴板内容作为
 * **第一个候选词**展示，点一下直接上屏。省掉「复制 → 长按 → 粘贴」。
 *
 * 设计要点：
 * - 只在「未组合」时注入。一旦用户开始打字，剪贴板候选必须让位，
 *   否则会长在候选栏第一位干扰选词。
 * - 通过 [shouldInject] / [build] 两个无状态函数暴露，方便单元测试。
 *   副作用（读剪贴板、判重）由调用方控制，本类不持有 Context。
 * - 内容去重靠调用方传入的 [lastInjected]，避免同一条内容反复注入。
 *
 * 接入位置见 INTEGRATION.md「剪贴板候选」一节：
 * 在 TrimeInputMethodService.updateCandidatesView() 里，拿到候选列表后
 * 调 [build]，把结果插到 list 头部即可。
 */
object ClipboardCandidateInjector {

    /** 超过这个长度不注入：长文本塞进候选栏既看不全也没意义 */
    private const val MAX_LENGTH = 200

    /** 候选栏最左边显示的前缀，让用户一眼看出这是剪贴板内容 */
    const val PREFIX = "剪贴板 · "

    /**
     * 判断此刻是否应该注入剪贴板候选。
     *
     * suspend：`RimeSession.run` 接收 suspend lambda，必须在协程里调用。
     * 调用方用 `rime.lifecycleScope.launch { ... }` 或现有的 `postRimeJob` 即可。
     *
     * @param rime          当前会话，用来查「是否正在组合」
     * @param context       用于解析剪贴板内容（coerceToText）
     * @param currentInput  候选栏现有的候选（非空说明 Rime 已出词，让位）
     * @param lastInjected  上一次注入过的文本，用于去重
     */
    suspend fun shouldInject(
        rime: RimeSession,
        context: Context,
        currentInput: List<String>,
        lastInjected: String?,
    ): Boolean {
        // 正在组合（用户打了拼音）→ 绝不插入，否则干扰选词
        val composing = rime.run { statusCached.isComposing }
        if (composing) return false

        // Rime 已有候选（比如联想词）→ 也不插入
        if (currentInput.isNotEmpty()) return false

        val text = readClipboardText(context) ?: return false

        // 同一条内容不重复注入
        if (text == lastInjected) return false

        return true
    }

    /**
     * 读剪贴板并加上前缀，得到最终要展示的候选文本。读不到或为空返回 null。
     *
     * @param context 用于 coerceToText。Trime 内部统一用 splitties 的
     *                `clipboardManager`（全局单例，基于 application context），
     *                但 coerceToText 需要一个 Context 才能解析 content:// URI，
     *                所以这里显式传入，避免依赖 splitties 的内部实现。
     *
     * 注意：Android 10+ 只有「前台应用」能读剪贴板。输入法通常是前台，
     * 但如果用户在系统设置里禁了剪贴板访问，这里会返回 null——按无剪贴板处理。
     */
    fun build(context: Context): String? {
        val raw = readClipboardText(context) ?: return null
        return PREFIX + raw
    }

    private fun readClipboardText(context: Context): String? = try {
        val clip = clipboardManager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            val item = clip.getItemAt(0)
            // 图片/文件这类非纯文本的 item，coerceToText 会给出无意义的描述串
            if (item.uri != null && item.text.isNullOrEmpty()) {
                null
            } else {
                val text = item.coerceToText(context)?.toString()
                if (TextUtils.isEmpty(text)) {
                    null
                } else {
                    text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
                }
            }
        }
    } catch (e: Exception) {
        // 某些 ROM 在无权限时抛 SecurityException，静默降级
        Timber.w(e, "读剪贴板失败，跳过注入")
        null
    }
}
