/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * myime —— 键盘背景设置
 *
 * 功能：
 *   1. 从系统文件选择器导入 .jpg / .jpeg / .png / .webp / .gif（支持多选）
 *   2. 列出已导入的背景（预览图，GIF 显示首帧）
 *   3. 删除背景
 *   4. 一键应用：按图片亮度生成一个自带半透明蒙层的配色方案并切过去
 *
 * 关键约束（来自 Trime 源码）：
 *   ColorTable.kt:44  IMAGE_SUFFIXES = [".png", ".webp", ".jpg", ".gif"]
 *     → 颜色值以这些后缀结尾时被当作图片路径。所以导入时必须保留扩展名。
 *   ColorManager.kt:246/248
 *     → 查找路径 userDataDir/backgrounds/<background_folder>/<值>
 *       降级到 userDataDir/backgrounds/<值>
 *   ThemeLoader.kt:260-274
 *     → 主题加载时会自动注入 <主题id>.custom.yaml 的 patch；
 *       但配色键必须写成 `preset_color_schemes/<id>` 这类 librime 路径形式，
 *       而 ThemeDslExpander 不支持路径键，于是主题改从**已部署产物**读取 ——
 *       ThemeLoader.loadDeployedTheme() 会顺手调 Rime.deployRimeConfigFile
 *       完成部署。所以改完补丁只要重载一次主题即可，不用自己写部署代码。
 *       细节见 data/theme/BackgroundPatch.kt 的说明。
 *
 * 接入位置：
 *   导航路由 ui/main/NavigationRoute.kt 的 KeyboardBackground；
 *   入口在 设置 → 键盘样式 → 键盘背景。
 */

package com.osfans.trime.ui.main.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.BackgroundPatch
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale

class BackgroundPickerFragment : Fragment() {

    companion object {
        /** 与 trime.yaml 的 style/background_folder 保持一致 */
        private const val BACKGROUND_FOLDER = "mybg"

        /**
         * 生成出来的配色方案 id。
         *
         * 注意这是**配色方案**（`preset_color_schemes` 下的键），不是主题配置名 ——
         * 切换要用 [ColorManager.setColorScheme]，不要调 `ThemeManager.selectTheme`。
         */
        private const val SCHEME_ID = "user_bg"

        /** 限制单张图大小，防止大 GIF 拖慢键盘渲染 */
        private const val MAX_BYTES = 4L * 1024 * 1024

        private val ALLOWED = setOf("jpg", "jpeg", "png", "webp", "gif")

        /**
         * APK 自带的示例背景：`R.raw 资源 id` → 铺到用户目录后的文件名。
         *
         * 用 res/raw 而不是 assets/shared，是为了绕开 Rime 那套
         * assets → sharedDataDir 的部署与 checksums.json 校验 ——
         * 背景图不是 Rime 配置，不该混进那条链路。
         */
        private val BUNDLED = listOf(R.raw.bg_preview to "preview.gif")

        /** 亮/暗背景的分界亮度，与 KeyboardBackground.LUM_THRESHOLD 保持一致 */
        private const val LUM_THRESHOLD = 140.0

        /**
         * 蒙层不透明度（约 60%）。
         *
         * 亮/暗背景共用同一个值：蒙层颜色已经承担了「压暗 / 提亮」的方向，
         * 再让透明度也随亮度变化只会让观感不稳定，没有额外收益。
         */
        private const val OVERLAY_ALPHA = 0x99
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var dir: File

    /** 文件选择器：只允许图片类型，支持一次选多张 */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            // 多选时结果放在 clipData，单选时在 data
            val uris =
                buildList {
                    data.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
                    }
                    if (isEmpty()) data.data?.let { add(it) }
                }
            if (uris.isNotEmpty()) importBackgrounds(uris)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        dir = File(DataManager.userDataDir, "backgrounds/$BACKGROUND_FOLDER").apply { mkdirs() }
        // 首次打开时把内置示例背景铺进来，省得用户还要自己找文件传进手机
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { seedBundledBackgrounds() }
            refreshList()
        }

        root.addView(
            TextView(requireContext()).apply {
                text = "键盘背景"
                textSize = 20f
                setPadding(0, 0, 0, 8)
            },
        )

        root.addView(
            TextView(requireContext()).apply {
                text = "支持 JPG / PNG / WebP / GIF。建议不超过 1 MB，过大或帧数过多的 GIF 会让键盘卡顿。" +
                    "\n图片存放目录：${dir.absolutePath}"
                textSize = 12f
                setPadding(0, 0, 0, 24)
            },
        )

        root.addView(
            android.widget.Button(requireContext()).apply {
                text = "导入背景图"
                setOnClickListener { launchPicker() }
            },
        )

        root.addView(
            TextView(requireContext()).apply {
                text = "已导入"
                textSize = 16f
                setPadding(0, 32, 0, 8)
            },
        )

        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        return root
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            // 允许一次选多张
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickImage.launch(Intent.createChooser(intent, "选择背景图"))
    }

    // ---------------------------------------------------------------- 导入

    private fun importBackgrounds(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val imported = mutableListOf<String>()
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    doImport(uri)
                        .onSuccess { imported += it }
                        .onFailure { failed += (it.message ?: "未知错误") }
                }
            }
            if (imported.isNotEmpty()) {
                toast(if (imported.size == 1) "已导入：${imported.first()}" else "已导入 ${imported.size} 张")
            }
            if (failed.isNotEmpty()) {
                toast("有 ${failed.size} 张导入失败：${failed.first()}")
            }
            refreshList()
        }
    }

    private fun doImport(uri: Uri): Result<String> = runCatching {
        val resolver = requireContext().contentResolver

        // 1. 取真实文件名 —— 必须保留扩展名，Trime 靠它判断是不是图片
        var displayName: String? = null
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) displayName = c.getString(nameIdx)
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }

        var name = displayName?.takeIf { it.isNotBlank() } ?: "bg_${System.currentTimeMillis()}.jpg"

        // 2. 校验扩展名；有的 provider 不给扩展名，从 MIME 推
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in ALLOWED) {
            val mime = resolver.getType(uri) ?: ""
            val guessed = when {
                mime.contains("gif") -> "gif"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                else -> throw IllegalStateException("只支持 JPG / PNG / WebP / GIF")
            }
            name = name.substringBeforeLast('.', name) + "." + guessed
        }

        // 3. 大小限制
        if (size > MAX_BYTES) {
            val mb = String.format(Locale.ROOT, "%.1f", size / 1024.0 / 1024.0)
            throw IllegalStateException("图片 ${mb}MB 超过 4MB 上限")
        }

        // 4. 允许同名覆盖（用户换图时不用先删）
        val dest = File(dir, name)
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取该文件")

        // 5. 二次校验：确保真的能解码（防伪装扩展名的坏文件）
        if (BitmapFactory.decodeFile(dest.absolutePath) == null) {
            dest.delete()
            throw IllegalStateException("文件不是有效图片")
        }

        name
    }

    // ---------------------------------------------------------------- 内置示例

    /**
     * 把 APK 里自带的示例背景铺到用户目录，省得用户还要自己把文件传进手机。
     *
     * 已存在的同名文件**不覆盖**（用户自己导入过的优先）。
     * 只在打开本页时执行，不在启动路径上，不影响输入法冷启动。
     */
    private fun seedBundledBackgrounds() {
        runCatching {
            BUNDLED.forEach { (resId, name) ->
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) return@forEach
                resources.openRawResource(resId).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }.onFailure { Timber.w(it, "内置背景铺入失败") }
    }

    // ---------------------------------------------------------------- 列表

    private fun refreshList() {
        listContainer.removeAllViews()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

        if (files.isEmpty()) {
            listContainer.addView(
                TextView(requireContext()).apply {
                    text = "（还没有背景图）"
                    setPadding(0, 8, 0, 8)
                },
            )
            return
        }

        files.forEach { file -> listContainer.addView(buildRow(file)) }
    }

    private fun buildRow(file: File): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // 缩略图（GIF 取首帧）
        val thumb = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(180, 120)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeSampled(file, 200, 140) }
            if (bmp != null) thumb.setImageBitmap(bmp)
        }
        row.addView(thumb)

        // 文件名 + 大小
        val info = TextView(ctx).apply {
            text = buildString {
                append(file.name)
                append("\n")
                append(String.format(Locale.ROOT, "%.0f KB", file.length() / 1024.0))
                if (file.extension.equals("gif", true)) append("  · 动图")
            }
            textSize = 13f
            setPadding(24, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(info)

        // 应用
        row.addView(
            android.widget.Button(ctx).apply {
                text = "应用"
                setOnClickListener { applyAsBackground(file) }
            },
        )

        // 删除
        row.addView(
            android.widget.Button(ctx).apply {
                text = "删除"
                setOnClickListener {
                    if (file.delete()) {
                        toast("已删除 ${file.name}")
                        refreshList()
                    } else {
                        toast("删除失败")
                    }
                }
            },
        )

        return row
    }

    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null

        var sample = 1
        while (opts.outWidth / sample > reqW || opts.outHeight / sample > reqH) sample *= 2

        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts2)
    }

    // ------------------------------------------------------------ 应用到主题

    /**
     * 把图片应用为键盘背景。
     *
     * 做三件事：
     *   1. 在 userDataDir 写一份 `<当前主题id>.custom.yaml`，把图片文件名与
     *      算好的蒙层/文字色写进一个名为 [SCHEME_ID] 的配色方案；
     *   2. **重新加载主题** —— 主题（含 `preset_color_schemes`）只在加载时读一次
     *      `<id>.custom.yaml`（`ThemeLoader.kt:260-274`），不重载则补丁不生效；
     *   3. 把当前配色切到 [SCHEME_ID]，并持久化选择。
     *
     * 为什么不直接改 trime.yaml？
     *   trime.yaml 打包在 APK 资源里，运行时不可写，且升级会被覆盖。
     */
    private fun applyAsBackground(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val themeId = ThemeManager.prefs.selectedTheme.getValue()
            val ok = withContext(Dispatchers.IO) { doApply(file, themeId) }
            if (!ok) {
                toast("应用失败")
                return@launch
            }
            // 重载主题，让刚写下的补丁被读进来（内部已切到主线程）
            runCatching { ThemeManager.selectTheme(themeId) }
                .onFailure { toast("主题重载失败：${it.message}") }

            val scheme = ThemeManager.activeTheme.colorSchemes.find { it.id == SCHEME_ID }
            if (scheme == null) {
                toast("背景已写入，但配色未生成；请到「键盘样式」里检查")
                return@launch
            }
            // setColorScheme 会同时切换当前配色并持久化 prefs.normalModeColor
            ColorManager.setColorScheme(scheme)
            toast("已应用背景：${file.name}")
        }
    }

    private fun doApply(
        file: File,
        themeId: String,
    ): Boolean = runCatching {
        // 检测图片平均亮度，决定蒙层深浅和文字颜色
        val bmp = decodeSampled(file, 64, 64) ?: return@runCatching false
        val luminance = averageLuminance(bmp)
        bmp.recycle()

        // 这里写的是 key_back_color —— 按键自身的半透明底色，会叠在背景图上，
        // 文字再画在按键之上。所以要保证「按键合成后的明度」与文字色相反：
        //   亮背景 → 按键盖白色（更亮）+ 深色字
        //   暗背景 → 按键盖黑色（更暗）+ 浅色字
        // 注意这与 KeyboardBackground.overlayColorFor() 不是同一个机制：
        // 那个是整块键盘上方的统一蒙层，作用于所有键的缝隙。
        val light = luminance > LUM_THRESHOLD
        val overlay = if (light) {
            String.format(Locale.ROOT, "0x%02XFFFFFF", OVERLAY_ALPHA)
        } else {
            String.format(Locale.ROOT, "0x%02X000000", OVERLAY_ALPHA)
        }
        val textColor = if (light) "0xFF1A1A1A" else "0xFFF5F5F5"
        val subColor = if (light) "0xCC444444" else "0xCCDDDDDD"
        val shadow = if (light) "0x40000000" else "0x80000000"

        // 只产出「我们这个配色的键值」；整个 preset_color_schemes 表由
        // BackgroundPatch.buildPatchBody 从当前主题现取 ——
        // librime 的 patch 对顶层键是整体替换，必须带上全表才不会删掉其它配色。
        val ownColors =
            linkedMapOf(
                "name" to "自定义背景",
                "author" to "myime",
                "keyboard_back_color" to file.name,
                "candidate_back_color" to file.name,
                "root_background" to file.name,
                "key_back_color" to overlay,
                "key_border_color" to "0x22FFFFFF",
                "key_text_color" to textColor,
                "key_symbol_color" to subColor,
                "label_color" to textColor,
                "candidate_text_color" to textColor,
                "comment_text_color" to subColor,
                "shadow_color" to shadow,
            )

        writeBackgroundPatch(themeId, ownColors)
        true
    }.getOrDefault(false)

    /**
     * 把受管区块写进 `<themeId>.custom.yaml`，保留文件里的其它用户改动。
     *
     * 合并与序列化规则抽在 [BackgroundPatch]（纯字符串逻辑，可单元测试）。
     */
    private fun writeBackgroundPatch(
        themeId: String,
        ownColors: Map<String, String>,
    ) {
        val custom = File(DataManager.userDataDir, "$themeId.custom.yaml")
        val existing = if (custom.exists()) custom.readText(Charsets.UTF_8) else ""
        val body =
            BackgroundPatch.buildPatchBody(
                schemes = ThemeManager.activeTheme.colorSchemes,
                ownSchemeId = SCHEME_ID,
                ownColors = ownColors,
            )
        custom.writeText(BackgroundPatch.merge(existing, body), Charsets.UTF_8)
    }

    private fun averageLuminance(bmp: Bitmap): Double {
        var sum = 0.0
        var n = 0
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 128.0 else sum / n
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
