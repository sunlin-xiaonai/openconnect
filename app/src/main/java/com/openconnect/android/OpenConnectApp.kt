package com.openconnect.android

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openconnect.android.codex.CodexApprovalRequest
import com.openconnect.android.codex.CodexPermissionPreset
import com.openconnect.android.codex.RemoteSessionSummary
import com.openconnect.android.codex.ServerMode
import com.openconnect.android.codex.TranscriptEntry
import com.openconnect.android.codex.TranscriptRole
import com.openconnect.android.codex.displayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

private fun sessionTimeLabel(epochSeconds: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

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

private enum class PixelMascotMode {
    Running,
    Resting,
}

private data class PixelBlock(
    val x: Int,
    val y: Int,
    val color: Color,
)

private data class PixelMascotPalette(
    val skin: Color,
    val hair: Color,
    val shirt: Color,
    val pants: Color,
    val accent: Color,
    val shoe: Color,
    val status: Color,
)

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
    unnamedTitle: String,
): String =
    projectNameFromPath(cwd)
        ?: fallbackTitle.trim().takeIf { it.isNotBlank() }
        ?: unnamedTitle

private fun threadPreviewLabel(
    rawTitle: String,
    cwd: String?,
): String? {
    val normalizedTitle = rawTitle.trim().takeIf { it.isNotBlank() } ?: return null
    return normalizedTitle.takeIf { it != projectNameFromPath(cwd) }
}

@Composable
private fun topBarConnectionLabel(uiState: AcpUiState): String {
    val prefix = stringResource(
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            R.string.status_mode_codex
        } else {
            R.string.status_mode_acp
        }
    )
    return when {
        uiState.isConnected && uiState.isInitialized ->
            stringResource(R.string.top_bar_status_online, prefix)
        uiState.isConnected ->
            stringResource(R.string.top_bar_status_connected, prefix)
        uiState.isReconnectScheduled ->
            stringResource(R.string.top_bar_status_reconnecting, prefix)
        else ->
            stringResource(R.string.top_bar_status_disconnected, prefix)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenConnectApp(
    viewModel: AcpViewModel,
    onScanPairCode: () -> Unit,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    GlobalApprovalDialog(
        approvals = uiState.pendingApprovals,
        onApprove = viewModel::approveRequest,
        onDecline = viewModel::declineRequest,
    )

    if (isCodexMode && selectedThreadId != null) {
        CodexThreadDetailScreen(
            uiState = uiState,
            threadId = selectedThreadId.orEmpty(),
            threadTitle = selectedThreadSummary?.title
                ?: selectedThreadTitle.ifBlank { context.getString(R.string.thread_detail_title) },
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
                    onAppLanguageChange = viewModel::updateAppLanguage,
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
                    NavigationPixelMascot(
                        uiState = uiState,
                        compact = true,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.action_settings),
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
                    label = { Text(stringResource(R.string.tab_threads)) },
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
                    label = { Text(stringResource(R.string.tab_logs)) },
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
private fun NavigationPixelMascot(
    uiState: AcpUiState,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mode = if (uiState.isStreaming || uiState.sessionSummaries.any { it.isRunning }) {
        PixelMascotMode.Running
    } else {
        PixelMascotMode.Resting
    }
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    var frame by rememberSaveable(mode, animationsEnabled) { mutableStateOf(0) }

    LaunchedEffect(mode, animationsEnabled) {
        frame = 0
        if (!animationsEnabled) {
            return@LaunchedEffect
        }
        val frameCount = when (mode) {
            PixelMascotMode.Running -> 4
            PixelMascotMode.Resting -> 2
        }
        val frameDelay = when (mode) {
            PixelMascotMode.Running -> 140L
            PixelMascotMode.Resting -> 900L
        }
        while (true) {
            delay(frameDelay)
            frame = (frame + 1) % frameCount
        }
    }

    val statusLabel = stringResource(
        when (mode) {
            PixelMascotMode.Running -> R.string.nav_mascot_running
            PixelMascotMode.Resting -> R.string.nav_mascot_resting
        }
    )
    val mascotDescription = stringResource(
        R.string.nav_mascot_content_description,
        statusLabel,
    )
    val statusColor by animateColorAsState(
        targetValue = when (mode) {
            PixelMascotMode.Running -> Color(0xFF33C26B)
            PixelMascotMode.Resting -> Color(0xFF7E8CA6)
        },
        label = "navMascotStatusColor",
    )
    val cardColor by animateColorAsState(
        targetValue = when (mode) {
            PixelMascotMode.Running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.98f)
            PixelMascotMode.Resting -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
        },
        label = "navMascotCardColor",
    )
    val borderColor by animateColorAsState(
        targetValue = statusColor.copy(alpha = 0.42f),
        label = "navMascotBorderColor",
    )
    val palette = PixelMascotPalette(
        skin = Color(0xFFF4D3A1),
        hair = Color(0xFF263238),
        shirt = if (mode == PixelMascotMode.Running) Color(0xFF2F80ED) else Color(0xFF5F6E82),
        pants = if (mode == PixelMascotMode.Running) Color(0xFF1E4DA1) else Color(0xFF364352),
        accent = if (mode == PixelMascotMode.Running) Color(0xFFFFB84D) else Color(0xFFB8C1D1),
        shoe = Color(0xFF1B1F24),
        status = statusColor,
    )

    Card(
        modifier = modifier.semantics {
            contentDescription = mascotDescription
        },
        shape = RoundedCornerShape(if (compact) 12.dp else 18.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .width(if (compact) 34.dp else 72.dp)
                .height(if (compact) 34.dp else 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!compact) {
                    val stripeWidth = size.width / 8f
                    repeat(6) { index ->
                        drawRoundRect(
                            color = statusColor.copy(alpha = 0.07f),
                            topLeft = Offset(
                                x = 6.dp.toPx() + (index * stripeWidth),
                                y = 10.dp.toPx(),
                            ),
                            size = Size(
                                width = stripeWidth / 2f,
                                height = size.height - 20.dp.toPx(),
                            ),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        )
                    }
                }
                val shadowWidth = if (compact) 14.dp.toPx() else if (mode == PixelMascotMode.Running) 24.dp.toPx() else 20.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.14f),
                    topLeft = Offset(
                        x = (size.width - shadowWidth) / 2f,
                        y = size.height - if (compact) 9.dp.toPx() else 12.dp.toPx(),
                    ),
                    size = Size(
                        width = shadowWidth,
                        height = if (compact) 3.dp.toPx() else 5.dp.toPx(),
                    ),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }
            Canvas(
                modifier = Modifier
                    .width(if (compact) 18.dp else 34.dp)
                    .height(if (compact) 18.dp else 34.dp),
            ) {
                drawPixelMascot(
                    blocks = buildPixelMascotBlocks(
                        mode = mode,
                        frame = frame,
                        palette = palette,
                    )
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (compact) 5.dp else 8.dp,
                        end = if (compact) 5.dp else 8.dp,
                    )
                    .width(if (compact) 6.dp else 8.dp)
                    .height(if (compact) 6.dp else 8.dp)
                    .background(statusColor, CircleShape),
            )
        }
    }
}

private fun buildPixelMascotBlocks(
    mode: PixelMascotMode,
    frame: Int,
    palette: PixelMascotPalette,
): List<PixelBlock> {
    val blocks = mutableListOf<PixelBlock>()

    fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Color) {
        for (dx in 0 until width) {
            for (dy in 0 until height) {
                blocks += PixelBlock(
                    x = x + dx,
                    y = y + dy,
                    color = color,
                )
            }
        }
    }

    val verticalOffset = if (mode == PixelMascotMode.Resting && frame % 2 == 1) 1 else 0

    fillRect(4, 1 + verticalOffset, 3, 1, palette.hair)
    fillRect(3, 2 + verticalOffset, 5, 2, palette.skin)
    fillRect(4, 2 + verticalOffset, 3, 1, palette.hair)
    fillRect(4, 3 + verticalOffset, 1, 1, palette.hair)
    fillRect(6, 3 + verticalOffset, 1, 1, palette.hair)
    fillRect(4, 4 + verticalOffset, 3, 3, palette.shirt)
    fillRect(4, 7 + verticalOffset, 3, 1, palette.accent)

    when (mode) {
        PixelMascotMode.Running -> when (frame % 4) {
            0 -> {
                fillRect(2, 4, 1, 2, palette.skin)
                fillRect(7, 5, 1, 2, palette.skin)
                fillRect(3, 8, 1, 3, palette.pants)
                fillRect(6, 8, 1, 1, palette.pants)
                fillRect(7, 9, 1, 2, palette.pants)
                fillRect(2, 11, 2, 1, palette.shoe)
                fillRect(7, 11, 2, 1, palette.shoe)
            }

            1 -> {
                fillRect(2, 5, 1, 2, palette.skin)
                fillRect(7, 4, 1, 2, palette.skin)
                fillRect(4, 8, 1, 2, palette.pants)
                fillRect(3, 10, 1, 1, palette.pants)
                fillRect(6, 8, 1, 2, palette.pants)
                fillRect(7, 10, 1, 1, palette.pants)
                fillRect(3, 11, 2, 1, palette.shoe)
                fillRect(6, 11, 2, 1, palette.shoe)
            }

            2 -> {
                fillRect(2, 5, 1, 2, palette.skin)
                fillRect(8, 4, 1, 2, palette.skin)
                fillRect(4, 8, 1, 1, palette.pants)
                fillRect(3, 9, 1, 2, palette.pants)
                fillRect(6, 8, 1, 3, palette.pants)
                fillRect(3, 11, 2, 1, palette.shoe)
                fillRect(7, 11, 2, 1, palette.shoe)
            }

            else -> {
                fillRect(3, 4, 1, 2, palette.skin)
                fillRect(8, 5, 1, 2, palette.skin)
                fillRect(4, 8, 1, 2, palette.pants)
                fillRect(5, 10, 1, 1, palette.pants)
                fillRect(6, 8, 1, 2, palette.pants)
                fillRect(7, 10, 1, 1, palette.pants)
                fillRect(4, 11, 2, 1, palette.shoe)
                fillRect(6, 11, 2, 1, palette.shoe)
            }
        }

        PixelMascotMode.Resting -> {
            fillRect(3, 5 + verticalOffset, 1, 2, palette.skin)
            fillRect(7, 5 + verticalOffset, 1, 2, palette.skin)
            fillRect(4, 8 + verticalOffset, 1, 3, palette.pants)
            fillRect(6, 8 + verticalOffset, 1, 3, palette.pants)
            fillRect(4, 11 + verticalOffset, 2, 1, palette.shoe)
            fillRect(6, 11 + verticalOffset, 2, 1, palette.shoe)
            fillRect(2, 10 + verticalOffset, 7, 1, palette.status.copy(alpha = 0.18f))
        }
    }

    return blocks
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelMascot(
    blocks: List<PixelBlock>,
) {
    val spriteWidth = 12
    val spriteHeight = 13
    val pixelSize = minOf(size.width / spriteWidth, size.height / spriteHeight)
    val originX = (size.width - (spriteWidth * pixelSize)) / 2f
    val originY = (size.height - (spriteHeight * pixelSize)) / 2f

    blocks.forEach { block ->
        drawRect(
            color = block.color,
            topLeft = Offset(
                x = originX + (block.x * pixelSize),
                y = originY + (block.y * pixelSize),
            ),
            size = Size(pixelSize, pixelSize),
        )
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

            if (!uiState.isConnected || !uiState.isInitialized) {
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
                            title = stringResource(R.string.thread_none_for_project_title),
                            description = stringResource(R.string.thread_none_for_project_description),
                        )
                    }
                } else {
                    items(visibleThreads, key = { it.id }) { summary ->
                        ThreadMiniCard(
                            summary = summary,
                            isSelected = summary.id == uiState.sessionId,
                            isRunning = summary.isRunning,
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
    val isReady = uiState.isConnected && uiState.isInitialized
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
                        ThreadScope.All -> stringResource(R.string.thread_list_title)
                        ThreadScope.CurrentProject -> projectNameFromPath(currentProjectPath)
                            ?: stringResource(R.string.thread_list_current_project_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (selectedScope) {
                        ThreadScope.All ->
                            stringResource(R.string.thread_list_all_description, allCount)
                        ThreadScope.CurrentProject ->
                            stringResource(
                                R.string.thread_list_current_project_description,
                                currentProjectCount,
                            )
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
                    label = stringResource(R.string.action_refresh),
                    onClick = onRefreshSessions,
                    outlined = true,
                )
                SmallToolbarButton(
                    label = stringResource(R.string.action_new),
                    onClick = onOpenNewThreadSetup,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScopePill(
                label = stringResource(R.string.thread_scope_all, allCount),
                selected = selectedScope == ThreadScope.All,
                onClick = { onScopeSelected(ThreadScope.All) },
            )
            if (currentProjectPath != null) {
                ScopePill(
                    label = stringResource(R.string.thread_scope_current_project, currentProjectCount),
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
                title = stringResource(R.string.logs_title),
                description = stringResource(R.string.logs_description),
            )
        }

        if (uiState.logs.isEmpty()) {
            item {
                ThreadSectionCard(
                    title = stringResource(R.string.logs_empty_title),
                    description = stringResource(R.string.logs_empty_description),
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
    val serviceDefaultDirectoryLabel = stringResource(R.string.label_service_default_directory)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.new_thread_title))
                        Text(
                            text = stringResource(R.string.new_thread_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                    title = stringResource(R.string.new_thread_choose_directory_title),
                    description = stringResource(R.string.new_thread_choose_directory_description),
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
                                text = stringResource(R.string.new_thread_recent_projects),
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
                            text = stringResource(R.string.new_thread_manual_path_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = selectedPath,
                            onValueChange = { selectedPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.field_project_directory)) },
                            supportingText = {
                                Text(stringResource(R.string.field_project_directory_hint))
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
                            text = stringResource(R.string.new_thread_upcoming_directory_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = selectedPath.ifBlank { serviceDefaultDirectoryLabel },
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
                                Text(stringResource(R.string.action_default_directory))
                            }
                            Button(
                                onClick = { onCreateThread(selectedPath.trim().ifBlank { null }) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_create_thread))
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
                text = stringResource(R.string.thread_scope_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToggleButton(
                    label = stringResource(R.string.thread_scope_all, allCount),
                    selected = selectedScope == ThreadScope.All,
                    onClick = { onScopeSelected(ThreadScope.All) },
                    modifier = Modifier.weight(1f),
                )
                ToggleButton(
                    label = stringResource(R.string.thread_scope_current_project, currentProjectCount),
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
    onAppLanguageChange: (AppLanguage) -> Unit,
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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                LanguageSettingsCard(
                    currentLanguage = uiState.appLanguage,
                    onAppLanguageChange = onAppLanguageChange,
                )
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
private fun LanguageSettingsCard(
    currentLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.language_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.language_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppLanguage.entries.forEach { language ->
                    ScopePill(
                        label = language.label(context),
                        selected = currentLanguage == language,
                        onClick = { onAppLanguageChange(language) },
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
                    text = stringResource(R.string.notifications_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (notificationsGranted) {
                        stringResource(R.string.notifications_enabled_description)
                    } else {
                        stringResource(R.string.notifications_disabled_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallToolbarButton(
                label = stringResource(
                    if (notificationsGranted) {
                        R.string.action_check
                    } else {
                        R.string.action_enable
                    }
                ),
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
                text = stringResource(R.string.auto_reconnect_title),
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
                    label = stringResource(R.string.auto_reconnect_enabled),
                    selected = uiState.autoReconnectEnabled,
                    onClick = { onAutoReconnectEnabledChange(true) },
                )
                ScopePill(
                    label = stringResource(R.string.auto_reconnect_disabled),
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
                    icon = {
                        Text(
                            stringResource(
                                if (uiState.serverMode == ServerMode.CodexAppServer) {
                                    R.string.status_mode_codex
                                } else {
                                    R.string.status_mode_acp
                                }
                            )
                        )
                    },
                    text = stringResource(
                        if (uiState.serverMode == ServerMode.CodexAppServer) {
                            R.string.overview_mode_codex
                        } else {
                            R.string.overview_mode_acp_bridge
                        }
                    ),
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
                text = uiState.workingDirectory.ifBlank {
                    stringResource(R.string.overview_default_directory)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (uiState.serverMode == ServerMode.CodexAppServer) {
                    stringResource(
                        R.string.overview_threads_pending,
                        uiState.sessionSummaries.size,
                        uiState.pendingApprovals.size,
                    )
                } else {
                    stringResource(R.string.overview_bridge_status, uiState.bridgeStatus)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.overview_auto_reconnect, uiState.reconnectStatus),
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
                text = stringResource(R.string.thread_actions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.thread_actions_description),
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
                    Text(stringResource(R.string.action_refresh_threads))
                }
                Button(
                    onClick = onOpenNewThreadSetup,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_create_thread))
                }
            }
            if (uiState.workingDirectory.isNotBlank()) {
                Text(
                    text = stringResource(R.string.label_current_directory, uiState.workingDirectory),
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
            title = stringResource(R.string.thread_setup_not_connected_title)
            message = stringResource(R.string.thread_setup_not_connected_message)
            actionLabel = stringResource(R.string.action_open_settings)
        }

        !uiState.isInitialized -> {
            title = stringResource(R.string.thread_setup_not_initialized_title)
            message = stringResource(
                R.string.thread_setup_not_initialized_message,
                uiState.initializationStatus,
            )
            actionLabel = stringResource(R.string.action_retry_in_settings)
        }

        else -> {
            title = stringResource(R.string.thread_setup_environment_unready_title)
            message = stringResource(R.string.thread_setup_environment_unready_message)
            actionLabel = stringResource(R.string.action_open_settings)
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
                text = stringResource(R.string.thread_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.thread_empty_description),
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
                    Text(stringResource(R.string.action_refresh))
                }
                Button(
                    onClick = onOpenNewThreadSetup,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_create_thread))
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
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
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
                        text = sessionTimeLabel(updatedAt),
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
                            text = stringResource(R.string.thread_running),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    } else if (isRecentlyCompleted) {
                        ThreadStateBadge(
                            text = stringResource(R.string.thread_recently_completed),
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
                Text(
                    stringResource(
                        if (isSelected) {
                            R.string.action_continue_viewing
                        } else {
                            R.string.action_open
                        }
                    )
                )
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
                text = stringResource(R.string.thread_load_more_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.thread_load_more_remaining, remainingThreadCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLoadMore) {
                Text(stringResource(R.string.action_show_more))
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
                text = stringResource(R.string.acp_home_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.acp_home_description),
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
                Text(stringResource(R.string.action_go_settings))
            }
        }
    }
}

@Composable
private fun SecuritySettingsCard(
    currentPreset: CodexPermissionPreset,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.security_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CodexPermissionPreset.entries.forEach { preset ->
                    ScopePill(
                        label = preset.displayName(context),
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
    val threadDetailFallbackTitle = stringResource(R.string.thread_detail_title)
    val isLoaded = uiState.sessionId == threadId
    val transcriptEntries = if (isLoaded) uiState.transcriptEntries else emptyList()
    val currentSummary = uiState.sessionSummaries.firstOrNull { it.id == threadId }
    val threadPath = currentSummary?.cwd ?: uiState.workingDirectory.takeIf { isLoaded && it.isNotBlank() }
    val displayTitle = threadDisplayTitle(
        cwd = threadPath,
        fallbackTitle = threadTitle.ifBlank { threadDetailFallbackTitle },
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
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
                            text = stringResource(
                                if (isLoaded) {
                                    R.string.thread_detail_loaded_subtitle
                                } else {
                                    R.string.thread_detail_loading_subtitle
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_threads),
                        )
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
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
    )
    val headerSummary = stringResource(
        R.string.thread_detail_header_summary,
        totalEntries,
        visibleEntries,
    )
    val headerMore = if (hiddenEntries > 0) {
        stringResource(
            R.string.thread_detail_header_more,
            TRANSCRIPT_PAGE_SIZE,
            hiddenEntries,
        )
    } else {
        ""
    }
    val headerRunning = if (isStreaming) {
        stringResource(R.string.thread_detail_header_running)
    } else {
        ""
    }
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
                    append(headerSummary)
                    append(headerMore)
                    append(headerRunning)
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
                    text = stringResource(R.string.thread_loading_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.thread_loading_description),
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
                label = { Text(stringResource(R.string.thread_prompt_label)) },
                supportingText = {
                    Text(
                        if (enabled) {
                            stringResource(R.string.thread_prompt_hint_enabled)
                        } else {
                            stringResource(R.string.thread_prompt_hint_disabled)
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
                Text(stringResource(R.string.action_send))
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
                    icon = {
                        Text(
                            stringResource(
                                if (uiState.serverMode == ServerMode.CodexAppServer) {
                                    R.string.status_mode_codex
                                } else {
                                    R.string.status_mode_acp
                                }
                            )
                        )
                    },
                    text = stringResource(
                        if (uiState.serverMode == ServerMode.CodexAppServer) {
                            R.string.overview_mode_codex
                        } else {
                            R.string.overview_mode_acp_weclaw
                        }
                    ),
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
                    text = uiState.sessionId ?: stringResource(
                        if (uiState.serverMode == ServerMode.CodexAppServer) {
                            R.string.status_no_thread_selected
                        } else {
                            R.string.status_no_session_created
                        }
                    ),
                )
                StatusChip(
                    icon = { Text(stringResource(R.string.status_mode_turn)) },
                    text = uiState.currentTurnId ?: stringResource(
                        if (uiState.isStreaming) {
                            R.string.status_running
                        } else {
                            R.string.status_idle
                        }
                    ),
                )
            }

            Text(
                text = stringResource(R.string.label_agent, uiState.agentLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.promptSource?.let { source ->
                Text(
                    text = stringResource(R.string.label_prompt_source, source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (uiState.serverMode == ServerMode.CodexAppServer) {
                Text(
                    text = stringResource(
                        R.string.overview_threads_pending,
                        uiState.sessionSummaries.size,
                        uiState.pendingApprovals.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.overview_bridge_status, uiState.bridgeStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.bridgePublicUrl?.let { publicUrl ->
                    Text(
                        text = stringResource(R.string.label_openai_compatible_entry, publicUrl),
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
                text = stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (uiState.isConnected) {
                    stringResource(R.string.pairing_connected_description)
                } else {
                    stringResource(R.string.pairing_disconnected_description)
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
                    Text(
                        stringResource(
                            if (uiState.isConnected) {
                                R.string.action_rescan
                            } else {
                                R.string.action_scan
                            }
                        )
                    )
                }
                SmallToolbarButton(
                    label = stringResource(
                        if (showManualConfig) {
                            R.string.action_collapse
                        } else {
                            R.string.action_manual
                        }
                    ),
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
                text = stringResource(R.string.advanced_connection_title),
                style = MaterialTheme.typography.titleSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopePill(
                    label = stringResource(R.string.server_mode_codex_remote),
                    selected = isCodex,
                    onClick = { onServerModeChange(ServerMode.CodexAppServer) },
                )
                ScopePill(
                    label = stringResource(R.string.server_mode_acp_bridge),
                    selected = !isCodex,
                    onClick = { onServerModeChange(ServerMode.ACP) },
                )
            }

            OutlinedTextField(
                value = uiState.endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            if (isCodex) {
                                R.string.field_codex_ws
                            } else {
                                R.string.field_acp_ws
                            }
                        )
                    )
                },
                supportingText = {
                    Text(
                        if (isCodex) {
                            stringResource(R.string.field_codex_ws_hint)
                        } else {
                            stringResource(R.string.field_acp_ws_hint)
                        }
                    )
                },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.bearerToken,
                onValueChange = onBearerTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_bearer_token)) },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.cfAccessClientId,
                onValueChange = onCfAccessClientIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_cf_access_client_id)) },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.cfAccessClientSecret,
                onValueChange = onCfAccessClientSecretChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_cf_access_client_secret)) },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.workingDirectory,
                onValueChange = onWorkingDirectoryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_working_directory)) },
                supportingText = {
                    Text(
                        if (isCodex) {
                            stringResource(R.string.field_working_directory_hint_codex)
                        } else {
                            stringResource(R.string.field_working_directory_hint_acp)
                        }
                    )
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(label = stringResource(R.string.action_connect), onClick = onConnect)
                SmallToolbarButton(
                    label = stringResource(R.string.action_disconnect),
                    onClick = onDisconnect,
                    outlined = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(
                    label = stringResource(R.string.action_initialize),
                    onClick = onInitialize,
                    outlined = true,
                )
                if (!isCodex) {
                    SmallToolbarButton(
                        label = stringResource(R.string.action_create_session),
                        onClick = onCreateSession,
                    )
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
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.thread_list_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.codex_control_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.codex_control_permission_preset),
                style = MaterialTheme.typography.labelLarge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CodexPermissionPreset.entries.forEach { preset ->
                    ToggleButton(
                        label = preset.displayName(context),
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
                    Text(stringResource(R.string.action_refresh_threads))
                }
                Button(
                    onClick = onCreateSession,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_create_thread))
                }
            }

            if (uiState.sessionSummaries.isEmpty()) {
                Text(
                    text = stringResource(R.string.codex_control_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        if (uiState.workingDirectory.isBlank()) {
                            R.string.thread_list_title
                        } else {
                            R.string.codex_control_current_directory_threads
                        }
                    ),
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
                    text = stringResource(
                        R.string.label_updated_at,
                        sessionTimeLabel(updatedAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    stringResource(
                        if (isSelected) {
                            R.string.thread_detail_title
                        } else {
                            R.string.action_open
                        }
                    )
                )
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
                text = stringResource(R.string.prompt_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
                label = { Text(stringResource(R.string.field_prompt)) },
                supportingText = {
                    Text(
                        if (uiState.serverMode == ServerMode.CodexAppServer) {
                            stringResource(R.string.prompt_hint_codex)
                        } else {
                            stringResource(R.string.prompt_hint_acp)
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
                Text(stringResource(R.string.action_send))
            }
        }
    }
}

@Composable
private fun GlobalApprovalDialog(
    approvals: List<CodexApprovalRequest>,
    onApprove: (kotlinx.serialization.json.JsonElement) -> Unit,
    onDecline: (kotlinx.serialization.json.JsonElement) -> Unit,
) {
    if (approvals.isEmpty()) {
        return
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.thread_approval_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.thread_approval_dialog_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApprovalCard(
                    approvals = approvals,
                    onApprove = onApprove,
                    onDecline = onDecline,
                    embedInCard = false,
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approvals: List<CodexApprovalRequest>,
    onApprove: (kotlinx.serialization.json.JsonElement) -> Unit,
    onDecline: (kotlinx.serialization.json.JsonElement) -> Unit,
    embedInCard: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (embedInCard) {
                Text(
                    text = stringResource(R.string.thread_approval_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
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
                                text = stringResource(R.string.label_cwd, cwd),
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
                                Text(stringResource(R.string.action_approve))
                            }
                            OutlinedButton(
                                onClick = { onDecline(approval.requestId) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_reject))
                            }
                        }
                    }
                }
            }
        }
    }

    if (embedInCard) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            content()
        }
    } else {
        content()
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
                text = stringResource(R.string.thread_transcript_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = uiState.sessionId ?: stringResource(R.string.thread_transcript_history_empty),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.thread_transcript_history_description),
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
                text = stringResource(R.string.thread_transcript_empty_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.thread_transcript_empty_description),
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
    val waitingOutputLabel = stringResource(R.string.label_waiting_output)

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
                            text = stringResource(R.string.label_streaming),
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
                        text = entry.text.ifBlank { waitingOutputLabel },
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
                text = stringResource(R.string.bridge_title),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = uiState.bridgeListenAddress,
                onValueChange = onListenAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_listen_address)) },
                supportingText = {
                    Text(stringResource(R.string.field_listen_address_hint))
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.bridgeApiToken,
                onValueChange = onApiTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_bridge_bearer_token)) },
                singleLine = true,
            )
            uiState.bridgePublicUrl?.let { publicUrl ->
                Text(
                    text = stringResource(R.string.label_bridge_entry, publicUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.bridge_background_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallToolbarButton(label = stringResource(R.string.action_start), onClick = onStart)
                SmallToolbarButton(
                    label = stringResource(R.string.action_stop),
                    onClick = onStop,
                    outlined = true,
                )
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
                text = stringResource(R.string.latest_output_title),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.lastStopReason?.let { stopReason ->
                Text(
                    text = stringResource(R.string.label_stop_reason, stopReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = uiState.lastAssistantMessage.ifBlank {
                        stringResource(R.string.latest_output_empty)
                    },
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
