// SPDX-FileCopyrightText: 2026 myime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.rime

import com.osfans.trime.data.base.DataManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 「打字习惯学习 / 联想猜想」的配置与数据契约回归。
 *
 * 需求：学用户的打字习惯，之后只打首字母就能联想到最常用的词，
 * 打到一半也能猜出来。实现分三层，本文件钉死其中最容易被改坏的连线：
 *
 *  1. **召回层** —— mydomain_abbrev 用 script_translator 做简拼/前缀匹配；
 *  2. **重排层** —— predictor 插件按当前输入把候选分「全拼 > 打一半 >
 *     只打首字母」三档排序（predict_db.cc 的 LookupRanked）；
 *  3. **学习层** —— enable_user_dict 打开，用户上屏的词写进
 *     mydomain.userdb，下次打首字母时因词频被提权。
 *
 * 这三层任何一层断开，用户看到的就是「打了首字母什么都没出来」。
 */
class PredictiveTypingTest :
    StringSpec({
        val schemaFile = File("src/main/assets/shared/mydomain.schema.yaml")
        val customFile = File("src/main/assets/shared/mydomain.custom.yaml")
        val predictData = File("src/main/assets/shared/predict.txt")

        "学习层：主翻译器开启用户词典，上屏的词会被记住并提权" {
            val text = schemaFile.readText()
            // 三个开关缺一不可：开学习、指定落盘库、词频型数据库
            text.contains("enable_user_dict: true") shouldBe true
            text.contains("user_dict: mydomain.user") shouldBe true
            text.contains("db_class: userdb") shouldBe true
        }

        "召回层：简拼翻译器必须是 script_translator，且开启补全（打一半就猜）" {
            val text = schemaFile.readText()
            // table_translator 只做逐字符精确匹配，首字母缩写永远命中不了
            text.contains("script_translator@mydomain_abbrev") shouldBe true
            text.contains("mydomain_abbrev:") shouldBe true
            val abbrevBlock = text.substringAfter("mydomain_abbrev:")
            abbrevBlock.contains("enable_completion: true") shouldBe true
            abbrevBlock.contains("enable_user_dict: true") shouldBe true
        }

        "重排层：predictor 与 predict_translator 都接进引擎，并带开关与词库路径" {
            val text = customFile.readText()
            text.contains("predictor") shouldBe true
            text.contains("predict_translator") shouldBe true
            // 联想必须能被关掉，否则用户不喜欢时只能改方案
            text.contains("name: prediction") shouldBe true
            text.contains("db: predict.db") shouldBe true
            // 上限与 menu/page_size(8) 对齐，联想候选不该把翻页挤掉
            text.contains("max_candidates: 8") shouldBe true
        }

        "重排层：词库训练数据里每条候选都带拼音编码（分档排序的前提）" {
            predictData.isFile shouldBe true
            val rows =
                predictData
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            (rows.isNotEmpty()) shouldBe true
            // 格式：上文词<TAB>候选词<TAB>权重<TAB>拼音编码
            val malformed = rows.filter { it.split("\t").size != 4 }
            malformed shouldBe emptyList()
            // 没有编码就没法做「打一半 / 只打首字母」重排
            val noCode = rows.filter { it.split("\t")[3].isBlank() }
            noCode shouldBe emptyList()
        }

        "重排层：首字母是编码的音节首字符，能被打首字母召回" {
            fun initials(code: String) = code.split(" ").filter { it.isNotBlank() }.map { it.first() }.joinToString("")

            val rows =
                predictData
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.split("\t") }
            // $ 表示句首起始词，必须有，否则空输入时联想区是空的
            val starters = rows.filter { it[0] == "$" }
            (starters.isNotEmpty()) shouldBe true
            // 抽查一条：原神(yuan shen) 的首字母必须是 ys
            val yuanshen = rows.firstOrNull { it[1] == "原神" }
            (yuanshen != null) shouldBe true
            initials(yuanshen!![3]) shouldBe "ys"
        }

        "联想词库随包发布，且部署时被镜像到用户目录（否则插件读不到）" {
            // 源数据（训练用）必须随包
            predictData.isFile shouldBe true
            // 构建产物 predict.db 必须随包。它由 Android 版 build_predict 在
            // 设备/模拟器上离线生成（宿主没有对应 C++ 工具链），生成脚本见
            // D:/trime-build/rebuild_predict_db.sh。
            val predictDb = File("src/main/assets/shared/predict.db")
            predictDb.isFile shouldBe true
            val head = predictDb.inputStream().use { it.readNBytes(32) }
            String(head, Charsets.US_ASCII).startsWith("Rime::Predict/2.0") shouldBe true
            // DataManager 负责把它从 shared 拷到 Rime 用户目录
            val dm = File("src/main/java/com/osfans/trime/data/base/DataManager.kt").readText()
            dm.contains("PREDICT_DB_NAME") shouldBe true
            dm.contains("PREDICT_TEXT_NAME") shouldBe true
            dm.contains("predict.db") shouldBe true
        }

        "默认方案列表仍以 mydomain 打头（联想只在主方案上生效）" {
            val patch = DataManager.defaultSchemaListPatch()
            val ids =
                patch
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith("- schema:") }
                    .map { it.removePrefix("- schema:").trim() }
            ids.first() shouldBe "mydomain"
        }
    })
