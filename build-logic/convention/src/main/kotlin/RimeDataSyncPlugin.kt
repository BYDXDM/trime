// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.security.MessageDigest

/**
 * Mirror the Rime source data from `data/rime/` into `src/main/assets/shared/`.
 *
 * ## Why this exists
 *
 * The Rime schemas/dictionaries are authored under `data/rime/<sub>/`, but the
 * APK ships whatever sits in `src/main/assets/shared/`. These are two physically
 * separate copies and nothing reconciled them, so editing
 * `data/rime/myvocab/mydomain.schema.yaml` and rebuilding silently packaged the
 * OLD schema. That has already cost at least one long "I fixed it but nothing
 * changed" debugging session.
 *
 * Upstream relies on symlinks (`assets/shared/X` pointing into `data/rime`).
 * That is fragile here: symlinks are stored as mode 120000, and on Windows
 * without Developer Mode git checks them out as tiny text files containing the
 * link *path* instead of the content. librime then parses that path as YAML,
 * finds no schema, and the keyboard produces zero candidates.
 *
 * So: copy, and compare by SHA-256. Unchanged files are skipped, so incremental
 * builds stay cheap and the task is safe to run on every build.
 *
 * ## Mapping
 *
 * `assets/shared/` is FLAT - the sub-module directory names are dropped:
 *
 *   data/rime/myvocab      -> assets/shared
 *   data/rime/prelude      -> assets/shared
 *   data/rime/luna-pinyin  -> assets/shared
 *   data/rime/essay        -> assets/shared
 *   data/rime/stroke       -> assets/shared
 *
 * Files that exist only under `assets/shared/` (trime.yaml, panels.yaml,
 * tongwenfeng.trime.yaml) have no counterpart in `data/rime` and are left
 * untouched - the task never deletes a file it has no source for.
 */
class RimeDataSyncPlugin : Plugin<Project> {
    companion object {
        const val TASK = "syncRimeSharedData"

        /** data/rime subdirectory names that get mirrored flat into assets/shared. */
        val MIRRORED_DIRS = listOf("myvocab", "prelude", "luna-pinyin", "essay", "stroke")

        /** Non-data files that live in the submodule trees and must not be mirrored. */
        val IGNORED = setOf(".git", ".gitignore", "AUTHORS", "LICENSE", "README.md", "Makefile", "check.py")

        /**
         * Files that live under a mirrored dir but are build-tool inputs rather
         * than Rime runtime data, so they must NOT be shipped as assets.
         * `keywords.tsv` is consumed by tools/gen_keywords.py to regenerate
         * mydomain.dict.yaml.
         */
        val NOT_ASSETS = setOf("keywords.tsv")
    }

    override fun apply(target: Project) {
        val syncTask =
            target.tasks.register<RimeDataSyncTask>(TASK) {
                sourceDir.set(target.layout.projectDirectory.dir("data/rime"))
                assetDir.set(target.layout.projectDirectory.dir("src/main/assets/shared"))
                mirroredDirs.set(MIRRORED_DIRS)
                ignoredNames.set(IGNORED + NOT_ASSETS)
                // Never skip: the source tree contains git submodule metadata that
                // makes Gradle up-to-date checks unreliable, and the whole point is
                // to guarantee the assets match. The hash comparison inside keeps
                // it cheap.
                outputs.upToDateWhen { false }
            }

        // The manifest records which files this task mirrored, so it can prune
        // stale ones later. It must live OUTSIDE assets/ or it would be packaged
        // into the APK.
        syncTask.configure {
            manifestFile.set(target.layout.buildDirectory.file("rime-data-sync/mirrored.txt"))
        }

        // Every task that packages or generates assets must see synced data.
        target.tasks
            .matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
            .configureEach { dependsOn(syncTask) }
        target.tasks
            .matching { it.name.startsWith("generate") && it.name.endsWith("Assets") }
            .configureEach { dependsOn(syncTask) }
        // generateDataChecksums reads assets/ as an INPUT, so it must run after
        // the sync, otherwise the recorded checksums describe pre-sync content.
        target.tasks
            .matching { it.name == "generateDataChecksums" }
            .configureEach { dependsOn(syncTask) }
    }

    abstract class RimeDataSyncTask : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourceDir: DirectoryProperty

        @get:OutputDirectory
        abstract val assetDir: DirectoryProperty

        @get:org.gradle.api.tasks.Input
        abstract val mirroredDirs: org.gradle.api.provider.ListProperty<String>

        @get:org.gradle.api.tasks.Input
        abstract val ignoredNames: org.gradle.api.provider.SetProperty<String>

        @get:org.gradle.api.tasks.OutputFile
        abstract val manifestFile: org.gradle.api.file.RegularFileProperty

        @TaskAction
        fun execute() {
            val src = sourceDir.get().asFile
            val dst = assetDir.get().asFile
            if (!src.isDirectory) {
                logger.lifecycle("RimeDataSync: source '$src' missing, skipping")
                return
            }
            dst.mkdirs()

            val ignored = ignoredNames.get()
            var copied = 0
            var skipped = 0
            var pruned = 0

            // Track what we own so we can prune only our own files.
            val owned = mutableSetOf<String>()

            mirroredDirs.get().forEach { sub ->
                val subDir = src.resolve(sub)
                if (!subDir.isDirectory) {
                    logger.info("RimeDataSync: '$sub' not present, skipping")
                    return@forEach
                }
                subDir.listFiles { f -> f.isFile && f.name !in ignored }?.forEach { file ->
                    val rel = file.name
                    owned += rel
                    val target = dst.resolve(rel)
                    if (target.isFile && sha256(target) == sha256(file)) {
                        skipped++
                    } else {
                        file.copyTo(target, overwrite = true)
                        copied++
                        logger.info("RimeDataSync: update $rel (from $sub)")
                    }
                }
            }

            // Prune stale mirrors: a file we copied from a mirrored dir, whose
            // source has since disappeared. Files that only ever lived in
            // assets/shared (trime.yaml, panels.yaml, ...) are never in `owned`
            // and therefore never touched.
            val previouslyMirrored = manifestFile.get().asFile
            previouslyMirrored.parentFile?.mkdirs()
            val known = previouslyMirrored.takeIf { it.isFile }?.readLines()?.toSet().orEmpty()
            (known - owned).forEach { stale ->
                val target = dst.resolve(stale)
                if (target.isFile) {
                    target.delete()
                    pruned++
                    logger.lifecycle("RimeDataSync: pruned stale mirror $stale")
                }
            }
            previouslyMirrored.writeText(owned.sorted().joinToString("\n"))

            logger.lifecycle(
                "RimeDataSync: $copied updated, $skipped unchanged, $pruned pruned " +
                    "(${owned.size} mirrored files)",
            )
        }

        private fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").run {
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        update(buf, 0, n)
                    }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
    }
}
