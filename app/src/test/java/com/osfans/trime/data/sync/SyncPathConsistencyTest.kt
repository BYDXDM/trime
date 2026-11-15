// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class SyncPathConsistencyTest :
    StringSpec({
        "normalize keeps leading and trailing spaces in file names" {
            SyncRelativePath.normalize(" test.yaml") shouldBe " test.yaml"
            SyncRelativePath.normalize("test.yaml ") shouldBe "test.yaml "
            SyncRelativePath.normalize("dir/ name .yaml ") shouldBe "dir/ name .yaml "
        }
        "normalize still rejects blank paths and dot segments" {
            shouldThrow<SyncRelativePath.PathEscapeException> { SyncRelativePath.normalize("   ") }
            shouldThrow<SyncRelativePath.PathEscapeException> { SyncRelativePath.normalize("a/./b.yaml") }
            shouldThrow<SyncRelativePath.PathEscapeException> { SyncRelativePath.normalize("a//b.yaml") }
        }
        "normalize maps equivalent spellings to one local target" {
            SyncRelativePath.normalize("/test.yaml") shouldBe "test.yaml"
            SyncRelativePath.normalize("./test.yaml") shouldBe "test.yaml"
            SyncRelativePath.normalize("dir\\test.yaml") shouldBe "dir/test.yaml"
        }
        "orphan cleanup keeps the file whose name has a leading space" {
            val root = createTempDirectory().toFile()
            try {
                File(root, " test.yaml").writeText("spaced")
                File(root, "test.yaml").writeText("plain")
                val result =
                    OrphanCleaner.removeLocalOrphans(
                        root,
                        externalPaths = setOf(" test.yaml"),
                    )
                File(root, " test.yaml").exists() shouldBe true
                File(root, " test.yaml").readText() shouldBe "spaced"
                File(root, "test.yaml").exists() shouldBe false
                result.deleted shouldBe 1
            } finally {
                root.deleteRecursively()
            }
        }
        "collision mapping keeps one local target and reports the duplicate" {
            val mapping = SyncPathMap.map(listOf("/test.yaml", "test.yaml", "dir/a.yaml"))
            mapping.targets["test.yaml"] shouldBe "test.yaml"
            mapping.targets["/test.yaml"] shouldBe null
            mapping.duplicates shouldBe listOf("/test.yaml")
            mapping.targets["dir/a.yaml"] shouldBe "dir/a.yaml"
        }
        "cleanup keeps local files whose copy failed" {
            val root = createTempDirectory().toFile()
            try {
                val failed = File(root, "luna.userdb.txt")
                failed.writeText("keep me")
                File(root, "orphan.yaml").writeText("orphan")
                val result =
                    OrphanCleaner.removeLocalOrphans(
                        root,
                        externalPaths = emptySet(),
                        protectPaths = setOf("luna.userdb.txt"),
                    )
                failed.exists() shouldBe true
                File(root, "orphan.yaml").exists() shouldBe false
                result.deleted shouldBe 1
            } finally {
                root.deleteRecursively()
            }
        }
    })
