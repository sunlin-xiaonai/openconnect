package com.agmente.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agmente.android.codex.CodexApprovalRequest
import com.agmente.android.codex.CodexPermissionPreset
import com.agmente.android.codex.RemoteSessionSummary
import com.agmente.android.codex.ServerMode
import com.agmente.android.codex.TranscriptEntry
import com.agmente.android.codex.TranscriptRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val sessionTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private const val TRANSCRIPT_PAGE_SIZE = 10
private const val RECENT_LOG_COUNT = 10
private const val THREAD_LIST_PAGE_SIZE = 2

private enum class HomeTab {
    Threads,
    Logs,
}

private enum class ThreadScope {
    All,
    CurrentProject,
}

private fun normalizedProjectPath(path: String?): String? =
    path?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.trimEnd('/', '\\')

private fun projectNameFromPath(path: String?): String? {
    val normalizedPath = normalizedProjectPath(path) ?: return null
    return normalizedPath
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .takeIf { it.isNotBlank() }
}

private fun threadDisplayTitle(
    cwd: String?,
    fallbackTitle: String,
): String =
    projectNameFromPath(cwd)
        ?: fallbackTitle.trim().takeIf { it.isNotBlank() }
        ?: "未命名线程"

private fun threadPreviewLabel(
    rawTitle: String,
    cwd: String?,
): String? {
    val normalizedTitle = rawTitle.trim().takeIf { it.isNotBlank() } ?: return null
    return normalizedTitle.takeIf { it != projectNameFromPath(cwd) }
}

private fun topBarConnectionLabel(uiState: AcpUiState): String {
    val prefix = if (uiState.serverMode == ServerMode.CodexAppServer) "Codex" else "ACP"
    return when {
        uiState.isConnected && uiState.initializationStatus == "已初始化" -> "$prefix 在线"
        uiState.isConnected -> "$prefix 已连"
        uiState.autoReconnectEnabled && uiState.reconnectStatus.contains("重连") -> "$prefix 重连中"
        else -> "$prefix 未连"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgmenteApp(
    viewModel: AcpViewModel,
    onScanPairCode: () -> Unit,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showManualConfig by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Threads) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var newThreadOpen by rememberSaveable { mutableStateOf(false) }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThreadTitle by rememberSaveable { mutableStateOf("") }
    var pendingOpenCreatedThread by rememberSaveable { mutableStateOf(false) }
    var pendingCreatedFromSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val isCodexMode = uiState.serverMode == ServerMode.CodexAppServer
    val selectedThreadSummary = uiState.sessionSummaries.firstOrNull { it.id == selectedThreadId }
    val canNavigateBack =
        settingsOpen ||
            (isCodexMode && newThreadOpen) ||
            (isCodexMode && selectedThreadId != null) ||
            selectedTab != HomeTab.Threads

    BackHandler(enabled = canNavigateBack) {
        when {
            settingsOpen -> settingsOpen = false
            isCodexMode && newThreadOpen -> newThreadOpen = false
            isCodexMode && selectedThreadId != null -> {
                selectedThreadId = null
                pendingOpenCreatedThread = false
                pendingCreatedFromSessionId = null
            }
            selectedTab != HomeTab.Threads -> selectedTab = HomeTab.Threads
        }
    }

    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) {
            showManualConfig = false
        }
    }

    LaunchedEffect(selectedThreadSummary?.title, selectedThreadId) {
        if (selectedThreadId != null && !selectedThreadSummary?.title.isNullOrBlank()) {
            selectedThreadTitle = selectedThreadSummary?.title.orEmpty()
        }
    }

    LaunchedEffect(
        uiState.sessionId,
        pendingOpenCreatedThread,
        pendingCreatedFromSessionId,
        uiState.sessionSummaries,
    ) {
        val currentSessionId = uiState.sessionId
        if (
            pendingOpenCreatedThread &&
            !currentSessionId.isNullOrBlank() &&
            currentSessionId != pendingCreatedFromSessionId
        ) {
            selectedThreadId = currentSessionId
            selectedThreadTitle = uiState.sessionSummaries
                .firstOrNull { it.id == currentSessionId }
                ?.title
                .orEmpty()
            pendingOpenCreatedThread = false
            pendingCreatedFromSessionId = null
        }
    }

    LaunchedEffect(uiState.pendingThreadNavigationId, isCodexMode, uiState.sessionSummaries) {
        val targetThreadId = uiState.pendingThreadNavigationId
        if (!isCodexMode || targetThreadId.isNullOrBlank()) {
            return@LaunchedEffect
        }

        settingsOpen = false
        newThreadOpen = false
        selectedTab = HomeTab.Threads
        pendingOpenCreatedThread = false
        pendingCreatedFromSessionId = null
        selectedThreadId = targetThreadId
        selectedThreadTitle = uiState.sessionSummaries
            .firstOrNull { it.id == targetThreadId }
            ?.title
            .orEmpty()
        viewModel.consumePendingThreadNavigation(targetThreadId)
    }

    if (isCodexMode && selectedThreadId != null) {
        CodexThreadDetailScreen(
            uiState = uiState,
            threadId = selectedThreadId.orEmpty(),
            threadTitle = selectedThreadSummary?.title ?: selectedThreadTitle.ifBlank { "线程详情" },
            onBack = {
                selectedThreadId = null
                pendingOpenCreatedThread = false
                pendingCreatedFromSessionId = null
            },
            onRefresh = {
                selectedThreadId?.let(viewModel::openSession)
            },
            onPromptChange = viewModel::updatePrompt,
            onSend = viewModel::sendPrompt,
            onApprove = viewModel::approveRequest,
            onDecline = viewModel::declineRequest,
        )
        return
    }

    if (settingsOpen) {
        SettingsScreen(
            uiState = uiState,
            notificationsGranted = notificationsGranted,
            showManualConfig = showManualConfig,
            onBack = { settingsOpen = false },
            onScanPairCode = onScanPairCode,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onToggleManualConfig = { showManualConfig = !showManualConfig },
            onServerModeChange = viewModel::updateServerMode,
            onEndpointChange = viewModel::updateEndpoint,
            onBearerTokenChange = viewModel::updateBearerToken,
            onCfAccessClientIdChange = viewModel::updateCfAccessClientId,
            onCfAccessClientSecretChange = viewModel::updateCfAccessClientSecret,
            onWorkingDirectoryChange = viewModel::updateWorkingDirectory,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onInitialize = viewModel::initialize,
            onCreateSession = viewModel::createSession,
            onAutoReconnectEnabledChange = viewModel::updateAutoReconnectEnabled,
            onPermissionPresetChange = viewModel::updateCodexPermissionPreset,
            onListenAddressChange = viewModel::updateBridgeListenAddress,
            onApiTokenChange = viewModel::updateBridgeApiToken,
            onStartBridge = viewModel::startBridgeServer,
            onStopBridge = viewModel::stopBridgeServer,
        )
        return
    }

    if (isCodexMode && newThreadOpen) {
        NewThreadScreen(
            uiState = uiState,
            onBack = { newThreadOpen = false },
            onCreateThread = { directory ->
                newThreadOpen = false
                selectedThreadId = null
                selectedThreadTitle = ""
                pendingOpenCreatedThread = true
                pendingCreatedFromSessionId = uiState.sessionId
                viewModel.updateWorkingDirectory(directory.orEmpty())
                viewModel.createSession()
            },
        )
        return
    }

    MainHomeScreen(
        uiState = uiState,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onOpenSettings = { settingsOpen = true },
        onRefreshSessions = viewModel::refreshSessions,
        onOpenNewThreadSetup = { newThreadOpen = true },
        onOpenSession = { summary ->
            pendingOpenCreatedThread = false
            pendingCreatedFromSessionId = null
            selectedThreadId = summary.id
            selectedThreadTitle = summary.title
            viewModel.openSession(summary.id)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainHomeScreen(
    uiState: AcpUiState,
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
    onOpenSession: (RemoteSessionSummary) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TopBarConnectionBadge(uiState = uiState)
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Threads,
                    onClick = { onTabSelected(HomeTab.Threads) },
                    icon = {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                        )
                    },
                    label = { Text("线程") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Logs,
                    onClick = { onTabSelected(HomeTab.Logs) },
                    icon = {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                        )
                    },
                    label = { Text("日志") },
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            HomeTab.Threads -> {
                ThreadsHomeTab(
                    uiState = uiState,
                    onOpenSettings = onOpenSettings,
                    onRefreshSessions = onRefreshSessions,
                    onOpenNewThreadSetup = onOpenNewThreadSetup,
                    onOpenSession = onOpenSession,
                    modifier = Modifier.padding(padding),
                )
            }

            HomeTab.Logs -> {
                LogsHomeTab(
                    uiState = uiState,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun ThreadsHomeTab(
    uiState: AcpUiState,
    onOpenSettings: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
    onOpenSession: (RemoteSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentProjectPath = uiState.workingDirectory.trim().takeIf { it.isNotBlank() }
    val currentProjectThreads = if (currentProjectPath == null) {
        emptyList()
    } else {
        uiState.sessionSummaries.filter { it.cwd == currentProjectPath }
    }
    var selectedScope by rememberSaveable(currentProjectPath) {
        mutableStateOf(ThreadScope.All)
    }
    val filteredThreads = when (selectedScope) {
        ThreadScope.All -> uiState.sessionSummaries
        ThreadScope.CurrentProject -> currentProjectThreads
    }
    var visibleThreadCount by rememberSaveable(
        uiState.serverMode,
        currentProjectPath,
        selectedScope,
        filteredThreads.size,
    ) {
        mutableStateOf(THREAD_LIST_PAGE_SIZE)
    }
    val visibleThreads = filteredThreads.take(visibleThreadCount)
    val remainingThreadCount = (filteredThreads.size - visibleThreadCount).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            item {
                CompactThreadToolbar(
                    selectedScope = selectedScope,
                    allCount = uiState.sessionSummaries.size,
                    currentProjectCount = currentProjectThreads.size,
                    currentProjectPath = currentProjectPath,
                    onRefreshSessions = onRefreshSessions,
                    onOpenNewThreadSetup = onOpenNewThreadSetup,
                    onScopeSelected = { selectedScope = it },
                )
            }

            if (!uiState.isConnected || uiState.initializationStatus != "已初始化") {
                item {
                    ThreadSetupHintCard(
                        uiState = uiState,
                        onOpenSettings = onOpenSettings,
                    )
                }
            } else if (uiState.sessionSummaries.isEmpty()) {
                item {
                    ThreadEmptyCard(
                        onRefreshSessions = onRefreshSessions,
                        onOpenNewThreadSetup = onOpenNewThreadSetup,
                    )
                }
            } else {
                if (visibleThreads.isEmpty()) {
                    item {
                        ThreadSectionCard(
                            title = "这个项目还没有线程",
                            description = "你可以先新建一个线程，或者切回“全部线程”查看其他项目。",
                        )
                    }
                } else {
                    items(visibleThreads, key = { it.id }) { summary ->
                        ThreadMiniCard(
                            summary = summary,
                            isSelected = summary.id == uiState.sessionId,
                            isRunning = uiState.isStreaming && summary.id == uiState.sessionId,
                            isRecentlyCompleted = summary.id == uiState.lastCompletedThreadId,
                            onOpen = { onOpenSession(summary) },
                        )
                    }
                }

                if (remainingThreadCount > 0) {
                    item {
                        LoadMoreThreadsCard(
                            remainingThreadCount = remainingThreadCount,
                            onLoadMore = {
                                visibleThreadCount = (visibleThreadCount + THREAD_LIST_PAGE_SIZE)
                                    .coerceAtMost(filteredThreads.size)
                            },
                        )
                    }
                }
            }
        } else {
            item {
                AcpHomeCard(
                    uiState = uiState,
                    onOpenSettings = onOpenSettings,
                )
            }

            if (uiState.lastAssistantMessage.isNotBlank() || uiState.lastStopReason != null) {
                item {
                    LatestReplyCard(uiState = uiState)
                }
            }
        }
    }
}

@Composable
private fun TopBarConnectionBadge(uiState: AcpUiState) {
    val isReady = uiState.isConnected && uiState.initializationStatus == "已初始化"
    val isConnected = uiState.isConnected
    val containerColor = when {
        isReady -> MaterialTheme.colorScheme.secondaryContainer
        isConnected -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val dotColor = when {
        isReady -> MaterialTheme.colorScheme.primary
        isConnected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "●",
                color = dotColor,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = topBarConnectionLabel(uiState),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactThreadToolbar(
    selectedScope: ThreadScope,
    allCount: Int,
    currentProjectCount: Int,
    currentProjectPath: String?,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
    onScopeSelected: (ThreadScope) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = when (selectedScope) {
                        ThreadScope.All -> "线程列表"
                        ThreadScope.CurrentProject -> projectNameFromPath(currentProjectPath) ?: "当前项目"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (selectedScope) {
                        ThreadScope.All -> "按最近活跃排序，共 $allCount 个线程"
                        ThreadScope.CurrentProject -> "当前项目下 $currentProjectCount 个线程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallToolbarButton(
                    label = "刷新",
                    onClick = onRefreshSessions,
                    outlined = true,
                )
                SmallToolbarButton(
                    label = "新建",
                    onClick = onOpenNewThreadSetup,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScopePill(
                label = "全部 $allCount",
                selected = selectedScope == ThreadScope.All,
                onClick = { onScopeSelected(ThreadScope.All) },
            )
            if (currentProjectPath != null) {
                ScopePill(
                    label = "当前项目 $currentProjectCount",
                    selected = selectedScope == ThreadScope.CurrentProject,
                    onClick = { onScopeSelected(ThreadScope.CurrentProject) },
                )
            }
        }
    }
}

@Composable
private fun LogsHomeTab(
    uiState: AcpUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ThreadSectionCard(
                title = "事件日志",
                description = "这里只放调试日志，首页不再展示，避免干扰日常使用。",
            )
        }

        if (uiState.logs.isEmpty()) {
            item {
                ThreadSectionCard(
                    title = "还没有日志",
                    description = "连接、打开线程、发送消息之后，调试日志会出现在这里。",
                )
            }
        } else {
            items(
                items = uiState.logs.takeLast(200).asReversed(),
                key = { it.id },
            ) { entry ->
                LogCard(entry = entry)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewThreadScreen(
    uiState: AcpUiState,
    onBack: () -> Unit,
    onCreateThread: (String?) -> Unit,
) {
    val recentProjects = buildList {
        uiState.workingDirectory.trim()
            .takeIf { it.isNotBlank() }
            ?.let(::add)
        uiState.sessionSummaries
            .mapNotNull { it.cwd?.trim()?.takeIf { path -> path.isNotBlank() } }
            .forEach { path ->
                if (!contains(path)) {
                    add(path)
                }
            }
    }
    var selectedPath by rememberSaveable(uiState.workingDirectory, recentProjects.joinToString("|")) {
        mutableStateOf(
            uiState.workingDirectory.trim()
                .takeIf { it.isNotBlank() }
                ?: recentProjects.firstOrNull().orEmpty()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("新建线程")
                        Text(
                            text = "先选项目目录，再创建线程",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ThreadSectionCard(
                    title = "选择项目目录",
                    description = "你可以直接选最近做过的项目，也可以手动输入一个新的代码路径。创建后，线程会在这个目录下执行。",
                )
            }

            if (recentProjects.isNotEmpty()) {
                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "最近项目",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            recentProjects.take(8).forEach { path ->
                                ToggleButton(
                                    label = path,
                                    selected = selectedPath == path,
                                    onClick = { selectedPath = path },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "手动输入路径",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = selectedPath,
                            onValueChange = { selectedPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("项目目录") },
                            supportingText = {
                                Text("例如 /Users/you/code/project-a。新路径也可以填，但需要电脑端可访问。")
                            },
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "即将使用的目录",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = selectedPath.ifBlank { "服务端默认目录" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onCreateThread(null) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("默认目录")
                            }
                            Button(
                                onClick = { onCreateThread(selectedPath.trim().ifBlank { null }) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("创建线程")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadScopeCard(
    selectedScope: ThreadScope,
    allCount: Int,
    currentProjectCount: Int,
    onScopeSelected: (ThreadScope) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "线程范围",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToggleButton(
                    label = "全部 $allCount",
                    selected = selectedScope == ThreadScope.All,
                    onClick = { onScopeSelected(ThreadScope.All) },
                    modifier = Modifier.weight(1f),
                )
                ToggleButton(
                    label = "当前项目 $currentProjectCount",
                    selected = selectedScope == ThreadScope.CurrentProject,
                    onClick = { onScopeSelected(ThreadScope.CurrentProject) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScopePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SmallToolbarButton(
    label: String,
    onClick: () -> Unit,
    outlined: Boolean = false,
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: AcpUiState,
    notificationsGranted: Boolean,
    showManualConfig: Boolean,
    onBack: () -> Unit,
    onScanPairCode: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleManualConfig: () -> Unit,
    onServerModeChange: (ServerMode) -> Unit,
    onEndpointChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onCfAccessClientIdChange: (String) -> Unit,
    onCfAccessClientSecretChange: (String) -> Unit,
    onWorkingDirectoryChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onInitialize: () -> Unit,
    onCreateSession: () -> Unit,
    onAutoReconnectEnabledChange: (Boolean) -> Unit,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
    onListenAddressChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HomeOverviewCard(uiState = uiState)
            }

            item {
                NotificationSettingsCard(
                    notificationsGranted = notificationsGranted,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                )
            }

            item {
                AutoReconnectSettingsCard(
                    uiState = uiState,
                    onAutoReconnectEnabledChange = onAutoReconnectEnabledChange,
                )
            }

            item {
                PairingCard(
                    uiState = uiState,
                    showManualConfig = showManualConfig,
                    onScanPairCode = onScanPairCode,
                    onToggleManualConfig = onToggleManualConfig,
                )
            }

            if (showManualConfig) {
                item {
                    ConnectionCard(
                        uiState = uiState,
                        onServerModeChange = onServerModeChange,
                        onEndpointChange = onEndpointChange,
                        onBearerTokenChange = onBearerTokenChange,
                        onCfAccessClientIdChange = onCfAccessClientIdChange,
                        onCfAccessClientSecretChange = onCfAccessClientSecretChange,
                        onWorkingDirectoryChange = onWorkingDirectoryChange,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onInitialize = onInitialize,
                        onCreateSession = onCreateSession,
                    )
                }
            }

            if (uiState.serverMode == ServerMode.CodexAppServer) {
                item {
                    SecuritySettingsCard(
                        currentPreset = uiState.codexPermissionPreset,
                        onPermissionPresetChange = onPermissionPresetChange,
                    )
                }
            } else {
                item {
                    BridgeCard(
                        uiState = uiState,
                        onListenAddressChange = onListenAddressChange,
                        onApiTokenChange = onApiTokenChange,
                        onStart = onStartBridge,
                        onStop = onStopBridge,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsCard(
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "通知",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (notificationsGranted) {
                        "已开启后台提醒和提示音。"
                    } else {
                        "通知未开启，后台完成时可能没有提醒。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallToolbarButton(
                label = if (notificationsGranted) "检查" else "开启",
                onClick = onRequestNotificationPermission,
                outlined = notificationsGranted,
            )
        }
    }
}

@Composable
private fun AutoReconnectSettingsCard(
    uiState: AcpUiState,
    onAutoReconnectEnabledChange: (Boolean) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "自动重连",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = uiState.reconnectStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopePill(
                    label = "开启",
                    selected = uiState.autoReconnectEnabled,
                    onClick = { onAutoReconnectEnabledChange(true) },
                )
                ScopePill(
                    label = "关闭",
                    selected = !uiState.autoReconnectEnabled,
                    onClick = { onAutoReconnectEnabledChange(false) },
                )
            }
        }
    }
}

@Composable
private fun HomeOverviewCard(uiState: AcpUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    icon = { Text(if (uiState.serverMode == ServerMode.CodexAppServer) "Codex" else "ACP") },
                    text = if (uiState.serverMode == ServerMode.CodexAppServer) "远程 Codex" else "ACP / Bridge",
                )
                StatusChip(
                    icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    text = uiState.connectionStatus,
                )
                StatusChip(
                    icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                    text = uiState.initializationStatus,
                )
            }
            Text(
                text = uiState.workingDirectory.ifBlank { "未指定默认目录" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (uiState.serverMode == ServerMode.CodexAppServer) {
                    "线程 ${uiState.sessionSummaries.size} 个，待审批 ${uiState.pendingApprovals.size} 个。"
                } else {
                    "Bridge 状态：${uiState.bridgeStatus}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "自动重连：${uiState.reconnectStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThreadActionsCard(
    uiState: AcpUiState,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "线程操作",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "安全策略、默认访问和连接参数都已放到设置里，首页只保留最常用的线程动作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRefreshSessions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新线程")
                }
                Button(
                    onClick = onOpenNewThreadSetup,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("新建线程")
                }
            }
            if (uiState.workingDirectory.isNotBlank()) {
                Text(
                    text = "当前目录：${uiState.workingDirectory}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThreadSetupHintCard(
    uiState: AcpUiState,
    onOpenSettings: () -> Unit,
) {
    val title: String
    val message: String
    val actionLabel: String
    when {
        !uiState.isConnected -> {
            title = "还没连上电脑"
            message = "还没有连上电脑端 Codex。去设置里扫码连接或者填写连接参数。"
            actionLabel = "打开设置"
        }

        uiState.initializationStatus != "已初始化" -> {
            title = "已连接，还没完成初始化"
            message =
                "现在只完成了 WebSocket 连接，Codex 还没进入可用状态。" +
                "当前状态：${uiState.initializationStatus}。去设置页重试 Initialize。"
            actionLabel = "去设置重试"
        }

        else -> {
            title = "线程环境还没准备好"
            message = "当前线程环境还没准备好。"
            actionLabel = "打开设置"
        }
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ThreadEmptyCard(
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "还没有线程",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "你可以先刷新现有线程，或者直接新建一个线程开始使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRefreshSessions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新")
                }
                Button(
                    onClick = onOpenNewThreadSetup,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("新建线程")
                }
            }
        }
    }
}

@Composable
private fun ThreadSectionCard(
    title: String,
    description: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThreadMiniCard(
    summary: RemoteSessionSummary,
    isSelected: Boolean,
    isRunning: Boolean,
    isRecentlyCompleted: Boolean,
    onOpen: () -> Unit,
) {
    val displayTitle = threadDisplayTitle(
        cwd = summary.cwd,
        fallbackTitle = summary.title,
    )
    val previewLabel = threadPreviewLabel(
        rawTitle = summary.title,
        cwd = summary.cwd,
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                summary.updatedAtEpochSeconds?.let { updatedAt ->
                    Text(
                        text = sessionTimeFormatter.format(Instant.ofEpochSecond(updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isRunning || isRecentlyCompleted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isRunning) {
                        ThreadStateBadge(
                            text = "执行中",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    } else if (isRecentlyCompleted) {
                        ThreadStateBadge(
                            text = "刚完成",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    }
                }
            }
            previewLabel?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (isSelected) "继续查看" else "打开")
            }
        }
    }
}

@Composable
private fun ThreadStateBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun LoadMoreThreadsCard(
    remainingThreadCount: Int,
    onLoadMore: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前先显示最近两条线程，你可以按需继续展开。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "还有 $remainingThreadCount 个线程可显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLoadMore) {
                Text("显示更多")
            }
        }
    }
}

@Composable
private fun AcpHomeCard(
    uiState: AcpUiState,
    onOpenSettings: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "当前处于 ACP / Bridge 模式",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "连接、扫码、Bridge 参数都已经挪到设置页。主界面只保留结果查看和日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.bridgePublicUrl?.let { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
    }
}

@Composable
private fun SecuritySettingsCard(
    currentPreset: CodexPermissionPreset,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "执行安全",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "新线程默认继承这里的权限预设。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CodexPermissionPreset.entries.forEach { preset ->
                    ScopePill(
                        label = preset.displayName,
                        selected = currentPreset == preset,
                        onClick = { onPermissionPresetChange(preset) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexThreadDetailScreen(
    uiState: AcpUiState,
    threadId: String,
    threadTitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onApprove: (kotlinx.serialization.json.JsonElement) -> Unit,
    onDecline: (kotlinx.serialization.json.JsonElement) -> Unit,
) {
    val isLoaded = uiState.sessionId == threadId
    val transcriptEntries = if (isLoaded) uiState.transcriptEntries else emptyList()
    val currentSummary = uiState.sessionSummaries.firstOrNull { it.id == threadId }
    val threadPath = currentSummary?.cwd ?: uiState.workingDirectory.takeIf { isLoaded && it.isNotBlank() }
    val displayTitle = threadDisplayTitle(
        cwd = threadPath,
        fallbackTitle = threadTitle.ifBlank { "线程详情" },
    )
    var visibleEntryCount by rememberSaveable(threadId) {
        mutableStateOf(TRANSCRIPT_PAGE_SIZE)
    }
    var initialBottomAligned by rememberSaveable(threadId) {
        mutableStateOf(false)
    }
    var canLoadMoreFromTop by rememberSaveable(threadId) {
        mutableStateOf(true)
    }
    var previousTranscriptSize by rememberSaveable(threadId) {
        mutableStateOf(0)
    }
    val hiddenEntryCount = (transcriptEntries.size - visibleEntryCount).coerceAtLeast(0)
    val visibleEntries = if (hiddenEntryCount > 0) {
        transcriptEntries.takeLast(visibleEntryCount)
    } else {
        transcriptEntries
    }
    val hasPendingApprovals = isLoaded && uiState.pendingApprovals.isNotEmpty()
    val leadingStaticItemCount = 1 + if (hasPendingApprovals) 1 else 0
    val listState = rememberLazyListState()

    LaunchedEffect(isLoaded, visibleEntries.size, leadingStaticItemCount) {
        if (!isLoaded || visibleEntries.isEmpty()) {
            previousTranscriptSize = transcriptEntries.size
            return@LaunchedEffect
        }

        if (!initialBottomAligned) {
            listState.scrollToItem(leadingStaticItemCount + visibleEntries.lastIndex)
            initialBottomAligned = true
            previousTranscriptSize = transcriptEntries.size
            return@LaunchedEffect
        }

        val appendedNewEntries = transcriptEntries.size > previousTranscriptSize
        if (appendedNewEntries) {
            val lastVisibleAbsoluteIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val currentBottomIndex = leadingStaticItemCount + visibleEntries.lastIndex
            val nearBottom = lastVisibleAbsoluteIndex >= currentBottomIndex - 1
            if (nearBottom) {
                listState.animateScrollToItem(currentBottomIndex)
            }
        }

        previousTranscriptSize = transcriptEntries.size
    }

    LaunchedEffect(
        isLoaded,
        hiddenEntryCount,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        listState.isScrollInProgress,
    ) {
        val atAbsoluteTop =
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

        if (!atAbsoluteTop) {
            canLoadMoreFromTop = true
            return@LaunchedEffect
        }

        if (
            isLoaded &&
            hiddenEntryCount > 0 &&
            canLoadMoreFromTop &&
            listState.isScrollInProgress
        ) {
            visibleEntryCount = (visibleEntryCount + TRANSCRIPT_PAGE_SIZE)
                .coerceAtMost(transcriptEntries.size)
            canLoadMoreFromTop = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isLoaded) "已进入线程，可继续聊天" else "正在加载线程历史…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新线程")
                    }
                }
            )
        },
        bottomBar = {
            ThreadPromptBar(
                uiState = uiState,
                enabled = isLoaded,
                onPromptChange = onPromptChange,
                onSend = onSend,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ThreadDetailHeaderCard(
                    threadTitle = threadTitle,
                    cwd = threadPath,
                    totalEntries = transcriptEntries.size,
                    visibleEntries = visibleEntries.size,
                    hiddenEntries = hiddenEntryCount,
                    isStreaming = isLoaded && uiState.isStreaming,
                )
            }

            if (isLoaded && uiState.pendingApprovals.isNotEmpty()) {
                item {
                    ApprovalCard(
                        approvals = uiState.pendingApprovals,
                        onApprove = onApprove,
                        onDecline = onDecline,
                    )
                }
            }

            if (!isLoaded) {
                item {
                    ThreadLoadingCard()
                }
            } else if (transcriptEntries.isEmpty()) {
                item {
                    TranscriptEmptyCard()
                }
            } else {
                items(visibleEntries, key = { it.id }) { entry ->
                    TranscriptEntryCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun ThreadDetailHeaderCard(
    threadTitle: String,
    cwd: String?,
    totalEntries: Int,
    visibleEntries: Int,
    hiddenEntries: Int,
    isStreaming: Boolean,
) {
    val displayTitle = threadDisplayTitle(
        cwd = cwd,
        fallbackTitle = threadTitle,
    )
    val previewLabel = threadPreviewLabel(
        rawTitle = threadTitle,
        cwd = cwd,
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
            )
            previewLabel?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cwd?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = buildString {
                    append("已加载 ")
                    append(totalEntries)
                    append(" 条记录")
                    append("，当前先显示最近 ")
                    append(visibleEntries)
                    append(" 条")
                    append("，最新内容在底部")
                    if (hiddenEntries > 0) {
                        append("，滑到顶部会继续补 ")
                        append(TRANSCRIPT_PAGE_SIZE)
                        append(" 条更早记录，当前还剩 ")
                        append(hiddenEntries)
                        append(" 条")
                    }
                    if (isStreaming) {
                        append("，当前线程执行中")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThreadLoadingCard() {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "正在读取线程历史",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "如果这个线程很长，第一次打开会比普通消息稍慢一些。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThreadPromptBar(
    uiState: AcpUiState,
    enabled: Boolean,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
                enabled = enabled,
                label = { Text("继续在当前线程输入任务") },
                supportingText = {
                    Text(
                        if (enabled) {
                            "发送后会继续沿用当前线程的上下文。"
                        } else {
                            "线程还没加载完成，暂时不能发送。"
                        }
                    )
                },
            )
            Button(
                onClick = onSend,
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("发送")
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: AcpUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusChip(
                    icon = { Text(if (uiState.serverMode == ServerMode.CodexAppServer) "Codex" else "ACP") },
                    text = if (uiState.serverMode == ServerMode.CodexAppServer) "远程 Codex" else "ACP / WeClaw",
                )
                StatusChip(
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Link, contentDescription = null) },
                    text = uiState.connectionStatus,
                )
                StatusChip(
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Bolt, contentDescription = null) },
                    text = uiState.initializationStatus,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusChip(
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    text = uiState.sessionId ?: if (uiState.serverMode == ServerMode.CodexAppServer) "未选择线程" else "未创建会话",
                )
                StatusChip(
                    icon = { Text("Turn") },
                    text = uiState.currentTurnId ?: if (uiState.isStreaming) "执行中" else "空闲",
                )
            }

            Text(
                text = "Agent: ${uiState.agentLabel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.promptSource?.let { source ->
                Text(
                    text = "当前 Prompt 来源: $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (uiState.serverMode == ServerMode.CodexAppServer) {
                Text(
                    text = "线程数: ${uiState.sessionSummaries.size}，待审批: ${uiState.pendingApprovals.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "WeClaw Bridge: ${uiState.bridgeStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.bridgePublicUrl?.let { publicUrl ->
                    Text(
                        text = "OpenAI 兼容入口: $publicUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    icon: @Composable () -> Unit,
    text: String,
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = icon,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun PairingCard(
    uiState: AcpUiState,
    showManualConfig: Boolean,
    onScanPairCode: () -> Unit,
    onToggleManualConfig: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "连接方式",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (uiState.isConnected) {
                    "已连接。如需换电脑，重新扫码即可。"
                } else {
                    "优先用扫码连接；手动配置放在下面折叠区。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onScanPairCode) {
                    androidx.compose.material3.Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isConnected) "重扫" else "扫码")
                }
                SmallToolbarButton(
                    label = if (showManualConfig) "收起" else "手动",
                    onClick = onToggleManualConfig,
                    outlined = true,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    uiState: AcpUiState,
    onServerModeChange: (ServerMode) -> Unit,
    onEndpointChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onCfAccessClientIdChange: (String) -> Unit,
    onCfAccessClientSecretChange: (String) -> Unit,
    onWorkingDirectoryChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onInitialize: () -> Unit,
    onCreateSession: () -> Unit,
) {
    val isCodex = uiState.serverMode == ServerMode.CodexAppServer

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "高级连接",
                style = MaterialTheme.typography.titleSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopePill(
                    label = "Codex 远程",
                    selected = isCodex,
                    onClick = { onServerModeChange(ServerMode.CodexAppServer) },
                )
                ScopePill(
                    label = "ACP / Bridge",
                    selected = !isCodex,
                    onClick = { onServerModeChange(ServerMode.ACP) },
                )
            }

            OutlinedTextField(
                value = uiState.endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isCodex) "Codex WebSocket 地址" else "ACP WebSocket 地址") },
                supportingText = {
                    Text(
                        if (isCodex) {
                            "例如 wss://agent.example.com"
                        } else {
                            "例如 ws://192.168.1.20:8765/message"
                        }
                    )
                },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.bearerToken,
                onValueChange = onBearerTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer Token") },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.cfAccessClientId,
                onValueChange = onCfAccessClientIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("CF Access Client ID") },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.cfAccessClientSecret,
                onValueChange = onCfAccessClientSecretChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("CF Access Client Secret") },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.workingDirectory,
                onValueChange = onWorkingDirectoryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("工作目录") },
                supportingText = {
                    Text(
                        if (isCodex) {
                            "留空则使用服务端默认目录。"
                        } else {
                            "用于 session/new。"
                        }
                    )
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(label = "连接", onClick = onConnect)
                SmallToolbarButton(label = "断开", onClick = onDisconnect, outlined = true)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(label = "初始化", onClick = onInitialize, outlined = true)
                if (!isCodex) {
                    SmallToolbarButton(label = "建 Session", onClick = onCreateSession)
                }
            }
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    }
}

@Composable
private fun CodexControlCard(
    uiState: AcpUiState,
    onRefreshSessions: () -> Unit,
    onCreateSession: () -> Unit,
    onOpenSession: (RemoteSessionSummary) -> Unit,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "线程列表",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "先在这里选择线程，点进去后再查看历史并继续聊天。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "权限预设",
                style = MaterialTheme.typography.labelLarge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CodexPermissionPreset.entries.forEach { preset ->
                    ToggleButton(
                        label = preset.displayName,
                        selected = uiState.codexPermissionPreset == preset,
                        onClick = { onPermissionPresetChange(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRefreshSessions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新线程")
                }
                Button(
                    onClick = onCreateSession,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("新建线程")
                }
            }

            if (uiState.sessionSummaries.isEmpty()) {
                Text(
                    text = "还没有加载到线程。先连接并 Initialize，再点“刷新线程”或“新建线程”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = if (uiState.workingDirectory.isBlank()) "线程列表" else "当前目录线程",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (uiState.workingDirectory.isNotBlank()) {
                    Text(
                        text = uiState.workingDirectory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                uiState.sessionSummaries.forEach { summary ->
                    SessionSummaryCard(
                        summary = summary,
                        isSelected = summary.id == uiState.sessionId,
                        onOpen = { onOpenSession(summary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    summary: RemoteSessionSummary,
    isSelected: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.id,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.cwd?.let { cwd ->
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            summary.updatedAtEpochSeconds?.let { updatedAt ->
                Text(
                    text = "更新时间 ${sessionTimeFormatter.format(Instant.ofEpochSecond(updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (isSelected) "进入详情" else "打开线程")
            }
        }
    }
}

@Composable
private fun PromptCard(
    uiState: AcpUiState,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "任务输入",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
                label = { Text("Prompt") },
                supportingText = {
                    Text(
                        if (uiState.serverMode == ServerMode.CodexAppServer) {
                            "发送后会在当前线程执行；如果还没选线程，应用会自动先创建一个。"
                        } else {
                            "支持从 agmente://task 或分享文本直接填充。"
                        }
                    )
                },
            )
            Button(
                onClick = onSend,
                modifier = Modifier.align(Alignment.End),
            ) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("发送")
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approvals: List<CodexApprovalRequest>,
    onApprove: (kotlinx.serialization.json.JsonElement) -> Unit,
    onDecline: (kotlinx.serialization.json.JsonElement) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "待审批请求",
                style = MaterialTheme.typography.titleMedium,
            )
            approvals.forEach { approval ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = approval.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = approval.method,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        approval.command?.let { command ->
                            SelectionContainer {
                                Text(
                                    text = command,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        approval.cwd?.let { cwd ->
                            Text(
                                text = "cwd: $cwd",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        approval.reason?.let { reason ->
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { onApprove(approval.requestId) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("批准")
                            }
                            OutlinedButton(
                                onClick = { onDecline(approval.requestId) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("拒绝")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptHeaderCard(uiState: AcpUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "会话历史",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = uiState.sessionId ?: "当前还没有线程",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "这里会显示用户输入、助手输出、命令执行、文件变更以及流式回传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptEmptyCard() {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前还没有历史记录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "打开已有线程后，这里会显示最近的对话和工具执行记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptEntryCard(entry: TranscriptEntry) {
    val accent = when (entry.role) {
        TranscriptRole.User -> MaterialTheme.colorScheme.primary
        TranscriptRole.Assistant -> MaterialTheme.colorScheme.secondary
        TranscriptRole.Tool -> MaterialTheme.colorScheme.tertiary
        TranscriptRole.System -> MaterialTheme.colorScheme.outline
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                    if (entry.isStreaming) {
                        Text(
                            text = "流式中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                entry.turnId?.let { turnId ->
                    Text(
                        text = turnId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SelectionContainer {
                    Text(
                        text = entry.text.ifBlank { "正在等待输出…" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun BridgeCard(
    uiState: AcpUiState,
    onListenAddressChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "WeClaw Bridge",
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = uiState.bridgeListenAddress,
                onValueChange = onListenAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("监听地址") },
                supportingText = {
                    Text("例如 0.0.0.0:18080")
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.bridgeApiToken,
                onValueChange = onApiTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bridge Bearer Token") },
                singleLine = true,
            )
            uiState.bridgePublicUrl?.let { publicUrl ->
                Text(
                    text = "入口: $publicUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "息屏或系统回收后 Bridge 可能中断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(label = "启动", onClick = onStart)
                SmallToolbarButton(label = "停止", onClick = onStop, outlined = true)
            }
        }
    }
}

@Composable
private fun LatestReplyCard(uiState: AcpUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "最近一次输出",
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.lastStopReason?.let { stopReason ->
                Text(
                    text = "stopReason: $stopReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = uiState.lastAssistantMessage.ifBlank { "当前还没有可显示的文本输出。" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    val accent = when (entry.kind) {
        LogKind.Incoming -> MaterialTheme.colorScheme.secondary
        LogKind.Outgoing -> MaterialTheme.colorScheme.primary
        LogKind.Event -> MaterialTheme.colorScheme.tertiary
        LogKind.Error -> MaterialTheme.colorScheme.error
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                Text(
                    text = entry.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
