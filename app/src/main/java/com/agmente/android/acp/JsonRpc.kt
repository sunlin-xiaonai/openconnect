package com.agmente.android.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun jsonRpcRequest(
    id: String,
    method: String,
    params: JsonElement,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("method", method)
    put("params", params)
}

internal fun jsonRpcResponse(
    id: JsonElement,
    result: JsonElement,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

internal fun jsonRpcError(
    id: JsonElement,
    code: Int,
    message: String,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put(
        "error",
        buildJsonObject {
            put("code", code)
            put("message", message)
        }
    )
}

internal fun JsonObject.string(name: String): String? =
    this[name].contentOrNull()

internal fun JsonObject.int(name: String): Int? =
    this[name]?.let { element ->
        (element as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

internal fun JsonElement?.jsonArrayOrNull(): JsonArray? =
    this as? JsonArray

internal fun JsonElement?.contentOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asRequestKey(): String? =
    when (this) {
        is JsonPrimitive -> this.content
        else -> null
    }
