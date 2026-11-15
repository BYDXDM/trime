package com.osfans.trime.data.base

import timber.log.Timber
import java.io.File

/**
 * predict.db 的落盘替换。
 *
 * 联想词库是在设备上现编的：直接写目标文件的话，一旦构建中途失败，用户原有的
 * 词库就被截断成半成品，下次启动要么没有联想、要么读到坏数据。这里改成先写到
 * 同目录的临时文件，头部校验通过后再用 rename 原子替换 —— 失败时旧文件原样保留。
 */
internal object PredictDbSwap {
    /** 所有版本的 predict.db 都以这个前缀开头（后面跟 1.0 / 2.0）。 */
    const val FORMAT_PREFIX = "Rime::Predict/"

    /** metadata 结构体的字节数，比这更小的文件不可能有合法头部。 */
    private const val METADATA_MIN_SIZE = 52

    /** 头部是否是 predict.db 的格式标记。只做结构前置检查，不代替原生加载校验。 */
    fun looksLikePredictDb(file: File): Boolean {
        if (!file.isFile || file.length() < METADATA_MIN_SIZE) return false
        val header = ByteArray(FORMAT_PREFIX.length)
        try {
            file.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) return false
                    read += n
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read predict db header")
            return false
        }
        return String(header, Charsets.US_ASCII) == FORMAT_PREFIX
    }

    /**
     * 用 [built] 替换 [target]。rename 在同一个文件系统上是原子的，覆盖已有文件
     * 不会出现「旧文件已删、新文件没就位」的中间态。
     *
     * @return 是否替换成功；失败时 [target] 保持原样，[built] 被清理。
     */
    fun replace(target: File, built: File): Boolean {
        if (!looksLikePredictDb(built)) {
            Timber.w("Refusing to install predict db without valid header")
            built.delete()
            return false
        }
        if (built.renameTo(target)) return true
        // 有些文件系统（例如 Windows、部分外置存储）不允许 rename 覆盖已有文件。
        // 退化成「旧库改名备份 -> 新库就位 -> 删备份」，任何一步失败都把旧库改回来，
        // 保证不会出现「旧库已删、新库没就位」。
        val backup = File(target.parentFile, target.name + ".old")
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            Timber.w("Failed to set aside the old predict db: %s", target.absolutePath)
            built.delete()
            return false
        }
        if (!built.renameTo(target)) {
            Timber.w("Failed to move predict db into place: %s", target.absolutePath)
            backup.renameTo(target)
            built.delete()
            return false
        }
        backup.delete()
        return true
    }
}
