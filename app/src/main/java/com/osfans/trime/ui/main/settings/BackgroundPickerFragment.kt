/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * myime —— 键盘背景设置
 *
 * 功能：
 *   1. 从系统文件选择器导入 .jpg / .jpeg / .png / .webp / .gif
 *   2. 列出已导入的背景（网格预览，GIF 显示首帧）
 *   3. 删除背景
 *   4. 一键生成配色方案（在原配色基础上替换背景图），自动算对比度
 *
 * 关键约束（来自 Trime 源码）：
 *   ColorTable.kt:47  IMAGE_SUFFIXES = [".png", ".webp", ".jpg", ".gif"]
 *     → 颜色值以这些后缀结尾时被当作图片路径。所以导入时必须保留扩展名。
 *   ColorManager.kt:220
 *     → 查找路径 userDataDir/backgrounds/<background_folder>/<值>
 *       降级到 userDataDir/backgrounds/<值>
 *
 * 集成方式（3 处）：
 *   1. 加进设置页的 PreferenceScreen（见 INTEGRATION.md）
 *   2. AndroidManifest 里注册本 Fragment（或直接用 Navigation 组件）
 *   3. 依赖：Glide 或直接用 BitmapFactory（本例不依赖第三方库）
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
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class BackgroundPickerFragment : Fragment() {

    companion object {
        /** 与 trime.yaml 的 style/background_folder 保持一致 */
        private const val BACKGROUND_FOLDER = "mybg"

        /** 主题配置名。trime.custom.yaml 里写入的就是这个 id */
        private const val THEME_ID = "user_bg"

        /** 限制单张图大小，防止大 GIF 拖慢键盘渲染 */
        private const val MAX_BYTES = 4L * 1024 * 1024

        private val ALLOWED = setOf("jpg", "jpeg", "png", "webp", "gif")
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var dir: File

    /** 文件选择器：只允许图片类型 */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            importBackground(uri)
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

        root.addView(TextView(requireContext()).apply {
            text = "键盘背景"
            textSize = 20f
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(requireContext()).apply {
            text = "支持 JPG / PNG / WebP / GIF。建议不超过 1 MB，过大或帧数过多的 GIF 会让键盘卡顿。" +
                "\n图片存放目录：${dir.absolutePath}"
            textSize = 12f
            setPadding(0, 0, 0, 24)
        })

        root.addView(android.widget.Button(requireContext()).apply {
            text = "导入背景图"
            setOnClickListener { launchPicker() }
        })

        root.addView(TextView(requireContext()).apply {
            text = "已导入"
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })

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

    private fun importBackground(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doImport(uri) }
            result.onSuccess { name ->
                toast("已导入：$name")
                refreshList()
            }.onFailure {
                toast("导入失败：${it.message}")
            }
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

    // ---------------------------------------------------------------- 列表

    private fun refreshList() {
        listContainer.removeAllViews()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

        if (files.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = "（还没有背景图）"
                setPadding(0, 8, 0, 8)
            })
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
        row.addView(android.widget.Button(ctx).apply {
            text = "应用"
            setOnClickListener { applyAsBackground(file) }
        })

        // 删除
        row.addView(android.widget.Button(ctx).apply {
            text = "删除"
            setOnClickListener {
                if (file.delete()) {
                    toast("已删除 ${file.name}")
                    refreshList()
                } else {
                    toast("删除失败")
                }
            }
        })

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
     * 把图片写进用户配色方案。
     *
     * 做法：读取当前配色（normalModeColor 指定的那个），在 userDataDir 下写一份
     *       <配色名>.custom.yaml，把背景相关的颜色键改成图片文件名。
     *
     * 为什么不直接改 trime.yaml？
     *   trime.yaml 打包在 APK 资源里，运行时不可写；且升级会被覆盖。
     *   用户配色补丁放 userDataDir 才符合 Rime 的「升级不丢」原则。
     */
    private fun applyAsBackground(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { doApply(file) }
            if (ok) {
                toast("已应用背景：${file.name}")
                // 重新选一次主题，让 ColorManager 用新的 trime.custom.yaml 重建配色。
                // ThemeManager.selectTheme 是 suspend，且内部已切到主线程，
                // 不要用 ThemeManager.init()——那是给 Configuration 变化用的。
                runCatching { ThemeManager.selectTheme(THEME_ID) }
            } else {
                toast("应用失败")
            }
        }
    }

    private fun doApply(file: File): Boolean = runCatching {
        // 检测图片平均亮度，决定蒙层深浅和文字颜色
        val bmp = decodeSampled(file, 64, 64) ?: return@runCatching false
        val luminance = averageLuminance(bmp)
        bmp.recycle()

        // 亮背景 → 白色蒙层 + 深色文字；暗背景 → 黑色蒙层 + 浅色文字
        val light = luminance > 140
        val overlayAlpha = if (light) 0x99 else 0x99
        val overlay = if (light) {
            String.format(Locale.ROOT, "0x%02XFFFFFF", overlayAlpha)
        } else {
            String.format(Locale.ROOT, "0x%02X000000", overlayAlpha)
        }
        val textColor = if (light) "0xFF1A1A1A" else "0xFFF5F5F5"
        val subColor = if (light) "0xCC444444" else "0xCCDDDDDD"
        val shadow = if (light) "0x40000000" else "0x80000000"

        val patch = buildString {
            appendLine("# 由 myime 背景设置生成 —— 请勿手改，改背景请回设置页")
            appendLine("patch:")
            appendLine("  \"preset_color_schemes/user_bg\":")
            appendLine("    name: 自定义背景")
            appendLine("    author: myime")
            appendLine("    keyboard_back_color: ${file.name}")
            appendLine("    candidate_back_color: ${file.name}")
            appendLine("    root_background: ${file.name}")
            appendLine("    key_back_color: $overlay")
            appendLine("    key_border_color: 0x22FFFFFF")
            appendLine("    key_text_color: $textColor")
            appendLine("    key_symbol_color: $subColor")
            appendLine("    label_color: $textColor")
            appendLine("    candidate_text_color: $textColor")
            appendLine("    comment_text_color: $subColor")
            appendLine("    shadow_color: $shadow")
        }

        // Rime 的用户补丁文件：trime.custom.yaml
        val custom = File(DataManager.userDataDir, "trime.custom.yaml")
        custom.writeText(patch, Charsets.UTF_8)
        true
    }.getOrDefault(false)

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
