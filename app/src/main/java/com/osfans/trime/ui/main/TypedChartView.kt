/*
 * SPDX-FileCopyrightText: 2026 myime
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.data.stats.TypedCharCounter
import java.util.Calendar

/**
 * 每日上屏字数的柱状图（Preference 里那张「近 14 天输入趋势」）。
 *
 * 主界面原本只有一行 Unicode 方块字符（▁▂▃▄▅▆▇█），信息量勉强；
 * 这个 View 用 Canvas 画真正的柱状图：柱高 = 当天字数占最大值的比例，
 * 柱顶标数值、柱底标星期，今天一列用主题色高亮。
 *
 * 全 0 也照画（一条贴地的柱 + 一排 0）——空数据由调用方决定要不要展示。
 */
class TypedChartView(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var counts: List<Int> = emptyList()
    private var dayLabels: List<String> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barTodayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = resources.displayMetrics.density * 10
            textAlign = Paint.Align.CENTER
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = resources.displayMetrics.density * 10
            textAlign = Paint.Align.CENTER
        }
    private val baselinePaint = Paint().apply { strokeWidth = resources.displayMetrics.density }
    private val barRect = RectF()

    /** [counts] 按时间升序（最后一项是今天）；[dayLabels] 与之等长（如 一二三四五六日）。 */
    fun setData(
        counts: List<Int>,
        dayLabels: List<String>,
    ) {
        require(counts.size == dayLabels.size) { "counts/dayLabels 长度不一致" }
        this.counts = counts
        this.dayLabels = dayLabels
        contentDescription = counts.joinToString(separator = "、")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (counts.isEmpty()) return

        val accent =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF6200EE.toInt())
        val onSurface =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        barPaint.color = accent
        barPaint.alpha = 120
        barTodayPaint.color = accent
        valuePaint.color = onSurface
        valuePaint.alpha = 170
        labelPaint.color = onSurface
        labelPaint.alpha = 170
        baselinePaint.color = onSurface
        baselinePaint.alpha = 60

        val gap = resources.displayMetrics.density * 6
        val slot = width / counts.size.toFloat()
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val labelH = labelPaint.textSize * 1.6f
        val valueH = valuePaint.textSize * 1.4f
        val chartH = (height - labelH - valueH).coerceAtLeast(1f)
        val max = counts.max().coerceAtLeast(1)
        val baselineY = height - labelH

        canvas.drawLine(0f, baselineY, width.toFloat(), baselineY, baselinePaint)

        counts.forEachIndexed { i, c ->
            val cx = slot * i + slot / 2f
            val h = (c.toFloat() / max * chartH).coerceAtLeast(resources.displayMetrics.density)
            barRect.set(cx - barWidth / 2f, baselineY - h, cx + barWidth / 2f, baselineY)
            canvas.drawRoundRect(barRect, barWidth / 4, barWidth / 4, if (i == counts.lastIndex) barTodayPaint else barPaint)
            canvas.drawText(c.toString(), cx, baselineY - h - valuePaint.textSize * 0.3f, valuePaint)
            dayLabels.getOrNull(i)?.let { canvas.drawText(it, cx, height - labelPaint.textSize * 0.2f, labelPaint) }
        }
    }
}

/**
 * 「近 14 天输入趋势」：整行自定义布局的只读 Preference。
 * 替代原来塞在 summary 里的 Unicode 迷你走势（▁▂▃▄▅▆▇█）。
 */
class TypedChartPreference(
    context: Context,
) : Preference(context) {
    init {
        layoutResource = R.layout.preference_typed_chart
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val days = 14
        val counts = TypedCharCounter.history(context, days)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val weekdays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val labels = List(days) {
            val label = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]
            cal.add(Calendar.DAY_OF_YEAR, 1)
            label
        }
        (holder.findViewById(R.id.typed_chart) as? TypedChartView)?.setData(counts, labels)
    }
}
