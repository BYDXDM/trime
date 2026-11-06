/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.matchesAny
import com.osfans.trime.util.removeRegexSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.systemservices.clipboardManager
import timber.log.Timber

object ClipboardHelper :
    ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: Database
    private lateinit var clbDao: DatabaseDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(bean: DatabaseBean)
    }

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val clipPref = AppPrefs.defaultInstance().clipboard

    private val enabledPref = clipPref.clipboardListening

    @Keep
    private val enabledListener = PreferenceDelegate.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = clipPref.clipboardLimit

    @Keep
    private val limitListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    private val compareRules: Set<Regex> by lazy {
        val rules by clipPref.clipboardCompareRules
        rules
            .split('\n')
            .map { Regex(it.trim()) }
            .toSet()
    }

    private val outputRules: Set<Regex> by lazy {
        val rules by clipPref.clipboardOutputRules
        rules
            .split('\n')
            .map { Regex(it) }
            .toSet()
    }

    var lastBean: DatabaseBean? = null

    private fun updateLastBean(bean: DatabaseBean) {
        lastBean = bean
        onUpdateListeners.forEach { it.onUpdate(bean) }
    }

    fun init(context: Context) {
        clbDb =
            Room
                .databaseBuilder(context, Database::class.java, "clipboard.db")
                .addMigrations(Database.MIGRATION_3_4)
                .build()
        clbDao = clbDb.databaseDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allBeans() = clbDao.allBeans()

    suspend fun pin(id: Int) = clbDao.updatePinned(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinned(id, false)

    suspend fun updateText(
        id: Int,
        text: String,
    ) {
        lastBean?.let {
            if (id == it.id) updateLastBean(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.delete(id)
        updateItemCount()
    }

    suspend fun deleteAll(skipUnpinned: Boolean = true) {
        if (skipUnpinned) {
            clbDao.deleteAllUnpinned()
        } else {
            clbDao.deleteAll()
        }
        updateItemCount()
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    /**
     * 此方法设置监听剪贴板变化，如有新的剪贴内容，就启动选定的剪贴板管理器
     *
     * - [compareRules] 比较规则。每次通知剪贴板管理器，都会保存 ClipBoardCompare 处理过的 string。
     * 如果两次处理过的内容不变，则不通知。
     *
     * - [outputRules] 输出规则。如果剪贴板内容与规则匹配，则不通知剪贴板管理器。
     */
    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        val timestamp = clip.description.timestamp
        val hash = clip.hashCode()
        // 部分应用（微信、浏览器等）连续复制不同内容时 timestamp 不变，
        // 只看时间戳会把后续内容全当重复丢弃；时间戳与内容都相同才算重复
        if (timestamp == lastClipTimestamp && hash == lastClipHash) return
        lastClipTimestamp = timestamp
        lastClipHash = hash
        ingest(clip)
    }

    /** 键盘这一次弹出期间已经处理过的剪贴板内容，避免切换输入框时反复弹提示 */
    private var lastSyncedText: String? = null

    private fun currentClipText(): String? = clipboardManager.primaryClip?.let { DatabaseBean.fromClipData(it)?.text }

    /** 键盘收起时把当时的剪贴板内容记为「用户已见过」；读不到就不覆盖，避免下次误判为「有新内容」 */
    fun onKeyboardHidden() {
        currentClipText()?.let { lastSyncedText = it }
    }

    /**
     * 键盘每次显示时补读一次系统剪贴板。
     *
     * [onPrimaryClipChanged] 只在「复制的那一刻」有回调，而常见路径是先在别的应用里复制、
     * 再切回来打开键盘：那时建议条早已按默认 20s 超时消失；若复制时输入法进程还没起来，
     * 监听器压根不存在，回调直接丢掉。此刻本输入法已获得焦点，读剪贴板一定合法，补录一次即可。
     *
     * 与「收起键盘时的内容」一致就不做任何事，因此不会每次弹键盘都重复提示。
     */
    fun syncOnKeyboardShown() {
        if (!enabledPref.getValue()) {
            Timber.d("syncOnKeyboardShown: clipboard listening disabled")
            return
        }
        val clip = clipboardManager.primaryClip
        if (clip == null) {
            Timber.d("syncOnKeyboardShown: primaryClip is null (system denied clipboard read)")
            return
        }
        val text = DatabaseBean.fromClipData(clip)?.text
        if (text == null) {
            Timber.d("syncOnKeyboardShown: clip has no text")
            return
        }
        if (text == lastSyncedText) {
            Timber.d("syncOnKeyboardShown: same as last synced, skip")
            return
        }
        lastSyncedText = text
        Timber.d("syncOnKeyboardShown: ingest %s", text.take(48))
        ingest(clip)
    }

    private fun ingest(clip: ClipData) {
        launch {
            mutex.withLock {
                val bean = DatabaseBean.fromClipData(clip)
                if (bean == null) {
                    Timber.d("ingest: clip produced no bean")
                    return@withLock
                }
                if (bean.text.isNullOrBlank()) return@withLock
                if (bean.text.matchesAny(outputRules) ||
                    bean.text.removeRegexSet(compareRules).isEmpty()
                ) {
                    Timber.d("ingest: filtered by output/compare rules: %s", bean.text.take(48))
                    return@withLock
                }
                try {
                    clbDao.find(bean.text)?.let {
                        updateLastBean(it.copy(time = bean.time))
                        clbDao.updateTime(it.id, bean.time)
                        return@withLock
                    }
                    val insertedBean =
                        clbDb.withTransaction {
                            val rowId = clbDao.insert(bean)
                            removeOutdated()
                            updateItemCount()
                            clbDao.get(rowId) ?: bean
                        }
                    updateLastBean(insertedBean)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastBean(bean)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            val outdated =
                unpinned
                    .sortedBy { it.id }
                    .getOrNull(unpinned.size - limit)
            clbDao.deletedUnpinnedEarlierThan(outdated?.time ?: System.currentTimeMillis())
        }
    }
}
