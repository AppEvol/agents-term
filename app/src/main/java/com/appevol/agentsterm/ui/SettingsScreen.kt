package com.appevol.agentsterm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appevol.agentsterm.R
import com.appevol.agentsterm.data.AppThemeId
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DaemonCompatibilityInfo
import com.appevol.agentsterm.data.DebugDumpUploadState
import com.appevol.agentsterm.data.DebugEvent
import com.appevol.agentsterm.data.DebugSharingSettings
import com.appevol.agentsterm.data.SshAuthMethod
import com.appevol.agentsterm.data.SshBootstrapConfig
import com.appevol.agentsterm.data.SshProfile
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.captureWindowScreenshotBase64
import com.appevol.agentsterm.data.evaluateDaemonCompatibility
import com.appevol.agentsterm.data.shareDebugBundleFile
import com.appevol.agentsterm.ui.theme.AppTheme
import com.appevol.agentsterm.ui.theme.MonoFontFamily
import com.appevol.agentsterm.ui.theme.ThemedButton
import com.appevol.agentsterm.ui.theme.ThemedOutlinedButton
import com.appevol.agentsterm.ui.theme.ThemedSwitch
import com.appevol.agentsterm.ui.theme.ThemedTextButton
import com.appevol.agentsterm.ui.theme.ThemedTextField
import com.appevol.agentsterm.ui.theme.TitleBarBackground
import com.appevol.agentsterm.ui.theme.TitleBarIconButton
import com.appevol.agentsterm.ui.theme.sectionTitleColor
import com.appevol.agentsterm.ui.theme.themedCorner
import com.appevol.agentsterm.ui.theme.titleBarColors
import com.appevol.agentsterm.ui.theme.titleBarTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    workspaceState: WorkspaceState,
    sshProfile: SshProfile = SshProfile(),
    debugSettings: DebugSharingSettings,
    appTheme: AppThemeId = AppThemeId.Default,
    onSelectTheme: (AppThemeId) -> Unit = {},
    uploadState: DebugDumpUploadState,
    eventCount: Int,
    hasLastCrash: Boolean,
    recentEventsProvider: () -> List<DebugEvent>,
    onBack: () -> Unit,
    onOpenVmConnection: () -> Unit,
    onUpdateDaemon: () -> Unit = onOpenVmConnection,
    daemonUpdateUiState: DaemonUpdateUiState = DaemonUpdateUiState(),
    daemonUpdateActions: DaemonUpdateActions = DaemonUpdateActions(
        onStartSelfUpdate = onUpdateDaemon,
        onOpenSshSetup = onOpenVmConnection,
    ),
    daemonCompatibilityOverride: DaemonCompatibilityInfo? = null,
    onUpdateDebugSettings: ((DebugSharingSettings) -> DebugSharingSettings) -> Unit,
    onUploadDumpToVm: (note: String?, screenshotBase64: String?) -> Unit,
    onExportOfflineZip: suspend (note: String?, screenshotBase64: String?) -> Result<File>,
    onClearDebugBuffer: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var bugNote by remember { mutableStateOf("") }
    var showRecentEvents by remember { mutableStateOf(false) }
    var copiedPath by remember { mutableStateOf(false) }
    var isExportingZip by remember { mutableStateOf(false) }
    var showForceRestartConfirm by remember { mutableStateOf(false) }
    var showRollbackConfirm by remember { mutableStateOf(false) }
    var showBreakglassDialog by remember { mutableStateOf(false) }

    val isConnected = workspaceState.connectionState == ConnectionState.CONNECTED && workspaceState.config.isValid
    val daemonCompat = daemonCompatibilityOverride ?: remember(workspaceState.serverInfo) {
        evaluateDaemonCompatibility(workspaceState.serverInfo)
    }
    val workingTabCount = remember(workspaceState.tabs) {
        workspaceState.tabs.count { it.status == "working" }
    }
    val copiedRollbackMsg = stringResource(R.string.snackbar_rollback_command_copied)

    fun triggerBreakglassSshRevert() {
        showBreakglassDialog = true
        val host = sshProfile.host.trim().ifBlank { workspaceState.config.host.trim() }
        if (host.isNotBlank() &&
            sshProfile.username.isNotBlank() &&
            sshProfile.authMethod == SshAuthMethod.TAILSCALE_SSH
        ) {
            val port = if (workspaceState.config.port in 1..65535) {
                workspaceState.config.port
            } else {
                sshProfile.daemonPort
            }
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

    LaunchedEffect(copiedPath) {
        if (copiedPath) {
            delay(1800)
            copiedPath = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TitleBarBackground {
                TopAppBar(
                    colors = titleBarColors(),
                    navigationIcon = {
                        TitleBarIconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("settings_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = titleBarTitleStyle(MaterialTheme.typography.titleLarge),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ThemePickerCard(selected = appTheme, onSelect = onSelectTheme)

                // 1. VM Connection Card
                Surface(
                    shape = themedCorner(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.settings_vm_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = sectionTitleColor,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val dotColor = when (workspaceState.connectionState) {
                                ConnectionState.CONNECTED -> AppTheme.colors.success
                                ConnectionState.CONNECTING -> AppTheme.colors.info
                                ConnectionState.ERROR, ConnectionState.AUTH_ERROR -> AppTheme.colors.danger
                                ConnectionState.DISCONNECTED -> AppTheme.colors.idle
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = workspaceState.serverInfo?.hostname?.ifBlank { workspaceState.config.host }
                                        ?: workspaceState.config.host.ifBlank { stringResource(R.string.drawer_not_connected) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (workspaceState.config.isValid) {
                                        "${workspaceState.config.host}:${workspaceState.config.port} · ${workspaceState.connectionState.name.lowercase()}"
                                    } else {
                                        "No VM configured"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (workspaceState.serverInfo != null) {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_version_summary,
                                            daemonCompat.appVersionLabel,
                                            daemonCompat.daemonVersionLabel,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = if (daemonCompat.isOutdated) {
                                            AppTheme.colors.warning
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.testTag("settings_version_summary"),
                                    )
                                }
                            }
                        }

                        DaemonSelfUpdateSettingsSection(
                            isConnected = isConnected,
                            daemonCompat = daemonCompat,
                            updateStatus = workspaceState.daemonUpdateStatus,
                            updateUiState = daemonUpdateUiState,
                            daemonPort = workspaceState.config.port,
                            onUpdateLegacySsh = onUpdateDaemon,
                            onStartSelfUpdate = daemonUpdateActions.onStartSelfUpdate,
                            onRequestForceRestart = { showForceRestartConfirm = true },
                            onCancelPendingUpdate = daemonUpdateActions.onCancelPendingUpdate,
                            onRequestRollback = { showRollbackConfirm = true },
                            onDismissUpdateError = daemonUpdateActions.onDismissUpdateError,
                            onOpenBreakglassDialog = { triggerBreakglassSshRevert() },
                            onCopiedRollbackCommand = {
                                scope.launch { snackbarHostState.showSnackbar(copiedRollbackMsg) }
                            },
                        )

                        ThemedOutlinedButton(
                            onClick = onOpenVmConnection,
                            shape = themedCorner(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_vm_connection"),
                        ) {
                            Text(stringResource(R.string.settings_vm_open_button))
                        }

                        val setupAutofillLink = remember(sshProfile, workspaceState.config) {
                            sshProfile.toSetupUri()
                                ?: workspaceState.config.takeIf { it.isValid }?.let {
                                    SshProfile(host = it.host, daemonPort = it.port).toSetupUri()
                                }
                        }
                        if (!setupAutofillLink.isNullOrBlank()) {
                            val copiedLinkMsg = stringResource(R.string.settings_vm_copy_setup_link_copied)
                            ThemedOutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(setupAutofillLink))
                                    scope.launch {
                                        snackbarHostState.showSnackbar(copiedLinkMsg)
                                    }
                                },
                                shape = themedCorner(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_copy_setup_link_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_vm_copy_setup_link))
                            }
                        }
                    }
                }

                // 2. Debug Sharing to VM Card
                Surface(
                    shape = themedCorner(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (debugSettings.enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.settings_debug_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = sectionTitleColor,
                            )
                        }

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_enable_title),
                            subtitle = stringResource(R.string.settings_debug_enable_subtitle),
                            checked = debugSettings.enabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(enabled = checked) }
                            },
                            testTag = "debug_sharing_switch",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_screenshot_title),
                            subtitle = stringResource(R.string.settings_debug_screenshot_subtitle),
                            checked = debugSettings.includeScreenshot,
                            enabled = debugSettings.enabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(includeScreenshot = checked) }
                            },
                            testTag = "debug_screenshot_switch",
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_logcat_title),
                            subtitle = stringResource(R.string.settings_debug_logcat_subtitle),
                            checked = debugSettings.includeLogcat,
                            enabled = debugSettings.enabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(includeLogcat = checked) }
                            },
                            testTag = "debug_logcat_switch",
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_autoupload_title),
                            subtitle = stringResource(R.string.settings_debug_autoupload_subtitle),
                            checked = debugSettings.autoUploadOnError,
                            enabled = debugSettings.enabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(autoUploadOnError = checked) }
                            },
                            testTag = "debug_autoupload_switch",
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_remote_inspection_title),
                            subtitle = stringResource(R.string.settings_debug_remote_inspection_subtitle),
                            checked = debugSettings.remoteInspectionEnabled,
                            enabled = debugSettings.enabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(remoteInspectionEnabled = checked) }
                            },
                            testTag = "debug_remote_inspection_switch",
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_remote_control_title),
                            subtitle = stringResource(R.string.settings_debug_remote_control_subtitle),
                            checked = debugSettings.remoteControlEnabled,
                            enabled = debugSettings.enabled && debugSettings.remoteInspectionEnabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(remoteControlEnabled = checked) }
                            },
                            testTag = "debug_remote_control_switch",
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_debug_remote_recording_title),
                            subtitle = stringResource(R.string.settings_debug_remote_recording_subtitle),
                            checked = debugSettings.remoteRecordingEnabled,
                            enabled = debugSettings.enabled && debugSettings.remoteInspectionEnabled,
                            onCheckedChange = { checked ->
                                onUpdateDebugSettings { it.copy(remoteRecordingEnabled = checked) }
                            },
                            testTag = "debug_remote_recording_switch",
                        )

                        // VM destination path indicator
                        Surface(
                            shape = themedCorner(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_debug_vm_path_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = uploadState.lastLatestSymlink
                                        ?: stringResource(R.string.settings_debug_vm_path_default),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = MonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        ThemedTextField(
                            value = bugNote,
                            onValueChange = { bugNote = it },
                            label = { Text(stringResource(R.string.settings_debug_note_label)) },
                            placeholder = { Text(stringResource(R.string.settings_debug_note_placeholder)) },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !uploadState.isUploading,
                            shape = themedCorner(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("debug_note_input"),
                        )

                        if (!uploadState.lastError.isNullOrBlank()) {
                            Surface(
                                shape = themedCorner(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = uploadState.lastError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }

                        if (!isConnected) {
                            Text(
                                text = stringResource(R.string.settings_debug_disconnected_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.warning,
                            )
                        }

                        ThemedButton(
                            onClick = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    val shot = if (debugSettings.includeScreenshot) {
                                        captureWindowScreenshotBase64(context.findActivityOrNull())
                                    } else {
                                        null
                                    }
                                    onUploadDumpToVm(bugNote.trim().ifBlank { null }, shot)
                                }
                            },
                            enabled = isConnected && !uploadState.isUploading,
                            shape = themedCorner(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("upload_debug_dump_button"),
                        ) {
                            if (uploadState.isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.settings_debug_uploading))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_debug_upload_button),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        ThemedOutlinedButton(
                            onClick = {
                                if (isExportingZip) return@ThemedOutlinedButton
                                scope.launch(Dispatchers.Main.immediate) {
                                    isExportingZip = true
                                    val shot = if (debugSettings.includeScreenshot) {
                                        captureWindowScreenshotBase64(context.findActivityOrNull())
                                    } else {
                                        null
                                    }
                                    val res = onExportOfflineZip(bugNote.trim().ifBlank { null }, shot)
                                    isExportingZip = false
                                    res.onSuccess { file ->
                                        runCatching { shareDebugBundleFile(context, file) }
                                            .onFailure { err ->
                                                snackbarHostState.showSnackbar(
                                                    err.message ?: "Unable to open Android share sheet",
                                                )
                                            }
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar(
                                            err.message ?: "Failed to export debug ZIP",
                                        )
                                    }
                                }
                            },
                            enabled = !isExportingZip,
                            shape = themedCorner(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("share_debug_dump_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_debug_share_button))
                        }

                        // Last Uploaded Dump confirmation card
                        if (!uploadState.lastRemoteDir.isNullOrBlank()) {
                            Surface(
                                shape = themedCorner(14.dp),
                                color = AppTheme.colors.success.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, AppTheme.colors.success.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("last_uploaded_dump_card"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = AppTheme.colors.success,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_debug_last_upload_title),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = AppTheme.colors.success,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = {
                                                val pathToCopy = uploadState.lastRemoteDir
                                                clipboard.setText(AnnotatedString(pathToCopy))
                                                copiedPath = true
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Copied VM dump path")
                                                }
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("copy_dump_path_button"),
                                        ) {
                                            Icon(
                                                imageVector = if (copiedPath) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                                contentDescription = stringResource(R.string.settings_debug_copy_path),
                                                tint = AppTheme.colors.success,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = uploadState.lastRemoteDir,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_debug_agent_tip),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Diagnostics Buffer Card
                Surface(
                    shape = themedCorner(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_debug_buffer_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.settings_debug_buffer_events, eventCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = MonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ThemedTextButton(
                                onClick = onClearDebugBuffer,
                                modifier = Modifier.testTag("clear_debug_buffer_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.settings_debug_clear_buffer))
                            }
                        }

                        if (hasLastCrash) {
                            Text(
                                text = stringResource(R.string.settings_debug_crash_detected),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTheme.colors.danger,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(themedCorner(10.dp))
                                .clickable { showRecentEvents = !showRecentEvents }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_debug_view_events),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = if (showRecentEvents) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(visible = showRecentEvents) {
                            val events = remember(eventCount, showRecentEvents) {
                                if (showRecentEvents) recentEventsProvider() else emptyList()
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = themedCorner(10.dp),
                                    )
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (events.isEmpty()) {
                                    Text(
                                        text = "No events recorded yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    for (evt in events) {
                                        Text(
                                            text = "[${evt.category}] ${evt.event}: ${evt.detail}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                            ),
                                            color = if (evt.category == "error") {
                                                AppTheme.colors.danger
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForceRestartConfirm) {
        val activeCount = workspaceState.daemonUpdateStatus?.effectiveActiveTurnCount
            ?.takeIf { it > 0 }
            ?: workingTabCount.coerceAtLeast(1)
        ForceRestartConfirmDialog(
            activeTabCount = activeCount,
            onConfirm = {
                showForceRestartConfirm = false
                daemonUpdateActions.onRestartNow()
            },
            onDismiss = { showForceRestartConfirm = false },
        )
    }

    if (showRollbackConfirm) {
        val prevShort = daemonCompat.shortPreviousBuildHash
            ?: workspaceState.daemonUpdateStatus?.previousBuildHash?.trim()?.takeIf { it.isNotEmpty() }?.take(7)
        RollbackConfirmDialog(
            previousBuildHashShort = prevShort,
            workingTabCount = workingTabCount,
            onConfirm = {
                showRollbackConfirm = false
                daemonUpdateActions.onRollbackDaemon()
            },
            onDismiss = { showRollbackConfirm = false },
        )
    }

    if (showBreakglassDialog) {
        BreakglassSshRollbackDialog(
            connectionConfig = workspaceState.config,
            sshProfile = sshProfile,
            sshRollbackState = daemonUpdateUiState.sshRollback,
            onStartRollback = { cfg -> daemonUpdateActions.onStartSshBreakglassRollback(cfg) },
            onOpenSshSetup = daemonUpdateActions.onOpenSshSetup,
            onCopyCommand = { cmd ->
                clipboard.setText(AnnotatedString(cmd))
                scope.launch { snackbarHostState.showSnackbar(copiedRollbackMsg) }
            },
            onDismiss = {
                showBreakglassDialog = false
                daemonUpdateActions.onClearSshRollbackState()
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 0.9f else 0.55f,
                ),
            )
        }
        ThemedSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}
