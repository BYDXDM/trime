// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.userdict

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.io.path.createTempDirectory

/**
 * F03 回归：词典导入/恢复/导出只能使用本次独占的临时文件，外部显示名不参与路径构造。
 */
class UserDictManagerScratchTest :
    StringSpec({
        "临时文件落在给定目录内，且与外部显示名无关" {
            val dir = createTempDirectory().toFile()
            try {
                listOf("../files/victim.txt", "../../victim.txt", "test.yaml", "a/b/c.txt", "..", "")
                    .forEach { hostile ->
                        val scratch = UserDictManager.newScratchFile(dir)
                        scratch.canonicalFile.parentFile shouldBe dir.canonicalFile
                        scratch.name shouldNotBe hostile
                        scratch.delete()
                    }
            } finally {
                dir.deleteRecursively()
            }
        }

        "连续两次调用得到不同的临时文件，不会互相覆盖" {
            val dir = createTempDirectory().toFile()
            try {
                val first = UserDictManager.newScratchFile(dir)
                val second = UserDictManager.newScratchFile(dir)
                first.canonicalPath shouldNotBe second.canonicalPath
                first.writeText("first")
                second.writeText("second")
                first.readText() shouldBe "first"
                second.readText() shouldBe "second"
            } finally {
                dir.deleteRecursively()
            }
        }

        "显示名带路径穿越时，cache 外的既有文件内容不变" {
            val root = createTempDirectory().toFile()
            val cache = File(root, "cache").apply { mkdirs() }
            val victim = File(root, "victim.txt").apply { writeText("ORIGINAL") }
            try {
                runCatching {
                    UserDictManager.importUserDict(
                        cache,
                        ByteArrayInputStream("词\tci\n".toByteArray()),
                        "demo",
                        "../victim.txt",
                    )
                }
                victim.readText() shouldBe "ORIGINAL"
                cache.listFiles().orEmpty().shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        "复制阶段抛异常时既有文件不变，临时文件被清理" {
            val root = createTempDirectory().toFile()
            val cache = File(root, "cache").apply { mkdirs() }
            val victim = File(root, "victim.txt").apply { writeText("ORIGINAL") }
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("boom")
                }
            try {
                val result = runCatching {
                    UserDictManager.restoreUserDict(cache, broken, "../victim.txt")
                }
                result.isFailure shouldBe true
                victim.readText() shouldBe "ORIGINAL"
                cache.listFiles().orEmpty().shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        "导出显示名带路径穿越时不在 cache 外产生文件" {
            val root = createTempDirectory().toFile()
            val cache = File(root, "cache").apply { mkdirs() }
            try {
                runCatching {
                    UserDictManager.exportUserDict(
                        cache,
                        java.io.ByteArrayOutputStream(),
                        "demo",
                        "../escaped.txt",
                    )
                }
                File(root, "escaped.txt").exists() shouldBe false
                cache.listFiles().orEmpty().shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }
    })
