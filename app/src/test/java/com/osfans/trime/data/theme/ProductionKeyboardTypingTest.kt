// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.view.KeyEvent
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyAction
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 随包主题（trime.yaml）的“能打出中文”契约回归测试。
 *
 * 背景：fork 主题曾把字母键的 `label`（KeyView 的**主显示文本**，见 Key.getLabel）
 * 写成角标数字/符号，导致键盘看起来是数字符号盘、看不到任何字母，
 * 用户无从键入拼音；第 3 行还混入一个重复的 `l` 键。
 * 这里在生产加载路径上钉死以下契约：
 *  1. 字母键主显示必须是字母本身（角标只允许放 label_symbol / hint）；
 *  2. 同一键盘内字母键不得重复；
 *  3. 每行键宽权重和恰为 100（Keyboard.kt 的换行语义）；
 *  4. 键入 nihao：屏幕上看到的字母就是发出的字母（显示字符与 click 键码一致）；
 *  5. 中英切换配置链完整（Mode_switch→ascii_mode，拼音盘↔英文盘）；
 *  6. 首次干净初始化的默认方案列表含拼音方案（DataManager 兜底补丁）。
 *
 * 说明：JVM 单测无法加载 librime 原生库，真实“nihao→中文候选→上屏”由
 * 模拟器/真机验收承担（见 PROGRESS.md）；本文件在配置层钉死不可 mock 的键盘事实。
 */
class ProductionKeyboardTypingTest :
    StringSpec({
        // Android 键名兜底解析是平台调用，测试内按 KeyActionTest 惯例打桩
        beforeTest { KeyCode.androidKeyNameToCode = { 0 } }

        val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")

        fun clickToken(key: TextKeyboard.TextKey): String? = (key.behaviors[KeyBehavior.CLICK] as? KeyActionToken.Plain)?.token

        /** 键盘内所有“click 是单个小写字母”的键：[(键, click字符)] */
        fun letterKeys(id: String): List<Pair<TextKeyboard.TextKey, String>> = theme.presetKeyboards.getValue(id).keys
            .mapNotNull { k -> clickToken(k)?.let { k to it } }
            .filter { (_, tok) -> tok.length == 1 && tok[0] in 'a'..'z' }

        "拼音与英文键盘的字母键必须以字母为主显示（角标只能放 label_symbol/hint）" {
            for (id in listOf("my_pinyin", "my_english")) {
                val wrong =
                    letterKeys(id).filter { (k, tok) ->
                        k.label.isNotEmpty() && !k.label.equals(tok, ignoreCase = true)
                    }.map { (k, tok) -> "[$id] click=$tok 被主显示 label='${k.label}' 顶掉" }
                wrong shouldBe emptyList()
            }
        }

        "同一键盘内字母键不得重复（跨行也算，重复键让布局多出一列）" {
            for (id in listOf("my_pinyin", "my_english")) {
                val clicks = letterKeys(id).map { it.second }
                val duplicated = clicks.groupBy { it }.filterValues { it.size > 1 }.keys.toList()
                duplicated shouldBe emptyList()
            }
        }

        "每行键宽权重和必须恰好 100（模拟 Keyboard.kt 换行语义）" {
            for (id in listOf("my_pinyin", "my_english")) {
                val kb = theme.presetKeyboards.getValue(id)
                val defaultWidth = kb.width.takeIf { it > 0f } ?: 10f
                val rows = mutableListOf<Float>()
                var acc = 0f
                for (k in kb.keys) {
                    val w = if (k.width == 0f && k.hasClickAction) defaultWidth else k.width
                    if (acc + w > 100f + 1e-4f) {
                        rows.add(acc)
                        acc = w
                    } else {
                        acc += w
                    }
                }
                rows.add(acc)
                rows.filter { it != 100f } shouldBe emptyList()
            }
        }

        "键入 nihao：每个字母都能在键盘上看到，且显示的键发出的就是该字母" {
            // 显示字符（label 非空取 label，否则取 click 字符）→ click 字符
            val displayToClick =
                letterKeys("my_pinyin").associate { (k, tok) ->
                    (k.label.ifEmpty { tok }).lowercase() to tok
                }
            for (ch in "nihao") {
                val tok = displayToClick[ch.toString()]
                (tok != null) shouldBe true // 键盘上必须能看到这个字母
                val code = KeyAction(KeyActionToken.Plain(tok ?: ""), theme.presetKeys).code
                code shouldBe KeyEvent.KEYCODE_A + (ch - 'a') // 发出的也是这个字母
            }
        }

        "中英切换配置链完整（Mode_switch 切 ascii_mode，拼音盘↔英文盘互为切换）" {
            val modeSwitch = KeyAction(KeyActionToken.Plain("Mode_switch"), theme.presetKeys)
            modeSwitch.toggle shouldBe "ascii_mode"

            val myPinyin = theme.presetKeyboards.getValue("my_pinyin")
            myPinyin.asciiMode shouldBe false // 首次启用默认中文（拼音）态
            myPinyin.asciiKeyboard shouldBe "my_english"

            val myEnglish = theme.presetKeyboards.getValue("my_english")
            myEnglish.asciiMode shouldBe true

            val keyboardLetter = KeyAction(KeyActionToken.Plain("Keyboard_letter"), theme.presetKeys)
            (keyboardLetter.code != 0) shouldBe true // Eisu_toggle 必须解析成有效键码
            keyboardLetter.select shouldBe "my_pinyin"
        }

        "首次干净初始化的默认方案列表：mydomain（简体+垂直词库）在前，明月简体次之" {
            val patch = DataManager.defaultSchemaListPatch()
            patch.contains("schema_list") shouldBe true
            val ids =
                patch
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith("- schema:") }
                    .map { it.removePrefix("- schema:").trim() }
            ids shouldBe listOf("mydomain", "luna_pinyin_simp", "luna_pinyin")
        }

        "模糊拼音已接入（mydomain 与明月两系方案都引用 pinyin:/ 模板）" {
            val fuzzyMarker = "pinyin:/zh_z_bufen"
            val files =
                listOf(
                    "src/main/assets/shared/mydomain.custom.yaml",
                    "src/main/assets/shared/luna_pinyin.custom.yaml",
                    "src/main/assets/shared/luna_pinyin_simp.custom.yaml",
                )
            val missing = files.filterNot { File(it).readText().contains(fuzzyMarker) }
            missing shouldBe emptyList()
            // 模板本体必须随包发布（librime 才能解析 pinyin:/ 引用）
            File("src/main/assets/shared/pinyin.yaml").isFile shouldBe true
        }

        "键盘背景图：default 配色引用 preview.gif，且图片随包发布" {
            val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")
            val default = theme.colorSchemes.first { it.id == "default" }
            default.colors["keyboard_back_color"] shouldBe "preview.gif"
            // 键面必须是半透明蒙层（0xAARRGGBB 且 alpha < 0xFF），否则背景图被完全盖住
            val keyBack = default.colors["key_back_color"].orEmpty()
            keyBack.startsWith("0x") shouldBe true
            keyBack.length shouldBe 10
            (keyBack.substring(2, 4).toInt(16) < 0xFF) shouldBe true
            File("src/main/assets/shared/backgrounds/mybg/preview.gif").isFile shouldBe true
        }

        "随包方案文件齐备（方案可被部署的前提）" {
            val files =
                listOf(
                    "default.yaml",
                    "default.custom.yaml",
                    "luna_pinyin.schema.yaml",
                    "luna_pinyin.dict.yaml",
                    "mydomain.schema.yaml",
                    "mydomain.dict.yaml",
                )
            val missing = files.filterNot { File("src/main/assets/shared/$it").isFile }
            missing shouldBe emptyList()
        }

        "键盘里引用的 preset 名必须都已定义 —— 未定义的会静默失效（哑键）" {
            // 名字既不是 preset、又不是 librime / Android 认得的键名时，KeyAction
            // 会把它当成字面量文本：按下去打出的是这个名字本身（例如 "copy"），
            // 既不报错也不打日志。CODE-REVIEW.md A4 就是这一批键。
            val presetLike = Regex("^[A-Za-z_][A-Za-z0-9_]+$") // ≥2 字符的标识符：像 preset 名，不像字面量
            val dangling =
                theme.presetKeyboards.entries
                    .flatMap { (id, kb) ->
                        kb.keys.flatMap { key ->
                            key.behaviors.values
                                .mapNotNull { (it as? KeyActionToken.Plain)?.token }
                                .filter(presetLike::matches)
                                .filter { it !in theme.presetKeys.keys }
                                .filter { KeyCode.nameToKeyCode(it) == 0 }
                                .map { "[$id] 引用了未定义的 preset '$it'" }
                        }
                    }.distinct()
            dangling shouldBe emptyList()
        }

        "schema_list 里的方案必须带「部署基座」，否则部署成功却零候选" {
            // librime 的 SchemaUpdate（deployment_tasks.cc）只为「非命名空间」的
            // translator/dictionary 编译词典与棱镜表。方案若把翻译器全写成
            // `script_translator@xxx`，又不另给一个顶层 translator.dictionary，
            // 部署不报错、也不产出任何 .table.bin / .prism.bin —— 该方案零候选，
            // 表现就是「打不出中文」。
            fun schemaTextWithIncludes(
                id: String,
                seen: MutableSet<String> = mutableSetOf(),
            ): String {
                if (!seen.add(id)) return ""
                val file = File("src/main/assets/shared/$id.schema.yaml")
                if (!file.isFile) return ""
                val text = file.readText()
                val included =
                    Regex("""^\s*__include:\s*([A-Za-z0-9_]+)\.schema:""", RegexOption.MULTILINE)
                        .find(text)?.groupValues?.get(1) ?: return text
                return "$text\n${schemaTextWithIncludes(included, seen)}"
            }

            fun hasNamespacedTranslator(text: String): Boolean = Regex("""^\s*-\s*(?:script|table)_translator@""", RegexOption.MULTILINE).containsMatchIn(text)

            /** 任一顶层 `translator:` 块里的 `dictionary:` 值不含 `@`，即视为部署基座 */
            fun hasDeploymentBase(text: String): Boolean {
                val lines = text.lines()
                return lines.withIndex().any { (i, line) ->
                    line.startsWith("translator:") &&
                        lines
                            .drop(i + 1)
                            .takeWhile { it.isEmpty() || it.startsWith(" ") }
                            .any { it.trim().let { t -> t.startsWith("dictionary:") && !t.substringAfter(':').contains("@") } }
                }
            }

            // 方案列表从随包 default.custom.yaml 现取，避免与配置脱节
            val schemaList =
                Regex("""-\s*schema:\s*([A-Za-z0-9_]+)""")
                    .findAll(File("src/main/assets/shared/default.custom.yaml").readText())
                    .map { it.groupValues[1] }
                    .toList()
            schemaList shouldBe listOf("mydomain", "luna_pinyin_simp", "luna_pinyin")

            // 先钉住 include 解析本身：luna_pinyin_simp 自身只有 `translator: prism`，
            // 部署基座是 `__include: luna_pinyin.schema:/` 继承来的。若 include 没被
            // 跟随，它会被下面这条静默跳过 —— 所以这里显式要求解析得到基座。
            hasDeploymentBase(schemaTextWithIncludes("luna_pinyin_simp")) shouldBe true

            val broken =
                schemaList.filter { id ->
                    schemaTextWithIncludes(id).let { hasNamespacedTranslator(it) && !hasDeploymentBase(it) }
                }
            broken shouldBe emptyList()
        }
    })
