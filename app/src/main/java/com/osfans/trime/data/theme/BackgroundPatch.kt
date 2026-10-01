// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme

/**
 * 生成 `<主题id>.custom.yaml` 里「键盘背景」那一段的受管区块。
 *
 * ## 为什么必须是平铺的嵌套键（重要）
 *
 * 最初这里写的是 librime 的路径语法 `preset_color_schemes/<id>`。那个写法有严重后果：
 * [ThemeDslExpander] 的 `checkPlainKey` 会拒绝含 `/` 的键并抛 `UnsupportedDsl`，
 * 于是 **应用内读源码的通道每次都失败**，主题被迫走
 * `ThemeLoader.loadDeployedTheme()` —— 那条路要调 JNI 的
 * `Rime.deployRimeConfigFile` 做一次完整部署。一旦 native 尚未就绪或部署失败，
 * 主题加载就会失败，**输入法界面直接空白**（实测踩到过）。
 *
 * 所以现在改成普通 YAML 嵌套结构：
 *
 * ```yaml
 * patch:
 *   preset_color_schemes:
 *     "default": { ... }      # 整表复制
 *     "user_bg": { ... }      # 我们追加的
 * ```
 *
 * 代价是 `ThemeDslExpander.applyPatch` 对**顶层键是整体替换**（不做深合并），
 * 所以 `preset_color_schemes` 必须整表带上 —— 只写 `user_bg` 会把其它配色全删掉。
 * 整表由 [buildPatchBody] 从当前主题现取，所以不会过期。
 *
 * 换来的是：不触发部署、不依赖 native、键盘不会因为换背景而打不开。
 *
 * ## 写文件时必须守住的两点
 *
 * 1. **顶层 `patch:` 只能有一个。** 出现第二个会让 YAML 解析失败。
 * 2. **只清理自己上次写的块**（[MARK_BEGIN] / [MARK_END] 之间），
 *    用户在同一文件里的其它 patch 一律保留。
 *
 * 纯字符串逻辑（不碰文件系统），便于单元测试 —— 见 `BackgroundPatchTest`。
 */
internal object BackgroundPatch {
    /** 受管区块开始标记；写入时缩进两格，作为 `patch:` 下的注释 */
    const val MARK_BEGIN = "# >>> myime background"

    /** 受管区块结束标记 */
    const val MARK_END = "# <<< myime background"

    /** 第 0 列的 `patch:` 根节点行 */
    private val TOP_LEVEL_PATCH = Regex("(?m)^patch:[ \t]*$")

    /**
     * 把受管区块合并进现有内容。
     *
     * @param existing 文件现有内容；文件不存在时传空串
     * @param patchBody `patch:` 之下的内容，须已缩进两格（见 [buildPatchBody]）
     * @return 应写回文件的内容，保证只有一个顶层 `patch:` 与一个受管区块
     */
    fun merge(
        existing: String,
        patchBody: String,
    ): String {
        val kept = stripManagedBlock(existing)
        val block =
            buildString {
                appendLine("  $MARK_BEGIN")
                appendLine(patchBody.trimEnd())
                append("  $MARK_END")
            }

        if (kept.isEmpty()) return "patch:\n$block\n"

        val patchLine =
            TOP_LEVEL_PATCH.find(kept)
                ?: return "$kept\n\npatch:\n$block\n"
        val insertAt = patchLine.range.last + 1
        return kept.substring(0, insertAt) + "\n" + block + kept.substring(insertAt) + "\n"
    }

    /**
     * 构造 `patch:` 的正文：把**当前主题的全部配色**原样带上，再把 [ownColors]
     * 作为 [ownSchemeId] 写进去（已存在则覆盖）。
     *
     * 之所以要整表复制，是因为 librime 的 patch 对顶层键是整体替换而不是深合并
     * （见 `ThemeDslExpander.applyPatch`），只带一项会抹掉其它配色。
     *
     * @param schemes 当前主题的配色（含此前已写入的 [ownSchemeId] 也没关系）
     */
    fun buildPatchBody(
        schemes: List<ColorScheme>,
        ownSchemeId: String,
        ownColors: Map<String, String>,
    ): String = buildString {
        appendLine("  preset_color_schemes:")
        val others = schemes.filter { it.id != ownSchemeId }
        (others.map { it.id to it.colors } + (ownSchemeId to ownColors)).forEach { (id, colors) ->
            appendLine("    ${quote(id)}:")
            colors.forEach { (key, value) ->
                // name 放最前，其余按原顺序，读起来更清楚
                appendLine("      ${quote(key)}: ${quote(value)}")
            }
        }
    }

    /** 去掉上一次写入的受管区块（连同两行标记） */
    private fun stripManagedBlock(existing: String): String {
        var inside = false
        return existing
            .lines()
            .filterNot { line ->
                when (line.trim()) {
                    MARK_BEGIN -> {
                        inside = true
                        true
                    }

                    MARK_END -> {
                        inside = false
                        true
                    }

                    else -> inside
                }
            }.joinToString("\n")
            .trimEnd()
    }

    /** 统一加双引号，避免颜色值里的 `#`、`: `、空格等把 YAML 弄坏 */
    private fun quote(raw: String): String = '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
}
