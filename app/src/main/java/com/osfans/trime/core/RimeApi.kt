/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.flow.SharedFlow

interface RimeApi {
    val messageFlow: SharedFlow<RimeMessage<*>>

    val isReady: Boolean

    val schemaCached: RimeSchema

    val statusCached: StatusProto

    val compositionCached: CompositionProto

    val hasMenu: Boolean

    val paging: Boolean

    suspend fun isEmpty(): Boolean

    suspend fun deploy(skipImport: Boolean = false)

    suspend fun updateConfig()

    suspend fun syncUserData(): Boolean

    suspend fun processKey(
        value: Int,
        modifiers: UInt = 0u,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun simulateKeySequence(
        sequence: String,
    ): Boolean

    suspend fun selectCandidate(idx: Int, global: Boolean): Boolean

    suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean

    suspend fun changeCandidatePage(backward: Boolean): Boolean

    suspend fun moveCursorPos(position: Int)

    suspend fun availableSchemata(): Array<SchemaItem>

    suspend fun enabledSchemata(): Array<SchemaItem>

    suspend fun setEnabledSchemata(schemaIds: Array<String>): Boolean

    suspend fun selectedSchemata(): Array<SchemaItem>

    suspend fun selectedSchemaId(): String

    suspend fun selectSchema(schemaId: String): Boolean

    suspend fun currentSchema(): RimeSchema

    suspend fun commitComposition(): Boolean

    suspend fun clearComposition()

    /**
     * 丢弃当前组合段（含预测插件造的 rawInput 为空的「联想」伪组合段）。
     *
     * 与 [clearComposition] 的区别：这里会先给 Rime 发一个 Escape 让预测插件收手，
     * 但那条 Escape **不会**被转发成客户端按键事件 —— 转发会让把 ESC 当「退出」的应用
     * 自己 finish（华为文件管理一获得输入焦点就弹回，见 HANDOFF-20261122.md）。
     */
    suspend fun discardComposition()

    suspend fun getRawInput(): String

    suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    )

    suspend fun getRuntimeOption(option: String): Boolean

    suspend fun setNullInputType(value: Boolean)

    suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto>

    /**
     * Read candidates within one Rime dispatcher task. Neither block nor fetch may suspend.
     * The fetch function is valid only during block; do not retain it or use it on another thread.
     * This does not retain a snapshot across separate calls.
     */
    suspend fun <T> readCandidatesAtomically(
        block: (fetch: (startIndex: Int, limit: Int) -> Array<CandidateProto>) -> T,
    ): T

    suspend fun setCandidatePagingMode(enabled: Boolean)
}
