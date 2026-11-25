package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ConnectionState
import com.example.data.ModelSlashCommand
import com.example.data.TabRecord
import com.example.data.WorkspaceState
import com.example.data.captureWindowScreenshotBase64
import com.example.data.formatModelChipLabel
import com.example.data.parseModelSlashCommand
import com.example.data.resolveAgentModelCatalog
import com.example.data.resolveDefaultSystemPrompt
import com.example.data.resolveModelIdFromQuery
import com.example.data.resolvePreferredNewTabCwd
import com.example.data.resolveTabLatestDetail
import com.example.data.resolveTabTaskSummary
import com.example.data.shareDebugBundleFile
import com.example.ui.theme.HerdrAmber
import com.example.ui.theme.HerdrEmerald
import com.example.ui.theme.HerdrRed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.statusColor
import com.example.ui.theme.statusLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showConnect by viewModel.showConnectScreen.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettingsScreen.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val pendingPairing by viewModel.pendingPairingConfig.collectAsStateWithLifecycle()
    val pendingSshAutofill by viewModel.pendingSshAutofill.collectAsStateWithLifecycle()
    val sshProfile by viewModel.sshProfile.collectAsStateWithLifecycle()
    val bootstrapState by viewModel.bootstrapState.collectAsStateWithLifecycle()
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsStateWithLifecycle()

    if (showSettings) {
        val debugUploadState by viewModel.debugUploadState.collectAsStateWithLifecycle()
        val debugEventCount by viewModel.debugEventCount.collectAsStateWithLifecycle()
        val hasLastCrash by viewModel.hasLastCrash.collectAsStateWithLifecycle()
        SettingsScreen(
            workspaceState = state,
            sshProfile = sshProfile,
            debugSettings = debugSettings,
            uploadState = debugUploadState,
            eventCount = debugEventCount,
            hasLastCrash = hasLastCrash,
            recentEventsProvider = { viewModel.recentDebugEvents() },
            onBack = { viewModel.closeAppSettings() },
            onOpenVmConnection = { viewModel.openConnectionSettings() },
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
        )
        return
    }

    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNewTabSheet by remember { mutableStateOf(false) }
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

    fun triggerQuickDebugDialog() {
        scope.launch {
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
        val summary = resolveTabTaskSummary(tab, state.messagesByTab[tabId].orEmpty())
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
                isExpanded = isExpanded,
                debugSharingEnabled = debugSettings.enabled,
                snackbarHostState = snackbarHostState,
                showOverflowMenu = showOverflowMenu,
                onToggleOverflow = { showOverflowMenu = it },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewTab = { showNewTabSheet = true },
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
                onDeleteTab = { viewModel.deleteTab(it) },
                onOpenSettings = { viewModel.openConnectionSettings() },
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
                onInterrupt = { viewModel.interruptActiveTab() },
                onForceInterrupt = { draftText ->
                    viewModel.interruptActiveTab(processQueue = true, text = draftText)
                },
                onRemoveQueuedMessage = { queuedId ->
                    viewModel.removeQueuedMessage(queuedId)
                },
                onRetryFullAccess = { viewModel.retryLastUserPromptWithFullAccess() },
            )
        }

        WorkspaceNavigationShell(
            isExpanded = isExpanded,
            drawerState = drawerState,
            state = state,
            debugSharingEnabled = debugSettings.enabled,
            onSelectTab = { handleTabTap(it) },
            onNewTab = { showNewTabSheet = true },
            onDeleteTab = { viewModel.deleteTab(it) },
            onCloseFinishedTabs = { viewModel.closeFinishedTabs() },
            onOpenSettings = { viewModel.openConnectionSettings() },
            onOpenAppSettings = { viewModel.openAppSettings() },
            onReconnect = { viewModel.reconnectNow() },
            content = mainScaffold,
        )
    }

    if (showNewTabSheet) {
        NewTabSheet(
            agents = state.serverInfo?.agents.orEmpty(),
            defaultCwd = resolvePreferredNewTabCwd(
                lastCwd = state.lastCwd,
                activeTabCwd = state.activeTab?.cwd,
                homeCwd = state.serverInfo?.defaultCwd,
            ),
            defaultSystemPromptForMode = { mode ->
                resolveDefaultSystemPrompt(state.serverInfo, permissionMode = mode)
            },
            onLoadInitialDirectories = { path -> viewModel.loadInitialDirectories(path) },
            onLoadDirectories = { path -> viewModel.loadDirectories(path) },
            onDismiss = { showNewTabSheet = false },
            onCreate = { agent, cwd, permMode, title, model, systemPrompt ->
                viewModel.createTab(
                    agent = agent,
                    cwd = cwd,
                    permissionMode = permMode,
                    title = title,
                    model = model,
                    systemPrompt = systemPrompt,
                    onCreated = { showNewTabSheet = false },
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
            agentLabel = current.agentLabel,
            currentModel = current.model,
            defaultModel = defaultModel,
            models = models,
            isRefreshing = isRefreshingModels,
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
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_title)) },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_rename_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            viewModel.renameTab(current.id, titleInput.trim())
                        }
                        showRenameDialog = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
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
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.dialog_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in modes) {
                        val selected = current.permissionMode == value
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
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
                TextButton(onClick = { showPermissionDialog = false }) {
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
        AlertDialog(
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
                        Switch(
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
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = {
                                Text(stringResource(R.string.new_tab_system_prompt_placeholder))
                            },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tab_system_prompt_input"),
                        )
                        if (promptInput.trim() != tabDefaultPrompt.trim()) {
                            TextButton(
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
                TextButton(
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
                TextButton(onClick = { showSystemPromptDialog = false }) {
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
        AlertDialog(
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
                        color = HerdrEmerald,
                    )
                    OutlinedTextField(
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
                TextButton(
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
                    TextButton(
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
                    TextButton(
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
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReconnect: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isExpanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
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
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReconnect: () -> Unit,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onForceInterrupt: (String?) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onRetryFullAccess: () -> Unit,
) {
    val activeTab = state.activeTab
    val finishedTabIds = state.finishedTabIds
    val activeTabFinished = activeTab != null && activeTab.id in finishedTabIds
    val activeTaskSummary = remember(activeTab, state.activeMessages) {
        activeTab?.let { resolveTabTaskSummary(it, state.activeMessages) }
    }
    val shouldDisplayTaskSummary = activeTab != null &&
        showTaskSummary &&
        (activeTaskSummary != null || showTaskSummaryWhenEmpty)
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
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        if (!isExpanded) {
                            IconButton(
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
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
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
                                            tint = HerdrEmerald,
                                            modifier = Modifier
                                                .size(13.dp)
                                                .testTag("topbar_done_icon"),
                                        )
                                    } else {
                                        TabStatusDot(
                                            status = activeTab.status,
                                            dotSize = 7.dp,
                                            modifier = Modifier.testTag("top_bar_status_dot"),
                                        )
                                    }
                                    Text(
                                        text = "${activeTab.agentLabel} · ${activeTab.folderName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .clickable(onClick = onToggleTaskSummary),
                                    )
                                    if (activeTabFinished) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = HerdrEmerald.copy(alpha = 0.18f),
                                            modifier = Modifier.testTag("topbar_done_badge"),
                                        ) {
                                            Text(
                                                text = statusLabel("done"),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = HerdrEmerald,
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
                            IconButton(
                                onClick = onQuickDebugDump,
                                modifier = Modifier.testTag("top_debug_dump_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BugReport,
                                    contentDescription = stringResource(R.string.action_quick_debug_dump),
                                    tint = HerdrEmerald,
                                )
                            }
                        }
                        IconButton(
                            onClick = onNewTab,
                            modifier = Modifier.testTag("top_new_tab_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.action_new_tab),
                            )
                        }
                        Box {
                            IconButton(
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
                                                color = HerdrRed,
                                            )
                                        },
                                        onClick = {
                                            onToggleOverflow(false)
                                            onDeleteTab(activeTab.id)
                                        },
                                    )
                                }
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

                // Connection warning strip when offline or reconnecting
                if (state.connectionState != ConnectionState.CONNECTED) {
                    Surface(
                        color = if (state.connectionState == ConnectionState.CONNECTING) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            HerdrAmber.copy(alpha = 0.2f)
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
                                imageVector = Icons.Rounded.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = when (state.connectionState) {
                                    ConnectionState.CONNECTING ->
                                        stringResource(R.string.status_connecting_to, state.config.host)

                                    else ->
                                        state.connectionError
                                            ?: stringResource(R.string.status_disconnected_default)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.connectionState != ConnectionState.CONNECTING) {
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
                            onDismiss = onDismissTaskSummary,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (activeTab == null) {
            NoTabsPlaceholder(
                connected = state.connectionState == ConnectionState.CONNECTED,
                hostname = state.serverInfo?.hostname?.ifBlank { state.config.host }
                    ?: state.config.host,
                onCreateTab = onNewTab,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            ChatWorkspace(
                tab = activeTab,
                messages = state.activeMessages,
                isLoading = activeTab.id in state.loadingTabIds,
                isConnected = state.connectionState == ConnectionState.CONNECTED,
                modelLabel = activeModelInfo,
                hasCustomModel = !activeTab.model.isNullOrBlank(),
                onOpenModelPicker = if (activeTab.agent != "shell") onChangeModel else null,
                onSendMessage = onSendMessage,
                onInterrupt = onInterrupt,
                onForceInterrupt = onForceInterrupt,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onRetryFullAccess = onRetryFullAccess,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
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
    val badgeColor = if (isTaskFinished) HerdrEmerald else statusColor(tab.status)
    val badgeLabel = statusLabel(if (isTaskFinished) "done" else tab.status)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
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
                        shape = RoundedCornerShape(6.dp),
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

@Composable
private fun QuickTabStrip(
    tabs: List<TabRecord>,
    activeTabId: String?,
    finishedTabIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tab in tabs) {
                val selected = tab.id == activeTabId
                val finished = tab.id in finishedTabIds
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        finished -> HerdrEmerald.copy(alpha = 0.10f)
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = when {
                        selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        finished -> BorderStroke(1.dp, HerdrEmerald.copy(alpha = 0.45f))
                        else -> BorderStroke(1.dp, Color.Transparent)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelect(tab.id) }
                        .testTag("quick_tab_${tab.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (finished) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = statusLabel("done"),
                                tint = HerdrEmerald,
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
                            fontWeight = if (selected || tab.unread || finished) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(105.dp),
                        )
                        if (tab.unread && !selected) {
                            UnreadDot(modifier = Modifier.testTag("quick_tab_unread_${tab.id}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoTabsPlaceholder(
    connected: Boolean,
    hostname: String,
    onCreateTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (connected) {
                stringResource(R.string.status_connected_to, hostname)
            } else {
                stringResource(R.string.status_connecting_to, hostname)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_workspace_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onCreateTab,
            enabled = connected,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .height(50.dp)
                .testTag("empty_new_tab_button"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_new_tab))
        }
    }
}
