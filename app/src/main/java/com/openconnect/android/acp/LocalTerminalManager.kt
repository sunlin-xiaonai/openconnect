package com.openconnect.android.acp

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LocalTerminalManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    suspend fun handle(method: String, params: JsonObject): JsonObject =
        when (method) {
            "terminal/create" -> create(params)
            "terminal/output" -> output(params)
            "terminal/kill" -> kill(params)
            "terminal/release" -> release(params)
            "terminal/wait_for_exit" -> waitForExit(params)
            else -> throw IllegalArgumentException("Unsupported terminal method: $method")
        }

    fun close() {
        sessions.values.forEach { session ->
            session.process.destroyForcibly()
        }
        sessions.clear()
        scope.cancel()
    }

    private suspend fun create(params: JsonObject): JsonObject {
        val command = params.string("command")
            ?: throw IllegalArgumentException("terminal/create missing command")
        val args = params["args"].jsonArrayOrNull()
            ?.mapNotNull { it.contentOrNull() }
            .orEmpty()
        val cwd = params.string("cwd")
        val outputLimit = params.int("outputByteLimit")

        return withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(buildList {
                add(command)
                addAll(args)
            }).redirectErrorStream(true)

            if (!cwd.isNullOrBlank()) {
                val directory = File(cwd)
                require(directory.exists()) { "Working directory does not exist: $cwd" }
                processBuilder.directory(directory)
            }

            params["env"].jsonArrayOrNull()
                ?.forEach { entry ->
                    val envObject = entry.jsonObjectOrNull() ?: return@forEach
                    val name = envObject.string("name") ?: return@forEach
                    val value = envObject.string("value") ?: ""
                    processBuilder.environment()[name] = value
                }

            val process = processBuilder.start()
            val terminalId = UUID.randomUUID().toString()
            val session = TerminalSession(
                process = process,
                outputByteLimit = outputLimit,
            )
            sessions[terminalId] = session

            scope.launch {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        session.appendOutput(line + "\n")
                    }
                }
            }

            scope.launch {
                val exitCode = process.waitFor()
                session.markExited(exitCode)
            }

            buildJsonObject {
                put("terminalId", terminalId)
            }
        }
    }

    private fun output(params: JsonObject): JsonObject {
        val terminalId = params.string("terminalId")
            ?: throw IllegalArgumentException("terminal/output missing terminalId")
        val session = sessions[terminalId]
            ?: throw IllegalArgumentException("Unknown terminalId: $terminalId")

        return buildJsonObject {
            put("output", session.output())
            put("truncated", session.truncated())
            session.exitStatus()?.let { put("exitStatus", it) }
        }
    }

    private suspend fun kill(params: JsonObject): JsonObject {
        val terminalId = params.string("terminalId")
            ?: throw IllegalArgumentException("terminal/kill missing terminalId")
        val session = sessions[terminalId]
            ?: throw IllegalArgumentException("Unknown terminalId: $terminalId")

        return withContext(Dispatchers.IO) {
            session.process.destroy()
            if (session.process.isAlive) {
                session.process.destroyForcibly()
            }
            buildJsonObject {}
        }
    }

    private suspend fun release(params: JsonObject): JsonObject {
        val terminalId = params.string("terminalId")
            ?: throw IllegalArgumentException("terminal/release missing terminalId")
        val session = sessions.remove(terminalId)
            ?: throw IllegalArgumentException("Unknown terminalId: $terminalId")

        return withContext(Dispatchers.IO) {
            if (session.process.isAlive) {
                session.process.destroy()
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                }
            }
            buildJsonObject {}
        }
    }

    private suspend fun waitForExit(params: JsonObject): JsonObject {
        val terminalId = params.string("terminalId")
            ?: throw IllegalArgumentException("terminal/wait_for_exit missing terminalId")
        val session = sessions[terminalId]
            ?: throw IllegalArgumentException("Unknown terminalId: $terminalId")

        val exitCode = withContext(Dispatchers.IO) {
            if (session.exitStatus() != null) {
                session.exitStatus()!!.string("code")?.toIntOrNull() ?: 0
            } else {
                val code = session.process.waitFor()
                session.markExited(code)
                code
            }
        }

        return buildJsonObject {
            put(
                "exitStatus",
                buildJsonObject {
                    put("code", exitCode)
                    put("signal", JsonNull)
                }
            )
        }
    }

    private class TerminalSession(
        val process: Process,
        private val outputByteLimit: Int?,
    ) {
        private val lock = Any()
        private val buffer = StringBuilder()
        private var hasTruncatedOutput = false
        private var exitCode: Int? = null

        fun appendOutput(chunk: String) {
            synchronized(lock) {
                buffer.append(chunk)
                trimToLimitLocked()
            }
        }

        fun output(): String = synchronized(lock) {
            buffer.toString()
        }

        fun truncated(): Boolean = synchronized(lock) {
            hasTruncatedOutput
        }

        fun markExited(code: Int) {
            synchronized(lock) {
                exitCode = code
            }
        }

        fun exitStatus(): JsonObject? = synchronized(lock) {
            exitCode?.let { code ->
                buildJsonObject {
                    put("code", code)
                }
            }
        }

        private fun trimToLimitLocked() {
            val limit = outputByteLimit ?: return
            if (limit <= 0) {
                buffer.clear()
                hasTruncatedOutput = true
                return
            }

            var text = buffer.toString()
            while (text.toByteArray(Charsets.UTF_8).size > limit && text.isNotEmpty()) {
                text = text.drop(1)
                hasTruncatedOutput = true
            }

            if (text != buffer.toString()) {
                buffer.clear()
                buffer.append(text)
            }
        }
    }
}
