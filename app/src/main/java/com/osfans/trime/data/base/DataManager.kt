// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

import android.content.res.AssetManager
import android.os.Build
import com.osfans.trime.core.Rime
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Resolve [name] under [parent].
 *
 * Returns null when [parent] is null, cannot be created, or the resulting directory
 * is not writable. Callers must not cache a failed result permanently; retry when
 * storage may have become available (for example after reboot).
 */
internal fun resolveWritableChildDir(
    parent: File?,
    name: String,
): File? {
    if (parent == null) return null
    if (!parent.exists() && !parent.mkdirs()) return null
    if (!parent.canWrite()) return null
    val dir = File(parent, name)
    if (!dir.exists() && !dir.mkdirs()) return null
    return dir.takeIf { it.canWrite() }
}

object DataManager {
    const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"
    const val USER_CONFIG_FILE_NAME = "user.yaml"
    const val INSTALLATION_FILE_NAME = "installation.yaml"

    val POST_SCHEMA_DEPLOY_EXPORT_FILES =
        listOf(
            DEFAULT_CUSTOM_FILE_NAME,
            USER_CONFIG_FILE_NAME,
        )

    private const val DATA_CHECKSUMS_NAME = "checksums.json"
    private const val SHARED_DIR_NAME = "shared"
    private const val USER_DIR_NAME = "rime"

    /** shared 下随包发布、需镜像到 Rime 用户目录的资源目录（见 [mirrorPackagedDirs]）。 */
    private val PACKAGED_MIRROR_DIRS = arrayOf("backgrounds", "soundeffect")

    /** 联想训练数据（随包发布，设备上编译成 [PREDICT_DB_NAME]）。 */
    private const val PREDICT_TEXT_NAME = "predict.txt"

    /** 联想词库（predictor 插件从 Rime 用户目录读这个文件）。 */
    private const val PREDICT_DB_NAME = "predict.db"

    /** 记录 predict.db 是用哪版 predict.txt 编的，避免每次启动重编。 */
    private const val PREDICT_DB_STAMP_NAME = "predict.db.version"

    private const val LEGACY_SCHEMA_LIST_CUSTOM_PATCH = """
      patch:
        schema_list:
          - schema: luna_pinyin
          - schema: luna_pinyin_simp
    """

    private const val SCHEMA_LIST_CUSTOM_PATCH = """
      patch:
        schema_list:
          - schema: mydomain
          - schema: luna_pinyin_simp
          - schema: luna_pinyin
    """

    /** 测试可见：首次干净初始化写入用户目录的默认方案列表补丁内容。 */
    internal fun defaultSchemaListPatch(): String = SCHEMA_LIST_CUSTOM_PATCH.trimIndent()

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }
    }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    @Volatile
    private var cachedSharedDataDir: File? = null

    @Volatile
    private var cachedUserDataDir: File? = null

    private fun resolveAppScopedDir(
        cached: File?,
        name: String,
        store: (File) -> Unit,
    ): File? {
        cached?.takeIf { it.canWrite() }?.let { return it }
        val resolved =
            resolveWritableChildDir(appContext.getExternalFilesDir(null), name) ?: return null
        store(resolved)
        return resolved
    }

    /** Writable shared assets dir, or null when external app files are not ready yet. */
    fun resolvedSharedDataDir(): File? = resolveAppScopedDir(cachedSharedDataDir, SHARED_DIR_NAME) { cachedSharedDataDir = it }

    /** Writable Rime user dir, or null when external app files are not ready yet. */
    fun resolvedUserDataDir(): File? = resolveAppScopedDir(cachedUserDataDir, USER_DIR_NAME) { cachedUserDataDir = it }

    val sharedDataDir: File
        get() =
            resolvedSharedDataDir()
                ?: error("Shared data dir is not available")

    /** App-scoped path used by Rime at runtime. */
    val userDataDir: File
        get() =
            resolvedUserDataDir()
                ?: error("User data dir is not available")

    val prebuiltDataDir: File
        get() = File(sharedDataDir, "build")
    val stagingDir get() = File(userDataDir, "build")

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                    ResourceUtils.copyFile(it.path, destPath)
                }

                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath)

        val custom = userDataDir.resolve(DEFAULT_CUSTOM_FILE_NAME)
        val defaultPatch = SCHEMA_LIST_CUSTOM_PATCH.trimIndent()
        val defaultPatchChanged =
            when {
                !custom.exists() -> {
                    if (custom.createNewFile()) {
                        custom.writeText(defaultPatch)
                        true
                    } else {
                        false
                    }
                }

                // 旧版本生成的默认补丁不含 mydomain（BA/崩铁/科学上网词库）：
                // 内容与旧默认逐字一致（即用户从未改过）时迁移到新默认；改过的不动。
                custom.readText() == LEGACY_SCHEMA_LIST_CUSTOM_PATCH.trimIndent() -> {
                    custom.writeText(defaultPatch)
                    true
                }

                else -> false
            }

        mirrorPackagedDirs()

        if (defaultPatchChanged) {
            // 方案列表变化后，普通启动（fullCheck=false）不会重编译既有缓存；
            // 升级安装时外置 rime 目录往往整体残留，旧 build 里没有新默认方案
            // （如 mydomain），会话只能落到无方案态。清掉 staging 强制下次
            // 启动全量重部署（一次性的分钟级开销），用户词典不受影响。
            FileUtils.delete(stagingDir)
        }

        Timber.d("Synced!")
    }

    /**
     * 把 shared 下随包发布的资源目录镜像到 Rime 用户目录
     * （背景图：ColorManager 从 <userDataDir>/backgrounds/<folder>/ 读图；
     * 按键音效：SoundEffectManager 从 <userDataDir>/soundeffect/ 读包）。
     * copy-if-missing / 尺寸变化才覆盖，用户自己放的文件不受影响。
     */
    private fun mirrorPackagedDirs() {
        for (name in PACKAGED_MIRROR_DIRS) {
            val src = File(sharedDataDir, name)
            val dst = File(userDataDir, name)
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(src)
                val target = File(dst, rel.path)
                if (!target.exists() || target.length() != file.length()) {
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                }
            }
        }
        buildPredictDb()
    }

    /**
     * 由随包的 predict.txt 在设备上编译联想词库 predict.db。
     *
     * predict.db 是 marisa-trie + Darts 的二进制，没法在仓库里手工维护，
     * 也不能靠宿主工具生成（CI 与开发机都未必有 C++ 环境）。这里改在设备上
     * 编译：predict 插件的代码本就静态链进 librime_jni，直接调用即可。
     *
     * 只在 predict.txt 变化时重编（用长度当版本号，够用且零成本）；
     * 失败不影响启动，只是联想功能不生效。
     */
    private fun buildPredictDb() {
        val src = File(sharedDataDir, PREDICT_TEXT_NAME)
        if (!src.isFile) return
        val target = File(userDataDir, PREDICT_DB_NAME)
        val stamp = File(userDataDir, PREDICT_DB_STAMP_NAME)
        val version = "${src.length()}"
        if (target.isFile && stamp.isFile && stamp.readText() == version) return

        runCatching {
            val ok = Rime.buildPredictDb(src.readText(), target.absolutePath)
            if (ok) {
                stamp.writeText(version)
                Timber.i("Predict db built: %d bytes", target.length())
            } else {
                Timber.w("Predict db build returned false")
            }
        }.onFailure {
            Timber.w(it, "Failed to build predict db")
        }
    }
}
