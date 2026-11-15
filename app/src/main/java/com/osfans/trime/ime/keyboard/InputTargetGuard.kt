/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/**
 * 延迟动作（如删除键上滑「清空」）是否仍落在发起时那个输入目标上。
 *
 * 判断依据两条，缺一不可：
 * - **输入会话代际**：编辑框切换、输入结束都会让服务侧代际自增；
 * - **InputConnection 同一性**：代际未变但连接对象被换掉时同样放弃。
 *
 * 任一不符就说明焦点已经跑到别处，此时继续按 [android.view.inputmethod.InputConnection]
 * 删除会删掉另一个编辑框里的内容，所以宁可放弃这次清空。
 */
internal fun isSameInputTarget(
    requestedSession: Long,
    currentSession: Long,
    requestedConnection: Any?,
    currentConnection: Any?,
): Boolean = requestedSession == currentSession && requestedConnection === currentConnection
