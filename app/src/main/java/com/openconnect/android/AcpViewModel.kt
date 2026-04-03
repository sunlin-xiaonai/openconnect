package com.openconnect.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openconnect.android.acp.AcpTransport
import com.openconnect.android.acp.LocalTerminalManager
import com.openconnect.android.acp.asRequestKey
import com.openconnect.android.acp.contentOrNull
import com.openconnect.android.acp.jsonArrayOrNull
import com.openconnect.android.acp.jsonObjectOrNull
import com.openconnect.android.codex.CodexApprovalRequest
import com.openconnect.android.codex.CodexPermissionPreset
import com.openconnect.android.codex.CodexThreadResume
import com.openconnect.android.codex.CodexTransport
import com.openconnect.android.codex.RemoteSessionSummary
import com.openconnect.android.codex.ServerMode
import com.openconnect.android.codex.TranscriptEntry
import com.openconnect.android.codex.TranscriptRole
import com.openconnect.android.codex.parseCodexApprovalRequest
import com.openconnect.android.codex.parseCodexThreadList
import com.openconnect.android.codex.parseCodexThreadResume
import com.openconnect.android.codex.parseCodexThreadStart
import com.openconnect.android.codex.parseCodexTranscriptEntry
import com.openconnect.android.weclaw.BridgeHttpException
import com.openconnect.android.weclaw.ChatCompletionRequest
import com.openconnect.android.weclaw.ChatCompletionResult
import com.openconnect.android.weclaw.WeclawBridgeServer
import android.net.Uri
import android.util.Log
import java.net.URLDecoder
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class LogEntry(
    val id: Long,
    val label: String,
    val body: String,
    val kind: LogKind,
)

enum class LogKind {
    Incoming,
    Outgoing,
    Event,
    Error,
}

data class AcpUiState(
    val serverMode: ServerMode = ServerMode.CodexAppServer,
    val appLanguage: AppLanguage = AppLanguage.System,
    val endpoint: String = "",
    val bearerToken: String = "",
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
    val workingDirectory: String = "",
    val prompt: String = "",
    val sessionId: String? = null,
    val connectionStatus: String = "",
    val initializationStatus: String = "",
    val isInitialized: Boolean = false,
    val agentLabel: String = "",
    val promptSource: String? = null,
    val codexPermissionPreset: CodexPermissionPreset = CodexPermissionPreset.Safe,
    val sessionSummaries: List<RemoteSessionSummary> = emptyList(),
    val transcriptEntries: List<TranscriptEntry> = emptyList(),
    val pendingApprovals: List<CodexApprovalRequest> = emptyList(),
    val currentTurnId: String? = null,
    val isStreaming: Boolean = false,
    val bridgeListenAddress: String = "0.0.0.0:18080",
    val bridgeApiToken: String = "",
    val bridgeStatus: String = "",
    val bridgePublicUrl: String? = null,
    val lastAssistantMessage: String = "",
    val lastStopReason: String? = null,
    val lastCompletedThreadId: String? = null,
    val logs: List<LogEntry> = emptyList(),
    val isConnected: Boolean = false,
    val autoReconnectEnabled: Boolean = true,
    val reconnectStatus: String = "",
    val isReconnectScheduled: Boolean = false,
    val pendingThreadNavigationId: String? = null,
)

class AcpViewModel(
    application: Application,
) : AndroidViewModel(application), AcpTransport.Listener, CodexTransport.Listener {
    companion object {
        private const val DEBUG_TAG = "OpenConnectDebug"
        private const val LOGCAT_CHUNK_SIZE = 3500
        private const val LOGCAT_MAX_TOTAL = 12000
        private const val UI_LOG_BODY_MAX = 4000
        private const val PREFS_NAME = "openconnect_remote_state"
        private const val KEY_SERVER_MODE = "server_mode"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_BEARER_TOKEN = "bearer_token"
        private const val KEY_CF_ACCESS_CLIENT_ID = "cf_access_client_id"
        private const val KEY_CF_ACCESS_CLIENT_SECRET = "cf_access_client_secret"
        private const val KEY_WORKING_DIRECTORY = "working_directory"
        private const val KEY_PERMISSION_PRESET = "permission_preset"
        private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
        private const val KEY_SHOULD_STAY_CONNECTED = "should_stay_connected"
        private const val KEY_LAST_SESSION_ID = "last_session_id"
    }

    private data class PendingRequest(
        val serverMode: ServerMode,
        val method: String,
        val onSuccess: ((JsonObject) -> Unit)? = null,
        val onError: ((JsonElement) -> Unit)? = null,
    )

    private data class PromptExecutionResult(
        val text: String,
        val stopReason: String,
    )

    private data class SocketBinding(
        val host: String,
        val port: Int,
    )

    private class PromptAccumulator {
        private val assistantBuffer = StringBuilder()
        private val toolBuffer = StringBuilder()

        fun appendAssistant(text: String) {
            if (text.isNotBlank()) {
                assistantBuffer.append(text)
            }
        }

        fun appendTool(text: String) {
            if (text.isBlank()) {
                return
            }
            if (toolBuffer.isNotEmpty() && toolBuffer.last() != '\n') {
                toolBuffer.append('\n')
            }
            toolBuffer.append(text)
        }

        fun build(stopReason: String): PromptExecutionResult {
            val assistantText = assistantBuffer.toString().trim()
            val toolText = toolBuffer.toString().trim()
            return PromptExecutionResult(
                text = assistantText.ifBlank { toolText },
                stopReason = stopReason,
            )
        }
    }

    private class RequestFailure(
        method: String,
        message: String,
    ) : IllegalStateException("$method 失败: $message")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val app: Application = application

    private fun string(resId: Int, vararg args: Any): String =
        app.getString(resId, *args)

    private fun initialUiState(): AcpUiState =
        AcpUiState(
            connectionStatus = string(R.string.status_connection_disconnected),
            initializationStatus = string(R.string.status_initialization_uninitialized),
            agentLabel = string(R.string.status_agent_label_default),
            bridgeStatus = string(R.string.status_bridge_stopped),
            reconnectStatus = reconnectStatusText(
                enabled = true,
                shouldKeepConnected = false,
                isConnected = false,
                attempt = 0,
            ),
        )

    private val terminalManager = LocalTerminalManager()
    private val preferences: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val acpTransport = AcpTransport(
        json = json,
        terminalManager = terminalManager,
        listener = this,
    )
    private val codexTransport = CodexTransport(
        json = json,
        listener = this,
    )
    private val bridgeServer = WeclawBridgeServer(
        json = json,
        listener = object : WeclawBridgeServer.Listener {
            override fun onEvent(message: String) {
                addLocalLog("WeClaw", message, LogKind.Event)
            }

            override fun onError(message: String, throwable: Throwable?) {
                onError(message, throwable)
            }
        },
        promptHandler = ::handleBridgeChatCompletion,
        healthProvider = ::buildBridgeHealthPayload,
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val activePrompts = ConcurrentHashMap<String, PromptAccumulator>()
    private val promptMutex = Mutex()
    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<AcpUiState> = _uiState.asStateFlow()

    private var nextLogId = 1L
    private var activeTransportMode: ServerMode? = null
    private var codexInitializedAckPending = false
    private var pendingAutoInitialize = false
    private var pendingAutoCreateSession = false
    private var pendingResumeSessionId: String? = null
    private var shouldStayConnected = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var manualDisconnectRequested = false
    private var suppressNextDisconnectEvent = false

    init {
        restorePersistedConnectionState()
        maybeReconnectOnLaunch()
    }

    fun updateServerMode(value: ServerMode) {
        if (_uiState.value.serverMode == value) {
            return
        }

        disconnect()
        _uiState.update {
            it.copy(
                serverMode = value,
                sessionId = null,
                sessionSummaries = emptyList(),
                transcriptEntries = emptyList(),
                pendingApprovals = emptyList(),
                currentTurnId = null,
                isStreaming = false,
                bridgeStatus = if (value == ServerMode.ACP) {
                    it.bridgeStatus
                } else {
                    string(R.string.status_bridge_stopped)
                },
                bridgePublicUrl = if (value == ServerMode.ACP) it.bridgePublicUrl else null,
                lastAssistantMessage = "",
                lastStopReason = null,
            )
        }
        persistConnectionState()
    }

    fun updateEndpoint(value: String) {
        _uiState.update { it.copy(endpoint = value) }
        persistConnectionState()
    }

    fun updateAppLanguage(value: AppLanguage) {
        if (_uiState.value.appLanguage == value) {
            return
        }
        _uiState.update { it.copy(appLanguage = value) }
        AppLanguageManager.update(app, value)
    }

    fun updateBearerToken(value: String) {
        _uiState.update { it.copy(bearerToken = value) }
        persistConnectionState()
    }

    fun updateCfAccessClientId(value: String) {
        _uiState.update { it.copy(cfAccessClientId = value) }
        persistConnectionState()
    }

    fun updateCfAccessClientSecret(value: String) {
        _uiState.update { it.copy(cfAccessClientSecret = value) }
        persistConnectionState()
    }

    fun updateWorkingDirectory(value: String) {
        _uiState.update { it.copy(workingDirectory = value) }
        persistConnectionState()
    }

    fun updatePrompt(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun updateCodexPermissionPreset(value: CodexPermissionPreset) {
        _uiState.update { it.copy(codexPermissionPreset = value) }
        persistConnectionState()
    }

    fun updateAutoReconnectEnabled(value: Boolean) {
        _uiState.update {
            it.copy(
                autoReconnectEnabled = value,
                reconnectStatus = reconnectStatusText(
                    enabled = value,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = it.isConnected,
                    attempt = reconnectAttempt,
                ),
                isReconnectScheduled = false,
            )
        }
        if (!value) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
        } else if (shouldStayConnected && !_uiState.value.isConnected) {
            scheduleReconnect("已恢复自动重连")
        }
        persistConnectionState()
    }

    fun updateBridgeListenAddress(value: String) {
        _uiState.update { it.copy(bridgeListenAddress = value) }
    }

    fun updateBridgeApiToken(value: String) {
        _uiState.update { it.copy(bridgeApiToken = value) }
    }

    fun connect(
        overrideServerMode: ServerMode? = null,
        overrideEndpoint: String? = null,
        overrideBearerToken: String? = null,
        overrideCfAccessClientId: String? = null,
        overrideCfAccessClientSecret: String? = null,
        preserveReconnectAttempt: Boolean = false,
    ) {
        val state = _uiState.value
        val targetServerMode = overrideServerMode ?: state.serverMode
        val endpoint = overrideEndpoint?.trim().takeUnless { it.isNullOrBlank() } ?: state.endpoint.trim()
        val bearerToken = overrideBearerToken?.trim().takeUnless { it.isNullOrBlank() }
            ?: state.bearerToken.trim()
        val cfAccessClientId = overrideCfAccessClientId?.trim().takeUnless { it.isNullOrBlank() }
            ?: state.cfAccessClientId.trim()
        val cfAccessClientSecret = overrideCfAccessClientSecret?.trim().takeUnless { it.isNullOrBlank() }
            ?: state.cfAccessClientSecret.trim()

        if (endpoint.isBlank()) {
            addLocalLog("校验", "请先填写 WebSocket 地址。", LogKind.Error)
            return
        }

        reconnectJob?.cancel()
        reconnectJob = null
        if (!preserveReconnectAttempt) {
            reconnectAttempt = 0
        }
        manualDisconnectRequested = false
        suppressNextDisconnectEvent = state.isConnected
        shouldStayConnected = true
        val autoInitialize = pendingAutoInitialize
        val autoCreateSession = pendingAutoCreateSession
        disconnectInternal(clearAutomation = false)
        pendingAutoInitialize = autoInitialize
        pendingAutoCreateSession = autoCreateSession
        activeTransportMode = targetServerMode
        _uiState.update {
            it.copy(
                connectionStatus = string(R.string.status_connection_connecting),
                initializationStatus = string(R.string.status_initialization_uninitialized),
                isInitialized = false,
                reconnectStatus = reconnectStatusText(
                    enabled = it.autoReconnectEnabled,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = false,
                    attempt = reconnectAttempt,
                    isConnecting = true,
                ),
                isReconnectScheduled = preserveReconnectAttempt && reconnectAttempt > 0,
            )
        }
        persistConnectionState()

        when (targetServerMode) {
            ServerMode.ACP -> {
                acpTransport.connect(
                    url = endpoint,
                    bearerToken = bearerToken,
                    cfAccessClientId = cfAccessClientId,
                    cfAccessClientSecret = cfAccessClientSecret,
                )
            }

            ServerMode.CodexAppServer -> {
                codexTransport.connect(
                    url = endpoint,
                    bearerToken = bearerToken,
                    cfAccessClientId = cfAccessClientId,
                    cfAccessClientSecret = cfAccessClientSecret,
                )
            }
        }
    }

    fun disconnect() {
        manualDisconnectRequested = true
        shouldStayConnected = false
        pendingResumeSessionId = null
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal(clearAutomation = true)
        _uiState.update {
            it.copy(
                reconnectStatus = reconnectStatusText(
                    enabled = it.autoReconnectEnabled,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = false,
                    attempt = reconnectAttempt,
                ),
                isReconnectScheduled = false,
            )
        }
        persistConnectionState()
    }

    private fun disconnectInternal(clearAutomation: Boolean) {
        acpTransport.disconnect()
        codexTransport.disconnect()
        activeTransportMode = null
        codexInitializedAckPending = false
        if (clearAutomation) {
            pendingAutoInitialize = false
            pendingAutoCreateSession = false
        }
        pendingRequests.clear()
        activePrompts.clear()
        _uiState.update {
            it.copy(
                isConnected = false,
                connectionStatus = string(R.string.status_connection_closed),
                initializationStatus = string(R.string.status_initialization_uninitialized),
                isInitialized = false,
                currentTurnId = null,
                isStreaming = false,
                pendingApprovals = emptyList(),
                reconnectStatus = reconnectStatusText(
                    enabled = it.autoReconnectEnabled,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = false,
                    attempt = reconnectAttempt,
                ),
                isReconnectScheduled = false,
            )
        }
    }

    fun initialize() {
        if (!_uiState.value.isConnected) {
            addLocalLog("初始化", "请先连接到服务。", LogKind.Error)
            return
        }

        when (_uiState.value.serverMode) {
            ServerMode.ACP -> {
                sendTrackedRequest(
                    serverMode = ServerMode.ACP,
                    method = "initialize",
                    params = buildAcpInitializeParams(),
                )
            }

            ServerMode.CodexAppServer -> {
                sendTrackedRequest(
                    serverMode = ServerMode.CodexAppServer,
                    method = "initialize",
                    params = buildCodexInitializeParams(),
                )
            }
        }
    }

    fun createSession() {
        if (!_uiState.value.isConnected) {
            addLocalLog("Session", "请先连接到服务。", LogKind.Error)
            return
        }

        when (_uiState.value.serverMode) {
            ServerMode.ACP -> {
                sendTrackedRequest(
                    serverMode = ServerMode.ACP,
                    method = "session/new",
                    params = buildAcpSessionParams(),
                )
            }

            ServerMode.CodexAppServer -> {
                viewModelScope.launch {
                    runCatching {
                        promptMutex.withLock {
                            startCodexThreadLocked()
                        }
                    }.onSuccess { summary ->
                        addLocalLog("Codex", "已创建线程: ${summary.id}", LogKind.Event)
                    }.onFailure { throwable ->
                        addLocalLog("Codex", throwable.message ?: "thread/start 失败", LogKind.Error)
                    }
                }
            }
        }
    }

    fun refreshSessions() {
        if (!_uiState.value.isConnected) {
            addLocalLog("刷新", "请先连接到服务。", LogKind.Error)
            return
        }

        when (_uiState.value.serverMode) {
            ServerMode.ACP -> addLocalLog("刷新", "ACP MVP 当前没有服务端 session/list 集成。", LogKind.Event)
            ServerMode.CodexAppServer -> viewModelScope.launch {
                runCatching {
                    refreshCodexThreadsLocked()
                }.onFailure { throwable ->
                    addLocalLog("Codex", throwable.message ?: "thread/list 失败", LogKind.Error)
                }
            }
        }
    }

    fun openSession(sessionId: String) {
        if (sessionId.isBlank()) {
            return
        }
        if (!_uiState.value.isConnected) {
            addLocalLog("打开线程", "请先连接到服务。", LogKind.Error)
            return
        }
        if (_uiState.value.serverMode != ServerMode.CodexAppServer) {
            return
        }

        viewModelScope.launch {
            runCatching {
                promptMutex.withLock {
                    openCodexThreadLocked(sessionId)
                }
            }.onFailure { throwable ->
                addLocalLog("Codex", throwable.message ?: "thread/resume 失败", LogKind.Error)
            }
        }
    }

    fun approveRequest(requestId: JsonElement) {
        respondToCodexApproval(requestId = requestId, decision = "accept")
    }

    fun declineRequest(requestId: JsonElement) {
        respondToCodexApproval(requestId = requestId, decision = "decline")
    }

    fun sendPrompt() {
        val state = _uiState.value
        if (state.prompt.isBlank()) {
            addLocalLog("Prompt", "请输入要交给 agent 的任务。", LogKind.Error)
            return
        }

        when (state.serverMode) {
            ServerMode.ACP -> sendAcpPrompt()
            ServerMode.CodexAppServer -> sendCodexPrompt()
        }
    }

    private fun sendAcpPrompt() {
        val state = _uiState.value
        val sessionId = state.sessionId
        if (sessionId.isNullOrBlank()) {
            addLocalLog("Prompt", "请先创建会话。", LogKind.Error)
            return
        }

        _uiState.update {
            it.copy(
                promptSource = null,
                lastAssistantMessage = "",
                lastStopReason = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                promptMutex.withLock {
                    runAcpPromptTurnLocked(
                        sessionId = sessionId,
                        promptText = state.prompt,
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        lastAssistantMessage = result.text,
                        lastStopReason = result.stopReason,
                    )
                }
                addLocalLog("Prompt", "本轮已完成，stopReason=${result.stopReason}", LogKind.Event)
            }.onFailure { throwable ->
                addLocalLog("Prompt", throwable.message ?: "Prompt 执行失败", LogKind.Error)
            }
        }
    }

    private fun sendCodexPrompt() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                promptSource = null,
                lastStopReason = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                promptMutex.withLock {
                    val threadSummary = if (state.sessionId.isNullOrBlank()) {
                        startCodexThreadLocked()
                    } else {
                        RemoteSessionSummary(
                            id = state.sessionId,
                            title = state.sessionSummaries.firstOrNull { it.id == state.sessionId }?.title
                                ?: string(R.string.label_current_thread),
                            cwd = state.workingDirectory.ifBlank { null },
                            updatedAtEpochSeconds = null,
                            isRunning = state.sessionSummaries.firstOrNull { it.id == state.sessionId }?.isRunning ?: false,
                        )
                    }
                    appendLocalCodexUserPrompt(threadSummary.id, state.prompt)
                    startCodexTurnLocked(threadSummary.id, state.prompt)
                    threadSummary.id
                }
            }.onSuccess { threadId ->
                _uiState.update {
                    it.copy(
                        sessionId = threadId,
                        prompt = "",
                        lastCompletedThreadId = null,
                    )
                }
            }.onFailure { throwable ->
                addLocalLog("Codex", throwable.message ?: "turn/start 失败", LogKind.Error)
            }
        }
    }

    fun startBridgeServer() {
        val state = _uiState.value
        if (state.serverMode != ServerMode.ACP) {
            addLocalLog("WeClaw", "Bridge 仅在 ACP 模式下可用。", LogKind.Error)
            return
        }

        val binding = runCatching {
            parseBridgeListenAddress(state.bridgeListenAddress)
        }.getOrElse { throwable ->
            addLocalLog("WeClaw", throwable.message ?: "监听地址无效", LogKind.Error)
            return
        }

        runCatching {
            bridgeServer.start(
                host = binding.host,
                port = binding.port,
                bearerToken = state.bridgeApiToken.trim(),
            )
        }.onSuccess {
            _uiState.update {
                it.copy(
                    bridgeStatus = string(R.string.status_bridge_running),
                    bridgePublicUrl = buildBridgePublicUrl(binding),
                )
            }
        }.onFailure { throwable ->
            addLocalLog("WeClaw", throwable.message ?: "Bridge 启动失败", LogKind.Error)
        }
    }

    fun stopBridgeServer() {
        bridgeServer.stop()
        _uiState.update {
            it.copy(
                bridgeStatus = string(R.string.status_bridge_stopped),
                bridgePublicUrl = null,
            )
        }
    }

    fun consumePendingThreadNavigation(threadId: String) {
        _uiState.update { state ->
            if (state.pendingThreadNavigationId == threadId) {
                state.copy(pendingThreadNavigationId = null)
            } else {
                state
            }
        }
    }

    fun consumeIntent(intent: Intent?) {
        intent ?: return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val mode = parseServerMode(
                    uri.firstQueryParameter("mode", "serverMode")
                )
                val prompt = uri.firstQueryParameter("prompt")
                val endpoint = uri.firstQueryParameter("endpoint", "url", "ws", "wss")
                val cwd = uri.firstQueryParameter("cwd", "workingDirectory")
                val bearerToken = uri.firstQueryParameter("bearerToken", "token", "bearer")
                val cfAccessClientId = uri.firstQueryParameter("cfAccessClientId", "cf_client_id")
                val cfAccessClientSecret = uri.firstQueryParameter("cfAccessClientSecret", "cf_client_secret")
                val permissionPreset = parsePermissionPreset(
                    uri.firstQueryParameter("permissionPreset", "permission")
                )
                val threadId = uri.firstQueryParameter("threadId", "sessionId")
                val shouldConnect = parseBooleanQueryValue(uri.firstQueryParameter("connect"))
                    || uri.host.equals("connect", ignoreCase = true)
                val shouldInitialize = parseBooleanQueryValue(uri.firstQueryParameter("initialize"))
                    || (uri.host.equals("connect", ignoreCase = true) && !parseBooleanQueryValue(uri.firstQueryParameter("skipInitialize")))
                val shouldCreateSession = parseBooleanQueryValue(
                    uri.firstQueryParameter("createSession", "newSession", "createThread", "newThread")
                )

                mode?.let(::updateServerMode)
                if (!endpoint.isNullOrBlank()) {
                    updateEndpoint(endpoint)
                }
                if (!cwd.isNullOrBlank()) {
                    updateWorkingDirectory(cwd)
                }
                if (!bearerToken.isNullOrBlank()) {
                    updateBearerToken(bearerToken)
                }
                if (!cfAccessClientId.isNullOrBlank()) {
                    updateCfAccessClientId(cfAccessClientId)
                }
                if (!cfAccessClientSecret.isNullOrBlank()) {
                    updateCfAccessClientSecret(cfAccessClientSecret)
                }
                permissionPreset?.let(::updateCodexPermissionPreset)
                if (!threadId.isNullOrBlank()) {
                    pendingResumeSessionId = threadId
                    _uiState.update { it.copy(pendingThreadNavigationId = threadId) }
                    addLocalLog("入口", "收到线程跳转请求。", LogKind.Event)
                    if (_uiState.value.isConnected && _uiState.value.serverMode == ServerMode.CodexAppServer) {
                        openSession(threadId)
                    }
                }
                if (!prompt.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            prompt = prompt,
                            promptSource = string(
                                if (uri.host.equals("connect", ignoreCase = true)) {
                                    R.string.label_prompt_source_pair_qr
                                } else {
                                    R.string.label_prompt_source_deep_link
                                }
                            ),
                        )
                    }
                    addLocalLog("入口", "收到${if (uri.host.equals("connect", ignoreCase = true)) "配对二维码" else "Deep link"}任务。", LogKind.Event)
                } else if (uri.host.equals("connect", ignoreCase = true)) {
                    addLocalLog("入口", "收到配对二维码配置。", LogKind.Event)
                }

                if (shouldConnect) {
                    pendingAutoInitialize = shouldInitialize || shouldCreateSession
                    pendingAutoCreateSession = shouldCreateSession
                    connect(
                        overrideServerMode = mode,
                        overrideEndpoint = endpoint,
                        overrideBearerToken = bearerToken,
                        overrideCfAccessClientId = cfAccessClientId,
                        overrideCfAccessClientSecret = cfAccessClientSecret,
                    )
                }
            }

            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            prompt = sharedText,
                            promptSource = "Share intent",
                        )
                    }
                    addLocalLog("入口", "收到分享文本并填入 Prompt。", LogKind.Event)
                }
            }
        }
    }

    fun consumeScannedCode(rawValue: String) {
        val normalized = rawValue.trim()
        if (normalized.isBlank()) {
            addLocalLog("扫码", "二维码内容为空。", LogKind.Error)
            return
        }

        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank()) {
            addLocalLog("扫码", "二维码内容不是有效链接。", LogKind.Error)
            return
        }
        if (!uri.scheme.equals("openconnect", ignoreCase = true)) {
            addLocalLog("扫码", "当前仅支持扫描 OpenConnect 配对二维码。", LogKind.Error)
            return
        }

        addLocalLog("扫码", "已识别配对二维码，准备连接。", LogKind.Event)
        consumeIntent(Intent(Intent.ACTION_VIEW, uri))
    }

    fun onScannerCancelled() {
        addLocalLog("扫码", "已取消扫码。", LogKind.Event)
    }

    fun onScannerFailure(message: String, throwable: Throwable? = null) {
        onError("扫码失败: $message", throwable)
    }

    override fun onConnected() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        manualDisconnectRequested = false
        _uiState.update {
            it.copy(
                isConnected = true,
                connectionStatus = string(R.string.status_connection_connected),
                reconnectStatus = reconnectStatusText(
                    enabled = it.autoReconnectEnabled,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = true,
                    attempt = reconnectAttempt,
                ),
                isReconnectScheduled = false,
            )
        }
        persistConnectionState()
        addLocalLog("连接", "WebSocket 已建立。", LogKind.Event)
        if (pendingAutoInitialize) {
            pendingAutoInitialize = false
            initialize()
        }
    }

    override fun onDisconnected(code: Int?, reason: String?) {
        if (suppressNextDisconnectEvent) {
            suppressNextDisconnectEvent = false
            return
        }
        val stateBeforeDisconnect = _uiState.value
        val wasManualDisconnect = manualDisconnectRequested
        val shouldReconnect = !wasManualDisconnect &&
            stateBeforeDisconnect.autoReconnectEnabled &&
            shouldStayConnected &&
            stateBeforeDisconnect.endpoint.isNotBlank()

        pendingAutoInitialize = false
        pendingAutoCreateSession = false
        _uiState.update {
            it.copy(
                isConnected = false,
                connectionStatus = string(R.string.status_connection_closed),
                initializationStatus = string(R.string.status_initialization_uninitialized),
                isInitialized = false,
                currentTurnId = null,
                isStreaming = false,
                pendingApprovals = emptyList(),
                reconnectStatus = reconnectStatusText(
                    enabled = it.autoReconnectEnabled,
                    shouldKeepConnected = shouldStayConnected,
                    isConnected = false,
                    attempt = reconnectAttempt,
                ),
                isReconnectScheduled = false,
            )
        }
        persistConnectionState()
        val suffix = buildString {
            if (code != null) {
                append(" code=")
                append(code)
            }
            if (!reason.isNullOrBlank()) {
                append(" reason=")
                append(reason)
            }
        }
        addLocalLog("断开", "连接已关闭$suffix", LogKind.Event)
        manualDisconnectRequested = false

        if (shouldReconnect) {
            pendingAutoInitialize = true
            pendingResumeSessionId = stateBeforeDisconnect.sessionId?.takeIf { it.isNotBlank() }
            scheduleReconnect(reason)
        } else {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
        }
    }

    override fun onWireMessage(direction: AcpTransport.WireDirection, payload: JsonObject) {
        val pretty = json.encodeToString(JsonObject.serializer(), payload)
        addLocalLog(
            label = if (direction == AcpTransport.WireDirection.Incoming) "IN" else "OUT",
            body = pretty,
            kind = if (direction == AcpTransport.WireDirection.Incoming) LogKind.Incoming else LogKind.Outgoing,
        )

        if (direction == AcpTransport.WireDirection.Incoming) {
            handleIncoming(payload)
        }
    }

    override fun onEvent(message: String) {
        addLocalLog("事件", message, LogKind.Event)
    }

    override fun onError(message: String, throwable: Throwable?) {
        val details = buildString {
            append(message)
            val cause = throwable?.message
            if (!cause.isNullOrBlank()) {
                append('\n')
                append(cause)
            }
        }
        addLocalLog("错误", details, LogKind.Error)
    }

    override fun onCleared() {
        super.onCleared()
        bridgeServer.close()
        acpTransport.close()
        codexTransport.disconnect()
        terminalManager.close()
    }

    private fun handleIncoming(payload: JsonObject) {
        when (_uiState.value.serverMode) {
            ServerMode.ACP -> handleAcpIncoming(payload)
            ServerMode.CodexAppServer -> handleCodexIncoming(payload)
        }
    }

    private fun handleAcpIncoming(payload: JsonObject) {
        if (payload["result"] != null || payload["error"] != null) {
            handleResponse(payload)
            return
        }

        val method = payload["method"].contentOrNull() ?: return
        val params = payload["params"].jsonObjectOrNull()

        when (method) {
            "session/update" -> {
                val sessionId = params?.get("sessionId").contentOrNull()
                val update = params?.get("update").jsonObjectOrNull()
                val updateType = update?.get("sessionUpdate").contentOrNull() ?: "stream"
                when (updateType) {
                    "agent_message_chunk" -> {
                        val text = extractAcpTextContent(update?.get("content"))
                        if (!sessionId.isNullOrBlank() && !text.isNullOrBlank()) {
                            activePrompts[sessionId]?.appendAssistant(text)
                        }
                    }

                    "tool_call_update" -> {
                        val text = extractAcpToolContent(update?.get("content"))
                        if (!sessionId.isNullOrBlank() && !text.isNullOrBlank()) {
                            activePrompts[sessionId]?.appendTool(text)
                        }
                    }
                }
                addLocalLog("session/update", "收到更新类型: $updateType", LogKind.Event)
            }

            else -> {
                if (payload["id"] == null) {
                    addLocalLog("通知", "收到 $method", LogKind.Event)
                }
            }
        }
    }

    private fun handleCodexIncoming(payload: JsonObject) {
        if (payload["result"] != null || payload["error"] != null) {
            handleResponse(payload)
            return
        }

        val method = payload["method"].contentOrNull() ?: return
        if (payload["id"] != null) {
            handleCodexRequest(payload)
            return
        }

        val params = payload["params"].jsonObjectOrNull() ?: JsonObject(emptyMap())
        val threadId = params["threadId"].contentOrNull()

        when (method) {
            "thread/started" -> {
                params["thread"].jsonObjectOrNull()?.let { thread ->
                    parseCodexThreadStart(
                        result = buildJsonObject { put("thread", thread) },
                        context = app,
                    )?.let { upsertSessionSummary(it) }
                }
            }

            "turn/started" -> {
                val turnId = params["turn"].jsonObjectOrNull()?.get("id").contentOrNull()
                if (!threadId.isNullOrBlank()) {
                    markThreadRunning(threadId)
                }
                if (!threadId.isNullOrBlank() && threadId == _uiState.value.sessionId && !turnId.isNullOrBlank()) {
                    ensureStreamingAssistantEntry(turnId)
                    _uiState.update {
                        it.copy(
                            currentTurnId = turnId,
                            isStreaming = true,
                            lastCompletedThreadId = null,
                        )
                    }
                }
            }

            "item/agentMessage/delta" -> {
                val turnId = params["turnId"].contentOrNull()
                val delta = params["delta"].contentOrNull().orEmpty()
                if (!threadId.isNullOrBlank()) {
                    markThreadRunning(threadId)
                }
                if (!threadId.isNullOrBlank() && threadId == _uiState.value.sessionId && !turnId.isNullOrBlank()) {
                    appendAssistantDelta(turnId, delta)
                    _uiState.update {
                        it.copy(
                            currentTurnId = turnId,
                            isStreaming = true,
                        )
                    }
                }
            }

            "item/completed" -> {
                if (!threadId.isNullOrBlank() && threadId == _uiState.value.sessionId) {
                    params["item"].jsonObjectOrNull()?.let { item ->
                        val turnId = params["turnId"].contentOrNull()
                        val entry = parseCodexTranscriptEntry(
                            item = item,
                            turnId = turnId,
                            fallbackItemId = "live-${System.nanoTime()}",
                            context = app,
                        )
                        if (entry != null && entry.role != TranscriptRole.User) {
                            applyLiveTranscriptEntry(entry)
                        }
                    }
                }
            }

            "turn/diff/updated" -> {
                if (!threadId.isNullOrBlank() && threadId == _uiState.value.sessionId) {
                    val turnId = params["turnId"].contentOrNull()
                    val diff = params["diff"].contentOrNull()
                    if (!diff.isNullOrBlank()) {
                        upsertTranscriptEntry(
                            TranscriptEntry(
                                id = "diff-${turnId ?: "unknown"}",
                                role = TranscriptRole.Tool,
                                title = string(R.string.codex_code_diff),
                                text = diff,
                                turnId = turnId,
                            )
                        )
                    }
                }
            }

            "turn/completed" -> {
                val turnId = params["turn"].jsonObjectOrNull()?.get("id").contentOrNull()
                    ?: params["turnId"].contentOrNull()
                markTurnCompleted(
                    turnId = turnId,
                    threadId = threadId,
                )
                if (!threadId.isNullOrBlank()) {
                    notifyThreadCompleted(threadId)
                }
                viewModelScope.launch {
                    runCatching { refreshCodexThreadsLocked() }
                }
            }

            "error" -> {
                val errorMessage = params["error"].jsonObjectOrNull()?.get("message").contentOrNull()
                    ?: params["message"].contentOrNull()
                    ?: string(R.string.error_unknown)
                addLocalLog("Codex", errorMessage, LogKind.Error)
                if (!threadId.isNullOrBlank()) {
                    markThreadStopped(
                        threadId = threadId,
                        turnId = params["turnId"].contentOrNull(),
                        stopReason = "error",
                        markAsCompleted = false,
                    )
                    notifyThreadFailed(
                        threadId = threadId,
                        errorMessage = errorMessage,
                    )
                }
                upsertTranscriptEntry(
                    TranscriptEntry(
                        id = "error-${System.nanoTime()}",
                        role = TranscriptRole.System,
                        title = string(R.string.codex_error_title),
                        text = errorMessage,
                    )
                )
            }
        }
    }

    private fun handleCodexRequest(payload: JsonObject) {
        val method = payload["method"].contentOrNull() ?: return
        val requestId = payload["id"] ?: return

        when (method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                val approval = parseCodexApprovalRequest(payload, app)
                if (approval == null) {
                    codexTransport.sendError(requestId, -32602, "Invalid approval request")
                    return
                }

                if (isDangerousCommand(approval.command)) {
                    codexTransport.sendResponse(
                        id = requestId,
                        result = buildJsonObject {
                            put("decision", "decline")
                        },
                    )
                    addLocalLog("安全", "已自动拒绝高危命令: ${approval.command}", LogKind.Error)
                    return
                }

                _uiState.update { state ->
                    state.copy(pendingApprovals = state.pendingApprovals + approval)
                }
                addLocalLog("审批", "收到审批请求: ${approval.title}", LogKind.Event)
            }

            "item/tool/requestUserInput" -> {
                codexTransport.sendError(requestId, -32601, "Plan mode is not supported on Android MVP")
                addLocalLog("Codex", "收到计划模式输入请求，当前 Android MVP 还不支持。", LogKind.Error)
            }

            else -> {
                codexTransport.sendError(requestId, -32601, "Unsupported Codex request: $method")
                addLocalLog("Codex", "未支持的 Codex 请求: $method", LogKind.Error)
            }
        }
    }

    private fun handleResponse(payload: JsonObject) {
        val requestId = payload["id"].asRequestKey() ?: return
        val pending = pendingRequests.remove(requestId) ?: return

        payload["error"]?.let { error ->
            if (pending.onError != null) {
                pending.onError.invoke(error)
            } else {
                addLocalLog(
                    "响应错误",
                    "${pending.method} 失败\n${json.encodeToString(JsonElement.serializer(), error)}",
                    LogKind.Error,
                )
            }
            return
        }

        val result = payload["result"].jsonObjectOrNull() ?: JsonObject(emptyMap())
        if (pending.onSuccess != null) {
            pending.onSuccess.invoke(result)
            return
        }

        when (pending.serverMode) {
            ServerMode.ACP -> handleAcpResponse(pending.method, result)
            ServerMode.CodexAppServer -> handleCodexResponse(pending.method, result)
        }
    }

    private fun handleAcpResponse(method: String, result: JsonObject) {
        when (method) {
            "initialize" -> {
                val agentInfo = result["agentInfo"].jsonObjectOrNull()
                val agentTitle = agentInfo?.get("title").contentOrNull()
                    ?: agentInfo?.get("name").contentOrNull()
                    ?: "ACP Agent"
                val version = agentInfo?.get("version").contentOrNull()
                val versionLabel = version?.let { " $it" } ?: ""
                _uiState.update {
                    it.copy(
                        initializationStatus = string(R.string.status_initialization_initialized),
                        isInitialized = true,
                        agentLabel = agentTitle + versionLabel,
                    )
                }
                addLocalLog("初始化", "Agent: ${agentTitle + versionLabel}", LogKind.Event)
                if (pendingAutoCreateSession) {
                    pendingAutoCreateSession = false
                    createSession()
                }
            }

            "session/new" -> {
                val sessionId = result["sessionId"].contentOrNull()
                if (!sessionId.isNullOrBlank()) {
                    _uiState.update { it.copy(sessionId = sessionId) }
                    addLocalLog("Session", "已创建会话: $sessionId", LogKind.Event)
                }
            }
        }
    }

    private fun handleCodexResponse(method: String, result: JsonObject) {
        when (method) {
            "initialize" -> {
                val userAgent = result["userAgent"].contentOrNull() ?: "codex"
                codexInitializedAckPending = true
                _uiState.update {
                    it.copy(
                        initializationStatus = string(R.string.status_initialization_initialized),
                        isInitialized = true,
                        agentLabel = userAgent,
                    )
                }
                addLocalLog("初始化", "Codex app-server: $userAgent", LogKind.Event)
                if (pendingAutoCreateSession) {
                    pendingAutoCreateSession = false
                    createSession()
                }
                viewModelScope.launch {
                    runCatching { refreshCodexThreadsLocked() }
                    val sessionIdToResume = pendingResumeSessionId
                    if (!sessionIdToResume.isNullOrBlank()) {
                        pendingResumeSessionId = null
                        runCatching {
                            promptMutex.withLock {
                                openCodexThreadLocked(sessionIdToResume)
                            }
                        }.onFailure { throwable ->
                            addLocalLog("重连", throwable.message ?: "恢复线程失败", LogKind.Error)
                        }
                    }
                }
            }

            "thread/list" -> {
                val summaries = parseCodexThreadList(result, app)
                    .sortedByDescending { it.updatedAtEpochSeconds ?: 0L }
                _uiState.update { it.copy(sessionSummaries = summaries) }
                addLocalLog("Codex", "已同步 ${summaries.size} 个线程。", LogKind.Event)
            }

            "thread/start" -> {
                parseCodexThreadStart(result, app)?.let { summary ->
                    _uiState.update {
                        it.copy(
                            sessionId = summary.id,
                            sessionSummaries = listOf(summary) + it.sessionSummaries.filter { item -> item.id != summary.id },
                            transcriptEntries = emptyList(),
                            currentTurnId = null,
                            isStreaming = false,
                        )
                    }
                    addLocalLog("Codex", "已创建线程: ${summary.id}", LogKind.Event)
                }
            }

            "thread/resume" -> {
                val resume = parseCodexThreadResume(result, app) ?: return
                applyCodexResume(resume)
                addLocalLog("Codex", "已加载线程: ${resume.id}", LogKind.Event)
            }

            "turn/start" -> {
                val turnId = result["turn"].jsonObjectOrNull()?.get("id").contentOrNull()
                _uiState.value.sessionId?.let(::markThreadRunning)
                if (!turnId.isNullOrBlank()) {
                    ensureStreamingAssistantEntry(turnId)
                    _uiState.update {
                        it.copy(
                            currentTurnId = turnId,
                            isStreaming = true,
                        )
                    }
                }
                addLocalLog("Codex", "已发起 turn/start${turnId?.let { " ($it)" } ?: ""}", LogKind.Event)
            }
        }
    }

    private fun sendTrackedRequest(
        serverMode: ServerMode,
        method: String,
        params: JsonObject,
        onSuccess: ((JsonObject) -> Unit)? = null,
        onError: ((JsonElement) -> Unit)? = null,
    ) {
        val requestId = when (serverMode) {
            ServerMode.ACP -> acpTransport.sendRequest(method, params)
            ServerMode.CodexAppServer -> codexTransport.sendRequest(method, params)
        }
        pendingRequests[requestId] = PendingRequest(
            serverMode = serverMode,
            method = method,
            onSuccess = onSuccess,
            onError = onError,
        )
    }

    private suspend fun awaitRequestResult(
        serverMode: ServerMode,
        method: String,
        params: JsonObject,
    ): JsonObject {
        val deferred = CompletableDeferred<JsonObject>()
        sendTrackedRequest(
            serverMode = serverMode,
            method = method,
            params = params,
            onSuccess = { deferred.complete(it) },
            onError = { error ->
                deferred.completeExceptionally(
                    RequestFailure(
                        method = method,
                        message = json.encodeToString(JsonElement.serializer(), error),
                    )
                )
            },
        )
        return withTimeout(60_000L) {
            deferred.await()
        }
    }

    private suspend fun awaitCodexRequest(method: String, params: JsonObject): JsonObject {
        ensureCodexInitializedAck()
        return awaitRequestResult(
            serverMode = ServerMode.CodexAppServer,
            method = method,
            params = params,
        )
    }

    private suspend fun ensureCodexInitializedAck() {
        if (!codexInitializedAckPending) {
            return
        }
        codexTransport.sendNotification("notifications/initialized")
        codexInitializedAckPending = false
    }

    private fun buildAcpInitializeParams(): JsonObject =
        buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") {
                    put("readTextFile", false)
                    put("writeTextFile", false)
                }
                put("terminal", true)
            }
            putJsonObject("clientInfo") {
                put("name", "openconnect-android")
                put("title", "OpenConnect Android")
                put("version", BuildConfig.VERSION_NAME)
            }
        }

    private fun buildCodexInitializeParams(): JsonObject =
        buildJsonObject {
            putJsonObject("clientInfo") {
                put("name", "openconnect-android")
                put("title", "OpenConnect Android")
                put("version", BuildConfig.VERSION_NAME)
            }
        }

    private fun buildAcpSessionParams(): JsonObject =
        buildJsonObject {
            put("cwd", _uiState.value.workingDirectory.ifBlank { "." })
            put("mcpServers", buildJsonArray {})
        }

    private fun buildAcpPromptParams(sessionId: String, promptText: String): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", promptText)
                    }
                )
            }
        }

    private fun buildCodexThreadStartParams(): JsonObject =
        buildJsonObject {
            put("approvalPolicy", _uiState.value.codexPermissionPreset.approvalPolicy)
            _uiState.value.workingDirectory
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { put("cwd", it) }
        }

    private fun buildCodexThreadResumeParams(threadId: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
        }

    private fun buildCodexTurnStartParams(threadId: String, promptText: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", promptText)
                    }
                )
            }
            put("approvalPolicy", _uiState.value.codexPermissionPreset.approvalPolicy)
            put("sandboxPolicy", _uiState.value.codexPermissionPreset.sandboxPolicy())
            _uiState.value.workingDirectory
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { put("cwd", it) }
        }

    private suspend fun runAcpPromptTurnLocked(
        sessionId: String,
        promptText: String,
    ): PromptExecutionResult {
        val accumulator = PromptAccumulator()
        activePrompts[sessionId] = accumulator
        try {
            val result = awaitRequestResult(
                serverMode = ServerMode.ACP,
                method = "session/prompt",
                params = buildAcpPromptParams(sessionId, promptText),
            )
            val stopReason = result["stopReason"].contentOrNull() ?: "completed"
            return accumulator.build(stopReason)
        } finally {
            activePrompts.remove(sessionId)
        }
    }

    private suspend fun refreshCodexThreadsLocked() {
        val result = awaitCodexRequest(
            method = "thread/list",
            params = buildJsonObject {
                put("limit", 50)
            },
        )
        val summaries = parseCodexThreadList(result, app)
            .sortedByDescending { it.updatedAtEpochSeconds ?: 0L }
            .distinctBy { it.id }

        _uiState.update { it.copy(sessionSummaries = summaries) }
        addLocalLog("线程", "已加载 ${summaries.size} 个线程。", LogKind.Event)
    }

    private suspend fun startCodexThreadLocked(): RemoteSessionSummary {
        val result = awaitCodexRequest(
            method = "thread/start",
            params = buildCodexThreadStartParams(),
        )
        val summary = parseCodexThreadStart(result, app)
            ?: throw IllegalStateException("thread/start 没有返回 thread.id")
        _uiState.update {
            it.copy(
                sessionId = summary.id,
                sessionSummaries = listOf(summary) + it.sessionSummaries.filter { item -> item.id != summary.id },
                transcriptEntries = emptyList(),
                currentTurnId = null,
                isStreaming = false,
                pendingApprovals = emptyList(),
                lastCompletedThreadId = null,
            )
        }
        persistConnectionState()
        return summary
    }

    private suspend fun openCodexThreadLocked(threadId: String) {
        val result = awaitCodexRequest(
            method = "thread/resume",
            params = buildCodexThreadResumeParams(threadId),
        )
        val resume = parseCodexThreadResume(result, app)
            ?: throw IllegalStateException("thread/resume 没有返回可用线程内容")
        applyCodexResume(resume)
    }

    private suspend fun startCodexTurnLocked(threadId: String, promptText: String) {
        val result = awaitCodexRequest(
            method = "turn/start",
            params = buildCodexTurnStartParams(threadId, promptText),
        )
        val turnId = result["turn"].jsonObjectOrNull()?.get("id").contentOrNull()
        markThreadRunning(threadId)
        if (!turnId.isNullOrBlank()) {
            ensureStreamingAssistantEntry(turnId)
            _uiState.update {
                it.copy(
                    currentTurnId = turnId,
                    isStreaming = true,
                )
            }
        }
    }

    private fun applyCodexResume(resume: CodexThreadResume) {
        val summary = _uiState.value.sessionSummaries.firstOrNull { it.id == resume.id }
            ?: RemoteSessionSummary(
                id = resume.id,
                title = resume.preview ?: string(R.string.thread_title_unnamed),
                cwd = resume.cwd,
                updatedAtEpochSeconds = null,
                isRunning = !resume.activeTurnId.isNullOrBlank(),
            )

        val entries = resume.entries.toMutableList()
        if (!resume.activeTurnId.isNullOrBlank() && entries.none { it.id == streamingEntryId(resume.activeTurnId) }) {
            entries += TranscriptEntry(
                id = streamingEntryId(resume.activeTurnId),
                role = TranscriptRole.Assistant,
                title = string(R.string.codex_role_assistant),
                text = "",
                turnId = resume.activeTurnId,
                isStreaming = true,
            )
        }

        _uiState.update {
            it.copy(
                sessionId = resume.id,
                sessionSummaries = listOf(
                    summary.copy(isRunning = !resume.activeTurnId.isNullOrBlank())
                ) + it.sessionSummaries.filter { item -> item.id != resume.id },
                workingDirectory = resume.cwd ?: it.workingDirectory,
                transcriptEntries = entries,
                currentTurnId = resume.activeTurnId,
                isStreaming = !resume.activeTurnId.isNullOrBlank(),
                pendingApprovals = emptyList(),
                lastAssistantMessage = entries.lastOrNull { entry -> entry.role == TranscriptRole.Assistant }?.text.orEmpty(),
            )
        }
        persistConnectionState()
    }

    private fun appendLocalCodexUserPrompt(threadId: String, promptText: String) {
        val entry = TranscriptEntry(
            id = "local-user-${System.nanoTime()}",
            role = TranscriptRole.User,
            title = string(R.string.codex_role_user),
            text = promptText,
        )
        _uiState.update {
            it.copy(
                sessionId = threadId,
                transcriptEntries = it.transcriptEntries + entry,
            )
        }
    }

    private fun ensureStreamingAssistantEntry(turnId: String) {
        val entryId = streamingEntryId(turnId)
        val current = _uiState.value.transcriptEntries
        if (current.any { it.id == entryId }) {
            _uiState.update { state ->
                state.copy(
                    transcriptEntries = state.transcriptEntries.map { entry ->
                        if (entry.id == entryId) {
                            entry.copy(isStreaming = true)
                        } else {
                            entry
                        }
                    }
                )
            }
            return
        }

        upsertTranscriptEntry(
            TranscriptEntry(
                id = entryId,
                role = TranscriptRole.Assistant,
                title = string(R.string.codex_role_assistant),
                text = "",
                turnId = turnId,
                isStreaming = true,
            )
        )
    }

    private fun appendAssistantDelta(turnId: String, delta: String) {
        if (delta.isBlank()) {
            return
        }
        val entryId = streamingEntryId(turnId)
        _uiState.update { state ->
            val updated = state.transcriptEntries.toMutableList()
            val index = updated.indexOfFirst { it.id == entryId }
            val nextAssistantText = if (index >= 0) {
                val existing = updated[index]
                val merged = existing.copy(
                    text = existing.text + delta,
                    isStreaming = true,
                )
                updated[index] = merged
                merged.text
            } else {
                val created = TranscriptEntry(
                    id = entryId,
                    role = TranscriptRole.Assistant,
                    title = string(R.string.codex_role_assistant),
                    text = delta,
                    turnId = turnId,
                    isStreaming = true,
                )
                updated += created
                created.text
            }
            state.copy(
                transcriptEntries = updated,
                lastAssistantMessage = nextAssistantText,
            )
        }
    }

    private fun applyLiveTranscriptEntry(entry: TranscriptEntry) {
        if (entry.role == TranscriptRole.Assistant && !entry.turnId.isNullOrBlank()) {
            val streamId = streamingEntryId(entry.turnId)
            _uiState.update { state ->
                val updated = state.transcriptEntries.toMutableList()
                val streamIndex = updated.indexOfFirst { it.id == streamId }
                if (streamIndex >= 0) {
                    val current = updated[streamIndex]
                    updated[streamIndex] = current.copy(
                        text = if (entry.text.length > current.text.length) entry.text else current.text,
                        isStreaming = state.currentTurnId == entry.turnId && state.isStreaming,
                    )
                } else {
                    updated += entry
                }
                state.copy(
                    transcriptEntries = updated,
                    lastAssistantMessage = updated.lastOrNull { item -> item.role == TranscriptRole.Assistant }?.text.orEmpty(),
                )
            }
            return
        }

        upsertTranscriptEntry(entry)
    }

    private fun upsertTranscriptEntry(entry: TranscriptEntry) {
        _uiState.update { state ->
            val updated = state.transcriptEntries.toMutableList()
            val index = updated.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                updated[index] = entry
            } else {
                updated += entry
            }
            state.copy(
                transcriptEntries = updated,
                lastAssistantMessage = updated.lastOrNull { item -> item.role == TranscriptRole.Assistant }?.text.orEmpty(),
            )
        }
    }

    private fun markTurnCompleted(
        turnId: String?,
        threadId: String?,
    ) {
        if (!threadId.isNullOrBlank()) {
            markThreadStopped(
                threadId = threadId,
                turnId = turnId,
                stopReason = "completed",
                markAsCompleted = true,
            )
            return
        }

        _uiState.update { state ->
            val updated = state.transcriptEntries.map { entry ->
                if (!turnId.isNullOrBlank() && entry.turnId == turnId) {
                    entry.copy(isStreaming = false)
                } else if (turnId.isNullOrBlank() && entry.isStreaming) {
                    entry.copy(isStreaming = false)
                } else {
                    entry
                }
            }
            state.copy(
                transcriptEntries = updated,
                currentTurnId = if (state.currentTurnId == turnId) null else state.currentTurnId,
                isStreaming = false,
                lastStopReason = "completed",
                lastCompletedThreadId = threadId ?: state.lastCompletedThreadId,
            )
        }
    }

    private fun markThreadRunning(threadId: String) {
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        _uiState.update { state ->
            val summary = state.sessionSummaries.firstOrNull { it.id == threadId } ?: return@update state
            val updatedSummary = summary.copy(
                isRunning = true,
                updatedAtEpochSeconds = nowEpochSeconds,
            )
            state.copy(
                sessionSummaries = listOf(updatedSummary) + state.sessionSummaries.filter { it.id != threadId },
                lastCompletedThreadId = if (state.lastCompletedThreadId == threadId) null else state.lastCompletedThreadId,
            )
        }
    }

    private fun markThreadStopped(
        threadId: String,
        turnId: String?,
        stopReason: String,
        markAsCompleted: Boolean,
    ) {
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        _uiState.update { state ->
            val affectsCurrentThread = state.sessionId == threadId
            val affectsCurrentTurn = !turnId.isNullOrBlank() && state.currentTurnId == turnId
            val shouldStopCurrentStream = affectsCurrentThread || affectsCurrentTurn

            val updatedEntries = if (shouldStopCurrentStream) {
                state.transcriptEntries.map { entry ->
                    if (!turnId.isNullOrBlank() && entry.turnId == turnId) {
                        entry.copy(isStreaming = false)
                    } else if (turnId.isNullOrBlank() && entry.isStreaming) {
                        entry.copy(isStreaming = false)
                    } else {
                        entry
                    }
                }
            } else {
                state.transcriptEntries
            }

            val updatedSummaries = state.sessionSummaries.map { summary ->
                if (summary.id == threadId) {
                    summary.copy(
                        isRunning = false,
                        updatedAtEpochSeconds = nowEpochSeconds,
                    )
                } else {
                    summary
                }
            }.sortedByDescending { it.updatedAtEpochSeconds ?: 0L }

            state.copy(
                sessionSummaries = updatedSummaries,
                transcriptEntries = updatedEntries,
                currentTurnId = if (shouldStopCurrentStream) null else state.currentTurnId,
                isStreaming = if (shouldStopCurrentStream) false else state.isStreaming,
                lastStopReason = if (shouldStopCurrentStream) stopReason else state.lastStopReason,
                lastCompletedThreadId = if (markAsCompleted) threadId else state.lastCompletedThreadId,
            )
        }
    }

    private fun notifyThreadCompleted(threadId: String) {
        val summary = _uiState.value.sessionSummaries.firstOrNull { it.id == threadId }
        val title = notificationThreadTitle(summary, threadId)
        val description = summary?.cwd?.takeIf { it.isNotBlank() }
            ?: string(R.string.notification_thread_completed_default_description)
        OpenConnectNotifications.notifyThreadCompleted(
            threadId = threadId,
            threadTitle = title,
            description = description,
        )
    }

    private fun notifyThreadFailed(
        threadId: String,
        errorMessage: String,
    ) {
        val summary = _uiState.value.sessionSummaries.firstOrNull { it.id == threadId }
        val title = notificationThreadTitle(summary, threadId)
        OpenConnectNotifications.notifyThreadFailed(
            threadId = threadId,
            threadTitle = title,
            description = errorMessage,
        )
    }

    private fun notificationThreadTitle(
        summary: RemoteSessionSummary?,
        threadId: String,
    ): String {
        val projectName = summary?.cwd
            ?.trim()
            ?.trimEnd('/', '\\')
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
        return projectName
            ?: summary?.title?.trim()?.takeIf { it.isNotBlank() }
            ?: string(R.string.label_thread_short, threadId.take(8))
    }

    private fun upsertSessionSummary(summary: RemoteSessionSummary) {
        _uiState.update { state ->
            state.copy(
                sessionSummaries = listOf(summary) + state.sessionSummaries.filter { it.id != summary.id }
            )
        }
    }

    private fun respondToCodexApproval(
        requestId: JsonElement,
        decision: String,
    ) {
        codexTransport.sendResponse(
            id = requestId,
            result = buildJsonObject {
                put("decision", decision)
            },
        )
        _uiState.update { state ->
            state.copy(
                pendingApprovals = state.pendingApprovals.filter { it.requestId != requestId }
            )
        }
        addLocalLog("审批", "已${if (decision == "accept") "批准" else "拒绝"}请求 ${requestId.contentOrNull() ?: requestId}", LogKind.Event)
    }

    private fun streamingEntryId(turnId: String): String = "stream-$turnId"

    private fun isDangerousCommand(command: String?): Boolean {
        val normalized = command?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        val dangerousPatterns = listOf(
            "rm -rf /",
            "sudo rm -rf /",
            "rm -rf ~",
            "sudo rm -rf ~",
            "mkfs",
            "dd if=",
        )
        return dangerousPatterns.any { normalized.contains(it) }
    }

    private suspend fun handleBridgeChatCompletion(request: ChatCompletionRequest): ChatCompletionResult {
        val state = _uiState.value
        if (state.serverMode != ServerMode.ACP) {
            throw BridgeHttpException(400, "当前 Bridge 只支持 ACP 模式。")
        }
        if (!state.isConnected) {
            throw BridgeHttpException(503, "ACP 尚未连接，请先在 App 内点击连接。")
        }
        if (!state.isInitialized) {
            throw BridgeHttpException(503, "ACP 尚未初始化，请先在 App 内点击 Initialize。")
        }
        if (request.messages.isEmpty()) {
            throw BridgeHttpException(400, "messages 不能为空")
        }

        val promptText = buildBridgePrompt(request)
        val sessionId = state.sessionId ?: throw BridgeHttpException(400, "请先在 ACP 模式下创建会话。")
        val result = promptMutex.withLock {
            runAcpPromptTurnLocked(sessionId = sessionId, promptText = promptText)
        }
        val outputText = result.text.ifBlank {
            "任务已完成，但 Agent 没有返回可显示的文本。stopReason=${result.stopReason}"
        }
        addLocalLog("WeClaw", "已完成一轮 chat/completions 请求，stopReason=${result.stopReason}", LogKind.Event)
        _uiState.update {
            it.copy(
                lastAssistantMessage = outputText,
                lastStopReason = result.stopReason,
            )
        }
        return ChatCompletionResult(
            model = request.model?.takeIf { it.isNotBlank() } ?: "openconnect-acp",
            content = outputText,
            stopReason = result.stopReason,
        )
    }

    private fun buildBridgePrompt(request: ChatCompletionRequest): String {
        val normalizedMessages = request.messages.mapNotNull { message ->
            val text = extractBridgeMessageContent(message.content).trim()
            if (text.isBlank()) {
                null
            } else {
                (message.role?.lowercase() ?: "user") to text
            }
        }

        if (normalizedMessages.isEmpty()) {
            throw BridgeHttpException(400, "messages 中没有可用文本")
        }

        if (normalizedMessages.size == 1 && normalizedMessages.first().first == "user") {
            return normalizedMessages.first().second
        }

        return buildString {
            appendLine("下面是来自 WeClaw / 微信入口的对话记录。")
            appendLine("请基于完整上下文继续处理，最后一条 user 是当前要执行的最新任务。")
            appendLine()
            normalizedMessages.forEach { (role, text) ->
                append(role)
                appendLine(":")
                appendLine(text)
                appendLine()
            }
        }.trim()
    }

    private fun buildBridgeHealthPayload(): JsonObject {
        val state = _uiState.value
        return buildJsonObject {
            put("ok", true)
            put("bridgeStatus", state.bridgeStatus)
            put("acpConnected", state.isConnected)
            put("initializationStatus", state.initializationStatus)
            put("endpoint", state.endpoint)
            put("workingDirectory", state.workingDirectory)
            state.bridgePublicUrl?.let { put("publicUrl", it) }
        }
    }

    private fun extractAcpTextContent(element: JsonElement?): String? {
        val content = element.jsonObjectOrNull() ?: return null
        if (content["type"].contentOrNull() == "text") {
            return content["text"].contentOrNull()
        }
        return null
    }

    private fun extractAcpToolContent(element: JsonElement?): String {
        val parts = mutableListOf<String>()

        when (element) {
            null -> Unit
            else -> {
                element.jsonArrayOrNull()?.forEach { entry ->
                    val text = entry.jsonObjectOrNull()
                        ?.get("content")
                        .jsonObjectOrNull()
                        ?.takeIf { it["type"].contentOrNull() == "text" }
                        ?.get("text")
                        .contentOrNull()
                    if (!text.isNullOrBlank()) {
                        parts += text
                    }
                } ?: extractAcpTextContent(element)?.let { parts += it }
            }
        }

        return parts.joinToString("\n").trim()
    }

    private fun extractBridgeMessageContent(element: JsonElement?): String {
        return when (element) {
            null -> ""
            is JsonPrimitive -> element.contentOrNull().orEmpty()
            else -> {
                element.jsonArrayOrNull()
                    ?.mapNotNull { part ->
                        val partObject = part.jsonObjectOrNull()
                        when (partObject?.get("type").contentOrNull()) {
                            "text", "input_text" -> partObject?.get("text").contentOrNull()
                            else -> null
                        }
                    }
                    ?.joinToString("\n")
                    .orEmpty()
            }
        }
    }

    private fun parseBridgeListenAddress(rawValue: String): SocketBinding {
        val sanitized = rawValue.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
        require(sanitized.isNotBlank()) { "请填写 Bridge 监听地址，例如 0.0.0.0:18080" }

        val lastColon = sanitized.lastIndexOf(':')
        val host: String
        val portText: String
        if (lastColon < 0) {
            host = "0.0.0.0"
            portText = sanitized
        } else {
            host = sanitized.substring(0, lastColon).ifBlank { "0.0.0.0" }
            portText = sanitized.substring(lastColon + 1)
        }

        val port = portText.toIntOrNull()
            ?: throw IllegalArgumentException("无效端口: $portText")
        require(port in 1..65535) { "端口必须在 1..65535 之间" }
        return SocketBinding(host = host, port = port)
    }

    private fun buildBridgePublicUrl(binding: SocketBinding): String? {
        return when (binding.host) {
            "0.0.0.0", "::", "[::]" -> detectLocalIpv4()?.let {
                "http://$it:${binding.port}/v1/chat/completions"
            }

            "127.0.0.1", "localhost" -> "http://127.0.0.1:${binding.port}/v1/chat/completions"
            else -> "http://${binding.host}:${binding.port}/v1/chat/completions"
        }
    }

    private fun detectLocalIpv4(): String? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()
        }.getOrNull() ?: return null

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                val hostAddress = address.hostAddress ?: continue
                if (!address.isLoopbackAddress && !hostAddress.contains(':')) {
                    return hostAddress
                }
            }
        }
        return null
    }

    private fun addLocalLog(label: String, body: String, kind: LogKind) {
        val sanitizedBody = sanitizeUiLogBody(body)
        val entry = LogEntry(
            id = nextLogId++,
            label = label,
            body = sanitizedBody,
            kind = kind,
        )
        _uiState.update { state ->
            val nextLogs = (state.logs + entry).takeLast(300)
            state.copy(logs = nextLogs)
        }
        writeToLogcat(
            entry = entry.copy(body = body),
        )
    }

    private fun writeToLogcat(entry: LogEntry) {
        val prefix = "[${entry.kind.name}][${entry.label}] "
        val rawText = (prefix + entry.body).ifBlank { prefix }
        val text = if (rawText.length > LOGCAT_MAX_TOTAL) {
            rawText.take(LOGCAT_MAX_TOTAL) + "\n...[truncated ${rawText.length - LOGCAT_MAX_TOTAL} chars]"
        } else {
            rawText
        }
        val logger: (String, String) -> Int = when (entry.kind) {
            LogKind.Error -> Log::e
            LogKind.Event -> Log::i
            LogKind.Incoming -> Log::d
            LogKind.Outgoing -> Log::d
        }

        if (text.length <= LOGCAT_CHUNK_SIZE) {
            logger(DEBUG_TAG, text)
            return
        }

        text.chunked(LOGCAT_CHUNK_SIZE).forEachIndexed { index, chunk ->
            logger(DEBUG_TAG, "$chunk (${index + 1}/${(text.length + LOGCAT_CHUNK_SIZE - 1) / LOGCAT_CHUNK_SIZE})")
        }
    }

    private fun sanitizeUiLogBody(body: String): String {
        val normalized = body.trim()
        if (normalized.length <= UI_LOG_BODY_MAX) {
            return normalized
        }
        return normalized.take(UI_LOG_BODY_MAX) +
            "\n...[已截断 ${normalized.length - UI_LOG_BODY_MAX} 个字符，完整内容仅保留在调试日志]"
    }

    private fun parseServerMode(rawValue: String?): ServerMode? {
        return when (rawValue?.trim()?.lowercase()) {
            "codex", "codexappserver", "app-server", "appserver" -> ServerMode.CodexAppServer
            "acp" -> ServerMode.ACP
            else -> null
        }
    }

    private fun parsePermissionPreset(rawValue: String?): CodexPermissionPreset? {
        return when (rawValue?.trim()?.lowercase()) {
            "safe", "default", "defaultsafe", "workspace", "workspacewrite" -> CodexPermissionPreset.Safe
            "full", "fullaccess", "danger", "dangerfullaccess", "never" -> CodexPermissionPreset.FullAccess
            else -> null
        }
    }

    private fun parseBooleanQueryValue(rawValue: String?): Boolean {
        return when (rawValue?.trim()?.lowercase()) {
            "1", "true", "yes", "y", "on" -> true
            else -> false
        }
    }

    private fun maybeReconnectOnLaunch() {
        val state = _uiState.value
        if (!state.autoReconnectEnabled || !shouldStayConnected || state.endpoint.isBlank()) {
            return
        }

        pendingAutoInitialize = true
        reconnectAttempt = 0
        viewModelScope.launch {
            delay(350)
            addLocalLog("重连", "已恢复上次连接配置，正在自动连接。", LogKind.Event)
            connect()
        }
    }

    private fun scheduleReconnect(reason: String?) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            while (
                shouldStayConnected &&
                _uiState.value.autoReconnectEnabled &&
                !_uiState.value.isConnected &&
                _uiState.value.endpoint.isNotBlank()
            ) {
                reconnectAttempt += 1
                val delaySeconds = reconnectDelaySeconds(reconnectAttempt)
                _uiState.update {
                    it.copy(
                        connectionStatus = string(R.string.status_connection_waiting_reconnect),
                        reconnectStatus = reconnectStatusText(
                            enabled = it.autoReconnectEnabled,
                            shouldKeepConnected = shouldStayConnected,
                            isConnected = false,
                            attempt = reconnectAttempt,
                            waitingSeconds = delaySeconds,
                        ),
                        isReconnectScheduled = true,
                    )
                }
                persistConnectionState()
                addLocalLog(
                    "重连",
                    "连接已断开${reason?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}，$delaySeconds 秒后自动重试第 $reconnectAttempt 次。",
                    LogKind.Event,
                )
                delay(delaySeconds * 1000L)
                if (
                    !shouldStayConnected ||
                    !_uiState.value.autoReconnectEnabled ||
                    _uiState.value.isConnected ||
                    _uiState.value.endpoint.isBlank()
                ) {
                    break
                }
                pendingAutoInitialize = true
                connect(preserveReconnectAttempt = true)
            }
        }
    }

    private fun reconnectDelaySeconds(attempt: Int): Int =
        when {
            attempt <= 1 -> 2
            attempt == 2 -> 4
            attempt == 3 -> 8
            attempt == 4 -> 15
            else -> 30
        }

    private fun reconnectStatusText(
        enabled: Boolean,
        shouldKeepConnected: Boolean,
        isConnected: Boolean,
        attempt: Int,
        isConnecting: Boolean = false,
        waitingSeconds: Int? = null,
    ): String {
        if (!enabled) {
            return string(R.string.status_reconnect_disabled)
        }
        if (isConnected) {
            return string(R.string.status_reconnect_online)
        }
        if (isConnecting) {
            return if (shouldKeepConnected) {
                string(R.string.status_reconnect_connecting_keep)
            } else {
                string(R.string.status_reconnect_connecting_once)
            }
        }
        if (waitingSeconds != null && shouldKeepConnected) {
            return string(R.string.status_reconnect_waiting_seconds, waitingSeconds, attempt)
        }
        return if (shouldKeepConnected) {
            string(R.string.status_reconnect_waiting_restore)
        } else {
            string(R.string.status_reconnect_waiting_manual)
        }
    }

    private fun restorePersistedConnectionState() {
        val savedMode = preferences.getString(KEY_SERVER_MODE, null)
            ?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() }
            ?: ServerMode.CodexAppServer
        val savedAppLanguage = AppLanguageManager.load(app)
        val savedPermissionPreset = preferences.getString(KEY_PERMISSION_PRESET, null)
            ?.let { runCatching { CodexPermissionPreset.valueOf(it) }.getOrNull() }
            ?: CodexPermissionPreset.Safe
        val autoReconnectEnabled = preferences.getBoolean(KEY_AUTO_RECONNECT_ENABLED, true)
        shouldStayConnected = preferences.getBoolean(KEY_SHOULD_STAY_CONNECTED, false)
        pendingResumeSessionId = preferences.getString(KEY_LAST_SESSION_ID, null)
            ?.takeIf { it.isNotBlank() }

        _uiState.value = _uiState.value.copy(
            serverMode = savedMode,
            appLanguage = savedAppLanguage,
            endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
            bearerToken = preferences.getString(KEY_BEARER_TOKEN, "").orEmpty(),
            cfAccessClientId = preferences.getString(KEY_CF_ACCESS_CLIENT_ID, "").orEmpty(),
            cfAccessClientSecret = preferences.getString(KEY_CF_ACCESS_CLIENT_SECRET, "").orEmpty(),
            workingDirectory = preferences.getString(KEY_WORKING_DIRECTORY, "").orEmpty(),
            codexPermissionPreset = savedPermissionPreset,
            autoReconnectEnabled = autoReconnectEnabled,
            reconnectStatus = reconnectStatusText(
                enabled = autoReconnectEnabled,
                shouldKeepConnected = shouldStayConnected,
                isConnected = false,
                attempt = reconnectAttempt,
            ),
            isReconnectScheduled = false,
        )
    }

    private fun persistConnectionState(state: AcpUiState = _uiState.value) {
        preferences.edit()
            .putString(KEY_SERVER_MODE, state.serverMode.name)
            .putString(KEY_ENDPOINT, state.endpoint)
            .putString(KEY_BEARER_TOKEN, state.bearerToken)
            .putString(KEY_CF_ACCESS_CLIENT_ID, state.cfAccessClientId)
            .putString(KEY_CF_ACCESS_CLIENT_SECRET, state.cfAccessClientSecret)
            .putString(KEY_WORKING_DIRECTORY, state.workingDirectory)
            .putString(KEY_PERMISSION_PRESET, state.codexPermissionPreset.name)
            .putBoolean(KEY_AUTO_RECONNECT_ENABLED, state.autoReconnectEnabled)
            .putBoolean(KEY_SHOULD_STAY_CONNECTED, shouldStayConnected)
            .putString(KEY_LAST_SESSION_ID, state.sessionId.orEmpty())
            .apply()
    }

    private fun Uri.firstQueryParameter(vararg names: String): String? {
        names.firstNotNullOfOrNull { name ->
            getQueryParameter(name)?.takeIf { it.isNotBlank() }
        }?.let { return it }

        val expectedNames = names.map { it.lowercase() }.toSet()
        val rawQuery = encodedQuery ?: toString().substringAfter('?', "")
        if (rawQuery.isBlank()) {
            return null
        }

        return rawQuery
            .split("&")
            .firstNotNullOfOrNull { part ->
                if (part.isBlank()) {
                    return@firstNotNullOfOrNull null
                }
                val separatorIndex = part.indexOf('=')
                val rawName = if (separatorIndex >= 0) {
                    part.substring(0, separatorIndex)
                } else {
                    part
                }
                val rawValue = if (separatorIndex >= 0) {
                    part.substring(separatorIndex + 1)
                } else {
                    ""
                }
                val decodedName = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
                    .trim()
                    .lowercase()
                if (decodedName !in expectedNames) {
                    return@firstNotNullOfOrNull null
                }
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
                    .takeIf { it.isNotBlank() }
            }
    }
}
