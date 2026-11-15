package com.osfans.trime.data.base

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

class PredictDbSwapTest :
    BehaviorSpec({
        fun tempDir(): File = Files.createTempDirectory("predict-db-swap").toFile()

        fun dbFile(dir: File, name: String, header: String = "Rime::Predict/2.0", size: Int = 128): File {
            val bytes = ByteArray(size)
            val head = header.toByteArray(Charsets.US_ASCII)
            head.copyInto(bytes, 0, 0, minOf(head.size, size))
            return File(dir, name).apply { writeBytes(bytes) }
        }

        Given("predict.db 头部校验") {
            When("文件带合法格式标记且长度足够") {
                Then("认为是 predict.db") {
                    val dir = tempDir()
                    PredictDbSwap.looksLikePredictDb(dbFile(dir, "ok.db")) shouldBe true
                }
            }
            When("文件短于 metadata 结构体") {
                Then("拒绝") {
                    val dir = tempDir()
                    PredictDbSwap.looksLikePredictDb(dbFile(dir, "short.db", size = 20)) shouldBe false
                }
            }
            When("头部前缀不是格式标记") {
                Then("拒绝") {
                    val dir = tempDir()
                    PredictDbSwap.looksLikePredictDb(
                        dbFile(dir, "bad.db", header = "Rime::Prsdict/2.0"),
                    ) shouldBe false
                }
            }
            When("文件不存在") {
                Then("拒绝") {
                    val dir = tempDir()
                    PredictDbSwap.looksLikePredictDb(File(dir, "missing.db")) shouldBe false
                }
            }
        }

        Given("用新构建的词库替换旧词库") {
            When("新库头部合法") {
                Then("目标内容被替换，临时文件不再存在") {
                    val dir = tempDir()
                    val target = dbFile(dir, "predict.db", header = "Rime::Predict/1.0")
                    val built = dbFile(dir, "predict.db.building.tmp", size = 256)
                    built.writeBytes(
                        built.readBytes().also { bytes -> for (i in 52 until bytes.size) bytes[i] = 7 },
                    )

                    PredictDbSwap.replace(target, built) shouldBe true

                    val replaced = target.readBytes()
                    replaced.size shouldBe 256
                    String(replaced.copyOfRange(0, 17), Charsets.US_ASCII) shouldBe "Rime::Predict/2.0"
                    replaced.copyOfRange(52, 256).all { it == 7.toByte() } shouldBe true
                    built.exists() shouldBe false
                }
            }
            When("新库头部非法") {
                Then("旧库内容保持原样，临时文件被清理") {
                    val dir = tempDir()
                    val target = dbFile(dir, "predict.db", header = "Rime::Predict/2.0")
                    val before = target.readBytes()
                    val built = File(dir, "predict.db.building.tmp").apply { writeBytes(ByteArray(128)) }

                    PredictDbSwap.replace(target, built) shouldBe false

                    target.readBytes() shouldBe before
                    built.exists() shouldBe false
                }
            }
            When("新库过短（构建被中断留下的半成品）") {
                Then("旧库内容保持原样") {
                    val dir = tempDir()
                    val target = dbFile(dir, "predict.db")
                    val before = target.readBytes()
                    val built = dbFile(dir, "predict.db.building.tmp", size = 8)

                    PredictDbSwap.replace(target, built) shouldBe false

                    target.readBytes() shouldBe before
                }
            }
            When("目标目录不存在（无法落盘）") {
                Then("返回失败且临时文件被清理") {
                    val dir = tempDir()
                    val target = File(File(dir, "missing"), "predict.db")
                    val built = dbFile(dir, "predict.db.building.tmp")

                    PredictDbSwap.replace(target, built) shouldBe false

                    target.exists() shouldBe false
                    built.exists() shouldBe false
                }
            }
            When("旧库存在且替换成功") {
                Then("不残留 .old 备份") {
                    val dir = tempDir()
                    val target = dbFile(dir, "predict.db")
                    val built = dbFile(dir, "predict.db.building.tmp", size = 256)

                    PredictDbSwap.replace(target, built) shouldBe true

                    File(dir, "predict.db.old").exists() shouldBe false
                    dir.listFiles()!!.map { it.name }.sorted() shouldBe listOf("predict.db")
                }
            }
        }
    })
