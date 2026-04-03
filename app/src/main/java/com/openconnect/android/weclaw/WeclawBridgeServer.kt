package com.openconnect.android.weclaw

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeHttpException(
    val statusCode: Int,
    override val message: String,
) : IllegalStateException(message)

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String? = null,
    val content: kotlinx.serialization.json.JsonElement? = null,
)

data class ChatCompletionResult(
    val model: String,
    val content: String,
    val stopReason: String,
)

class WeclawBridgeServer(
    private val json: Json,
    private val listener: Listener,
    private val promptHandler: suspend (ChatCompletionRequest) -> ChatCompletionResult,
    private val healthProvider: () -> JsonObject,
) {
    interface Listener {
        fun onEvent(message: String)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private data class ServerConfig(
        val host: String,
        val port: Int,
        val bearerToken: String?,
    )

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private data class HttpResponse(
        val statusCode: Int,
        val body: ByteArray,
        val contentType: String = "application/json; charset=utf-8",
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var config: ServerConfig? = null

    val isRunning: Boolean
        get() = serverSocket != null

    fun start(host: String, port: Int, bearerToken: String?) {
        stop(emitEvent = false)

        val listeningSocket = ServerSocket()
        listeningSocket.reuseAddress = true
        listeningSocket.bind(InetSocketAddress(host, port))

        serverSocket = listeningSocket
        config = ServerConfig(
            host = host,
            port = port,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() },
        )

        listener.onEvent("WeClaw Bridge 监听中: http://$host:$port")

        scope.launch {
            acceptLoop(listeningSocket)
        }
    }

    fun stop(emitEvent: Boolean = true) {
        val socket = serverSocket ?: return
        serverSocket = null
        config = null
        runCatching { socket.close() }
        if (emitEvent) {
            listener.onEvent("WeClaw Bridge 已停止")
        }
    }

    fun close() {
        stop(emitEvent = false)
        scope.cancel()
    }

    private fun acceptLoop(listeningSocket: ServerSocket) {
        while (serverSocket === listeningSocket && !listeningSocket.isClosed) {
            val socket = try {
                listeningSocket.accept()
            } catch (_: SocketException) {
                break
            } catch (throwable: Throwable) {
                listener.onError("WeClaw Bridge accept 失败", throwable)
                break
            }

            scope.launch {
                handleConnection(socket)
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = 30_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())

            val response = runCatching {
                dispatch(readRequest(input))
            }.getOrElse { throwable ->
                when (throwable) {
                    is BridgeHttpException -> jsonError(throwable.statusCode, throwable.message)
                    else -> {
                        listener.onError("WeClaw Bridge 请求处理失败", throwable)
                        jsonError(500, throwable.message ?: "Internal server error")
                    }
                }
            }

            writeResponse(output, response)
        }
    }

    private suspend fun dispatch(request: HttpRequest): HttpResponse {
        return when {
            request.method == "GET" && request.path == "/health" -> {
                jsonResponse(200, healthProvider())
            }

            request.method == "GET" && request.path == "/v1/models" -> {
                ensureAuthorized(request)
                jsonResponse(
                    statusCode = 200,
                    payload = buildJsonObject {
                        put(
                            "data",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "openconnect-acp")
                                        put("object", "model")
                                        put("owned_by", "openconnect-android")
                                    }
                                )
                            }
                        )
                        put("object", "list")
                    }
                )
            }

            request.method == "POST" && request.path == "/v1/chat/completions" -> {
                ensureAuthorized(request)
                val requestText = request.body.toString(Charsets.UTF_8)
                val chatRequest = runCatching {
                    json.decodeFromString(ChatCompletionRequest.serializer(), requestText)
                }.getOrElse { throwable ->
                    throw BridgeHttpException(400, "无效的 JSON 请求体: ${throwable.message}")
                }

                if (chatRequest.stream) {
                    throw BridgeHttpException(400, "当前版本不支持 stream=true")
                }

                val result = promptHandler(chatRequest)
                jsonResponse(
                    statusCode = 200,
                    payload = buildJsonObject {
                        put("id", "chatcmpl-${UUID.randomUUID()}")
                        put("object", "chat.completion")
                        put("created", Instant.now().epochSecond)
                        put("model", result.model)
                        put(
                            "choices",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("index", 0)
                                        put(
                                            "message",
                                            buildJsonObject {
                                                put("role", "assistant")
                                                put("content", result.content)
                                            }
                                        )
                                        put("finish_reason", mapFinishReason(result.stopReason))
                                    }
                                )
                            }
                        )
                        put(
                            "usage",
                            buildJsonObject {
                                put("prompt_tokens", 0)
                                put("completion_tokens", 0)
                                put("total_tokens", 0)
                            }
                        )
                    }
                )
            }

            else -> jsonError(404, "Not found: ${request.method} ${request.path}")
        }
    }

    private fun ensureAuthorized(request: HttpRequest) {
        val bearerToken = config?.bearerToken ?: return
        val authorization = request.headers["authorization"].orEmpty()
        val expected = "bearer $bearerToken"
        if (authorization.trim().lowercase() != expected.lowercase()) {
            throw BridgeHttpException(401, "缺少或无效的 Bearer Token")
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest {
        val requestLine = input.readAsciiLine()
            ?: throw BridgeHttpException(400, "空请求")
        val requestParts = requestLine.split(' ')
        if (requestParts.size < 2) {
            throw BridgeHttpException(400, "无效的请求行")
        }

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readAsciiLine()
                ?: throw BridgeHttpException(400, "请求头未结束")
            if (line.isEmpty()) {
                break
            }
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) {
                throw BridgeHttpException(400, "无效的请求头: $line")
            }
            val name = line.substring(0, separatorIndex).trim().lowercase()
            val value = line.substring(separatorIndex + 1).trim()
            headers[name] = value
        }

        val transferEncoding = headers["transfer-encoding"].orEmpty()
        if (transferEncoding.contains("chunked", ignoreCase = true)) {
            throw BridgeHttpException(400, "当前版本不支持 chunked 请求体")
        }

        val contentLength = headers["content-length"]?.toIntOrNull()
            ?: 0
        if (contentLength < 0) {
            throw BridgeHttpException(400, "无效的 Content-Length")
        }

        val body = ByteArray(contentLength)
        input.readFully(body)

        val rawPath = requestParts[1]
        return HttpRequest(
            method = requestParts[0].uppercase(),
            path = rawPath.substringBefore('?'),
            headers = headers,
            body = body,
        )
    }

    private fun writeResponse(output: BufferedOutputStream, response: HttpResponse) {
        val headerLines = buildString {
            append("HTTP/1.1 ${response.statusCode} ${statusText(response.statusCode)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n")
            response.extraHeaders.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("\r\n")
        }
        output.write(headerLines.toByteArray(Charsets.UTF_8))
        output.write(response.body)
        output.flush()
    }

    private fun jsonResponse(statusCode: Int, payload: JsonObject): HttpResponse =
        HttpResponse(
            statusCode = statusCode,
            body = json.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8),
        )

    private fun jsonError(statusCode: Int, message: String): HttpResponse =
        jsonResponse(
            statusCode = statusCode,
            payload = buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("message", message)
                        put("type", "openconnect_error")
                    }
                )
            }
        )

    private fun mapFinishReason(stopReason: String): String =
        when (stopReason) {
            "max_tokens" -> "length"
            else -> "stop"
        }

    private fun statusText(statusCode: Int): String =
        when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            500 -> "Internal Server Error"
            else -> "OK"
        }
}

private fun BufferedInputStream.readAsciiLine(maxLength: Int = 8 * 1024): String? {
    val bytes = ArrayList<Byte>(128)
    while (true) {
        val value = read()
        if (value == -1) {
            return if (bytes.isEmpty()) {
                null
            } else {
                bytes.toByteArray().toString(Charsets.UTF_8)
            }
        }

        if (value == '\n'.code) {
            break
        }

        if (value != '\r'.code) {
            bytes += value.toByte()
        }

        if (bytes.size > maxLength) {
            throw BridgeHttpException(400, "请求行过长")
        }
    }

    return bytes.toByteArray().toString(Charsets.UTF_8)
}

private fun BufferedInputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val readCount = read(target, offset, target.size - offset)
        if (readCount == -1) {
            throw BridgeHttpException(400, "请求体读取不完整")
        }
        offset += readCount
    }
}
