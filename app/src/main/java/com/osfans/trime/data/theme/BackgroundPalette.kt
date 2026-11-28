package com.osfans.trime.data.theme

import java.util.Locale

/**
 * 由背景图的平均亮度推出一套配色键值。
 *
 * 设置页只负责把结果写进 `<themeId>.custom.yaml`；这里保持纯逻辑，便于单元测试。
 */
internal object BackgroundPalette {
    /** 亮/暗背景的分界亮度，与 KeyboardBackground.LUM_THRESHOLD 保持一致 */
    const val LUM_THRESHOLD = 140.0

    /**
     * 按键蒙层不透明度（约 20%）。
     *
     * 只求把键位轮廓与壁纸分开，不求盖住壁纸：再高就会把整块壁纸洗成一片白（或黑），
     * 图片本身反而看不见了。文字色已经按亮度取反，弱蒙层不影响可读性。
     */
    private const val KEY_ALPHA = 0x33

    /**
     * 候选栏底色不透明度（约 90%）。
     *
     * 候选栏不铺壁纸：候选词压在杂色图上几乎读不清，而键区还有按键形状托底。
     * 用近乎不透明的纯色，既能透出一点壁纸色调，又保证文字对比度。
     */
    private const val CANDIDATE_ALPHA = 0xE6

    /**
     * @param fileName 背景图文件名，写进 [KEYBOARD_BACK] 与 [ROOT_BACKGROUND]
     * @param luminance 图片平均亮度（0..255）
     * @return 不含方向后缀的配色键值
     *
     * 这里写的是 [KEY_BACK] —— 按键自身的半透明底色，会叠在背景图上，文字再画在按键之上。
     * 所以要保证「按键合成后的明度」与文字色相反：
     *   亮背景 → 按键盖白色（更亮）+ 深色字
     *   暗背景 → 按键盖黑色（更暗）+ 浅色字
     * 注意这与 KeyboardBackground.overlayColorFor() 不是同一个机制：
     * 那个是整块键盘上方的统一蒙层，作用于所有键的缝隙。
     */
    fun forImage(fileName: String, luminance: Double): Map<String, String> {
        val light = luminance > LUM_THRESHOLD
        val text = if (light) "0xFF1A1A1A" else "0xFFF5F5F5"
        val sub = if (light) "0xCC444444" else "0xCCDDDDDD"
        return linkedMapOf(
            KEYBOARD_BACK to fileName,
            CANDIDATE_BACK to argb(CANDIDATE_ALPHA, if (light) "FFFFFF" else "1A1A1A"),
            ROOT_BACKGROUND to fileName,
            KEY_BACK to argb(KEY_ALPHA, if (light) "FFFFFF" else "000000"),
            "key_text_color" to text,
            "key_symbol_color" to sub,
            "label_color" to text,
            "candidate_text_color" to text,
            "comment_text_color" to sub,
            "shadow_color" to if (light) "0x40000000" else "0x80000000",
        )
    }

    const val KEYBOARD_BACK = "keyboard_back_color"
    const val CANDIDATE_BACK = "candidate_background"
    const val ROOT_BACKGROUND = "root_background"
    const val KEY_BACK = "key_back_color"

    private fun argb(alpha: Int, rgb: String) = String.format(Locale.ROOT, "0x%02X%s", alpha, rgb)
}
