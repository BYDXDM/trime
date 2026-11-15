// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.provider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory

/**
 * F08 回归：renameDocument/moveDocument 必须检查 renameTo 的结果，
 * 失败时报错并且不返回虚构的 docId；目标已存在时不得静默覆盖。
 */
class MoveDocumentTest :
    StringSpec({
        "改名成功时返回新文件并保留内容" {
            val dir = createTempDirectory().toFile()
            try {
                val source = File(dir, "old.yaml").also { it.writeText("cfg: 1") }
                val target = File(dir, "new.yaml")
                val moved = RimeDataProvider.moveOrFail(source, target) { "rename failed" }
                moved.canonicalFile shouldBe target.canonicalFile
                source.exists() shouldBe false
                target.readText() shouldBe "cfg: 1"
            } finally {
                dir.deleteRecursively()
            }
        }

        "源文件不存在时抛错，不再返回不存在的目标 docId" {
            val dir = createTempDirectory().toFile()
            try {
                val source = File(dir, "missing.yaml")
                val target = File(dir, "new.yaml")
                shouldThrow<FileNotFoundException> {
                    RimeDataProvider.moveOrFail(source, target) { "renameDocument failed" }
                }
                // 旧实现会返回 target.docId，而该文件并不存在
                target.exists() shouldBe false
            } finally {
                dir.deleteRecursively()
            }
        }

        "移动失败时源文件保留且目标不被覆盖" {
            val dir = createTempDirectory().toFile()
            try {
                val source = File(dir, "source.yaml").also { it.writeText("keep me") }
                val blocker = File(dir, "blocker").also { it.writeText("i am a file") }
                val target = File(blocker, "child.yaml")
                shouldThrow<FileNotFoundException> {
                    RimeDataProvider.moveOrFail(source, target) { "moveDocument failed" }
                }
                source.readText() shouldBe "keep me"
                blocker.readText() shouldBe "i am a file"
            } finally {
                dir.deleteRecursively()
            }
        }

        "目标已存在时拒绝覆盖并保留双方内容" {
            val dir = createTempDirectory().toFile()
            try {
                val source = File(dir, "source.yaml").also { it.writeText("source") }
                val target = File(dir, "target.yaml").also { it.writeText("target") }
                shouldThrow<FileNotFoundException> {
                    RimeDataProvider.moveOrFail(source, target) { "renameDocument failed" }
                }
                source.readText() shouldBe "source"
                target.readText() shouldBe "target"
            } finally {
                dir.deleteRecursively()
            }
        }

        "旧实现等价对照：忽略 renameTo 结果会报告不存在的文档" {
            val dir = createTempDirectory().toFile()
            try {
                val source = File(dir, "missing.yaml")
                val target = File(dir, "new.yaml")
                // 旧实现就是这一行：不检查返回值，直接当作已移动
                source.renameTo(target)
                target.exists() shouldBe false
            } finally {
                dir.deleteRecursively()
            }
        }

        "renameDocument/moveDocument 都走 moveOrFail" {
            val source = File("src/main/java/com/osfans/trime/provider/RimeDataProvider.kt").readText()
            source.contains("oldFile.renameTo(newFile)") shouldBe false
            Regex("moveOrFail\\(oldFile, newFile\\)").findAll(source).count() shouldBe 2
        }
    })
