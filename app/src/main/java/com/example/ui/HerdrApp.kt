package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import com.example.data.TabRecord
import com.example.data.WorkspaceState
import com.example.data.captureWindowScreenshotBase64
import com.example.data.shareDebugBundleFile
import com.example.ui.theme.HerdrAmber
import com.example.ui.theme.HerdrEmerald
import com.example.ui.theme.HerdrRed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.statusColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showConnect by viewModel.showConnectScreen.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettingsScreen.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val pendingPairing by viewModel.pendingPairingConfig.collectAsStateWithLifecycle()
    val sshProfile by viewModel.sshProfile.collectAsStateWithLifecycle()
    val bootstrapState by viewModel.bootstrapState.collectAsStateWithLifecycle()
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    val debugUploadState by viewModel.debugUploadState.collectAsStateWithLifecycle()
    val debugEventCount by viewModel.debugEventCount.collectAsStateWithLifecycle()
    val hasLastCrash by viewModel.hasLastCrash.collectAsStateWithLifecycle()

    if (showSettings) {
        SettingsScreen(
            workspaceState = state,
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
            isTesting = isTesting,
            errorMessage = state.connectionError,
            canCancel = state.config.isValid,
            onCancel = { viewModel.closeConnectionSettingsIfConfigured() },
            onConnect = { viewModel.connectToVm(it) },
            onStartSshBootstrap = { viewModel.startSshBootstrap(it) },
            onCancelSshBootstrap = { viewModel.cancelSshBootstrap() },
            onTrustNewHostKeyAndRetry = { viewModel.trustChangedHostKeyAndRetry(it) },
            onConsumedPendingPairing = { viewModel.clearPendingPairingConfig() },
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
    var showQuickDebugDialog by remember { mutableStateOf(false) }
    var pendingScreenshotBase64 by remember { mutableStateOf<String?>(null) }

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

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
                onSelectTab = { viewModel.selectTab(it) },
                onRenameTab = { showRenameDialog = true },
                onChangePermission = { showPermissionDialog = true },
                onDeleteTab = { viewModel.deleteTab(it) },
                onOpenSettings = { viewModel.openConnectionSettings() },
                onOpenAppSettings = { viewModel.openAppSettings() },
                onReconnect = { viewModel.reconnectNow() },
                onSendMessage = { viewModel.sendMessage(it) },
                onInterrupt = { viewModel.interruptActiveTab() },
                onRetryFullAccess = { viewModel.retryLastUserPromptWithFullAccess() },
            )
        }

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
                        debugSharingEnabled = debugSettings.enabled,
                        onSelectTab = { viewModel.selectTab(it) },
                        onNewTab = { showNewTabSheet = true },
                        onDeleteTab = { viewModel.deleteTab(it) },
                        onOpenSettings = { viewModel.openConnectionSettings() },
                        onOpenAppSettings = { viewModel.openAppSettings() },
                        onReconnect = { viewModel.reconnectNow() },
                    )
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(modifier = Modifier.weight(1f)) {
                    mainScaffold()
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    TabDrawerContent(
                        state = state,
                        debugSharingEnabled = debugSettings.enabled,
                        onSelectTab = { id ->
                            viewModel.selectTab(id)
                            scope.launch { drawerState.close() }
                        },
                        onNewTab = {
                            scope.launch { drawerState.close() }
                            showNewTabSheet = true
                        },
                        onDeleteTab = { viewModel.deleteTab(it) },
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            viewModel.openConnectionSettings()
                        },
                        onOpenAppSettings = {
                            scope.launch { drawerState.close() }
                            viewModel.openAppSettings()
                        },
                        onReconnect = { viewModel.reconnectNow() },
                    )
                },
            ) {
                mainScaffold()
            }
        }
    }

    if (showNewTabSheet) {
        NewTabSheet(
            agents = state.serverInfo?.agents.orEmpty(),
            defaultCwd = state.activeTab?.cwd ?: state.serverInfo?.defaultCwd ?: "/home",
            onLoadDirectories = { path -> viewModel.loadDirectories(path) },
            onDismiss = { showNewTabSheet = false },
            onCreate = { agent, cwd, permMode, title ->
                viewModel.createTab(
                    agent = agent,
                    cwd = cwd,
                    permissionMode = permMode,
                    title = title,
                    onCreated = { showNewTabSheet = false },
                )
            },
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
                    }
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

    if (showQuickDebugDialog) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScaffold(
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
    onRenameTab: () -> Unit,
    onChangePermission: () -> Unit,
    onDeleteTab: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onReconnect: () -> Unit,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onRetryFullAccess: () -> Unit,
) {
    val activeTab = state.activeTab
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
                                    }
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
                            )
                            if (activeTab != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColor(activeTab.status))
                                    )
                                    Text(
                                        text = "${activeTab.agentLabel} · ${activeTab.folderName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                                            text = { Text("Permission mode (${activeTab.permissionMode})") },
                                            onClick = {
                                                onToggleOverflow(false)
                                                onChangePermission()
                                            },
                                        )
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
                        modifier = Modifier.fillMaxWidth(),
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
                        onSelect = onSelectTab,
                    )
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
                onSendMessage = onSendMessage,
                onInterrupt = onInterrupt,
                onRetryFullAccess = onRetryFullAccess,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun QuickTabStrip(
    tabs: List<TabRecord>,
    activeTabId: String?,
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
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = if (selected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    } else {
                        BorderStroke(1.dp, Color.Transparent)
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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor(tab.status))
                        )
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected || tab.unread) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(105.dp),
                        )
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
