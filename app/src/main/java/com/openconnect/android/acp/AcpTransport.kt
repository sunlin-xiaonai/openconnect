package com.openconnect.android.acp

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class AcpTransport(
    private val json: Json,
    private val terminalManager: LocalTerminalManager,
    private val listener: Listener,
) {
    enum class WireDirection {
        Incoming,
        Outgoing,
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected(code: Int?, reason: String?)
        fun onWireMessage(direction: WireDirection, payload: JsonObject)
        fun onEvent(message: String)
        fun onError(message: String, throwable: Throwable?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val requestCounter = AtomicLong(1)

    @Volatile
    private var socket: WebSocket? = null

    fun connect(
        url: String,
        bearerToken: String?,
        cfAccessClientId: String?,
        cfAccessClientSecret: String?,
    ) {
        disconnect()

        val requestBuilder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }
        if (!cfAccessClientId.isNullOrBlank() && !cfAccessClientSecret.isNullOrBlank()) {
            requestBuilder.addHeader("CF-Access-Client-Id", cfAccessClientId)
            requestBuilder.addHeader("CF-Access-Client-Secret", cfAccessClientSecret)
        }

        socket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                private var disconnectedNotified = false

                private fun notifyDisconnected(code: Int?, reason: String?) {
                    if (disconnectedNotified) {
                        return
                    }
                    disconnectedNotified = true
                    listener.onDisconnected(code, reason)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    disconnectedNotified = false
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingText(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    notifyDisconnected(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    notifyDisconnected(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onError(
                        message = "WebSocket 连接失败",
                        throwable = t,
                    )
                    notifyDisconnected(response?.code, response?.message ?: t.message)
                }
            }
        )
    }

    fun sendRequest(method: String, params: JsonObject): String {
        val id = "android-${requestCounter.getAndIncrement()}"
        sendJson(jsonRpcRequest(id = id, method = method, params = params))
        return id
    }

    fun disconnect() {
        socket?.close(1000, "client disconnect")
        socket = null
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun handleIncomingText(text: String) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                try {
                    val payload = json.parseToJsonElement(line) as? JsonObject
                        ?: throw IllegalArgumentException("Expected JSON object payload")
                    listener.onWireMessage(WireDirection.Incoming, payload)

                    if (payload["method"] != null && payload["id"] != null) {
                        handleClientRequest(payload)
                    }
                } catch (throwable: Throwable) {
                    listener.onError("解析 WebSocket 消息失败", throwable)
                }
            }
    }

    private fun handleClientRequest(payload: JsonObject) {
        val method = payload.string("method") ?: return
        val requestId = payload["id"] ?: return
        val params = payload["params"].jsonObjectOrNull() ?: buildJsonObject {}

        if (!method.startsWith("terminal/")) {
            sendJson(
                jsonRpcError(
                    id = requestId,
                    code = -32601,
                    message = "Unsupported client method: $method",
                )
            )
            return
        }

        scope.launch {
            runCatching {
                terminalManager.handle(method = method, params = params)
            }.onSuccess { result ->
                sendJson(jsonRpcResponse(id = requestId, result = result))
                listener.onEvent("Handled $method")
            }.onFailure { throwable ->
                sendJson(
                    jsonRpcError(
                        id = requestId,
                        code = -32000,
                        message = throwable.message ?: "Client request failed",
                    )
                )
                listener.onError("处理 $method 失败", throwable)
            }
        }
    }

    private fun sendJson(payload: JsonObject) {
        listener.onWireMessage(WireDirection.Outgoing, payload)
        val encoded = json.encodeToString(JsonObject.serializer(), payload)
        socket?.send(encoded + "\n")
    }
}
