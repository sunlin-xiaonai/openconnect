package com.openconnect.android.codex

import com.openconnect.android.acp.AcpTransport
import com.openconnect.android.acp.jsonRpcError
import com.openconnect.android.acp.jsonRpcRequest
import com.openconnect.android.acp.jsonRpcResponse
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class CodexTransport(
    private val json: Json,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(code: Int?, reason: String?)
        fun onWireMessage(direction: AcpTransport.WireDirection, payload: JsonObject)
        fun onEvent(message: String)
        fun onError(message: String, throwable: Throwable?)
    }

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
                    listener.onError("Codex WebSocket 连接失败", t)
                    notifyDisconnected(response?.code, response?.message ?: t.message)
                }
            }
        )
    }

    fun sendRequest(method: String, params: JsonObject): String {
        val id = "android-codex-${requestCounter.getAndIncrement()}"
        sendJson(jsonRpcRequest(id = id, method = method, params = params))
        return id
    }

    fun sendNotification(method: String, params: JsonObject? = null) {
        val payload = buildMap<String, JsonElement> {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("method", kotlinx.serialization.json.JsonPrimitive(method))
            params?.let { put("params", it) }
        }
        sendJson(JsonObject(payload))
    }

    fun sendResponse(id: JsonElement, result: JsonObject) {
        sendJson(jsonRpcResponse(id = id, result = result))
    }

    fun sendError(id: JsonElement, code: Int, message: String) {
        sendJson(jsonRpcError(id = id, code = code, message = message))
    }

    fun disconnect() {
        socket?.close(1000, "client disconnect")
        socket = null
    }

    private fun handleIncomingText(text: String) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                try {
                    val payload = json.parseToJsonElement(line) as? JsonObject
                        ?: throw IllegalArgumentException("Expected JSON object payload")
                    listener.onWireMessage(AcpTransport.WireDirection.Incoming, payload)
                } catch (throwable: Throwable) {
                    listener.onError("解析 Codex WebSocket 消息失败", throwable)
                }
            }
    }

    private fun sendJson(payload: JsonObject) {
        listener.onWireMessage(AcpTransport.WireDirection.Outgoing, payload)
        val encoded = json.encodeToString(JsonObject.serializer(), payload)
        socket?.send(encoded + "\n")
    }
}
