// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * F07 回归：SAF 的 isChildDocument 必须按路径层级判断后代关系，
 * 不能把同前缀兄弟目录或文档自己当成后代。
 */
class IsChildDocumentTest :
    StringSpec({
        "子目录属于父目录" {
            RimeDataProvider.isChildDocumentId("a", "a/b") shouldBe true
        }

        "同前缀兄弟路径不属于父目录" {
            RimeDataProvider.isChildDocumentId("a", "a-other") shouldBe false
            RimeDataProvider.isChildDocumentId("files/folder", "files/folder-sibling/file") shouldBe false
        }

        "自己不是自己的后代" {
            RimeDataProvider.isChildDocumentId("files/folder", "files/folder") shouldBe false
        }

        "根文档覆盖其下所有文档但不是自己" {
            RimeDataProvider.isChildDocumentId("files", "files/folder/file.yaml") shouldBe true
            RimeDataProvider.isChildDocumentId("files", "files") shouldBe false
            RimeDataProvider.isChildDocumentId("files", "filesx/a") shouldBe false
        }

        "深层嵌套属于祖先" {
            RimeDataProvider.isChildDocumentId("files/a", "files/a/b/c/d.yaml") shouldBe true
        }

        "尾部斜杠与反斜杠归一后仍按层级判断" {
            RimeDataProvider.isChildDocumentId("files/folder/", "files/folder/file.yaml") shouldBe true
            RimeDataProvider.isChildDocumentId("files/folder", "files/folder" + File.separator + "file.yaml") shouldBe true
        }

        "空文档 id 不算后代" {
            RimeDataProvider.isChildDocumentId("", "files/x") shouldBe false
            RimeDataProvider.isChildDocumentId("files", "") shouldBe false
        }

        "isChildDocument 走层级判断而非裸 startsWith" {
            val source = File("src/main/java/com/osfans/trime/provider/RimeDataProvider.kt").readText()
            source.contains("isChildDocumentId(parentDocumentId, documentId)") shouldBe true
            source.contains("documentId.startsWith(parentDocumentId)") shouldBe false
        }
    })
