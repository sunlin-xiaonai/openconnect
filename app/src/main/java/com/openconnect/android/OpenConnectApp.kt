package com.openconnect.android

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay

private fun sessionTimeLabel(epochSeconds: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

private fun sessionCompactTimeLabel(epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochSecond(epochSeconds)
    val currentDate = Instant.now().atZone(zone).toLocalDate()
    val targetDate = instant.atZone(zone).toLocalDate()
    val formatter = if (currentDate == targetDate) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
    return formatter
        .withLocale(Locale.getDefault())
        .withZone(zone)
        .format(instant)
}

private const val TRANSCRIPT_PAGE_SIZE = 10
private const val RECENT_LOG_COUNT = 10
private const val THREAD_LIST_PAGE_SIZE = 2

private sealed interface TranscriptRenderSegment

private data class TranscriptTextSegment(
    val text: String,
) : TranscriptRenderSegment

private data class TranscriptCodeSegment(
    val language: String?,
    val code: String,
) : TranscriptRenderSegment

private val TranscriptCodeFenceRegex = Regex("(?s)```([\\w.+-]*)\\n?(.*?)```")

private sealed interface TranscriptTimelineItem {
    val key: String
}

private data class TranscriptEntryTimelineItem(
    val entry: TranscriptEntry,
) : TranscriptTimelineItem {
    override val key: String = entry.id
}

private data class TranscriptAssistantTimelineItem(
    val assistant: TranscriptEntry,
    val details: MutableList<TranscriptEntry> = mutableListOf(),
) : TranscriptTimelineItem {
    override val key: String = assistant.id
}

private data class TranscriptTechnicalTimelineItem(
    override val key: String,
    val details: List<TranscriptEntry>,
) : TranscriptTimelineItem

private enum class HomeTab {
    Threads,
    Logs,
}

private enum class ThreadScope {
    All,
    CurrentProject,
}

private data class ProjectThreadGroup(
    val key: String,
    val cwd: String?,
    val displayName: String,
    val updatedAtEpochSeconds: Long?,
    val threads: List<RemoteSessionSummary>,
    val isCurrentProject: Boolean,
)

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

private const val DEFAULT_PROJECT_GROUP_KEY = "__default_project__"
private const val PROJECT_LIST_PAGE_SIZE = 6

private fun projectGroupKey(path: String?): String =
    normalizedProjectPath(path) ?: DEFAULT_PROJECT_GROUP_KEY

private fun buildProjectThreadGroups(
    sessionSummaries: List<RemoteSessionSummary>,
    currentProjectPath: String?,
    defaultDirectoryLabel: String,
): List<ProjectThreadGroup> {
    val grouped = sessionSummaries
        .groupBy { summary -> projectGroupKey(summary.cwd) }
        .map { (key, summaries) ->
            val sortedThreads = summaries.sortedByDescending { it.updatedAtEpochSeconds ?: 0L }
            val normalizedCwd = normalizedProjectPath(sortedThreads.firstNotNullOfOrNull { it.cwd })
            ProjectThreadGroup(
                key = key,
                cwd = normalizedCwd,
                displayName = projectNameFromPath(normalizedCwd) ?: defaultDirectoryLabel,
                updatedAtEpochSeconds = sortedThreads.mapNotNull { it.updatedAtEpochSeconds }.maxOrNull(),
                threads = sortedThreads,
                isCurrentProject = normalizedCwd != null && normalizedCwd == currentProjectPath,
            )
        }
        .toMutableList()

    if (
        currentProjectPath != null &&
        grouped.none { group -> group.cwd == currentProjectPath }
    ) {
        grouped += ProjectThreadGroup(
            key = projectGroupKey(currentProjectPath),
            cwd = currentProjectPath,
            displayName = projectNameFromPath(currentProjectPath) ?: currentProjectPath,
            updatedAtEpochSeconds = null,
            threads = emptyList(),
            isCurrentProject = true,
        )
    }

    return grouped.sortedWith(
        compareByDescending<ProjectThreadGroup> { it.isCurrentProject }
            .thenByDescending { it.updatedAtEpochSeconds ?: Long.MIN_VALUE }
            .thenBy { it.displayName.lowercase(Locale.getDefault()) }
    )
}

private fun threadConversationTitle(
    fallbackTitle: String,
    unnamedTitle: String,
    threadId: String,
): String =
    fallbackTitle.trim()
        .takeIf { it.isNotBlank() && it != unnamedTitle }
        ?: "$unnamedTitle ${threadId.take(8)}"

private fun threadPreviewLabel(
    rawTitle: String,
    cwd: String?,
): String? {
    val normalizedTitle = rawTitle.trim().takeIf { it.isNotBlank() } ?: return null
    return normalizedTitle.takeIf { it != projectNameFromPath(cwd) }
}

private fun threadAvatarLabel(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isBlank()) {
        return "OC"
    }
    val words = trimmed
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> words.take(2).joinToString("") { it.take(1).uppercase(Locale.getDefault()) }
        trimmed.length >= 2 -> trimmed.take(2).uppercase(Locale.getDefault())
        else -> trimmed.take(1).uppercase(Locale.getDefault())
    }
}

private fun threadAvatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF53B9F9),
        Color(0xFFFFA248),
        Color(0xFF4FB867),
        Color(0xFF7E8BFF),
        Color(0xFFF37A9B),
        Color(0xFF4FC3B6),
    )
    return palette[seed.hashCode().absoluteValue % palette.size]
}

private fun parseTranscriptRenderSegments(text: String): List<TranscriptRenderSegment> {
    val normalized = text.replace("\r\n", "\n")
    if ("```" !in normalized) {
        return listOf(TranscriptTextSegment(normalized))
    }

    val segments = mutableListOf<TranscriptRenderSegment>()
    var cursor = 0
    TranscriptCodeFenceRegex.findAll(normalized).forEach { match ->
        val range = match.range
        val before = normalized.substring(cursor, range.first).trim('\n')
        if (before.isNotBlank()) {
            segments += TranscriptTextSegment(before)
        }
        val language = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val code = match.groupValues.getOrNull(2).orEmpty().trim('\n')
        if (code.isNotBlank()) {
            segments += TranscriptCodeSegment(language = language, code = code)
        }
        cursor = range.last + 1
    }
    val after = normalized.substring(cursor).trim('\n')
    if (after.isNotBlank()) {
        segments += TranscriptTextSegment(after)
    }
    return if (segments.isEmpty()) {
        listOf(TranscriptTextSegment(normalized))
    } else {
        segments
    }
}

private fun transcriptLineCount(text: String): Int =
    text.lineSequence().count().coerceAtLeast(1)

private fun transcriptLooksLikeDiff(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("diff --git") ||
        trimmed.startsWith("@@") ||
        trimmed.startsWith("--- ") ||
        trimmed.startsWith("+++ ")
}

private fun transcriptLooksLikeCommand(text: String): Boolean {
    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.startsWith("$") ||
        firstLine.startsWith(">") ||
        firstLine.startsWith("./") ||
        firstLine.startsWith("adb ") ||
        firstLine.startsWith("git ") ||
        firstLine.startsWith("npm ") ||
        firstLine.startsWith("pnpm ") ||
        firstLine.startsWith("yarn ") ||
        firstLine.startsWith("bash ") ||
        firstLine.startsWith("sh ")
}

private fun transcriptShouldAutoCollapse(entry: TranscriptEntry, text: String): Boolean =
    when (entry.role) {
        TranscriptRole.Tool -> !entry.isStreaming
        TranscriptRole.System -> !entry.isStreaming && (text.length > 80 || transcriptLineCount(text) > 3)
        else -> false
    }

private fun buildTranscriptTimelineItems(entries: List<TranscriptEntry>): List<TranscriptTimelineItem> {
    val result = mutableListOf<TranscriptTimelineItem>()
    val lastAssistantByTurn = mutableMapOf<String, TranscriptAssistantTimelineItem>()
    val pendingTechnicalByTurn = LinkedHashMap<String, MutableList<TranscriptEntry>>()
    var standaloneCounter = 0

    fun flushPendingExcept(turnIdToKeep: String?) {
        val iterator = pendingTechnicalByTurn.entries.iterator()
        while (iterator.hasNext()) {
            val (turnId, items) = iterator.next()
            if (turnId != turnIdToKeep) {
                result += TranscriptTechnicalTimelineItem(
                    key = "tech-$turnId-${standaloneCounter++}",
                    details = items.toList(),
                )
                iterator.remove()
            }
        }
    }

    entries.forEach { entry ->
        val turnId = entry.turnId
        when (entry.role) {
            TranscriptRole.User -> {
                flushPendingExcept(null)
                result += TranscriptEntryTimelineItem(entry)
            }

            TranscriptRole.Assistant -> {
                flushPendingExcept(turnId)
                val details = if (turnId == null) {
                    mutableListOf()
                } else {
                    pendingTechnicalByTurn.remove(turnId) ?: mutableListOf()
                }
                val group = TranscriptAssistantTimelineItem(
                    assistant = entry,
                    details = details,
                )
                result += group
                if (turnId != null) {
                    lastAssistantByTurn[turnId] = group
                }
            }

            TranscriptRole.Tool,
            TranscriptRole.System,
            -> {
                if (turnId != null) {
                    val group = lastAssistantByTurn[turnId]
                    if (group != null) {
                        group.details += entry
                    } else {
                        pendingTechnicalByTurn.getOrPut(turnId) { mutableListOf() }.add(entry)
                    }
                } else {
                    result += TranscriptTechnicalTimelineItem(
                        key = "tech-${entry.id}-${standaloneCounter++}",
                        details = listOf(entry),
                    )
                }
            }
        }
    }

    flushPendingExcept(null)
    return result
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

private val PageHorizontalPadding = 18.dp
private val FloatingChromeShape = RoundedCornerShape(30.dp)
private val SectionShape = RoundedCornerShape(28.dp)
private val PillShape = RoundedCornerShape(20.dp)

@Composable
private fun AppBackdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                            Color.Transparent,
                        )
                    )
                )
        )
        content()
    }
}

@Composable
fun OpenConnectApp(
    viewModel: AcpViewModel,
    onScanPairCode: () -> Unit,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultDirectoryLabel = context.getString(R.string.label_service_default_directory)
    val currentProjectPath = normalizedProjectPath(uiState.workingDirectory)
    var showManualConfig by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Threads) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var newThreadOpen by rememberSaveable { mutableStateOf(false) }
    var selectedProjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThreadTitle by rememberSaveable { mutableStateOf("") }
    var pendingOpenCreatedThread by rememberSaveable { mutableStateOf(false) }
    var pendingCreatedFromSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCreatedProjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    val isCodexMode = uiState.serverMode == ServerMode.CodexAppServer
    val projectGroups = remember(
        uiState.sessionSummaries,
        currentProjectPath,
        defaultDirectoryLabel,
    ) {
        buildProjectThreadGroups(
            sessionSummaries = uiState.sessionSummaries,
            currentProjectPath = currentProjectPath,
            defaultDirectoryLabel = defaultDirectoryLabel,
        )
    }
    val selectedProjectGroup = projectGroups.firstOrNull { it.key == selectedProjectKey }
    val selectedThreadSummary = uiState.sessionSummaries.firstOrNull { it.id == selectedThreadId }
    val canNavigateBack =
        settingsOpen ||
            (isCodexMode && newThreadOpen) ||
            (isCodexMode && selectedProjectKey != null) ||
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
            isCodexMode && selectedProjectKey != null -> selectedProjectKey = null
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

    LaunchedEffect(selectedProjectKey, projectGroups) {
        if (selectedProjectKey != null && selectedProjectGroup == null) {
            selectedProjectKey = null
        }
    }

    LaunchedEffect(selectedThreadSummary?.cwd, selectedThreadId, selectedProjectKey) {
        if (selectedThreadId != null && selectedProjectKey == null && selectedThreadSummary != null) {
            selectedProjectKey = projectGroupKey(selectedThreadSummary.cwd)
        }
    }

    LaunchedEffect(
        uiState.sessionId,
        pendingOpenCreatedThread,
        pendingCreatedFromSessionId,
        pendingCreatedProjectKey,
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
            selectedProjectKey = pendingCreatedProjectKey
            pendingOpenCreatedThread = false
            pendingCreatedFromSessionId = null
            pendingCreatedProjectKey = null
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
        pendingCreatedProjectKey = null
        selectedProjectKey = uiState.sessionSummaries
            .firstOrNull { it.id == targetThreadId }
            ?.let { summary -> projectGroupKey(summary.cwd) }
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
                pendingCreatedProjectKey = null
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
                selectedProjectKey = directory?.let(::projectGroupKey) ?: DEFAULT_PROJECT_GROUP_KEY
                selectedThreadId = null
                selectedThreadTitle = ""
                pendingOpenCreatedThread = true
                pendingCreatedFromSessionId = uiState.sessionId
                pendingCreatedProjectKey = directory?.let(::projectGroupKey) ?: DEFAULT_PROJECT_GROUP_KEY
                viewModel.updateWorkingDirectory(directory.orEmpty())
                viewModel.createSession()
            },
        )
        return
    }

    if (isCodexMode && selectedProjectGroup != null) {
        ProjectDetailScreen(
            group = selectedProjectGroup,
            selectedThreadId = uiState.sessionId,
            lastCompletedThreadId = uiState.lastCompletedThreadId,
            onBack = { selectedProjectKey = null },
            onRefresh = viewModel::refreshSessions,
            onCreateSession = { directory ->
                selectedThreadId = null
                selectedThreadTitle = ""
                pendingOpenCreatedThread = true
                pendingCreatedFromSessionId = uiState.sessionId
                pendingCreatedProjectKey = selectedProjectGroup.key
                viewModel.updateWorkingDirectory(directory.orEmpty())
                viewModel.createSession()
            },
            onOpenSession = { summary ->
                pendingOpenCreatedThread = false
                pendingCreatedFromSessionId = null
                pendingCreatedProjectKey = selectedProjectGroup.key
                selectedThreadId = summary.id
                selectedThreadTitle = summary.title
                viewModel.openSession(summary.id)
            },
        )
        return
    }

    MainHomeScreen(
        uiState = uiState,
        projectGroups = projectGroups,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onOpenSettings = { settingsOpen = true },
        onRefreshSessions = viewModel::refreshSessions,
        onOpenNewThreadSetup = { newThreadOpen = true },
        onOpenProject = { group ->
            pendingOpenCreatedThread = false
            pendingCreatedFromSessionId = null
            pendingCreatedProjectKey = null
            selectedProjectKey = group.key
        },
    )
}

@Composable
private fun MainHomeScreen(
    uiState: AcpUiState,
    projectGroups: List<ProjectThreadGroup>,
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
    onOpenProject: (ProjectThreadGroup) -> Unit,
) {
    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                HomeHeaderBar(
                    uiState = uiState,
                    onOpenSettings = onOpenSettings,
                )
            },
            floatingActionButton = {
                if (selectedTab == HomeTab.Threads && uiState.serverMode == ServerMode.CodexAppServer) {
                    TelegramComposeFab(onClick = onOpenNewThreadSetup)
                }
            },
            bottomBar = {
                FloatingHomeTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                )
            },
        ) { padding ->
            when (selectedTab) {
                HomeTab.Threads -> {
                    ProjectsHomeTab(
                        uiState = uiState,
                        projectGroups = projectGroups,
                        onOpenSettings = onOpenSettings,
                        onRefreshSessions = onRefreshSessions,
                        onOpenNewThreadSetup = onOpenNewThreadSetup,
                        onOpenProject = onOpenProject,
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
}

@Composable
private fun HomeHeaderBar(
    uiState: AcpUiState,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = PageHorizontalPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenConnectBrandPlate(
            modifier = Modifier.weight(1f),
        )
        TopBarModeBadge(uiState = uiState)
        ChromeIconButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.action_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun OpenConnectBrandPlate(
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = surface.copy(alpha = 0.96f),
        ),
        border = BorderStroke(
            1.dp,
            outline.copy(alpha = 0.12f),
        ),
    ) {
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                val cell = size.height / 10f

                fun pixel(x: Int, y: Int, color: Color) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell * 0.68f, cell * 0.68f),
                        cornerRadius = CornerRadius(cell * 0.12f, cell * 0.12f),
                    )
                }

                listOf(
                    10 to 1,
                    12 to 2,
                    11 to 3,
                    13 to 4,
                    12 to 6,
                    9 to 7,
                ).forEach { (x, y) ->
                    pixel(
                        x = x,
                        y = y,
                        color = primary.copy(alpha = 0.11f),
                    )
                }

                listOf(
                    8 to 0,
                    11 to 5,
                    14 to 3,
                ).forEach { (x, y) ->
                    pixel(
                        x = x,
                        y = y,
                        color = tertiary.copy(alpha = 0.14f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenConnectPixelGlyph()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "OPEN",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 1.8.sp,
                            lineHeight = 10.sp,
                        ),
                        color = primary,
                        maxLines = 1,
                    )
                    Text(
                        text = "CONNECT",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.1.sp,
                            lineHeight = 12.sp,
                        ),
                        color = onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenConnectPixelGlyph(
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .size(34.dp)
            .background(
                color = primaryContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(5.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 7
            val rows = 7
            val pixelSize = minOf(size.width / cols, size.height / rows)
            val offsetX = (size.width - cols * pixelSize) / 2f
            val offsetY = (size.height - rows * pixelSize) / 2f

            fun pixel(x: Int, y: Int, color: Color) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(
                        x = offsetX + x * pixelSize,
                        y = offsetY + y * pixelSize,
                    ),
                    size = Size(pixelSize * 0.82f, pixelSize * 0.82f),
                    cornerRadius = CornerRadius(pixelSize * 0.14f, pixelSize * 0.14f),
                )
            }

            val primaryPixels = listOf(
                0 to 1, 1 to 1, 2 to 1,
                0 to 2,
                0 to 3,
                0 to 4, 1 to 4, 2 to 4,
                3 to 2, 4 to 2,
            )
            val accentPixels = listOf(
                3 to 2, 4 to 2, 5 to 2,
                5 to 3,
                3 to 4, 4 to 4, 5 to 4,
                2 to 3,
            )

            primaryPixels.forEach { (x, y) ->
                pixel(
                    x = x,
                    y = y,
                    color = primary,
                )
            }
            accentPixels.forEach { (x, y) ->
                pixel(
                    x = x,
                    y = y,
                    color = tertiary,
                )
            }

            pixel(
                x = 6,
                y = 1,
                color = primary.copy(alpha = 0.38f),
            )
            pixel(
                x = 1,
                y = 6,
                color = tertiary.copy(alpha = 0.32f),
            )
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FloatingHomeTabBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PageHorizontalPadding, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = FloatingChromeShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingHomeTabItem(
                    label = stringResource(R.string.tab_threads),
                    icon = Icons.Outlined.Terminal,
                    selected = selectedTab == HomeTab.Threads,
                    onClick = { onTabSelected(HomeTab.Threads) },
                    modifier = Modifier.weight(1f),
                )
                FloatingHomeTabItem(
                    label = stringResource(R.string.tab_logs),
                    icon = Icons.Outlined.Bolt,
                    selected = selectedTab == HomeTab.Logs,
                    onClick = { onTabSelected(HomeTab.Logs) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FloatingHomeTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TelegramComposeFab(onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(bottom = 14.dp),
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.action_create_thread),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailingDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = PageHorizontalPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (onBack != null) {
            ChromeIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingIcon != null && onTrailingClick != null) {
            ChromeIconButton(
                icon = trailingIcon,
                contentDescription = trailingDescription ?: "",
                onClick = onTrailingClick,
            )
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
private fun ProjectsHomeTab(
    uiState: AcpUiState,
    projectGroups: List<ProjectThreadGroup>,
    onOpenSettings: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenNewThreadSetup: () -> Unit,
    onOpenProject: (ProjectThreadGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PageHorizontalPadding,
            top = 6.dp,
            end = PageHorizontalPadding,
            bottom = 124.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            item {
                ProjectOverviewCard(
                    uiState = uiState,
                    projectCount = projectGroups.size,
                    onRefresh = onRefreshSessions,
                )
            }

            if (!uiState.isConnected || !uiState.isInitialized) {
                item {
                    ThreadSetupHintCard(
                        uiState = uiState,
                        onOpenSettings = onOpenSettings,
                    )
                }
            } else if (projectGroups.isEmpty()) {
                item {
                    ThreadEmptyCard(
                        onRefreshSessions = onRefreshSessions,
                        onOpenNewThreadSetup = onOpenNewThreadSetup,
                    )
                }
            } else {
                items(projectGroups, key = { it.key }) { group ->
                    ProjectGroupCard(
                        group = group,
                        activeThreadId = uiState.sessionId,
                        lastCompletedThreadId = uiState.lastCompletedThreadId,
                        onOpen = { onOpenProject(group) },
                    )
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
private fun ProjectOverviewCard(
    uiState: AcpUiState,
    projectCount: Int,
    onRefresh: () -> Unit,
) {
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.project_home_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.project_home_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThreadStateBadge(
                    text = stringResource(
                        R.string.project_home_summary,
                        projectCount,
                        uiState.sessionSummaries.size,
                    ),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                )
                if (uiState.pendingApprovals.isNotEmpty()) {
                    ThreadStateBadge(
                        text = stringResource(
                            R.string.overview_threads_pending,
                            uiState.sessionSummaries.size,
                            uiState.pendingApprovals.size,
                        ),
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    )
                }
            }
            SmallToolbarButton(
                label = stringResource(R.string.action_refresh),
                onClick = onRefresh,
                outlined = true,
            )
        }
    }
}

@Composable
private fun ProjectGroupCard(
    group: ProjectThreadGroup,
    activeThreadId: String?,
    lastCompletedThreadId: String?,
    onOpen: () -> Unit,
) {
    val runningCount = group.threads.count { it.isRunning }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(
            1.dp,
            if (group.isCurrentProject) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThreadAvatar(
                    label = threadAvatarLabel(group.displayName),
                    seed = group.key,
                    highlighted = runningCount > 0,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                group.updatedAtEpochSeconds?.let { updatedAt ->
                    Text(
                        text = sessionCompactTimeLabel(updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (group.isCurrentProject) {
                    ThreadStateBadge(
                        text = stringResource(R.string.project_card_current),
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    )
                }
                ThreadStateBadge(
                    text = stringResource(
                        R.string.project_card_thread_count,
                        group.threads.size,
                    ),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                if (runningCount > 0) {
                    ThreadStateBadge(
                        text = stringResource(R.string.project_card_running_count, runningCount),
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    )
                }
            }

            if (group.threads.isEmpty()) {
                Text(
                    text = stringResource(R.string.project_detail_empty_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.threads.take(3).forEach { summary ->
                        ProjectThreadPreviewRow(
                            summary = summary,
                            isActive = summary.id == activeThreadId,
                            isRecentlyCompleted = summary.id == lastCompletedThreadId,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectThreadPreviewRow(
    summary: RemoteSessionSummary,
    isActive: Boolean,
    isRecentlyCompleted: Boolean,
) {
    val title = threadConversationTitle(
        fallbackTitle = summary.title,
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
        threadId = summary.id,
    )
    val statusLabel = when {
        summary.isRunning -> stringResource(R.string.thread_running)
        isRecentlyCompleted -> stringResource(R.string.thread_recently_completed)
        else -> stringResource(R.string.label_thread_short, summary.id.take(6))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = when {
                        summary.isRunning -> Color(0xFF4CC85A)
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)
                    },
                    shape = CircleShape,
                )
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isActive) {
            Text(
                text = stringResource(R.string.label_current_thread),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            summary.updatedAtEpochSeconds?.let { updatedAt ->
                Text(
                    text = sessionCompactTimeLabel(updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectDetailScreen(
    group: ProjectThreadGroup,
    selectedThreadId: String?,
    lastCompletedThreadId: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: (String?) -> Unit,
    onOpenSession: (RemoteSessionSummary) -> Unit,
) {
    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ScreenHeader(
                    title = group.displayName,
                    subtitle = group.cwd ?: stringResource(R.string.project_card_default_path),
                    onBack = onBack,
                    trailingIcon = Icons.Outlined.Refresh,
                    trailingDescription = stringResource(R.string.action_refresh_threads),
                    onTrailingClick = onRefresh,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = PageHorizontalPadding,
                    top = 6.dp,
                    end = PageHorizontalPadding,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ProjectSummaryCard(
                        group = group,
                        onRefresh = onRefresh,
                        onCreateSession = { onCreateSession(group.cwd) },
                    )
                }

                if (group.threads.isEmpty()) {
                    item {
                        ProjectEmptyStateCard(
                            onCreateSession = { onCreateSession(group.cwd) },
                        )
                    }
                } else {
                    item {
                        ThreadSectionCard(
                            title = stringResource(R.string.project_items_title),
                            description = stringResource(R.string.project_items_description),
                        )
                    }
                    items(group.threads, key = { it.id }) { summary ->
                        ThreadMiniCard(
                            summary = summary,
                            isSelected = summary.id == selectedThreadId,
                            isRunning = summary.isRunning,
                            isRecentlyCompleted = summary.id == lastCompletedThreadId,
                            onOpen = { onOpenSession(summary) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectSummaryCard(
    group: ProjectThreadGroup,
    onRefresh: () -> Unit,
    onCreateSession: () -> Unit,
) {
    val runningCount = group.threads.count { it.isRunning }

    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (group.isCurrentProject) {
                    ThreadStateBadge(
                        text = stringResource(R.string.project_card_current),
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    )
                }
                ThreadStateBadge(
                    text = stringResource(R.string.project_card_thread_count, group.threads.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                if (runningCount > 0) {
                    ThreadStateBadge(
                        text = stringResource(R.string.project_card_running_count, runningCount),
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    )
                }
            }

            Text(
                text = group.cwd ?: stringResource(R.string.project_card_default_path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallToolbarButton(
                    label = stringResource(R.string.action_refresh_threads),
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
                SmallToolbarButton(
                    label = stringResource(R.string.action_create_session),
                    onClick = onCreateSession,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProjectEmptyStateCard(
    onCreateSession: () -> Unit,
) {
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.project_detail_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.project_detail_empty_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SmallToolbarButton(
                label = stringResource(R.string.action_create_session),
                onClick = onCreateSession,
            )
        }
    }
}

@Composable
private fun TopBarModeBadge(uiState: AcpUiState) {
    val isReady = uiState.isConnected && uiState.isInitialized
    val statusDescription = topBarConnectionLabel(uiState)
    val modeLabel = stringResource(
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            R.string.status_mode_codex
        } else {
            R.string.status_mode_acp
        }
    )
    val containerColor = when {
        isReady -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        uiState.isConnected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }
    val accentColor = when {
        isReady -> MaterialTheme.colorScheme.primary
        uiState.isConnected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .background(
                color = containerColor,
                shape = PillShape,
            )
            .semantics {
                contentDescription = statusDescription
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isReady) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accentColor, CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = modeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (isReady) accentColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactThreadToolbar(
    selectedScope: ThreadScope,
    allCount: Int,
    currentProjectCount: Int,
    currentProjectPath: String?,
    onScopeSelected: (ThreadScope) -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                    label = stringResource(
                        R.string.thread_scope_current_project,
                        currentProjectCount,
                    ),
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
        contentPadding = PaddingValues(
            start = PageHorizontalPadding,
            top = 6.dp,
            end = PageHorizontalPadding,
            bottom = 124.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ScreenHeader(
                    title = stringResource(R.string.new_thread_title),
                    subtitle = stringResource(R.string.new_thread_subtitle),
                    onBack = onBack,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = PageHorizontalPadding,
                    top = 6.dp,
                    end = PageHorizontalPadding,
                    bottom = 40.dp,
                ),
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
                        Card(
                            shape = SectionShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
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
                    Card(
                        shape = SectionShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.new_thread_manual_path_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            InsetTextField(
                                value = selectedPath,
                                onValueChange = { selectedPath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.field_project_directory),
                                supportingText = stringResource(R.string.field_project_directory_hint),
                                singleLine = true,
                            )
                        }
                    }
                }

                item {
                    Card(
                        shape = SectionShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                SmallToolbarButton(
                                    label = stringResource(R.string.action_default_directory),
                                    onClick = { onCreateThread(null) },
                                    modifier = Modifier.weight(1f),
                                    outlined = true,
                                )
                                SmallToolbarButton(
                                    label = stringResource(R.string.action_create_thread),
                                    onClick = { onCreateThread(selectedPath.trim().ifBlank { null }) },
                                    modifier = Modifier.weight(1f),
                                )
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
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = PillShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
            modifier = modifier,
            shape = PillShape,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = PillShape,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = PillShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun InsetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        label = { Text(label) },
        supportingText = supportingText?.let { text ->
            {
                Text(text)
            }
        },
        singleLine = singleLine,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        ),
    )
}

@Composable
private fun SettingsPanelCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
    )
}

@Composable
private fun SettingsValuePill(
    text: String,
    emphasized: Boolean = false,
) {
    Card(
        shape = PillShape,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
    BackHandler(enabled = true) { onBack() }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TelegramSettingsHeader(
                    onScanPairCode = onScanPairCode,
                    onToggleManualConfig = onToggleManualConfig,
                    showManualConfig = showManualConfig,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = PageHorizontalPadding,
                    top = 4.dp,
                    end = PageHorizontalPadding,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsConnectionSection(
                        uiState = uiState,
                        showManualConfig = showManualConfig,
                        onScanPairCode = onScanPairCode,
                        onToggleManualConfig = onToggleManualConfig,
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

                item {
                    SettingsPreferenceSection(
                        currentLanguage = uiState.appLanguage,
                        notificationsGranted = notificationsGranted,
                        autoReconnectEnabled = uiState.autoReconnectEnabled,
                        reconnectStatus = uiState.reconnectStatus,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onAppLanguageChange = onAppLanguageChange,
                        onAutoReconnectEnabledChange = onAutoReconnectEnabledChange,
                    )
                }

                if (uiState.serverMode == ServerMode.CodexAppServer) {
                    item {
                        SettingsSecuritySection(
                            currentPreset = uiState.codexPermissionPreset,
                            onPermissionPresetChange = onPermissionPresetChange,
                        )
                    }
                } else {
                    item {
                        SettingsBridgeSection(
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
}

@Composable
private fun TelegramSettingsHeader(
    onScanPairCode: () -> Unit,
    onToggleManualConfig: () -> Unit,
    showManualConfig: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = PageHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.weight(1f),
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onScanPairCode) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.action_scan),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onToggleManualConfig) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(
                        if (showManualConfig) {
                            R.string.action_collapse
                        } else {
                            R.string.action_manual
                        }
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_page_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun TelegramSettingsGroupCard(
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.06f),
        ),
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun TelegramSettingsRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
    )
}

@Composable
private fun TelegramSettingsIconBubble(
    icon: ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TelegramSettingsRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) {
                onClick?.invoke()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TelegramSettingsIconBubble(
            icon = icon,
            containerColor = iconColor,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TelegramSettingsInlineDetail(
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.padding(start = 64.dp, end = 14.dp, bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsConnectionSection(
    uiState: AcpUiState,
    showManualConfig: Boolean,
    onScanPairCode: () -> Unit,
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
) {
    val modeTitle = stringResource(
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            R.string.overview_mode_codex
        } else {
            R.string.overview_mode_acp_bridge
        }
    )
    val modeSubtitle = buildString {
        append(topBarConnectionLabel(uiState))
        append(" · ")
        append(
            if (uiState.serverMode == ServerMode.CodexAppServer) {
                uiState.initializationStatus
            } else {
                uiState.bridgeStatus
            }
        )
    }
    var pairingExpanded by rememberSaveable { mutableStateOf(false) }

    TelegramSettingsGroupCard {
        TelegramSettingsRow(
            icon = Icons.Outlined.Bolt,
            iconColor = Color(0xFF2EAAE1),
            title = modeTitle,
            subtitle = modeSubtitle,
        )
        TelegramSettingsRowDivider()
        TelegramSettingsRow(
            icon = Icons.Outlined.Link,
            iconColor = Color(0xFF5BC65B),
            title = stringResource(R.string.pairing_title),
            subtitle = if (uiState.isConnected) {
                stringResource(R.string.pairing_connected_description)
            } else {
                stringResource(R.string.pairing_disconnected_description)
            },
            onClick = { pairingExpanded = !pairingExpanded },
        )
        if (pairingExpanded) {
            TelegramSettingsInlineDetail {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallToolbarButton(
                        label = stringResource(
                            if (uiState.isConnected) {
                                R.string.action_rescan
                            } else {
                                R.string.action_scan
                            }
                        ),
                        onClick = onScanPairCode,
                        modifier = Modifier.weight(1f),
                    )
                    SmallToolbarButton(
                        label = stringResource(
                            if (showManualConfig) {
                                R.string.action_collapse
                            } else {
                                R.string.action_manual
                            }
                        ),
                        onClick = onToggleManualConfig,
                        modifier = Modifier.weight(1f),
                        outlined = showManualConfig,
                    )
                }
            }
        }
        TelegramSettingsRowDivider()
        TelegramSettingsRow(
            icon = Icons.Outlined.SettingsEthernet,
            iconColor = Color(0xFF4D7DFF),
            title = stringResource(R.string.advanced_connection_title),
            subtitle = stringResource(
                if (showManualConfig) {
                    R.string.settings_manual_section_hint
                } else {
                    R.string.settings_manual_hidden_hint
                }
            ),
            onClick = onToggleManualConfig,
        )
        if (showManualConfig) {
            TelegramSettingsInlineDetail {
                CompactConnectionEditor(
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
    }
}

@Composable
private fun SettingsPreferenceSection(
    currentLanguage: AppLanguage,
    notificationsGranted: Boolean,
    autoReconnectEnabled: Boolean,
    reconnectStatus: String,
    onRequestNotificationPermission: () -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onAutoReconnectEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var notificationsExpanded by rememberSaveable { mutableStateOf(false) }
    var reconnectExpanded by rememberSaveable { mutableStateOf(false) }
    var languageExpanded by rememberSaveable { mutableStateOf(false) }

    TelegramSettingsGroupCard {
        TelegramSettingsRow(
            icon = Icons.Outlined.Notifications,
            iconColor = Color(0xFFF45D5D),
            title = stringResource(R.string.notifications_title),
            subtitle = if (notificationsGranted) {
                stringResource(R.string.notifications_enabled_description)
            } else {
                stringResource(R.string.notifications_disabled_description)
            },
            onClick = { notificationsExpanded = !notificationsExpanded },
        )
        if (notificationsExpanded) {
            TelegramSettingsInlineDetail {
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
        TelegramSettingsRowDivider()
        TelegramSettingsRow(
            icon = Icons.Outlined.Sync,
            iconColor = Color(0xFFF4A328),
            title = stringResource(R.string.auto_reconnect_title),
            subtitle = reconnectStatus,
            onClick = { reconnectExpanded = !reconnectExpanded },
        )
        if (reconnectExpanded) {
            TelegramSettingsInlineDetail {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScopePill(
                        label = stringResource(R.string.auto_reconnect_enabled),
                        selected = autoReconnectEnabled,
                        onClick = { onAutoReconnectEnabledChange(true) },
                    )
                    ScopePill(
                        label = stringResource(R.string.auto_reconnect_disabled),
                        selected = !autoReconnectEnabled,
                        onClick = { onAutoReconnectEnabledChange(false) },
                    )
                }
            }
        }
        TelegramSettingsRowDivider()
        TelegramSettingsRow(
            icon = Icons.Outlined.Public,
            iconColor = Color(0xFFB468F4),
            title = stringResource(R.string.language_title),
            subtitle = currentLanguage.label(context),
            onClick = { languageExpanded = !languageExpanded },
        )
        if (languageExpanded) {
            TelegramSettingsInlineDetail {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
}

@Composable
private fun SettingsSecuritySection(
    currentPreset: CodexPermissionPreset,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    TelegramSettingsGroupCard {
        TelegramSettingsRow(
            icon = Icons.Outlined.Security,
            iconColor = Color(0xFF58C04D),
            title = stringResource(R.string.security_title),
            subtitle = currentPreset.displayName(context),
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            TelegramSettingsInlineDetail {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
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
}

@Composable
private fun SettingsBridgeSection(
    uiState: AcpUiState,
    onListenAddressChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var expanded by rememberSaveable(
        uiState.bridgeListenAddress,
        uiState.bridgeApiToken,
    ) {
        mutableStateOf(
            uiState.bridgeListenAddress.isNotBlank() ||
                uiState.bridgeApiToken.isNotBlank()
        )
    }

    TelegramSettingsGroupCard {
        TelegramSettingsRow(
            icon = Icons.Outlined.SettingsEthernet,
            iconColor = Color(0xFF35B3C9),
            title = stringResource(R.string.bridge_title),
            subtitle = stringResource(R.string.overview_bridge_status, uiState.bridgeStatus),
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            TelegramSettingsInlineDetail {
                CompactBridgeEditor(
                    uiState = uiState,
                    onListenAddressChange = onListenAddressChange,
                    onApiTokenChange = onApiTokenChange,
                    onStart = onStart,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun CompactConnectionEditor(
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
    var authExpanded by rememberSaveable(
        uiState.serverMode,
        uiState.bearerToken,
        uiState.cfAccessClientId,
        uiState.cfAccessClientSecret,
    ) {
        mutableStateOf(
            uiState.bearerToken.isNotBlank() ||
                uiState.cfAccessClientId.isNotBlank() ||
                uiState.cfAccessClientSecret.isNotBlank()
        )
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
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

    InsetTextField(
        value = uiState.endpoint,
        onValueChange = onEndpointChange,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(
            if (isCodex) {
                R.string.field_codex_ws
            } else {
                R.string.field_acp_ws
            }
        ),
        supportingText = if (isCodex) {
            stringResource(R.string.field_codex_ws_hint)
        } else {
            stringResource(R.string.field_acp_ws_hint)
        },
        singleLine = true,
    )

    InsetTextField(
        value = uiState.workingDirectory,
        onValueChange = onWorkingDirectoryChange,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.field_working_directory),
        supportingText = if (isCodex) {
            stringResource(R.string.field_working_directory_hint_codex)
        } else {
            stringResource(R.string.field_working_directory_hint_acp)
        },
        singleLine = true,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { authExpanded = !authExpanded }) {
            Text(
                text = stringResource(
                    if (authExpanded) {
                        R.string.action_collapse
                    } else {
                        R.string.action_show_more
                    }
                ),
                fontSize = 12.sp,
            )
        }
    }

    if (authExpanded) {
        InsetTextField(
            value = uiState.bearerToken,
            onValueChange = onBearerTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_bearer_token),
            singleLine = true,
        )

        InsetTextField(
            value = uiState.cfAccessClientId,
            onValueChange = onCfAccessClientIdChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_cf_access_client_id),
            singleLine = true,
        )

        InsetTextField(
            value = uiState.cfAccessClientSecret,
            onValueChange = onCfAccessClientSecretChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_cf_access_client_secret),
            singleLine = true,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallToolbarButton(
            label = stringResource(R.string.action_connect),
            onClick = onConnect,
            modifier = Modifier.weight(1f),
        )
        SmallToolbarButton(
            label = stringResource(R.string.action_disconnect),
            onClick = onDisconnect,
            modifier = Modifier.weight(1f),
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
            modifier = if (isCodex) Modifier.fillMaxWidth() else Modifier.weight(1f),
            outlined = true,
        )
        if (!isCodex) {
            SmallToolbarButton(
                label = stringResource(R.string.action_create_session),
                onClick = onCreateSession,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CompactBridgeEditor(
    uiState: AcpUiState,
    onListenAddressChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    InsetTextField(
        value = uiState.bridgeListenAddress,
        onValueChange = onListenAddressChange,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.field_listen_address),
        supportingText = stringResource(R.string.field_listen_address_hint),
        singleLine = true,
    )
    InsetTextField(
        value = uiState.bridgeApiToken,
        onValueChange = onApiTokenChange,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.field_bridge_bearer_token),
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
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallToolbarButton(
            label = stringResource(R.string.action_start),
            onClick = onStart,
            modifier = Modifier.weight(1f),
        )
        SmallToolbarButton(
            label = stringResource(R.string.action_stop),
            onClick = onStop,
            modifier = Modifier.weight(1f),
            outlined = true,
        )
    }
}

@Composable
private fun SettingsPreferencesCard(
    currentLanguage: AppLanguage,
    notificationsGranted: Boolean,
    autoReconnectEnabled: Boolean,
    reconnectStatus: String,
    onRequestNotificationPermission: () -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onAutoReconnectEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SettingsPanelCard {
        Text(
            text = stringResource(R.string.settings_preferences_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.settings_preferences_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                }
                SettingsValuePill(
                    text = currentLanguage.label(context),
                    emphasized = true,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
        SettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        SettingsDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.auto_reconnect_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = reconnectStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsValuePill(
                    text = stringResource(
                        if (autoReconnectEnabled) {
                            R.string.auto_reconnect_enabled
                        } else {
                            R.string.auto_reconnect_disabled
                        }
                    ),
                    emphasized = autoReconnectEnabled,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopePill(
                    label = stringResource(R.string.auto_reconnect_enabled),
                    selected = autoReconnectEnabled,
                    onClick = { onAutoReconnectEnabledChange(true) },
                )
                ScopePill(
                    label = stringResource(R.string.auto_reconnect_disabled),
                    selected = !autoReconnectEnabled,
                    onClick = { onAutoReconnectEnabledChange(false) },
                )
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
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
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
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
    val modeLabel = stringResource(
        if (uiState.serverMode == ServerMode.CodexAppServer) {
            R.string.overview_mode_codex
        } else {
            R.string.overview_mode_acp_bridge
        }
    )
    val summaryLabel = if (uiState.serverMode == ServerMode.CodexAppServer) {
        stringResource(
            R.string.overview_threads_pending,
            uiState.sessionSummaries.size,
            uiState.pendingApprovals.size,
        )
    } else {
        stringResource(R.string.overview_bridge_status, uiState.bridgeStatus)
    }
    SettingsPanelCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = topBarConnectionLabel(uiState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsValuePill(
                text = uiState.initializationStatus,
                emphasized = uiState.isInitialized,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                text = uiState.connectionStatus,
            )
            StatusChip(
                icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                text = uiState.reconnectStatus,
            )
            if (uiState.serverMode == ServerMode.ACP) {
                StatusChip(
                    icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    text = uiState.bridgeStatus,
                )
            }
        }
        Text(
            text = summaryLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                text = stringResource(R.string.project_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.project_empty_description),
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
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
    val displayTitle = threadConversationTitle(
        fallbackTitle = summary.title,
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
        threadId = summary.id,
    )
    val subtitleParts = buildList {
        when {
            isRunning -> add(stringResource(R.string.thread_running))
            isRecentlyCompleted -> add(stringResource(R.string.thread_recently_completed))
        }
        add(stringResource(R.string.label_thread_short, summary.id.take(6)))
    }
    val subtitle = subtitleParts.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThreadAvatar(
                label = threadAvatarLabel(displayTitle),
                seed = summary.id,
                highlighted = isRunning,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                summary.updatedAtEpochSeconds?.let { updatedAt ->
                    Text(
                        text = sessionCompactTimeLabel(updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (isRunning || isRecentlyCompleted) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isRunning) {
                                    Color(0xFF4CC85A)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                                },
                                shape = CircleShape,
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadAvatar(
    label: String,
    seed: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val avatarColor = threadAvatarColor(seed)
    Box(
        modifier = modifier.size(54.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = avatarColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
        }
        if (highlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF4CC85A), CircleShape)
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
        shape = PillShape,
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
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            SmallToolbarButton(
                label = stringResource(R.string.action_show_more),
                onClick = onLoadMore,
            )
        }
    }
}

@Composable
private fun AcpHomeCard(
    uiState: AcpUiState,
    onOpenSettings: () -> Unit,
) {
    Card(
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            SmallToolbarButton(
                label = stringResource(R.string.action_go_settings),
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun SecuritySettingsCard(
    currentPreset: CodexPermissionPreset,
    onPermissionPresetChange: (CodexPermissionPreset) -> Unit,
) {
    val context = LocalContext.current
    SettingsPanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
            }
            SettingsValuePill(
                text = currentPreset.displayName(context),
                emphasized = true,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
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
    val displayTitle = threadConversationTitle(
        fallbackTitle = currentSummary?.title ?: threadTitle,
        unnamedTitle = stringResource(R.string.thread_title_unnamed),
        threadId = threadId,
    )
    val statusSubtitle = when {
        !isLoaded -> stringResource(R.string.thread_detail_loading_subtitle)
        uiState.isStreaming -> stringResource(R.string.thread_running)
        else -> stringResource(R.string.thread_detail_loaded_subtitle)
    }
    val projectLabel = projectNameFromPath(threadPath)
        ?: threadPath
        ?: stringResource(R.string.project_card_default_path)
    val headerSubtitle = buildString {
        if (projectLabel.isNotBlank() && projectLabel != displayTitle) {
            append(projectLabel)
            append(" · ")
        }
        append(statusSubtitle)
    }
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
    val timelineItems = remember(visibleEntries) {
        buildTranscriptTimelineItems(visibleEntries)
    }
    val hasPendingApprovals = isLoaded && uiState.pendingApprovals.isNotEmpty()
    val leadingStaticItemCount = 1 + if (hasPendingApprovals) 1 else 0
    val listState = rememberLazyListState()

    LaunchedEffect(isLoaded, timelineItems.size, leadingStaticItemCount) {
        if (!isLoaded || timelineItems.isEmpty()) {
            previousTranscriptSize = transcriptEntries.size
            return@LaunchedEffect
        }

        if (!initialBottomAligned) {
            listState.scrollToItem(leadingStaticItemCount + timelineItems.lastIndex)
            initialBottomAligned = true
            previousTranscriptSize = transcriptEntries.size
            return@LaunchedEffect
        }

        val appendedNewEntries = transcriptEntries.size > previousTranscriptSize
        if (appendedNewEntries) {
            val lastVisibleAbsoluteIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val currentBottomIndex = leadingStaticItemCount + timelineItems.lastIndex
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

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TelegramThreadHeader(
                    title = displayTitle,
                    subtitle = headerSubtitle,
                    avatarSeed = currentSummary?.id ?: threadId,
                    onBack = onBack,
                    onRefresh = onRefresh,
                    highlighted = isLoaded && uiState.isStreaming,
                )
            },
            bottomBar = {
                ThreadPromptBar(
                    uiState = uiState,
                    enabled = isLoaded,
                    onPromptChange = onPromptChange,
                    onSend = onSend,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = PaddingValues(
                    start = PageHorizontalPadding,
                    top = 10.dp,
                    end = PageHorizontalPadding,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    ThreadDetailHeaderCard(
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
                    items(timelineItems, key = { it.key }) { item ->
                        when (item) {
                            is TranscriptEntryTimelineItem -> TranscriptEntryCard(entry = item.entry)
                            is TranscriptAssistantTimelineItem -> TranscriptEntryCard(
                                entry = item.assistant,
                                attachedTechnicalEntries = item.details,
                            )
                            is TranscriptTechnicalTimelineItem -> TranscriptTechnicalGroupCard(
                                details = item.details,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramThreadHeader(
    title: String,
    subtitle: String,
    avatarSeed: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    highlighted: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            ThreadAvatar(
                label = threadAvatarLabel(title),
                seed = avatarSeed,
                highlighted = highlighted,
                modifier = Modifier.size(42.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChromeIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.action_refresh_threads),
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun ThreadDetailHeaderCard(
    totalEntries: Int,
    visibleEntries: Int,
    hiddenEntries: Int,
    isStreaming: Boolean,
) {
    val summaryText = buildString {
        append(
            stringResource(
                R.string.thread_detail_header_summary,
                totalEntries,
                visibleEntries,
            )
        )
        if (hiddenEntries > 0) {
            append(
                stringResource(
                    R.string.thread_detail_header_more,
                    TRANSCRIPT_PAGE_SIZE,
                    hiddenEntries,
                )
            )
        }
        if (isStreaming) {
            append(stringResource(R.string.thread_detail_header_running))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Card(
            shape = PillShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Text(
                text = summaryText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
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
    val sendEnabled = enabled && uiState.prompt.trim().isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PageHorizontalPadding, vertical = 12.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .weight(1f),
                enabled = enabled,
                minLines = 1,
                maxLines = 5,
                placeholder = {
                    Text(
                        text = if (enabled) {
                            stringResource(R.string.thread_prompt_label)
                        } else {
                            stringResource(R.string.thread_prompt_hint_disabled)
                        },
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                ),
            )
            IconButton(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (sendEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.action_send),
                    tint = if (sendEnabled) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
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
    SettingsPanelCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
            }
            SettingsValuePill(
                text = uiState.connectionStatus,
                emphasized = uiState.isConnected,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallToolbarButton(
                label = stringResource(
                    if (uiState.isConnected) {
                        R.string.action_rescan
                    } else {
                        R.string.action_scan
                    }
                ),
                onClick = onScanPairCode,
                modifier = Modifier.weight(1f),
            )
            SmallToolbarButton(
                label = stringResource(
                    if (showManualConfig) {
                        R.string.action_collapse
                    } else {
                        R.string.action_manual
                    }
                ),
                onClick = onToggleManualConfig,
                modifier = Modifier.weight(1f),
                outlined = true,
            )
        }
        if (showManualConfig) {
            Text(
                text = stringResource(R.string.settings_manual_section_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var authExpanded by rememberSaveable(
        uiState.serverMode,
        uiState.bearerToken,
        uiState.cfAccessClientId,
        uiState.cfAccessClientSecret,
    ) {
        mutableStateOf(
            uiState.bearerToken.isNotBlank() ||
                uiState.cfAccessClientId.isNotBlank() ||
                uiState.cfAccessClientSecret.isNotBlank()
        )
    }

    SettingsPanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.advanced_connection_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = topBarConnectionLabel(uiState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsValuePill(
                text = uiState.connectionStatus,
                emphasized = uiState.isConnected,
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
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

        InsetTextField(
            value = uiState.endpoint,
            onValueChange = onEndpointChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(
                if (isCodex) {
                    R.string.field_codex_ws
                } else {
                    R.string.field_acp_ws
                }
            ),
            supportingText = if (isCodex) {
                stringResource(R.string.field_codex_ws_hint)
            } else {
                stringResource(R.string.field_acp_ws_hint)
            },
            singleLine = true,
        )

        InsetTextField(
            value = uiState.workingDirectory,
            onValueChange = onWorkingDirectoryChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_working_directory),
            supportingText = if (isCodex) {
                stringResource(R.string.field_working_directory_hint_codex)
            } else {
                stringResource(R.string.field_working_directory_hint_acp)
            },
            singleLine = true,
        )

        SettingsDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_auth_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_auth_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { authExpanded = !authExpanded }) {
                Text(
                    text = stringResource(
                        if (authExpanded) {
                            R.string.action_collapse
                        } else {
                            R.string.action_show_more
                        }
                    ),
                )
            }
        }

        if (authExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InsetTextField(
                    value = uiState.bearerToken,
                    onValueChange = onBearerTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.field_bearer_token),
                    singleLine = true,
                )

                InsetTextField(
                    value = uiState.cfAccessClientId,
                    onValueChange = onCfAccessClientIdChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.field_cf_access_client_id),
                    singleLine = true,
                )

                InsetTextField(
                    value = uiState.cfAccessClientSecret,
                    onValueChange = onCfAccessClientSecretChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.field_cf_access_client_secret),
                    singleLine = true,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallToolbarButton(
                label = stringResource(R.string.action_connect),
                onClick = onConnect,
                modifier = Modifier.weight(1f),
            )
            SmallToolbarButton(
                label = stringResource(R.string.action_disconnect),
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
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
                modifier = if (isCodex) Modifier.fillMaxWidth() else Modifier.weight(1f),
                outlined = true,
            )
            if (!isCodex) {
                SmallToolbarButton(
                    label = stringResource(R.string.action_create_session),
                    onClick = onCreateSession,
                    modifier = Modifier.weight(1f),
                )
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
    ScopePill(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
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
            InsetTextField(
                value = uiState.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
                label = stringResource(R.string.field_prompt),
                supportingText = if (uiState.serverMode == ServerMode.CodexAppServer) {
                    stringResource(R.string.prompt_hint_codex)
                } else {
                    stringResource(R.string.prompt_hint_acp)
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
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    contentPadding = PaddingValues(vertical = 4.dp),
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier.padding(contentPadding),
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
                    val hasDetails = !approval.command.isNullOrBlank() ||
                        !approval.cwd.isNullOrBlank() ||
                        !approval.reason.isNullOrBlank()
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        }

                        if (hasDetails) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 196.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                approval.command?.let { command ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                        )
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                text = command,
                                                modifier = Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
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
                            }
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
private fun TranscriptDisclosureRow(
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    textColor: Color,
    accentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = if (expanded) {
                Icons.Outlined.ExpandLess
            } else {
                Icons.Outlined.ExpandMore
            },
            contentDescription = stringResource(
                if (expanded) {
                    R.string.action_collapse
                } else {
                    R.string.action_show_more
                }
            ),
            tint = accentColor,
        )
    }
}

@Composable
private fun TranscriptTechnicalBody(
    text: String,
    contentColor: Color,
    containerColor: Color,
    borderColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun TranscriptCodeDisclosure(
    entryId: String,
    blockIndex: Int,
    language: String?,
    code: String,
    isUser: Boolean,
) {
    val lineCount = transcriptLineCount(code)
    val kindLabel = stringResource(
        when {
            transcriptLooksLikeDiff(code) -> R.string.transcript_code_kind_diff
            transcriptLooksLikeCommand(code) ||
                (language ?: "").equals("bash", ignoreCase = true) ||
                (language ?: "").equals("sh", ignoreCase = true) ||
                (language ?: "").equals("shell", ignoreCase = true) -> R.string.transcript_code_kind_command
            else -> R.string.transcript_code_kind_code
        }
    )
    var expanded by rememberSaveable(entryId, blockIndex) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TranscriptDisclosureRow(
            summary = stringResource(
                R.string.transcript_folded_code_summary,
                lineCount,
                kindLabel,
            ),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            textColor = if (isUser) {
                Color.White.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            accentColor = if (isUser) {
                Color.White.copy(alpha = 0.82f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        if (expanded) {
            TranscriptTechnicalBody(
                text = code,
                contentColor = if (isUser) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                containerColor = if (isUser) {
                    Color.White.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                borderColor = if (isUser) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
                },
            )
        }
    }
}

@Composable
private fun TranscriptEntryContent(
    entry: TranscriptEntry,
    bodyText: String,
    isUser: Boolean,
    isNeutral: Boolean,
    contentColor: Color,
) {
    if (isNeutral) {
        val lineCount = transcriptLineCount(bodyText)
        var expanded by rememberSaveable(entry.id) {
            mutableStateOf(!transcriptShouldAutoCollapse(entry, bodyText))
        }
        val summary = stringResource(
            if (entry.role == TranscriptRole.Tool) {
                R.string.transcript_folded_tool_summary
            } else {
                R.string.transcript_folded_system_summary
            },
            lineCount,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TranscriptDisclosureRow(
                summary = summary,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            if (expanded) {
                TranscriptTechnicalBody(
                    text = bodyText,
                    contentColor = contentColor,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                )
            }
        }
        return
    }

    val segments = remember(bodyText) { parseTranscriptRenderSegments(bodyText) }
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is TranscriptTextSegment -> SelectionContainer {
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }

            is TranscriptCodeSegment -> TranscriptCodeDisclosure(
                entryId = entry.id,
                blockIndex = index,
                language = segment.language,
                code = segment.code,
                isUser = isUser,
            )
        }
    }
}

@Composable
private fun transcriptProcessKind(entry: TranscriptEntry, text: String): Int =
    when {
        transcriptLooksLikeDiff(text) -> R.string.transcript_code_kind_diff
        transcriptLooksLikeCommand(text) -> R.string.transcript_code_kind_command
        entry.role == TranscriptRole.System -> R.string.transcript_process_kind_system
        else -> R.string.transcript_process_kind_tool
    }

@Composable
private fun TranscriptExecutionStepCard(
    stepIndex: Int,
    entry: TranscriptEntry,
) {
    val bodyText = entry.text.ifBlank { stringResource(R.string.label_waiting_output) }
    val kindLabel = stringResource(transcriptProcessKind(entry, bodyText))
    val lineCount = transcriptLineCount(bodyText)
    var dialogOpen by rememberSaveable(entry.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.clickable(onClick = { dialogOpen = true }),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${stepIndex + 1}. ${entry.title}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.transcript_process_step_meta,
                            kindLabel,
                            lineCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.action_open),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = bodyText.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (entry.role == TranscriptRole.Tool || entry.role == TranscriptRole.System) {
                    FontFamily.Monospace
                } else {
                    null
                },
            )
        }
    }

    if (dialogOpen) {
        TranscriptExecutionDetailDialog(
            stepIndex = stepIndex,
            entry = entry,
            kindLabel = kindLabel,
            lineCount = lineCount,
            bodyText = bodyText,
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun TranscriptExecutionDetailDialog(
    stepIndex: Int,
    entry: TranscriptEntry,
    kindLabel: String,
    lineCount: Int,
    bodyText: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.84f),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${stepIndex + 1}. ${entry.title}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.transcript_process_step_meta,
                                    kindLabel,
                                    lineCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            entry.turnId?.let { turnId ->
                                Text(
                                    text = turnId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = bodyText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantExecutionDisclosure(
    details: List<TranscriptEntry>,
) {
    if (details.isEmpty()) {
        return
    }

    val commandCount = details.count { transcriptProcessKind(it, it.text) == R.string.transcript_code_kind_command }
    val fileCount = details.count { transcriptProcessKind(it, it.text) == R.string.transcript_code_kind_diff }
    val otherCount = (details.size - commandCount - fileCount).coerceAtLeast(0)
    val summaryTitle = stringResource(R.string.transcript_process_summary, details.size)
    val summaryParts = mutableListOf<String>()
    if (commandCount > 0) {
        summaryParts += stringResource(R.string.transcript_process_command_count, commandCount)
    }
    if (fileCount > 0) {
        summaryParts += stringResource(R.string.transcript_process_file_count, fileCount)
    }
    if (otherCount > 0) {
        summaryParts += stringResource(R.string.transcript_process_other_count, otherCount)
    }
    val summary = if (summaryParts.isEmpty()) {
        summaryTitle
    } else {
        "$summaryTitle · ${summaryParts.joinToString(" · ")}"
    }
    var expanded by rememberSaveable(details.map { it.id }.joinToString("|")) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
        )
        TranscriptDisclosureRow(
            summary = summary,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            accentColor = MaterialTheme.colorScheme.primary,
        )
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details.forEachIndexed { index, detail ->
                    TranscriptExecutionStepCard(
                        stepIndex = index,
                        entry = detail,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptTechnicalGroupCard(
    details: List<TranscriptEntry>,
) {
    if (details.isEmpty()) {
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistantExecutionDisclosure(details = details)
            }
        }
    }
}

@Composable
private fun TranscriptEntryCard(
    entry: TranscriptEntry,
    attachedTechnicalEntries: List<TranscriptEntry> = emptyList(),
) {
    val waitingOutputLabel = stringResource(R.string.label_waiting_output)
    val bodyText = entry.text.ifBlank { waitingOutputLabel }
    val isUser = entry.role == TranscriptRole.User
    val isNeutral = entry.role == TranscriptRole.Tool || entry.role == TranscriptRole.System
    val bubbleShape = when (entry.role) {
        TranscriptRole.User -> RoundedCornerShape(
            topStart = 22.dp,
            topEnd = 22.dp,
            bottomStart = 22.dp,
            bottomEnd = 8.dp,
        )

        TranscriptRole.Assistant -> RoundedCornerShape(
            topStart = 22.dp,
            topEnd = 22.dp,
            bottomStart = 8.dp,
            bottomEnd = 22.dp,
        )

        TranscriptRole.Tool,
        TranscriptRole.System,
        -> RoundedCornerShape(20.dp)
    }
    val bubbleColor = when (entry.role) {
        TranscriptRole.User -> MaterialTheme.colorScheme.primary
        TranscriptRole.Assistant -> MaterialTheme.colorScheme.surface
        TranscriptRole.Tool -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.54f)
        TranscriptRole.System -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when (entry.role) {
        TranscriptRole.User -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when (entry.role) {
        TranscriptRole.User -> Color.Transparent
        TranscriptRole.Assistant -> MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
        TranscriptRole.Tool -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        TranscriptRole.System -> MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            isUser -> Arrangement.End
            isNeutral -> Arrangement.Center
            else -> Arrangement.Start
        },
    ) {
        Card(
            modifier = if (isNeutral) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth(0.84f)
            },
            shape = bubbleShape,
            colors = CardDefaults.cardColors(
                containerColor = bubbleColor,
            ),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                        color = if (isUser) {
                            Color.White.copy(alpha = 0.90f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    if (entry.isStreaming) {
                        Text(
                            text = stringResource(R.string.label_streaming),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                Color.White.copy(alpha = 0.82f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                entry.turnId?.let { turnId ->
                    Text(
                        text = turnId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isUser) {
                            Color.White.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TranscriptEntryContent(
                    entry = entry,
                    bodyText = bodyText,
                    isUser = isUser,
                    isNeutral = isNeutral,
                    contentColor = contentColor,
                )
                if (entry.role == TranscriptRole.Assistant && attachedTechnicalEntries.isNotEmpty()) {
                    AssistantExecutionDisclosure(details = attachedTechnicalEntries)
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
    SettingsPanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.bridge_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.bridge_background_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsValuePill(
                text = uiState.bridgeStatus,
                emphasized = uiState.bridgeStatus == stringResource(R.string.status_bridge_running),
            )
        }
        InsetTextField(
            value = uiState.bridgeListenAddress,
            onValueChange = onListenAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_listen_address),
            supportingText = stringResource(R.string.field_listen_address_hint),
            singleLine = true,
        )
        InsetTextField(
            value = uiState.bridgeApiToken,
            onValueChange = onApiTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.field_bridge_bearer_token),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallToolbarButton(
                label = stringResource(R.string.action_start),
                onClick = onStart,
                modifier = Modifier.weight(1f),
            )
            SmallToolbarButton(
                label = stringResource(R.string.action_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f),
                outlined = true,
            )
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
