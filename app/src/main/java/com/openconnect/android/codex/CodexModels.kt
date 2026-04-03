package com.openconnect.android.codex

import android.content.Context
import androidx.annotation.StringRes
import com.openconnect.android.R
import com.openconnect.android.acp.contentOrNull
import com.openconnect.android.acp.jsonArrayOrNull
import com.openconnect.android.acp.jsonObjectOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ServerMode {
    ACP,
    CodexAppServer,
}

enum class CodexPermissionPreset(
    @StringRes val displayNameResId: Int,
    val approvalPolicy: String,
) {
    Safe(
        displayNameResId = R.string.permission_preset_safe,
        approvalPolicy = "on-request",
    ),
    FullAccess(
        displayNameResId = R.string.permission_preset_full_access,
        approvalPolicy = "never",
    ),
    ;

    fun sandboxPolicy(): JsonObject =
        when (this) {
            Safe -> buildJsonObject {
                put("type", "workspaceWrite")
            }

            FullAccess -> buildJsonObject {
                put("type", "dangerFullAccess")
            }
        }
}

fun CodexPermissionPreset.displayName(context: Context): String =
    context.getString(displayNameResId)

data class RemoteSessionSummary(
    val id: String,
    val title: String,
    val cwd: String?,
    val updatedAtEpochSeconds: Long?,
    val isRunning: Boolean = false,
)

enum class TranscriptRole {
    User,
    Assistant,
    Tool,
    System,
}

private const val MAX_TRANSCRIPT_TEXT_LENGTH = 6000

data class TranscriptEntry(
    val id: String,
    val role: TranscriptRole,
    val title: String,
    val text: String,
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
)

data class CodexApprovalRequest(
    val requestId: JsonElement,
    val requestLabel: String,
    val method: String,
    val title: String,
    val reason: String?,
    val command: String?,
    val cwd: String?,
)

data class CodexThreadResume(
    val id: String,
    val cwd: String?,
    val preview: String?,
    val activeTurnId: String?,
    val entries: List<TranscriptEntry>,
)

fun parseCodexThreadList(
    result: JsonObject,
    context: Context,
): List<RemoteSessionSummary> {
    val data = result["data"].jsonArrayOrNull().orEmpty()
    return data.mapNotNull { value ->
        val thread = value.jsonObjectOrNull() ?: return@mapNotNull null
        val id = thread["id"].contentOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val activeTurnId = extractActiveTurnId(thread)
        RemoteSessionSummary(
            id = id,
            title = thread["preview"].contentOrNull().orEmpty()
                .ifBlank { context.getString(R.string.thread_title_unnamed) },
            cwd = firstNonEmpty(
                thread["cwd"].contentOrNull(),
                thread["workingDirectory"].contentOrNull(),
            ),
            updatedAtEpochSeconds = parseUnixSeconds(thread["updatedAt"])
                ?: parseUnixSeconds(thread["createdAt"]),
            isRunning = !activeTurnId.isNullOrBlank() || isTurnInProgressStatus(thread["status"].contentOrNull()),
        )
    }
}

fun parseCodexThreadStart(
    result: JsonObject,
    context: Context,
): RemoteSessionSummary? {
    val thread = result["thread"].jsonObjectOrNull() ?: return null
    val id = thread["id"].contentOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val activeTurnId = extractActiveTurnId(thread)
    return RemoteSessionSummary(
        id = id,
        title = thread["preview"].contentOrNull().orEmpty()
            .ifBlank { context.getString(R.string.thread_title_unnamed) },
        cwd = firstNonEmpty(
            thread["cwd"].contentOrNull(),
            thread["workingDirectory"].contentOrNull(),
        ),
        updatedAtEpochSeconds = parseUnixSeconds(thread["updatedAt"])
            ?: parseUnixSeconds(thread["createdAt"]),
        isRunning = !activeTurnId.isNullOrBlank() || isTurnInProgressStatus(thread["status"].contentOrNull()),
    )
}

fun parseCodexThreadResume(
    result: JsonObject,
    context: Context,
): CodexThreadResume? {
    val thread = result["thread"].jsonObjectOrNull() ?: return null
    val id = thread["id"].contentOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val cwd = firstNonEmpty(
        thread["cwd"].contentOrNull(),
        result["cwd"].contentOrNull(),
    )
    val preview = thread["preview"].contentOrNull()

    var activeTurnId: String? = null
    val entries = mutableListOf<TranscriptEntry>()

    thread["turns"].jsonArrayOrNull().orEmpty().forEachIndexed outer@{ turnIndex, turnValue ->
        val turn = turnValue.jsonObjectOrNull() ?: return@outer
        val turnId = turn["id"].contentOrNull() ?: "turn-$turnIndex"
        if (activeTurnId == null && isTurnInProgressStatus(turn["status"].contentOrNull())) {
            activeTurnId = turnId
        }

        turn["items"].jsonArrayOrNull().orEmpty().forEachIndexed inner@{ itemIndex, itemValue ->
            val item = itemValue.jsonObjectOrNull() ?: return@inner
            parseCodexTranscriptEntry(
                item = item,
                turnId = turnId,
                fallbackItemId = "$turnId-$itemIndex",
                context = context,
            )?.let(entries::add)
        }
    }

    return CodexThreadResume(
        id = id,
        cwd = cwd,
        preview = preview,
        activeTurnId = activeTurnId,
        entries = entries,
    )
}

fun parseCodexTranscriptEntry(
    item: JsonObject,
    turnId: String?,
    fallbackItemId: String,
    context: Context,
): TranscriptEntry? {
    val type = item["type"].contentOrNull() ?: return null
    val itemId = item["id"].contentOrNull() ?: fallbackItemId
    val normalizedType = type.replace("_", "").lowercase()

    return when (normalizedType) {
        "usermessage" -> buildTextEntry(
            id = "item-$itemId",
            role = TranscriptRole.User,
            title = context.getString(R.string.codex_role_user),
            text = extractCodexTextContent(item),
            turnId = turnId,
            itemId = itemId,
            context = context,
        )

        "message" -> {
            val roleValue = item["role"].contentOrNull()?.lowercase()
            val entryRole = when {
                roleValue?.contains("user") == true -> TranscriptRole.User
                roleValue?.contains("assistant") == true -> TranscriptRole.Assistant
                roleValue?.contains("agent") == true -> TranscriptRole.Assistant
                else -> TranscriptRole.Assistant
            }
            buildTextEntry(
                id = "item-$itemId",
                role = entryRole,
                title = context.getString(
                    if (entryRole == TranscriptRole.User) {
                        R.string.codex_role_user
                    } else {
                        R.string.codex_role_assistant
                    }
                ),
                text = extractCodexTextContent(item),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )
        }

        "agentmessage", "assistantmessage" -> buildTextEntry(
            id = "item-$itemId",
            role = TranscriptRole.Assistant,
            title = context.getString(R.string.codex_role_assistant),
            text = extractCodexTextContent(item),
            turnId = turnId,
            itemId = itemId,
            context = context,
        )

        "plan" -> buildTextEntry(
            id = "item-$itemId",
            role = TranscriptRole.System,
            title = context.getString(R.string.codex_role_plan),
            text = extractCodexTextContent(item),
            turnId = turnId,
            itemId = itemId,
            context = context,
        )

        "reasoning", "thought", "analysis" -> buildTextEntry(
            id = "item-$itemId",
            role = TranscriptRole.System,
            title = context.getString(R.string.codex_role_reasoning),
            text = extractCodexReasoningText(item),
            turnId = turnId,
            itemId = itemId,
            context = context,
        )

        "commandexecution", "command", "exec", "shell" -> {
            val command = firstNonEmpty(
                item["command"].contentOrNull(),
                item["name"].contentOrNull(),
            )
            val output = extractCodexToolOutput(item)
            buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.Tool,
                title = commandExecutionDisplayTitle(command, context),
                text = output ?: command.orEmpty(),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )
        }

        "filechange", "file", "diff", "patch" -> {
            val details = extractCodexFileChangeDetails(item)
            val body = listOf(details.changeType, details.diff)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { context.getString(R.string.codex_file_modified) }
            buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.Tool,
                title = details.path ?: context.getString(R.string.codex_file_change),
                text = body,
                turnId = turnId,
                itemId = itemId,
                context = context,
            )
        }

        "toolcall", "tool", "functioncall", "function" -> {
            val title = firstNonEmpty(
                item["title"].contentOrNull(),
                item["name"].contentOrNull(),
                item["toolName"].contentOrNull(),
                item["command"].contentOrNull(),
                item["path"].contentOrNull(),
                item["tool"].jsonObjectOrNull()?.get("name").contentOrNull(),
            ) ?: context.getString(R.string.codex_tool_call)
            val kind = firstNonEmpty(
                item["kind"].contentOrNull(),
                item["toolType"].contentOrNull(),
                item["tool"].jsonObjectOrNull()?.get("type").contentOrNull(),
                item["state"].contentOrNull(),
            )
            val body = listOf(extractCodexToolOutput(item), kind)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { context.getString(R.string.codex_tool_call_completed) }
            buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.Tool,
                title = title,
                text = body,
                turnId = turnId,
                itemId = itemId,
                context = context,
            )
        }

        else -> when {
            normalizedType.contains("user") -> buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.User,
                title = context.getString(R.string.codex_role_user),
                text = extractCodexTextContent(item),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )

            normalizedType.contains("assistant") || normalizedType.contains("agent") -> buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.Assistant,
                title = context.getString(R.string.codex_role_assistant),
                text = extractCodexTextContent(item),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )

            normalizedType.contains("plan") -> buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.System,
                title = context.getString(R.string.codex_role_plan),
                text = extractCodexTextContent(item),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )

            normalizedType.contains("reason")
                || normalizedType.contains("thought")
                || normalizedType.contains("analysis") -> buildTextEntry(
                id = "item-$itemId",
                role = TranscriptRole.System,
                title = context.getString(R.string.codex_role_reasoning),
                text = extractCodexReasoningText(item),
                turnId = turnId,
                itemId = itemId,
                context = context,
            )

            normalizedType.contains("command")
                || normalizedType.contains("exec")
                || normalizedType.contains("shell") -> {
                val command = firstNonEmpty(
                    item["command"].contentOrNull(),
                    item["name"].contentOrNull(),
                )
                buildTextEntry(
                    id = "item-$itemId",
                    role = TranscriptRole.Tool,
                    title = commandExecutionDisplayTitle(command, context),
                    text = extractCodexToolOutput(item) ?: command.orEmpty(),
                    turnId = turnId,
                    itemId = itemId,
                    context = context,
                )
            }

            normalizedType.contains("file")
                || normalizedType.contains("patch")
                || normalizedType.contains("diff") -> {
                val details = extractCodexFileChangeDetails(item)
                val body = listOf(details.changeType, details.diff)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .ifBlank { context.getString(R.string.codex_file_modified) }
                buildTextEntry(
                    id = "item-$itemId",
                    role = TranscriptRole.Tool,
                    title = details.path ?: context.getString(R.string.codex_file_change),
                    text = body,
                    turnId = turnId,
                    itemId = itemId,
                    context = context,
                )
            }

            normalizedType.contains("tool") || normalizedType.contains("function") -> {
                val title = firstNonEmpty(
                    item["title"].contentOrNull(),
                    item["name"].contentOrNull(),
                    item["toolName"].contentOrNull(),
                    item["command"].contentOrNull(),
                    type,
                ) ?: context.getString(R.string.codex_tool_call)
                buildTextEntry(
                    id = "item-$itemId",
                    role = TranscriptRole.Tool,
                    title = title,
                    text = extractCodexToolOutput(item) ?: title,
                    turnId = turnId,
                    itemId = itemId,
                    context = context,
                )
            }

            else -> null
        }
    }
}

fun parseCodexApprovalRequest(
    request: JsonObject,
    context: Context,
): CodexApprovalRequest? {
    val requestId = request["id"] ?: return null
    val method = request["method"].contentOrNull() ?: return null
    val params = request["params"].jsonObjectOrNull() ?: JsonObject(emptyMap())
    val command = params["command"].contentOrNull()
    val cwd = params["cwd"].contentOrNull()
    val reason = firstNonEmpty(
        params["reason"].contentOrNull(),
        params["message"].contentOrNull(),
    )

    val title = when {
        method.contains("commandExecution", ignoreCase = true) ->
            commandExecutionDisplayTitle(command, context)
        method.contains("fileChange", ignoreCase = true) -> {
            firstNonEmpty(
                params["path"].contentOrNull(),
                params["targetPath"].contentOrNull(),
            ) ?: context.getString(R.string.codex_file_change)
        }

        else -> context.getString(R.string.codex_approval_required)
    }

    return CodexApprovalRequest(
        requestId = requestId,
        requestLabel = requestId.contentOrNull() ?: requestId.toString(),
        method = method,
        title = title,
        reason = reason,
        command = command,
        cwd = cwd,
    )
}

fun commandExecutionDisplayTitle(
    command: String?,
    context: Context,
): String {
    return command?.trim().orEmpty().ifBlank { context.getString(R.string.codex_command_execution) }
}

private data class CodexFileChangeDetails(
    val path: String?,
    val changeType: String?,
    val diff: String?,
)

private fun buildTextEntry(
    id: String,
    role: TranscriptRole,
    title: String,
    text: String,
    turnId: String?,
    itemId: String?,
    context: Context,
): TranscriptEntry? {
    val normalized = text.trim()
    if (normalized.isBlank()) {
        return null
    }
    val displayText = if (normalized.length > MAX_TRANSCRIPT_TEXT_LENGTH) {
        normalized.take(MAX_TRANSCRIPT_TEXT_LENGTH) +
            context.getString(
                R.string.codex_text_truncated,
                normalized.length - MAX_TRANSCRIPT_TEXT_LENGTH,
            )
    } else {
        normalized
    }
    return TranscriptEntry(
        id = id,
        role = role,
        title = title,
        text = displayText,
        turnId = turnId,
        itemId = itemId,
    )
}

private fun extractCodexTextContent(payload: JsonObject): String {
    val parts = mutableListOf<String>()

    for (contentItem in payload["content"].jsonArrayOrNull().orEmpty()) {
        val scalar = extractScalarText(contentItem)
        if (!scalar.isNullOrBlank()) {
            parts += scalar
            continue
        }

        val contentObject = contentItem.jsonObjectOrNull() ?: continue
        val type = contentObject["type"].contentOrNull()?.lowercase()
        val isTextType = type == null
            || type == "text"
            || type == "input_text"
            || type == "output_text"
            || type == "message"
        if (!isTextType) {
            continue
        }

        val nestedTextObject = contentObject["text"].jsonObjectOrNull()
        firstNonEmpty(
            extractScalarText(contentObject["text"]),
            extractScalarText(contentObject["delta"]),
            extractScalarText(contentObject["message"]),
            nestedTextObject?.get("value").contentOrNull(),
            nestedTextObject?.get("text").contentOrNull(),
        )?.let(parts::add)
    }

    if (parts.isNotEmpty()) {
        return parts.joinToString("\n")
    }

    val directTextObject = payload["text"].jsonObjectOrNull()
    return firstNonEmpty(
        extractScalarText(payload["text"]),
        extractScalarText(payload["delta"]),
        extractScalarText(payload["message"]),
        directTextObject?.get("value").contentOrNull(),
        directTextObject?.get("text").contentOrNull(),
        extractCodexToolOutput(payload),
    ).orEmpty()
}

private fun extractCodexReasoningText(payload: JsonObject): String {
    val directText = extractScalarText(payload["text"])
    if (!directText.isNullOrBlank()) {
        return directText
    }

    val contentText = extractCodexTextContent(payload)
    if (contentText.isNotBlank()) {
        return contentText
    }

    return payload["summary"].jsonArrayOrNull().orEmpty()
        .mapNotNull(::extractScalarText)
        .joinToString("\n\n")
}

private fun extractCodexToolOutput(payload: JsonObject): String? {
    payload["output"].jsonArrayOrNull()?.let { outputArray ->
        val parts = mutableListOf<String>()
        for (outputValue in outputArray) {
            val scalar = extractScalarText(outputValue)
            if (!scalar.isNullOrBlank()) {
                parts += scalar
                continue
            }

            val outputObject = outputValue.jsonObjectOrNull() ?: continue
            val nested = firstNonEmpty(
                extractScalarText(outputObject["text"]),
                extractScalarText(outputObject["delta"]),
                extractScalarText(outputObject["result"]),
                extractScalarText(outputObject["content"]),
                extractScalarText(outputObject["stdout"]),
                extractScalarText(outputObject["stderr"]),
                extractCodexTextContent(outputObject),
            )
            if (!nested.isNullOrBlank()) {
                parts += nested
            }
        }
        if (parts.isNotEmpty()) {
            return parts.joinToString("\n")
        }
    }

    payload["output"].jsonObjectOrNull()?.let { outputObject ->
        firstNonEmpty(
            extractScalarText(outputObject["text"]),
            extractScalarText(outputObject["result"]),
            extractScalarText(outputObject["stdout"]),
            extractScalarText(outputObject["stderr"]),
        )?.let { return it }
    }

    return firstNonEmpty(
        extractScalarText(payload["output"]),
        extractScalarText(payload["result"]),
        extractScalarText(payload["response"]),
        extractScalarText(payload["stdout"]),
        extractScalarText(payload["stderr"]),
    )
}

private fun extractCodexDiffText(payload: JsonObject): String? {
    firstNonEmpty(
        extractScalarText(payload["diff"]),
        extractScalarText(payload["patch"]),
    )?.let { return it }

    for (changeItem in payload["changes"].jsonArrayOrNull().orEmpty()) {
        val changeObject = changeItem.jsonObjectOrNull() ?: continue
        firstNonEmpty(
            extractScalarText(changeObject["diff"]),
            extractScalarText(changeObject["patch"]),
        )?.let { return it }
    }

    val diffParts = mutableListOf<String>()
    for (contentItem in payload["content"].jsonArrayOrNull().orEmpty()) {
        val contentObject = contentItem.jsonObjectOrNull() ?: continue
        val type = contentObject["type"].contentOrNull()?.lowercase()
        if (type == "diff" || type == "patch") {
            extractScalarText(contentObject["text"])
                ?.takeIf { it.isNotBlank() }
                ?.let(diffParts::add)
        }
    }

    return diffParts.joinToString("\n").ifBlank { null }
}

private fun extractCodexFileChangeDetails(payload: JsonObject): CodexFileChangeDetails {
    for (changeItem in payload["changes"].jsonArrayOrNull().orEmpty()) {
        val changeObject = changeItem.jsonObjectOrNull() ?: continue
        val path = extractScalarText(changeObject["path"])
        val kind = firstNonEmpty(
            extractScalarText(changeObject["kind"]),
            extractScalarText(changeObject["changeType"]),
        )?.takeIf { it != "Tool call" }
        val diff = extractCodexDiffText(changeObject)
        if (!path.isNullOrBlank() || !kind.isNullOrBlank() || !diff.isNullOrBlank()) {
            return CodexFileChangeDetails(
                path = path,
                changeType = kind,
                diff = diff,
            )
        }
    }

    return CodexFileChangeDetails(
        path = extractScalarText(payload["path"]),
        changeType = extractScalarText(payload["changeType"]),
        diff = extractCodexDiffText(payload),
    )
}

private fun extractScalarText(element: JsonElement?): String? {
    return when (element) {
        is JsonPrimitive -> element.contentOrNull()?.trim()
        else -> null
    }?.takeIf { it.isNotEmpty() }
}

private fun parseUnixSeconds(element: JsonElement?): Long? {
    val raw = extractScalarText(element)?.toDoubleOrNull() ?: return null
    return when {
        raw >= 1e17 -> (raw / 1_000_000_000.0).toLong()
        raw >= 1e14 -> (raw / 1_000_000.0).toLong()
        raw >= 1e11 -> (raw / 1_000.0).toLong()
        else -> raw.toLong()
    }
}

private fun extractActiveTurnId(thread: JsonObject): String? {
    firstNonEmpty(
        thread["activeTurnId"].contentOrNull(),
        thread["currentTurnId"].contentOrNull(),
        thread["inProgressTurnId"].contentOrNull(),
    )?.let { return it }

    thread["turns"].jsonArrayOrNull().orEmpty().forEachIndexed { turnIndex, turnValue ->
        val turn = turnValue.jsonObjectOrNull() ?: return@forEachIndexed
        if (isTurnInProgressStatus(turn["status"].contentOrNull())) {
            return turn["id"].contentOrNull() ?: "turn-$turnIndex"
        }
    }
    return null
}

private fun isTurnInProgressStatus(status: String?): Boolean {
    val normalized = status?.replace("_", "")?.lowercase() ?: return false
    return normalized == "inprogress"
        || normalized == "running"
        || normalized == "pending"
        || normalized == "started"
}

private fun firstNonEmpty(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
