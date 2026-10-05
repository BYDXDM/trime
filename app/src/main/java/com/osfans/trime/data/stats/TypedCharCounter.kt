/*
 * SPDX-FileCopyrightText: 2026 myime
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.stats

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日上屏字数统计。
 *
 * 只统计「实际上屏的字符」，不含空白，所以中文按字、英文按字母计。
 * 用 SharedPreferences 存 `day_yyyyMMdd -> 计数`，顺手清掉 30 天前的键，
 * 避免无限增长。
 *
 * 统计口径是**字符数**而不是词数：用户要的是「今天打了多少字」，
 * 一个中文词两个字就该记 2。
 */
object TypedCharCounter {
    private const val PREF_NAME = "typed_chars"
    private const val KEEP_DAYS = 30

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    private fun keyOf(date: Date) = "day_" + dayFormat.format(date)

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 记一次上屏。空白字符不计。 */
    fun record(context: Context, text: String) {
        val count = text.count { !it.isWhitespace() }
        if (count <= 0) return
        val prefs = prefs(context)
        val key = keyOf(Date())
        prefs.edit { putInt(key, prefs.getInt(key, 0) + count) }
        prune(context, prefs)
    }

    /** 今天已输入的字数。 */
    fun todayCount(context: Context): Int = prefs(context).getInt(keyOf(Date()), 0)

    /**
     * 清掉超过 KEEP_DAYS 的旧键。每次写入时顺手做，代价很低。
     * 用「键的日期字符串 < 截止日字符串」比较即可 —— yyyyMMdd 定长，字典序即时间序。
     */
    private fun prune(context: Context, prefs: android.content.SharedPreferences) {
        val cutoff = dayFormat.format(Date(System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L))
        val stale = prefs.all.keys.filter { it.startsWith("day_") && it.removePrefix("day_") < cutoff }
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }
}
