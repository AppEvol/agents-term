package com.appevol.agentsterm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appevol.agentsterm.R
import com.appevol.agentsterm.data.AgentUsageUiState
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DaemonCompatibilityInfo
import com.appevol.agentsterm.data.ModelSlashCommand
import com.appevol.agentsterm.data.SshAuthMethod
import com.appevol.agentsterm.data.SshBootstrapConfig
import com.appevol.agentsterm.data.SshProfile
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.attachmentLimitsForTab
import com.appevol.agentsterm.data.captureWindowScreenshotBase64
import com.appevol.agentsterm.data.daemonIdentityKey
import com.appevol.agentsterm.data.evaluateDaemonCompatibility
import com.appevol.agentsterm.data.formatModelChipLabel
import com.appevol.agentsterm.data.parseModelSlashCommand
import com.appevol.agentsterm.data.resolveAgentModelCatalog
import com.appevol.agentsterm.data.resolveDefaultSystemPrompt
import com.appevol.agentsterm.data.resolveModelIdFromQuery
import com.appevol.agentsterm.data.resolvePreferredNewTabCwd
import com.appevol.agentsterm.data.resolveTabLatestDetail
import com.appevol.agentsterm.data.resolveTabTaskSummary
import com.appevol.agentsterm.data.shareDebugBundleFile
import com.appevol.agentsterm.terminal.PtyTabSession
import com.appevol.agentsterm.terminal.TerminalThemePalette
import com.appevol.agentsterm.ui.theme.AppTheme
import com.appevol.agentsterm.ui.theme.LunaTabBorder
import com.appevol.agentsterm.ui.theme.LunaTabPageBorder
import com.appevol.agentsterm.ui.theme.MonoFontFamily
import com.appevol.agentsterm.ui.theme.ThemedAlertDialog
import com.appevol.agentsterm.ui.theme.ThemedButton
import com.appevol.agentsterm.ui.theme.ThemedOutlinedButton
import com.appevol.agentsterm.ui.theme.ThemedSwitch
import com.appevol.agentsterm.ui.theme.ThemedTextButton
import com.appevol.agentsterm.ui.theme.ThemedTextField
import com.appevol.agentsterm.ui.theme.TitleBarBackground
import com.appevol.agentsterm.ui.theme.TitleBarIconButton
import com.appevol.agentsterm.ui.theme.isLunaControls
import com.appevol.agentsterm.ui.theme.statusColor
import com.appevol.agentsterm.ui.theme.statusLabel
import com.appevol.agentsterm.ui.theme.themedCorner
import com.appevol.agentsterm.ui.theme.titleBarColors
import com.appevol.agentsterm.ui.theme.titleBarTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Keep the editors composed while their tabs are open, including while Settings covers the workspace.
@Composable
private fun rememberActiveDraft(state: WorkspaceState): TextFieldState? {
    var activeDraft: TextFieldState? = null
    val selectedId = state.activeTab?.id
    for (tab in state.tabs) {
        val draft = key(state.config.host, state.config.port, tab.id) { rememberTextFieldState() }
        if (tab.id == selectedId) activeDraft = draft
    }
    return activeDraft
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsTermApp(viewModel: MainViewModel) {
    val state by viewModel.chromeState.collectAsStateWithLifecycle()
    val activeDraft = rememberActiveDraft(state)
    val showConnect by viewModel.showConnectScreen.collectAsStateWithLifecycle()
    val preferSshForConnect by viewModel.preferSshModeForConnect.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettingsScreen.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val pendingPairing by viewModel.pendingPairingConfig.collectAsStateWithLifecycle()
    val pendingSshAutofill by viewModel.pendingSshAutofill.collectAsStateWithLifecycle()
    val sshProfile by viewModel.sshProfile.collectAsStateWithLifecycle()
    // SSH progress arrives on I/O threads, even when the Compose test dispatcher is unconfined.
    val bootstrapState by viewModel.bootstrapState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsStateWithLifecycle()
    val usageByAgent by viewModel.usageController.usageByAgent.collectAsStateWithLifecycle()
    val isRefreshingClosedTabs by viewModel.isRefreshingClosedTabs.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.attachments.pendingByTab.collectAsStateWithLifecycle()
    // Null hides the attach button: on shell tabs, and on daemons that predate attachments.
    val composerAttachments = remember(state.activeTab, state.serverInfo?.attachments, pendingAttachments) {
        val tab = state.activeTab
        attachmentLimitsForTab(state.serverInfo, tab)?.let { limits ->
            ComposerAttachments(
                limits = limits,
                pending = pendingAttachments[tab?.id].orEmpty(),
                onAttach = { uris -> tab?.let { viewModel.attachments.attach(it.id, uris, limits) } },
                onRemove = { localId -> tab?.let { viewModel.attachments.remove(it.id, localId) } },
            )
        }
    }
    val revivingTabId by viewModel.revivingTabId.collectAsStateWithLifecycle()
    val createTabError by viewModel.createTabError.collectAsStateWithLifecycle()
    val isCreatingTab by viewModel.isCreatingTab.collectAsStateWithLifecycle()
    val daemonUpdateUiState by viewModel.daemonUpdateController.uiState.collectAsStateWithLifecycle()
    val daemonUpdateActions = remember(viewModel) {
        DaemonUpdateActions(
            onStartSelfUpdate = { viewModel.daemonUpdateController.startUpdateForCurrentDaemon() },
            onRestartNow = { viewModel.daemonUpdateController.forceRestartForCurrentDaemon() },
            onCancelPendingUpdate = { viewModel.daemonUpdateController.cancelPendingUpdate() },
            onRollbackDaemon = { viewModel.daemonUpdateController.rollbackDaemon() },
            onDismissUpdateError = { viewModel.daemonUpdateController.dismissError() },
            onDismissAutoRollbackError = { key -> viewModel.daemonUpdateController.dismissAutoRollbackNotice(key) },
            onOpenSshSetup = { viewModel.openConnectionSettings(preferSsh = true) },
            onStartSshBreakglassRollback = { cfg -> viewModel.daemonUpdateController.startSshBreakglassRollback(cfg) },
            onClearSshRollbackState = { viewModel.daemonUpdateController.clearSshRollbackState() },
        )
    }

    if (showSettings) {
        val debugUploadState by viewModel.debugUploadState.collectAsStateWithLifecycle()
        val debugEventCount by viewModel.debugEventCount.collectAsStateWithLifecycle()
        val hasLastCrash by viewModel.hasLastCrash.collectAsStateWithLifecycle()
        SettingsScreen(
            workspaceState = state,
            sshProfile = sshProfile,
            debugSettings = debugSettings,
            appTheme = appTheme,
            onSelectTheme = { viewModel.setAppTheme(it) },
            uploadState = debugUploadState,
            eventCount = debugEventCount,
            hasLastCrash = hasLastCrash,
            recentEventsProvider = { viewModel.recentDebugEvents() },
            onBack = { viewModel.closeAppSettings() },
            onOpenVmConnection = { viewModel.openConnectionSettings() },
            onUpdateDaemon = { viewModel.openConnectionSettings(preferSsh = true) },
            daemonUpdateUiState = daemonUpdateUiState,
            daemonUpdateActions = daemonUpdateActions,
            onUpdateDebugSettings = { viewModel.updateDebugSettings(it) },
            onUploadDumpToVm = { note, shot ->
                viewModel.uploadDebugDumpToVm(note = note, screenshotBase64 = shot)
            },
            onExportOfflineZip = { note, shot ->
                viewModel.createOfflineDebugZip(note = note, screenshotBase64 = shot)
            },
            onClearDebugBuffer = { viewModel.clearDebugLogs() },
        )
        return
    }

    if (showConnect) {
        ConnectScreen(
            initialConfig = state.config,
            sshProfile = sshProfile,
            bootstrapState = bootstrapState,
            pendingPairing = pendingPairing,
            pendingSshAutofill = pendingSshAutofill,
            isTesting = isTesting,
            errorMessage = state.connectionError,
            canCancel = state.config.isValid,
            onCancel = { viewModel.closeConnectionSettingsIfConfigured() },
            onConnect = { viewModel.connectToVm(it) },
            onStartSshBootstrap = { viewModel.startSshBootstrap(it) },
            onCancelSshBootstrap = { viewModel.cancelSshBootstrap() },
            onTrustNewHostKeyAndRetry = { viewModel.trustChangedHostKeyAndRetry(it) },
            onConsumedPendingPairing = { viewModel.clearPendingPairingConfig() },
            onConsumedPendingSshAutofill = { viewModel.clearPendingSshAutofill() },
            onOpenAppSettings = { viewModel.openAppSettings() },
            preferSshMode = preferSshForConnect ||
                (state.connectionState == ConnectionState.AUTH_ERROR && sshProfile.username.isNotBlank()),
        )
        return
    }

    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNewTabSheet by remember { mutableStateOf(false) }
    var showRecentlyClosedSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showQuickDebugDialog by remember { mutableStateOf(false) }
    var pendingScreenshotBase64 by remember { mutableStateOf<String?>(null) }
    var taskSummaryTabId by remember { mutableStateOf<String?>(null) }
    var taskSummaryAllowEmpty by remember { mutableStateOf(false) }
    val activeTabDefaultSystemPrompt = remember(
        state.serverInfo?.defaultSystemPrompt,
        state.serverInfo?.defaultSystemPromptFullAccess,
        state.activeTab?.permissionMode,
    ) {
        resolveDefaultSystemPrompt(
            state.serverInfo,
            permissionMode = state.activeTab?.permissionMode ?: "full-access",
        )
    }

    fun openNewTabSheet() {
        viewModel.newTabController.clearError()
        showNewTabSheet = true
    }

    fun openRecentlyClosedSheet() {
        showRecentlyClosedSheet = true
        viewModel.refreshClosedTabs()
    }

    fun handleDeleteTab(tabId: String) {
        viewModel.deleteTab(tabId) { archived ->
            if (archived != null) {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.snackbar_tab_closed, archived.title),
                        actionLabel = context.getString(R.string.action_undo),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.reviveClosedTab(archived.id)
                    }
                }
            }
        }
    }

    fun handleCloseFinishedTabs() {
        viewModel.closeFinishedTabs { archived ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                if (archived.size == 1) {
                    val only = archived.first()
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.snackbar_tab_closed, only.title),
                        actionLabel = context.getString(R.string.action_undo),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.reviveClosedTab(only.id)
                    }
                } else {
                    val result = snackbarHostState.showSnackbar(
                        message = context.resources.getQuantityString(
                            R.plurals.snackbar_tabs_closed_count,
                            archived.size,
                            archived.size,
                        ),
                        actionLabel = context.getString(R.string.action_recently_closed_tabs),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        openRecentlyClosedSheet()
                    }
                }
            }
        }
    }

    fun openModelPickerForActiveTab() {
        val current = state.activeTab ?: return
        if (current.agent == "shell") return
        showModelPicker = true
        val serverAgent = state.serverInfo?.agents?.firstOrNull { it.type == current.agent }
        if (serverAgent == null || serverAgent.models.isEmpty()) {
            viewModel.refreshModelsForAgent(current.agent, forceRefresh = false)
        }
    }

    LaunchedEffect(state.bannerMessage) {
        val msg = state.bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    LaunchedEffect(showNewTabSheet) {
        if (!showNewTabSheet && createTabError != null) {
            viewModel.newTabController.clearError()
        }
    }

    fun triggerQuickDebugDialog() {
        scope.launch(Dispatchers.Main.immediate) {
            pendingScreenshotBase64 = if (debugSettings.includeScreenshot) {
                captureWindowScreenshotBase64(context.findActivityOrNull())
            } else {
                null
            }
            showQuickDebugDialog = true
        }
    }

    fun isSummaryCurrentlyVisibleFor(tabId: String): Boolean {
        if (taskSummaryTabId != tabId) return false
        val tab = state.tabs.firstOrNull { it.id == tabId } ?: return false
        val summary = resolveTabTaskSummary(tab, viewModel.state.value.messagesByTab[tabId].orEmpty())
        return summary != null || taskSummaryAllowEmpty
    }

    fun handleTabTap(tabId: String) {
        val currentId = state.activeTab?.id
        if (tabId == currentId) {
            if (isSummaryCurrentlyVisibleFor(tabId)) {
                taskSummaryTabId = null
                taskSummaryAllowEmpty = false
            } else {
                taskSummaryTabId = tabId
                taskSummaryAllowEmpty = true
            }
        } else {
            viewModel.selectTab(tabId)
            taskSummaryTabId = tabId
            taskSummaryAllowEmpty = false
        }
    }

    fun toggleActiveTabSummary() {
        val currentId = state.activeTab?.id ?: return
        if (isSummaryCurrentlyVisibleFor(currentId)) {
            taskSummaryTabId = null
            taskSummaryAllowEmpty = false
        } else {
            taskSummaryTabId = currentId
            taskSummaryAllowEmpty = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp
        val showTaskSummary = state.activeTab != null && taskSummaryTabId == state.activeTab?.id

        val mainScaffold: @Composable () -> Unit = {
            WorkspaceScaffold(
                state = state,
                activeMessagesFlow = viewModel.activeMessages,
                activeDraft = activeDraft,
                isExpanded = isExpanded,
                debugSharingEnabled = debugSettings.enabled,
                snackbarHostState = snackbarHostState,
                showOverflowMenu = showOverflowMenu,
                onToggleOverflow = { showOverflowMenu = it },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewTab = { openNewTabSheet() },
                onQuickDebugDump = { triggerQuickDebugDialog() },
                onSelectTab = { handleTabTap(it) },
                showTaskSummary = showTaskSummary,
                showTaskSummaryWhenEmpty = taskSummaryAllowEmpty,
                onToggleTaskSummary = { toggleActiveTabSummary() },
                onDismissTaskSummary = {
                    taskSummaryTabId = null
                    taskSummaryAllowEmpty = false
                },
                onRenameTab = { showRenameDialog = true },
                onChangePermission = { showPermissionDialog = true },
                onChangeSystemPrompt = { showSystemPromptDialog = true },
                onChangeModel = { openModelPickerForActiveTab() },
                onToggleTaskDone = { tab, markDone ->
                    val nextStatus = if (markDone) "done" else "idle"
                    viewModel.updateTabStatus(tab.id, nextStatus) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (markDone) "Tab marked as done" else "Tab marked as active",
                            )
                        }
                    }
                },
                defaultSystemPrompt = activeTabDefaultSystemPrompt,
                onDeleteTab = { handleDeleteTab(it) },
                onOpenRecentlyClosed = { openRecentlyClosedSheet() },
                onOpenSettings = { viewModel.openConnectionSettings() },
                onUpdateDaemon = { viewModel.openDaemonUpdate() },
                sshProfile = sshProfile,
                daemonUpdateUiState = daemonUpdateUiState,
                daemonUpdateActions = daemonUpdateActions,
                onOpenAppSettings = { viewModel.openAppSettings() },
                onReconnect = { viewModel.reconnectNow() },
                onSendMessage = { rawText ->
                    val current = state.activeTab
                    val modelCmd = if (current != null && current.agent != "shell") {
                        parseModelSlashCommand(rawText)
                    } else {
                        null
                    }
                    when (modelCmd) {
                        is ModelSlashCommand.OpenPicker -> openModelPickerForActiveTab()

                        is ModelSlashCommand.ResetToDefault -> {
                            if (current != null) {
                                viewModel.updateTabModel(current.id, null) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Model reset to default")
                                    }
                                }
                            }
                        }

                        is ModelSlashCommand.SetModel -> {
                            if (current != null) {
                                val (models, _) = resolveAgentModelCatalog(
                                    current.agent,
                                    state.serverInfo?.agents.orEmpty(),
                                )
                                val resolvedId = resolveModelIdFromQuery(modelCmd.modelQuery, models)
                                val label = formatModelChipLabel(resolvedId, models)
                                viewModel.updateTabModel(current.id, resolvedId) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Model switched to $label")
                                    }
                                }
                            }
                        }

                        null -> viewModel.sendMessage(rawText)
                    }
                },
                onSendStdin = { data, eof -> viewModel.sendShellStdin(data = data, eof = eof) },
                onInterrupt = { viewModel.interruptActiveTab() },
                onForceInterrupt = { draftText ->
                    viewModel.interruptActiveTab(processQueue = true, text = draftText)
                },
                onRemoveQueuedMessage = { queuedId ->
                    viewModel.removeQueuedMessage(queuedId)
                },
                onRetryFullAccess = { viewModel.retryLastUserPromptWithFullAccess() },
                composerAttachments = composerAttachments,
                ptySessionProvider = { tabId, palette -> viewModel.ptySessionFor(tabId, palette) },
                onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        }

        WorkspaceNavigationShell(
            isExpanded = isExpanded,
            drawerState = drawerState,
            state = state,
            debugSharingEnabled = debugSettings.enabled,
            onSelectTab = { handleTabTap(it) },
            onNewTab = { openNewTabSheet() },
            onDeleteTab = { handleDeleteTab(it) },
            onCloseFinishedTabs = { handleCloseFinishedTabs() },
            onOpenRecentlyClosed = { openRecentlyClosedSheet() },
            onOpenSettings = { viewModel.openConnectionSettings() },
            onOpenAppSettings = { viewModel.openAppSettings() },
            onReconnect = { viewModel.reconnectNow() },
            content = mainScaffold,
        )
    }

    if (showRecentlyClosedSheet) {
        RecentlyClosedTabsSheet(
            closedTabs = state.closedTabs,
            canReviveTab = state.canCreateTab,
            isRefreshing = isRefreshingClosedTabs,
            revivingTabId = revivingTabId,
            onRefresh = { viewModel.refreshClosedTabs() },
            onRevive = { tabId ->
                viewModel.reviveClosedTab(tabId) { revived ->
                    showRecentlyClosedSheet = false
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.snackbar_tab_revived, revived.title),
                        )
                    }
                }
            },
            onDismiss = { showRecentlyClosedSheet = false },
        )
    }

    if (showNewTabSheet) {
        NewTabSheet(
            throughput = state.throughput,
            agents = state.serverInfo?.agents.orEmpty(),
            canCreateTab = state.canCreateTab,
            ptySupported = state.serverInfo?.supportsPtyShell == true,
            createError = createTabError,
            isCreating = isCreatingTab,
            defaultCwd = resolvePreferredNewTabCwd(
                lastCwd = state.lastCwd,
                activeTabCwd = state.activeTab?.cwd,
                homeCwd = state.serverInfo?.defaultCwd,
            ),
            defaultSystemPromptForMode = { mode ->
                resolveDefaultSystemPrompt(state.serverInfo, permissionMode = mode)
            },
            onLoadInitialDirectories = { path -> viewModel.newTabController.loadInitialDirectories(path) },
            onLoadDirectories = { path -> viewModel.newTabController.loadDirectories(path) },
            onDismiss = {
                viewModel.newTabController.clearError()
                showNewTabSheet = false
            },
            onCreate = { agent, cwd, permMode, title, model, systemPrompt, shellMode ->
                viewModel.newTabController.createTab(
                    agent = agent,
                    cwd = cwd,
                    permissionMode = permMode,
                    title = title,
                    model = model,
                    systemPrompt = systemPrompt,
                    shellMode = shellMode,
                    onCreated = {
                        viewModel.newTabController.clearError()
                        showNewTabSheet = false
                    },
                )
            },
        )
    }

    if (showModelPicker && state.activeTab != null && state.activeTab!!.agent != "shell") {
        val current = state.activeTab!!
        val (models, defaultModel) = remember(current.agent, state.serverInfo?.agents) {
            resolveAgentModelCatalog(current.agent, state.serverInfo?.agents.orEmpty())
        }
        ModelPickerSheet(
            throughput = rememberThroughputLabels(state.throughput, current.agent),
            agentLabel = current.agentLabel,
            currentModel = current.model,
            defaultModel = defaultModel,
            models = models,
            isRefreshing = isRefreshingModels,
            usageState = usageByAgent[current.agent] ?: AgentUsageUiState(),
            onCheckUsage = { viewModel.usageController.checkUsage(current.agent) },
            onUpdateDaemon = {
                showModelPicker = false
                viewModel.openDaemonUpdate()
            },
            onRefresh = { viewModel.refreshModelsForAgent(current.agent, forceRefresh = true) },
            onSelectModel = { chosenModel ->
                showModelPicker = false
                viewModel.updateTabModel(current.id, chosenModel) {
                    val label = formatModelChipLabel(chosenModel, models, defaultModel)
                    scope.launch {
                        snackbarHostState.showSnackbar("Model: $label")
                    }
                }
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showRenameDialog && state.activeTab != null) {
        val current = state.activeTab!!
        var titleInput by remember(current.id) { mutableStateOf(current.title) }
        ThemedAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_title)) },
            text = {
                ThemedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_rename_label)) },
                )
            },
            confirmButton = {
                ThemedTextButton(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            viewModel.renameTab(current.id, titleInput.trim())
                        }
                        showRenameDialog = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                ThemedTextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showPermissionDialog && state.activeTab != null) {
        val current = state.activeTab!!
        val modes = listOf(
            "full-access" to "Full Access (auto-approve tools)",
            "accept-edits" to "Accept Edits",
            "plan" to "Plan Only (read-only)",
            "default" to "Default / Ask",
        )
        ThemedAlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.dialog_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in modes) {
                        val selected = current.permissionMode == value
                        Surface(
                            shape = themedCorner(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(themedCorner(10.dp))
                                .clickable {
                                    viewModel.updatePermissionMode(current.id, value)
                                    showPermissionDialog = false
                                },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                ThemedTextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showSystemPromptDialog && state.activeTab != null && state.activeTab!!.agent != "shell") {
        val current = state.activeTab!!
        val tabDefaultPrompt = resolveDefaultSystemPrompt(state.serverInfo, permissionMode = current.permissionMode)
        var includePrompt by remember(current.id, current.systemPrompt) {
            mutableStateOf(!current.systemPrompt.isNullOrBlank())
        }
        var promptInput by remember(current.id, current.systemPrompt) {
            mutableStateOf(current.systemPrompt.orEmpty())
        }
        ThemedAlertDialog(
            onDismissRequest = { showSystemPromptDialog = false },
            title = { Text(stringResource(R.string.dialog_system_prompt_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dialog_system_prompt_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_system_prompt_include_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        ThemedSwitch(
                            checked = includePrompt,
                            onCheckedChange = { checked ->
                                includePrompt = checked
                                if (checked && promptInput.isBlank()) {
                                    promptInput = tabDefaultPrompt
                                }
                            },
                            modifier = Modifier.testTag("edit_tab_system_prompt_switch"),
                        )
                    }
                    if (includePrompt) {
                        ThemedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = {
                                Text(stringResource(R.string.new_tab_system_prompt_placeholder))
                            },
                            minLines = 4,
                            maxLines = 8,
                            shape = themedCorner(12.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tab_system_prompt_input"),
                        )
                        if (promptInput.trim() != tabDefaultPrompt.trim()) {
                            ThemedTextButton(
                                onClick = { promptInput = tabDefaultPrompt },
                                modifier = Modifier.testTag("edit_tab_system_prompt_reset"),
                            ) {
                                Text(stringResource(R.string.new_tab_system_prompt_reset))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                ThemedTextButton(
                    onClick = {
                        val effective = if (includePrompt) promptInput.trim() else ""
                        showSystemPromptDialog = false
                        viewModel.updateTabSystemPrompt(current.id, effective) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (effective.isEmpty()) "System prompt cleared" else "System prompt updated",
                                )
                            }
                        }
                    },
                    modifier = Modifier.testTag("edit_tab_system_prompt_save"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                ThemedTextButton(onClick = { showSystemPromptDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showQuickDebugDialog) {
        val debugUploadState by viewModel.debugUploadState.collectAsStateWithLifecycle()
        val debugEventCount by viewModel.debugEventCount.collectAsStateWithLifecycle()
        val hasLastCrash by viewModel.hasLastCrash.collectAsStateWithLifecycle()
        var noteInput by remember { mutableStateOf("") }
        val isConnected = state.connectionState == ConnectionState.CONNECTED && state.config.isValid
        ThemedAlertDialog(
            onDismissRequest = {
                showQuickDebugDialog = false
                pendingScreenshotBase64 = null
            },
            title = { Text(stringResource(R.string.dialog_debug_dump_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_debug_dump_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val summaryParts = buildList {
                        add("$debugEventCount events")
                        if (pendingScreenshotBase64 != null) add("screenshot")
                        if (debugSettings.includeLogcat) add("logcat")
                        if (hasLastCrash) add("crash trace")
                    }.joinToString(" · ")
                    Text(
                        text = "Included: $summaryParts",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = AppTheme.colors.success,
                    )
                    ThemedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.settings_debug_note_label)) },
                        placeholder = { Text(stringResource(R.string.settings_debug_note_placeholder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_debug_note_input"),
                    )
                }
            },
            confirmButton = {
                ThemedTextButton(
                    enabled = isConnected && !debugUploadState.isUploading,
                    onClick = {
                        val shot = pendingScreenshotBase64
                        val note = noteInput.trim().ifBlank { null }
                        showQuickDebugDialog = false
                        pendingScreenshotBase64 = null
                        viewModel.uploadDebugDumpToVm(note = note, screenshotBase64 = shot) { res ->
                            scope.launch {
                                res.onSuccess { uploaded ->
                                    snackbarHostState.showSnackbar("Uploaded debug dump to ${uploaded.remoteDir}")
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar(err.message ?: "Debug upload failed")
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("quick_debug_upload_confirm"),
                ) {
                    Text(stringResource(R.string.dialog_debug_dump_upload))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemedTextButton(
                        onClick = {
                            val shot = pendingScreenshotBase64
                            val note = noteInput.trim().ifBlank { null }
                            showQuickDebugDialog = false
                            pendingScreenshotBase64 = null
                            scope.launch {
                                viewModel.createOfflineDebugZip(note = note, screenshotBase64 = shot)
                                    .onSuccess { file ->
                                        runCatching { shareDebugBundleFile(context, file) }
                                    }
                                    .onFailure { err ->
                                        snackbarHostState.showSnackbar(err.message ?: "Export failed")
                                    }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.dialog_debug_dump_share))
                    }
                    ThemedTextButton(
                        onClick = {
                            showQuickDebugDialog = false
                            pendingScreenshotBase64 = null
                        },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

@Composable
internal fun WorkspaceNavigationShell(
    isExpanded: Boolean,
    drawerState: DrawerState,
    state: WorkspaceState,
    debugSharingEnabled: Boolean,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDeleteTab: (String) -> Unit,
    onCloseFinishedTabs: () -> Unit,
    onOpenRecentlyClosed: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReconnect: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isExpanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = sidebarColor,
                modifier = Modifier
                    .width(304.dp)
                    .fillMaxHeight(),
            ) {
                TabSidebarBody(
                    state = state,
                    debugSharingEnabled = debugSharingEnabled,
                    onSelectTab = onSelectTab,
                    onNewTab = onNewTab,
                    onDeleteTab = onDeleteTab,
                    onCloseFinishedTabs = onCloseFinishedTabs,
                    onOpenRecentlyClosed = onOpenRecentlyClosed,
                    onOpenSettings = onOpenSettings,
                    onOpenAppSettings = onOpenAppSettings,
                    onReconnect = onReconnect,
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    } else {
        val scope = rememberCoroutineScope()
        BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
            scope.launch { drawerState.close() }
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                TabDrawerContent(
                    state = state,
                    debugSharingEnabled = debugSharingEnabled,
                    onSelectTab = { id ->
                        onSelectTab(id)
                        scope.launch { drawerState.close() }
                    },
                    onNewTab = {
                        scope.launch { drawerState.close() }
                        onNewTab()
                    },
                    onDeleteTab = onDeleteTab,
                    onCloseFinishedTabs = onCloseFinishedTabs,
                    onOpenRecentlyClosed = {
                        scope.launch { drawerState.close() }
                        onOpenRecentlyClosed()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onOpenAppSettings = {
                        scope.launch { drawerState.close() }
                        onOpenAppSettings()
                    },
                    onReconnect = onReconnect,
                )
            },
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScaffold(
    state: WorkspaceState,
    isExpanded: Boolean,
    debugSharingEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    showOverflowMenu: Boolean,
    onToggleOverflow: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onNewTab: () -> Unit,
    onQuickDebugDump: () -> Unit,
    onSelectTab: (String) -> Unit,
    showTaskSummary: Boolean = false,
    showTaskSummaryWhenEmpty: Boolean = false,
    onToggleTaskSummary: () -> Unit = {},
    onDismissTaskSummary: () -> Unit = {},
    onRenameTab: () -> Unit,
    onChangePermission: () -> Unit,
    onChangeSystemPrompt: () -> Unit,
    onChangeModel: () -> Unit,
    onToggleTaskDone: (TabRecord, Boolean) -> Unit,
    defaultSystemPrompt: String,
    onDeleteTab: (String) -> Unit,
    onOpenRecentlyClosed: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onUpdateDaemon: () -> Unit = onOpenSettings,
    sshProfile: SshProfile = SshProfile(),
    daemonUpdateUiState: DaemonUpdateUiState = DaemonUpdateUiState(),
    daemonUpdateActions: DaemonUpdateActions = DaemonUpdateActions(
        onStartSelfUpdate = onUpdateDaemon,
        onOpenSshSetup = onOpenSettings,
    ),
    daemonCompatibilityOverride: DaemonCompatibilityInfo? = null,
    onOpenAppSettings: () -> Unit,
    onReconnect: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendStdin: ((data: String?, eof: Boolean) -> Unit)? = null,
    onInterrupt: () -> Unit,
    onForceInterrupt: (String?) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onRetryFullAccess: () -> Unit,
    activeMessagesFlow: StateFlow<List<ChatMessage>>? = null,
    composerAttachments: ComposerAttachments? = null,
    activeDraft: TextFieldState? = rememberActiveDraft(state),
    ptySessionProvider: ((tabId: String, palette: TerminalThemePalette) -> PtyTabSession)? = null,
    onShowMessage: (String) -> Unit = {},
) {
    val activeTab = state.activeTab
    val finishedTabIds = state.finishedTabIds
    val activeTabFinished = activeTab != null && activeTab.id in finishedTabIds
    val daemonCompat = daemonCompatibilityOverride
        ?: remember(state.serverInfo) { evaluateDaemonCompatibility(state.serverInfo) }
    val daemonIdentity = remember(state.serverInfo, state.config.host, state.config.port) {
        state.serverInfo?.daemonIdentityKey(state.config.host, state.config.port)
    }
    var dismissedOutdatedDaemonKey by remember { mutableStateOf<String?>(null) }
    var showForceRestartConfirm by remember { mutableStateOf(false) }
    var showBreakglassDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copiedRollbackMsg = stringResource(R.string.snackbar_rollback_command_copied)

    fun triggerBreakglassSshRevert() {
        showBreakglassDialog = true
        val host = sshProfile.host.trim().ifBlank { state.config.host.trim() }
        if (host.isNotBlank() &&
            sshProfile.username.isNotBlank() &&
            sshProfile.authMethod == SshAuthMethod.TAILSCALE_SSH
        ) {
            val port = if (state.config.port in 1..65535) state.config.port else sshProfile.daemonPort
            daemonUpdateActions.onStartSshBreakglassRollback(
                SshBootstrapConfig(
                    host = host,
                    sshPort = sshProfile.sshPort,
                    username = sshProfile.username.trim(),
                    authMethod = SshAuthMethod.TAILSCALE_SSH,
                    daemonPort = port,
                ),
            )
        }
    }
    val activeModelInfo = remember(activeTab?.agent, activeTab?.model, state.serverInfo?.agents) {
        if (activeTab == null || activeTab.agent == "shell") {
            null
        } else {
            val (models, defaultModel) = resolveAgentModelCatalog(
                activeTab.agent,
                state.serverInfo?.agents.orEmpty(),
            )
            formatModelChipLabel(activeTab.model, models, defaultModel)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                val titleBar = AppTheme.chrome.titleBar
                TitleBarBackground {
                    TopAppBar(
                        colors = titleBarColors(),
                        navigationIcon = {
                            if (!isExpanded) {
                                TitleBarIconButton(
                                    onClick = onOpenDrawer,
                                    modifier = Modifier.testTag("open_drawer_button"),
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (state.attentionCount > 0) {
                                                Badge { Text(state.attentionCount.toString()) }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Menu,
                                            contentDescription = stringResource(R.string.action_tabs),
                                        )
                                    }
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = activeTab?.title ?: stringResource(R.string.app_name),
                                    style = titleBarTitleStyle(MaterialTheme.typography.titleMedium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(themedCorner(6.dp))
                                        .clickable(
                                            enabled = activeTab != null,
                                            onClickLabel = stringResource(R.string.action_toggle_task_summary),
                                            onClick = onToggleTaskSummary,
                                        )
                                        .testTag("top_bar_tab_title"),
                                )
                                if (activeTab != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (activeTabFinished) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = titleBar.semantic.success,
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .testTag("topbar_done_icon"),
                                            )
                                        } else {
                                            TabStatusDot(
                                                status = activeTab.status,
                                                dotSize = 7.dp,
                                                colors = titleBar.semantic,
                                                modifier = Modifier.testTag("top_bar_status_dot"),
                                            )
                                        }
                                        Text(
                                            text = "${activeTab.agentLabel} · ${activeTab.folderName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = MonoFontFamily,
                                            color = titleBar.mutedContent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .clickable(onClick = onToggleTaskSummary),
                                        )
                                        if (activeTabFinished) {
                                            Surface(
                                                shape = themedCorner(6.dp),
                                                color = titleBar.semantic.success.copy(alpha = 0.18f),
                                                modifier = Modifier.testTag("topbar_done_badge"),
                                            ) {
                                                Text(
                                                    text = statusLabel("done"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = titleBar.semantic.success,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            if (debugSharingEnabled) {
                                TitleBarIconButton(
                                    onClick = onQuickDebugDump,
                                    modifier = Modifier.testTag("top_debug_dump_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BugReport,
                                        contentDescription = stringResource(R.string.action_quick_debug_dump),
                                        tint = titleBar.semantic.success,
                                    )
                                }
                            }
                            TitleBarIconButton(
                                onClick = onNewTab,
                                enabled = state.canCreateTab,
                                modifier = Modifier.testTag("top_new_tab_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = newTabButtonLabel(state.canCreateTab),
                                )
                            }
                            Box {
                                TitleBarIconButton(
                                    onClick = { onToggleOverflow(true) },
                                    modifier = Modifier.testTag("overflow_menu_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.action_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { onToggleOverflow(false) },
                                ) {
                                    if (activeTab != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename_tab)) },
                                            onClick = {
                                                onToggleOverflow(false)
                                                onRenameTab()
                                            },
                                        )
                                        if (activeTab.agent != "shell") {
                                            DropdownMenuItem(
                                                text = { Text("Model (${activeModelInfo ?: "Default"})") },
                                                onClick = {
                                                    onToggleOverflow(false)
                                                    onChangeModel()
                                                },
                                                modifier = Modifier.testTag("menu_change_model"),
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Permission mode (${activeTab.permissionMode})") },
                                                onClick = {
                                                    onToggleOverflow(false)
                                                    onChangePermission()
                                                },
                                            )
                                            val systemPromptStatus = when {
                                                activeTab.systemPrompt.isNullOrBlank() -> "Empty"
                                                activeTab.systemPrompt.trim() == defaultSystemPrompt.trim() -> "Default"
                                                else -> "Custom"
                                            }
                                            DropdownMenuItem(
                                                text = { Text("System prompt ($systemPromptStatus)") },
                                                onClick = {
                                                    onToggleOverflow(false)
                                                    onChangeSystemPrompt()
                                                },
                                                modifier = Modifier.testTag("menu_system_prompt"),
                                            )
                                            if (activeTab.status != "working") {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                if (activeTabFinished) {
                                                                    R.string.action_mark_tab_active
                                                                } else {
                                                                    R.string.action_mark_task_done
                                                                },
                                                            ),
                                                        )
                                                    },
                                                    onClick = {
                                                        onToggleOverflow(false)
                                                        onToggleTaskDone(activeTab, !activeTabFinished)
                                                    },
                                                    modifier = Modifier.testTag("menu_toggle_task_done"),
                                                )
                                            }
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.action_close_tab),
                                                    color = AppTheme.colors.danger,
                                                )
                                            },
                                            onClick = {
                                                onToggleOverflow(false)
                                                onDeleteTab(activeTab.id)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.closedTabs.isNotEmpty()) {
                                                    stringResource(
                                                        R.string.action_recently_closed_tabs_count,
                                                        state.closedTabs.size,
                                                    )
                                                } else {
                                                    stringResource(R.string.action_recently_closed_tabs)
                                                },
                                            )
                                        },
                                        onClick = {
                                            onToggleOverflow(false)
                                            onOpenRecentlyClosed()
                                        },
                                        modifier = Modifier.testTag("menu_recently_closed_tabs"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_app_settings)) },
                                        onClick = {
                                            onToggleOverflow(false)
                                            onOpenAppSettings()
                                        },
                                        modifier = Modifier.testTag("menu_app_settings"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_vm_settings)) },
                                        onClick = {
                                            onToggleOverflow(false)
                                            onOpenSettings()
                                        },
                                    )
                                }
                            }
                        },
                    )
                }

                // Connection warning strip when offline, auth-rejected, or reconnecting
                if (state.connectionState != ConnectionState.CONNECTED) {
                    val isAuthError = state.connectionState == ConnectionState.AUTH_ERROR
                    Surface(
                        color = when (state.connectionState) {
                            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
                            ConnectionState.AUTH_ERROR -> MaterialTheme.colorScheme.errorContainer
                            else -> AppTheme.colors.warning.copy(alpha = 0.2f)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("connection_banner"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isAuthError) Icons.Rounded.Lock else Icons.Rounded.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = when {
                                    daemonUpdateUiState.awaitingReconnectAfterRestart &&
                                        !daemonUpdateUiState.reconnectTimedOutAfterUpdate ->
                                        stringResource(R.string.status_daemon_update_restarting)

                                    state.connectionState == ConnectionState.CONNECTING ->
                                        stringResource(R.string.status_connecting_to, state.config.host)

                                    state.connectionState == ConnectionState.AUTH_ERROR ->
                                        stringResource(R.string.status_auth_error)

                                    else ->
                                        state.connectionError
                                            ?: stringResource(R.string.status_disconnected_default)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isAuthError) {
                                ThemedTextButton(
                                    onClick = onOpenSettings,
                                    modifier = Modifier.testTag("banner_repair_button"),
                                ) {
                                    Text(stringResource(R.string.action_repair_connection))
                                }
                            } else if (state.connectionState != ConnectionState.CONNECTING) {
                                IconButton(
                                    onClick = onReconnect,
                                    modifier = Modifier.testTag("banner_reconnect_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.action_retry),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (daemonUpdateUiState.reconnectTimedOutAfterUpdate) {
                        DaemonBreakglassRecoveryBanner(
                            daemonPort = state.config.port,
                            onOpenRevertViaSsh = { triggerBreakglassSshRevert() },
                            onCopyCommand = { cmd ->
                                clipboard.setText(AnnotatedString(cmd))
                                onShowMessage(copiedRollbackMsg)
                            },
                        )
                    }
                }

                // Live daemon self-update progress, deferred restart, or error strip
                val hideTimedOutRestart = daemonUpdateUiState.reconnectTimedOutAfterUpdate &&
                    state.daemonUpdateStatus?.isRestarting == true
                val hasActiveStatus = state.daemonUpdateStatus?.isActiveOverlay == true && !hideTimedOutRestart
                val hasActiveUpdateOverlay = hasActiveStatus ||
                    daemonUpdateUiState.isSubmitting ||
                    !daemonUpdateUiState.localError.isNullOrBlank()
                if (hasActiveUpdateOverlay) {
                    DaemonUpdateStatusBanner(
                        status = state.daemonUpdateStatus,
                        localError = daemonUpdateUiState.localError,
                        isSubmitting = daemonUpdateUiState.isSubmitting,
                        onRequestForceRestart = { showForceRestartConfirm = true },
                        onCancelPending = daemonUpdateActions.onCancelPendingUpdate,
                        onRetry = daemonUpdateActions.onStartSelfUpdate,
                        onOpenSshSetup = daemonUpdateActions.onOpenSshSetup,
                        onDismissError = daemonUpdateActions.onDismissUpdateError,
                    )
                }

                // Auto-rollback notice from previous failed daemon update
                val autoRollbackError = daemonCompat.lastAutoRollbackError
                val autoRollbackKey = remember(daemonIdentity, autoRollbackError) {
                    if (autoRollbackError != null) "${daemonIdentity.orEmpty()}|$autoRollbackError" else null
                }
                val canShowConnectedBanner = state.connectionState == ConnectionState.CONNECTED && !hasActiveUpdateOverlay
                val isAutoRollbackVisible = autoRollbackError != null &&
                    autoRollbackKey != null &&
                    daemonUpdateUiState.dismissedAutoRollbackErrorKey != autoRollbackKey
                if (canShowConnectedBanner && isAutoRollbackVisible) {
                    DaemonAutoRollbackBanner(
                        errorText = autoRollbackError,
                        onRetry = daemonUpdateActions.onStartSelfUpdate,
                        onDismiss = { daemonUpdateActions.onDismissAutoRollbackError(autoRollbackKey) },
                    )
                }

                // Outdated / mismatched VM daemon banner (wait-for-tap)
                val isOutdatedUndismissed = daemonCompat.isOutdated &&
                    daemonIdentity != null &&
                    dismissedOutdatedDaemonKey != daemonIdentity
                if (canShowConnectedBanner && isOutdatedUndismissed) {
                    OutdatedDaemonBanner(
                        daemonCompat = daemonCompat,
                        onUpdate = onUpdateDaemon,
                        onDismiss = { dismissedOutdatedDaemonKey = daemonIdentity },
                    )
                }

                // Quick horizontal tab strip on compact screens when multiple tabs exist
                if (!isExpanded && state.tabs.size > 1) {
                    QuickTabStrip(
                        tabs = state.tabs,
                        activeTabId = activeTab?.id,
                        finishedTabIds = finishedTabIds,
                        onSelect = onSelectTab,
                    )
                }

                // Task summary card shown when tapping a tab or toggling the tab header
                ActiveTabSummaryBanner(
                    activeTab = activeTab,
                    showTaskSummary = showTaskSummary,
                    showTaskSummaryWhenEmpty = showTaskSummaryWhenEmpty,
                    activeTabFinished = activeTabFinished,
                    activeMessagesFlow = activeMessagesFlow,
                    fallbackMessages = state.activeMessages,
                    onDismiss = onDismissTaskSummary,
                )
            }
        },
    ) { innerPadding ->
        if (activeTab == null) {
            NoTabsPlaceholder(
                connectionState = state.connectionState,
                hostname = state.serverInfo?.hostname?.ifBlank { state.config.host }
                    ?: state.config.host,
                closedTabCount = state.closedTabs.size,
                onCreateTab = onNewTab,
                onOpenRecentlyClosed = onOpenRecentlyClosed,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(innerPadding),
            )
        } else if (activeTab.isPtyShell && ptySessionProvider != null) {
            key(activeTab.id) {
                val palette = rememberTerminalThemePalette()
                val ptySession = remember(activeTab.id, state.config) {
                    ptySessionProvider(activeTab.id, palette)
                }
                PtyTerminalWorkspace(
                    tab = activeTab,
                    session = ptySession,
                    onShowMessage = onShowMessage,
                    modifier = Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                )
            }
        } else {
            ActiveTabChatContent(
                throughput = rememberThroughputLabels(state.throughput, activeTab.agent),
                tab = activeTab,
                draft = requireNotNull(activeDraft),
                activeMessagesFlow = activeMessagesFlow,
                fallbackMessages = state.activeMessages,
                isLoading = activeTab.id in state.loadingTabIds,
                isConnected = state.connectionState == ConnectionState.CONNECTED,
                modelLabel = activeModelInfo,
                hasCustomModel = !activeTab.model.isNullOrBlank(),
                onOpenModelPicker = if (activeTab.agent != "shell") onChangeModel else null,
                onSendMessage = onSendMessage,
                onSendStdin = if (activeTab.agent == "shell") onSendStdin else null,
                onInterrupt = onInterrupt,
                onForceInterrupt = onForceInterrupt,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onRetryFullAccess = onRetryFullAccess,
                attachments = composerAttachments,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showForceRestartConfirm) {
        val activeCount = state.daemonUpdateStatus?.effectiveActiveTurnCount
            ?.takeIf { it > 0 }
            ?: state.tabs.count { it.status == "working" }.coerceAtLeast(1)
        ForceRestartConfirmDialog(
            activeTabCount = activeCount,
            onConfirm = {
                showForceRestartConfirm = false
                daemonUpdateActions.onRestartNow()
            },
            onDismiss = { showForceRestartConfirm = false },
        )
    }

    if (showBreakglassDialog) {
        BreakglassSshRollbackDialog(
            connectionConfig = state.config,
            sshProfile = sshProfile,
            sshRollbackState = daemonUpdateUiState.sshRollback,
            onStartRollback = { cfg -> daemonUpdateActions.onStartSshBreakglassRollback(cfg) },
            onOpenSshSetup = daemonUpdateActions.onOpenSshSetup,
            onCopyCommand = { cmd ->
                clipboard.setText(AnnotatedString(cmd))
                onShowMessage(copiedRollbackMsg)
            },
            onDismiss = {
                showBreakglassDialog = false
                daemonUpdateActions.onClearSshRollbackState()
            },
        )
    }
}

@Composable
private fun OutdatedDaemonBanner(
    daemonCompat: DaemonCompatibilityInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = AppTheme.colors.warning.copy(alpha = 0.2f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("outdated_daemon_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = AppTheme.colors.warning,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (daemonCompat.isBuildMismatch) {
                    stringResource(
                        R.string.status_daemon_build_mismatch,
                        daemonCompat.daemonVersionLabel,
                        daemonCompat.expectedReleaseTag.ifBlank { daemonCompat.appVersionLabel },
                    )
                } else {
                    stringResource(
                        R.string.status_daemon_outdated,
                        daemonCompat.daemonVersionLabel,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ThemedTextButton(
                onClick = onUpdate,
                modifier = Modifier.testTag("banner_update_daemon_button"),
            ) {
                Text(stringResource(R.string.action_update_daemon))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("banner_dismiss_outdated_daemon_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_dismiss_daemon_warning),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveTabSummaryBanner(
    activeTab: TabRecord?,
    showTaskSummary: Boolean,
    showTaskSummaryWhenEmpty: Boolean,
    activeTabFinished: Boolean,
    activeMessagesFlow: StateFlow<List<ChatMessage>>?,
    fallbackMessages: List<ChatMessage>,
    onDismiss: () -> Unit,
) {
    // Only subscribe to active messages when the banner is requested and the tab has no server summary.
    val needsMessageFallback = showTaskSummary && activeTab != null && activeTab.taskSummary.isNullOrBlank()
    val messages = if (needsMessageFallback && activeMessagesFlow != null) {
        activeMessagesFlow.collectAsStateWithLifecycle().value
    } else {
        fallbackMessages
    }
    val activeTaskSummary = remember(activeTab, messages) {
        activeTab?.let { resolveTabTaskSummary(it, messages) }
    }
    val shouldDisplayTaskSummary = activeTab != null &&
        showTaskSummary &&
        (activeTaskSummary != null || showTaskSummaryWhenEmpty)
    AnimatedVisibility(
        visible = shouldDisplayTaskSummary,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        if (activeTab != null) {
            TabTaskSummaryCard(
                tab = activeTab,
                taskSummary = activeTaskSummary,
                isTaskFinished = activeTabFinished,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ActiveTabChatContent(
    throughput: Map<String, String>,
    tab: TabRecord,
    draft: TextFieldState,
    activeMessagesFlow: StateFlow<List<ChatMessage>>?,
    fallbackMessages: List<ChatMessage>,
    isLoading: Boolean,
    isConnected: Boolean,
    modelLabel: String?,
    hasCustomModel: Boolean,
    onOpenModelPicker: (() -> Unit)?,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onForceInterrupt: (String?) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onRetryFullAccess: () -> Unit,
    modifier: Modifier = Modifier,
    onSendStdin: ((data: String?, eof: Boolean) -> Unit)? = null,
    attachments: ComposerAttachments? = null,
) {
    val messages = if (activeMessagesFlow != null) {
        activeMessagesFlow.collectAsStateWithLifecycle().value
    } else {
        fallbackMessages
    }
    ChatWorkspace(
        throughput = throughput,
        tab = tab,
        draft = draft,
        messages = messages,
        isLoading = isLoading,
        isConnected = isConnected,
        modelLabel = modelLabel,
        hasCustomModel = hasCustomModel,
        onOpenModelPicker = onOpenModelPicker,
        onSendMessage = onSendMessage,
        onSendStdin = onSendStdin,
        onInterrupt = onInterrupt,
        onForceInterrupt = onForceInterrupt,
        onRemoveQueuedMessage = onRemoveQueuedMessage,
        onRetryFullAccess = onRetryFullAccess,
        modifier = modifier,
        attachments = attachments,
    )
}

@Composable
private fun TabTaskSummaryCard(
    tab: TabRecord,
    taskSummary: String?,
    isTaskFinished: Boolean = false,
    onDismiss: () -> Unit,
) {
    var expanded by remember(tab.id, taskSummary) { mutableStateOf(false) }
    var isOverflowing by remember(tab.id, taskSummary) { mutableStateOf(false) }
    val latestDetail = remember(tab.status, tab.statusDetail, tab.lastMessagePreview, taskSummary) {
        resolveTabLatestDetail(tab, taskSummary)
    }
    val badgeColor = if (isTaskFinished) AppTheme.colors.success else statusColor(tab.status, AppTheme.colors)
    val badgeLabel = statusLabel(if (isTaskFinished) "done" else tab.status)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = themedCorner(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("tab_task_summary_card"),
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.task_summary_label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = themedCorner(6.dp),
                        color = badgeColor.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("dismiss_task_summary_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_dismiss_task_summary),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (taskSummary != null) {
                    Text(
                        text = taskSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 8 else 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            if (!expanded) {
                                isOverflowing = layout.hasVisualOverflow
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 6.dp)
                            .clickable(enabled = isOverflowing || expanded) { expanded = !expanded }
                            .testTag("tab_task_summary_text"),
                    )
                    if (isOverflowing || expanded) {
                        Text(
                            text = stringResource(
                                if (expanded) R.string.task_summary_show_less else R.string.task_summary_show_more,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .testTag("toggle_task_summary_expand"),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.task_summary_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .testTag("tab_task_summary_empty"),
                    )
                }

                if (latestDetail != null) {
                    val latestText = if (tab.status == "idle") {
                        stringResource(R.string.task_summary_latest_prefix, latestDetail)
                    } else {
                        latestDetail
                    }
                    Text(
                        text = latestText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .testTag("tab_task_summary_latest"),
                    )
                }
            }
        }
    }
}

internal fun computeQuickTabScrollTarget(
    tabLeft: Int,
    tabRight: Int,
    viewportWidth: Int,
    currentScroll: Int,
    maxScroll: Int,
    edgeMarginPx: Int = 0,
): Int? {
    if (viewportWidth <= 0 || maxScroll <= 0) return null
    val visibleStart = currentScroll
    val visibleEnd = currentScroll + viewportWidth
    val effectiveStart = if (currentScroll > 0) visibleStart + edgeMarginPx else visibleStart
    val effectiveEnd = if (currentScroll < maxScroll) visibleEnd - edgeMarginPx else visibleEnd
    if (tabLeft >= effectiveStart && tabRight <= effectiveEnd) {
        return null
    }
    return ((tabLeft + tabRight) / 2 - viewportWidth / 2).coerceIn(0, maxScroll)
}

@Composable
internal fun QuickTabStrip(
    tabs: List<TabRecord>,
    activeTabId: String?,
    finishedTabIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
) {
    val luna = isLunaControls
    val scrollState = rememberScrollState()
    val tabBounds = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    var hasAutoScrolled by remember { mutableStateOf(false) }
    val tabIds = remember(tabs) { tabs.map { it.id } }
    val density = LocalDensity.current
    val horizontalPaddingPx = remember(density) { with(density) { 12.dp.roundToPx() } }

    LaunchedEffect(tabIds) {
        tabBounds.keys.retainAll(tabIds.toSet())
    }

    LaunchedEffect(activeTabId, tabIds, scrollState.viewportSize) {
        withContext(Dispatchers.Main.immediate) {
            val targetId = activeTabId ?: return@withContext
            val (bounds, viewport, maxScroll) = snapshotFlow {
                Triple(tabBounds[targetId], scrollState.viewportSize, scrollState.maxValue)
            }.first { (bounds, viewport, maxScroll) ->
                bounds != null && viewport > 0 && bounds.second <= viewport + maxScroll
            }
            val (tabLeft, tabRight) = bounds ?: return@withContext
            val targetScroll = computeQuickTabScrollTarget(
                tabLeft = tabLeft,
                tabRight = tabRight,
                viewportWidth = viewport,
                currentScroll = scrollState.value,
                maxScroll = maxScroll,
                edgeMarginPx = horizontalPaddingPx,
            )
            if (targetScroll != null) {
                if (!hasAutoScrolled) {
                    scrollState.scrollTo(targetScroll)
                } else {
                    scrollState.animateScrollTo(targetScroll)
                }
            }
            hasAutoScrolled = true
        }
    }

    val stripBackground = if (luna) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    val baselineColor = if (luna) {
        LunaTabPageBorder
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        color = stripBackground,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quick_tab_strip"),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = baselineColor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (tab in tabs) {
                    val selected = tab.id == activeTabId
                    val finished = tab.id in finishedTabIds
                    QuickTabItem(
                        tab = tab,
                        selected = selected,
                        finished = finished,
                        luna = luna,
                        onSelect = { onSelect(tab.id) },
                        modifier = Modifier.onPlaced { coordinates ->
                            val left = horizontalPaddingPx + coordinates.positionInParent().x.roundToInt()
                            val right = left + coordinates.size.width
                            val nextBounds = left to right
                            if (tabBounds[tab.id] != nextBounds) {
                                tabBounds[tab.id] = nextBounds
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickTabItem(
    tab: TabRecord,
    selected: Boolean,
    finished: Boolean,
    luna: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabShape = themedCorner(10.dp)
    val indicatorShape = if (luna) {
        RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)
    } else {
        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
    }
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        finished -> AppTheme.colors.success.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val borderStroke = when {
        selected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        finished -> BorderStroke(1.dp, AppTheme.colors.success.copy(alpha = 0.45f))
        luna -> BorderStroke(1.dp, LunaTabBorder)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    }
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        tab.unread || finished -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(IntrinsicSize.Max)
            .semantics { this.selected = selected }
            .clickable(onClick = onSelect)
            .testTag("quick_tab_${tab.id}"),
    ) {
        Surface(
            shape = tabShape,
            color = containerColor,
            border = borderStroke,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (finished) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = statusLabel("done"),
                        tint = AppTheme.colors.success,
                        modifier = Modifier
                            .size(14.dp)
                            .testTag("quick_tab_done_icon_${tab.id}"),
                    )
                } else {
                    TabStatusDot(
                        status = tab.status,
                        dotSize = 8.dp,
                    )
                }
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = when {
                        selected -> FontWeight.Bold
                        tab.unread || finished -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 52.dp, max = 128.dp),
                )
                if (tab.unread && !selected) {
                    UnreadDot(modifier = Modifier.testTag("quick_tab_unread_${tab.id}"))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .height(3.5.dp)
                .clip(indicatorShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .then(if (selected) Modifier.testTag("quick_tab_indicator_${tab.id}") else Modifier),
        )
    }
}

@Composable
private fun NoTabsPlaceholder(
    connectionState: ConnectionState,
    hostname: String,
    onCreateTab: () -> Unit,
    modifier: Modifier = Modifier,
    closedTabCount: Int = 0,
    onOpenRecentlyClosed: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val connected = connectionState == ConnectionState.CONNECTED
    val isAuthError = connectionState == ConnectionState.AUTH_ERROR
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = themedCorner(18.dp),
            color = if (isAuthError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = if (isAuthError) Icons.Rounded.Lock else Icons.Rounded.Hub,
                contentDescription = null,
                tint = if (isAuthError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (connectionState) {
                ConnectionState.CONNECTED -> stringResource(R.string.status_connected_to, hostname)
                ConnectionState.AUTH_ERROR -> stringResource(R.string.status_auth_required_title, hostname)
                else -> stringResource(R.string.status_connecting_to, hostname)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isAuthError) {
                stringResource(R.string.empty_workspace_auth_error_description)
            } else {
                stringResource(R.string.empty_workspace_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        if (isAuthError) {
            ThemedButton(
                onClick = onOpenSettings,
                shape = themedCorner(14.dp),
                modifier = Modifier
                    .height(50.dp)
                    .testTag("empty_repair_button"),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_repair_vm))
            }
        } else {
            ThemedButton(
                onClick = onCreateTab,
                enabled = connected,
                shape = themedCorner(14.dp),
                modifier = Modifier
                    .height(50.dp)
                    .testTag("empty_new_tab_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_new_tab))
            }
        }
        if (closedTabCount > 0) {
            Spacer(Modifier.height(10.dp))
            ThemedOutlinedButton(
                onClick = onOpenRecentlyClosed,
                enabled = connected,
                shape = themedCorner(14.dp),
                modifier = Modifier
                    .height(46.dp)
                    .testTag("empty_recently_closed_button"),
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_recently_closed_tabs_count, closedTabCount))
            }
        }
    }
}
