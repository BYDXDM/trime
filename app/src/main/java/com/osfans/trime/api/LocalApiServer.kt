/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.api

import androidx.core.content.ContextCompat
import com.osfans.trime.core.RimeApi
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * 进程内本地 HTTP API：只绑 127.0.0.1，供 `adb forward` 后的 curl 驱动输入法。
 *
 * 设计约束：
 * - 单后台线程串行 accept + 逐连接处理，不引入线程池；
 * - 所有 Rime 读写都经 [TrimeInputMethodService.postRimeJob] 串行执行，用
 *   [CompletableDeferred] 把结果带回请求线程，因此读到的是最新状态而不是缓存；
 * - 键盘 id 与上屏文本只在主线程读写，避免碰非 volatile 字段；
 * - 任何异常只影响当前请求（500 JSON），绝不让监听线程把异常抛出线程外。
 */
class LocalApiServer(
    private val service: TrimeInputMethodService,
) {
    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 25344
        const val API_VERSION = 1
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        const val CLIENT_SO_TIMEOUT_MS = 10_000
        const val RIME_TIMEOUT_MS = 5_000L
        const val MAIN_TIMEOUT_MS = 2_000L
        const val CANDIDATE_LIMIT = 10
        const val SETTLE_TRIES = 5
        const val SETTLE_INTERVAL_MS = 40L
        // 只等很短一下：监听线程是 daemon，收尾不阻塞 onDestroy
        const val STOP_JOIN_TIMEOUT_MS = 300L
        const val LF = 0x0A
        const val CR = 0x0D
    }

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var activeClient: Socket? = null

    /** 启动监听；端口占用等失败只打日志，不影响输入法本体。 */
    fun start() {
        if (running) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(HOST), PORT), 8)
            serverSocket = socket
            running = true
            worker =
                Thread({ acceptLoop(socket) }, "trime-local-api").apply {
                    isDaemon = true
                    start()
                }
            Timber.i("LocalApiServer listening on %s:%d", HOST, PORT)
        } catch (e: Throwable) {
            Timber.e(e, "LocalApiServer failed to start on %s:%d", HOST, PORT)
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    /** 关闭监听；进行中的连接会被关掉，未完成的请求随之失败。 */
    fun stop() {
        running = false
        runCatching { activeClient?.close() }
        activeClient = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker?.let { thread ->
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        worker = null
        Timber.i("LocalApiServer stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client =
                try {
                    socket.accept()
                } catch (e: Throwable) {
                    if (running) Timber.e(e, "LocalApiServer accept failed")
                    break
                }
            activeClient = client
            try {
                client.soTimeout = CLIENT_SO_TIMEOUT_MS
                handleClient(client)
            } catch (e: Throwable) {
                Timber.e(e, "LocalApiServer failed to serve a request")
            } finally {
                activeClient = null
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val output = client.getOutputStream()
        val request = readRequest(client.getInputStream())
        if (request == null) {
            writeResponse(output, ApiResponse(400, errorJson("bad request")))
            return
        }
        val response =
            try {
                route(request)
            } catch (e: Throwable) {
                Timber.e(e, "LocalApiServer %s %s failed", request.method, request.path)
                ApiResponse(500, errorJson(e.message ?: e.javaClass.simpleName))
            }
        Timber.d("LocalApiServer %s %s -> %d", request.method, request.path, response.status)
        writeResponse(output, response)
    }

    // region HTTP 解析与应答

    private fun readRequest(input: InputStream): HttpRequest? {
        val header = ByteArrayOutputStream()
        var prev = -1
        var prevPrev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)
            if (b == LF && (prev == LF || (prev == CR && prevPrev == LF))) break
            if (header.size() > MAX_HEADER_BYTES) return null
            prevPrev = prev
            prev = b
        }
        val headerText = String(header.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.lines()
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val path = parts[1].substringBefore('?')
        var contentLength = 0
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val body =
            if (contentLength == 0) {
                ""
            } else {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            }
        return HttpRequest(method, path, body)
    }

    private fun writeResponse(
        output: OutputStream,
        response: ApiResponse,
    ) {
        val body = response.payload.toString().toByteArray(Charsets.UTF_8)
        val head =
            buildString {
                append("HTTP/1.1 ")
                append(response.status)
                append(' ')
                append(statusText(response.status))
                append("\r\nContent-Type: application/json; charset=utf-8")
                append("\r\nContent-Length: ")
                append(body.size)
                append("\r\nConnection: close\r\n\r\n")
            }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun statusText(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }

    private fun errorJson(message: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", message)

    // endregion

    // region 路由

    private fun route(request: HttpRequest): ApiResponse {
        if (request.method == "GET") {
            when (request.path) {
                "/ping" ->
                    return ApiResponse(
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("api", API_VERSION),
                    )

                "/state" ->
                    return ApiResponse(
                        200,
                        JSONObject()
                            .put("ok", true)
                            .also { fillState(it) },
                    )
            }
        }
        if (request.method == "POST") {
            when (request.path) {
                "/type" -> return handleType(request.body)
                "/press" -> return handlePress(request.body)
                "/keyboard" -> return handleKeyboard(request.body)
                "/candidate" -> return handleCandidate(request.body)
                "/clear" -> return handleClear()
            }
        }
        return ApiResponse(404, errorJson("not found"))
    }

    private fun parseBody(body: String): JSONObject? {
        if (body.isBlank()) return JSONObject()
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun handleType(body: String): ApiResponse {
        val json = parseBody(body) ?: return ApiResponse(400, errorJson("bad request"))
        val text = json.optString("text", "")
        if (text.isEmpty()) return ApiResponse(400, errorJson("bad request"))
        val previousCommit = readLastCommit()
        runRime { simulateKeySequence(text) }
        settleLastCommit(previousCommit)
        val state = JSONObject()
        fillState(state)
        return ApiResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("state", state),
        )
    }

    private fun handlePress(body: String): ApiResponse {
        val json = parseBody(body) ?: return ApiResponse(400, errorJson("bad request"))
        val label = json.optString("label", "")
        if (label.isEmpty()) return ApiResponse(400, errorJson("bad request"))
        val previousCommit = readLastCommit()
        val pressed =
            onMain {
                val window = service.apiInputView?.apiKeyboardWindow ?: return@onMain false
                window.pressKeyByLabelForApi(label)
            }
        if (!pressed) return ApiResponse(200, errorJson("key not found"))
        // 空任务排在按键任务之后：它返回即表示 Rime 队列已排空。
        runRime { }
        settleLastCommit(previousCommit)
        val (keyboardId, lastCommit) =
            onMain {
                val window = service.apiInputView?.apiKeyboardWindow
                (window?.apiCurrentKeyboardId ?: "") to service.apiLastCommittedText
            }
        return ApiResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("keyboardId", keyboardId)
                .put("lastCommit", lastCommit),
        )
    }

    private fun handleKeyboard(body: String): ApiResponse {
        val json = parseBody(body) ?: return ApiResponse(400, errorJson("bad request"))
        val id = json.optString("id", "")
        if (id.isEmpty()) return ApiResponse(400, errorJson("bad request"))
        val window =
            onMain { service.apiInputView?.apiKeyboardWindow }
                ?: return ApiResponse(500, errorJson("keyboard not ready"))
        // switchKeyboard 自己再投递一次主线程任务做挂载，紧跟的空任务保证挂载已完成。
        onMain { window.switchKeyboard(id) }
        onMain { }
        return ApiResponse(200, JSONObject().put("ok", true))
    }

    private fun handleCandidate(body: String): ApiResponse {
        val json = parseBody(body) ?: return ApiResponse(400, errorJson("bad request"))
        if (!json.has("index")) return ApiResponse(400, errorJson("bad request"))
        val index = json.optInt("index", -1)
        if (index < 0) return ApiResponse(400, errorJson("bad request"))
        val previousCommit = readLastCommit()
        runRime { selectCandidate(index, false) }
        settleLastCommit(previousCommit)
        return ApiResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("lastCommit", readLastCommit()),
        )
    }

    private fun handleClear(): ApiResponse {
        runRime { clearComposition() }
        return ApiResponse(200, JSONObject().put("ok", true))
    }

    // endregion

    // region 状态读取

    private fun fillState(target: JSONObject) {
        if (service.apiRimeReady) {
            readRimeState(target)
        } else {
            target.put("ready", false)
            target.put("schemaId", "")
            target.put("asciiMode", false)
            target.put("composing", false)
            target.put("rawInput", "")
            target.put("candidates", JSONArray())
        }
        val ui =
            onMain {
                val window = service.apiInputView?.apiKeyboardWindow
                UiState(
                    keyboardId = window?.apiCurrentKeyboardId ?: "",
                    symbolsReturnId = window?.apiSymbolsReturnKeyboardId ?: "",
                    lastCommit = service.apiLastCommittedText,
                )
            }
        target.put("keyboardId", ui.keyboardId)
        target.put("symbolsReturnId", ui.symbolsReturnId)
        target.put("lastCommit", ui.lastCommit)
    }

    /** 在 Rime 串行队列里同步读一次状态；调用返回时这些字段就是读取瞬间的值。 */
    private fun readRimeState(target: JSONObject) {
        val done = CompletableDeferred<Unit>()
        service.postRimeJob {
            try {
                target.put("ready", isReady)
                val status = statusCached
                target.put("schemaId", status.schemaId.ifEmpty { schemaCached.schemaId })
                target.put("asciiMode", status.isAsciiMode)
                target.put("composing", status.isComposing)
                target.put("rawInput", getRawInput())
                val candidates = JSONArray()
                getCandidates(0, CANDIDATE_LIMIT).forEach { candidate ->
                    candidates.put(
                        JSONObject()
                            .put("text", candidate.text)
                            .put("comment", candidate.comment),
                    )
                }
                target.put("candidates", candidates)
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        runBlocking { withTimeout(RIME_TIMEOUT_MS) { done.await() } }
    }

    // endregion

    // region 跨线程等待

    /** 把一个 Rime 操作排进串行队列，并等到它执行完。 */
    private fun runRime(block: suspend RimeApi.() -> Unit) {
        val done = CompletableDeferred<Unit>()
        service.postRimeJob {
            try {
                block()
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        runBlocking { withTimeout(RIME_TIMEOUT_MS) { done.await() } }
    }

    /** 在主线程执行并取回结果（键盘字段与上屏文本都只在主线程读写）。 */
    private fun <T> onMain(block: () -> T): T {
        val result = CompletableDeferred<T>()
        ContextCompat.getMainExecutor(service).execute {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return runBlocking { withTimeout(MAIN_TIMEOUT_MS) { result.await() } }
    }

    private fun readLastCommit(): String = onMain { service.apiLastCommittedText }

    /**
     * 上屏文本由主线程的 commitText 写入，而 Rime 的上屏消息是异步投递到主线程的，
     * 队列排空时它可能还没到；这里做一次有上限的短轮询，避免读到上一轮的值。
     */
    private fun settleLastCommit(previous: String) {
        repeat(SETTLE_TRIES) {
            if (readLastCommit() != previous) return
            Thread.sleep(SETTLE_INTERVAL_MS)
        }
    }

    // endregion

    private data class UiState(
        val keyboardId: String,
        val symbolsReturnId: String,
        val lastCommit: String,
    )

    private class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private class ApiResponse(
        val status: Int,
        val payload: JSONObject,
    )
}
