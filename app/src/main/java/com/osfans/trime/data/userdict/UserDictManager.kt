/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.userdict

import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object UserDictManager {
    private const val SCRATCH_PREFIX = "userdict-scratch-"
    private const val SCRATCH_SUFFIX = ".tmp"

    /**
     * 为一次导入/恢复/导出新建独占临时文件。
     *
     * 外部来源提供的显示名只用于展示与错误信息，绝不参与路径构造：显示名来自任意
     * ContentProvider，含 `../` 时会逃出 cache 目录，先截断再在 finally 删除用户既有文件。
     */
    @JvmStatic
    internal fun newScratchFile(scratchDir: File): File = File.createTempFile(SCRATCH_PREFIX, SCRATCH_SUFFIX, scratchDir)

    private fun deleteScratch(file: File) {
        if (file.exists() && !file.delete()) {
            Timber.w("failed to delete scratch file %s", file.name)
        }
    }

    fun restoreUserDict(stream: InputStream, displayName: String): Result<Unit> = restoreUserDict(appContext.cacheDir, stream, displayName)

    @JvmStatic
    internal fun restoreUserDict(
        scratchDir: File,
        stream: InputStream,
        displayName: String,
    ): Result<Unit> {
        val scratch = newScratchFile(scratchDir)
        try {
            scratch.outputStream().use {
                stream.copyTo(it)
            }
            val success = restoreUserDict(scratch.absolutePath)
            return if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to restore from '$displayName'"))
            }
        } finally {
            deleteScratch(scratch)
        }
    }

    fun importUserDict(stream: InputStream, dictName: String, displayName: String): Result<Int> = importUserDict(appContext.cacheDir, stream, dictName, displayName)

    @JvmStatic
    internal fun importUserDict(
        scratchDir: File,
        stream: InputStream,
        dictName: String,
        displayName: String,
    ): Result<Int> {
        val scratch = newScratchFile(scratchDir)
        try {
            scratch.outputStream().use {
                stream.copyTo(it)
            }
            val count = importUserDict(dictName, scratch.absolutePath)
            return if (count >= 0) {
                Result.success(count)
            } else {
                Result.failure(
                    Exception("Failed to import from '$displayName' to '$dictName'"),
                )
            }
        } finally {
            deleteScratch(scratch)
        }
    }

    fun exportUserDict(dest: OutputStream, dictName: String, displayName: String): Result<Int> = exportUserDict(appContext.cacheDir, dest, dictName, displayName)

    @JvmStatic
    internal fun exportUserDict(
        scratchDir: File,
        dest: OutputStream,
        dictName: String,
        displayName: String,
    ): Result<Int> {
        val scratch = newScratchFile(scratchDir)
        try {
            val count = exportUserDict(dictName, scratch.absolutePath)
            if (count >= 0 && scratch.exists()) {
                scratch.inputStream().use {
                    it.copyTo(dest)
                }
                return Result.success(count)
            } else {
                return Result.failure(
                    Exception("Failed to export '$dictName' to '$displayName'"),
                )
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            deleteScratch(scratch)
        }
    }

    @JvmStatic
    external fun getUserDictList(): Array<String>

    @JvmStatic
    external fun backupUserDict(dictName: String): Boolean

    @JvmStatic
    external fun restoreUserDict(snapshotFile: String): Boolean

    @JvmStatic
    external fun exportUserDict(dictName: String, textFile: String): Int

    @JvmStatic
    external fun importUserDict(dictName: String, textFile: String): Int
}
