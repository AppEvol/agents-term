package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ConnectionConfig
import com.example.data.DaemonCompatibilityInfo
import com.example.data.DaemonUpdateStatus
import com.example.data.SshAuthMethod
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.displayBreakglassRollbackCommand
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedAlertDialog
import com.example.ui.theme.ThemedButton
import com.example.ui.theme.ThemedOutlinedButton
import com.example.ui.theme.ThemedTextButton
import com.example.ui.theme.ThemedTextField
import com.example.ui.theme.themedCorner

@Composable
internal fun formatUpdatePhaseLabel(status: DaemonUpdateStatus): String {
    status.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return when (status.state) {
        "checking" -> stringResource(R.string.status_daemon_update_checking)

        "downloading" -> stringResource(R.string.status_daemon_update_downloading)

        "verifying" -> stringResource(R.string.status_daemon_update_verifying)

        "restarting" -> stringResource(R.string.status_daemon_update_restarting)

        "pending_restart" -> {
            val count = status.effectiveActiveTurnCount.coerceAtLeast(1)
            pluralStringResource(R.plurals.status_daemon_update_pending_restart, count, count)
        }

        "error" -> stringResource(
            R.string.status_daemon_update_error_prefix,
            status.error?.ifBlank { null } ?: "Unknown error",
        )

        else -> stringResource(R.string.status_daemon_update_checking)
    }
}

@Composable
fun DaemonUpdateStatusBanner(
    status: DaemonUpdateStatus?,
    localError: String?,
    isSubmitting: Boolean,
    onRequestForceRestart: () -> Unit,
    onCancelPending: () -> Unit,
    onRetry: () -> Unit,
    onOpenSshSetup: () -> Unit,
    onDismissError: () -> Unit,
) {
    val errorText = localError?.takeIf { it.isNotBlank() }
        ?: status?.takeIf { it.isError }?.error?.takeIf { it.isNotBlank() }
        ?: status?.takeIf { it.isError }?.detail?.takeIf { it.isNotBlank() }
    val isPending = status?.isPendingRestart == true && errorText == null
    val isInProgress = (status?.isCheckingOrApplying == true || status?.isRestarting == true || isSubmitting) &&
        errorText == null &&
        !isPending

    if (!isInProgress && !isPending && errorText == null) return

    val bgColor = when {
        errorText != null -> MaterialTheme.colorScheme.errorContainer
        isPending -> AppTheme.colors.warning.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        errorText != null -> MaterialTheme.colorScheme.onErrorContainer
        isPending -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("daemon_update_status_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                isInProgress -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )

                errorText != null -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )

                else -> Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = AppTheme.colors.warning,
                    modifier = Modifier.size(16.dp),
                )
            }

            val message = when {
                errorText != null -> stringResource(R.string.status_daemon_update_error_prefix, errorText)

                isPending -> {
                    val count = status.effectiveActiveTurnCount.coerceAtLeast(1)
                    pluralStringResource(R.plurals.status_daemon_update_pending_restart, count, count)
                }

                status != null -> formatUpdatePhaseLabel(status)

                else -> stringResource(R.string.status_daemon_update_checking)
            }

            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (isPending) {
                ThemedTextButton(
                    onClick = onRequestForceRestart,
                    modifier = Modifier.testTag("banner_restart_now_button"),
                ) {
                    Text(stringResource(R.string.action_restart_now))
                }
                ThemedTextButton(
                    onClick = onCancelPending,
                    modifier = Modifier.testTag("banner_cancel_update_button"),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else if (errorText != null) {
                ThemedTextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("banner_retry_update_button"),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
                ThemedTextButton(
                    onClick = onOpenSshSetup,
                    modifier = Modifier.testTag("banner_ssh_fallback_button"),
                ) {
                    Text(stringResource(R.string.action_ssh_setup))
                }
                IconButton(
                    onClick = onDismissError,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("banner_dismiss_update_error_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = contentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DaemonAutoRollbackBanner(
    errorText: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("daemon_auto_rollback_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.status_daemon_auto_rollback, errorText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ThemedTextButton(
                onClick = onRetry,
                modifier = Modifier.testTag("banner_retry_after_rollback_button"),
            ) {
                Text(stringResource(R.string.action_retry))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("banner_dismiss_auto_rollback_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun DaemonBreakglassRecoveryBanner(
    daemonPort: Int,
    onOpenRevertViaSsh: () -> Unit,
    onCopyCommand: (String) -> Unit,
) {
    val command = remember(daemonPort) { displayBreakglassRollbackCommand(daemonPort) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("daemon_breakglass_recovery_banner"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.status_daemon_reconnect_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemedButton(
                    onClick = onOpenRevertViaSsh,
                    shape = themedCorner(8.dp),
                    modifier = Modifier.testTag("breakglass_revert_ssh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_revert_via_ssh),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ThemedOutlinedButton(
                    onClick = { onCopyCommand(command) },
                    shape = themedCorner(8.dp),
                    modifier = Modifier.testTag("breakglass_copy_command_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_copy_rollback_command),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ForceRestartConfirmDialog(
    activeTabCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = activeTabCount.coerceAtLeast(1)
    ThemedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dialog_force_restart_title),
                modifier = Modifier.testTag("confirm_force_restart_dialog"),
            )
        },
        text = {
            Text(
                text = pluralStringResource(R.plurals.dialog_force_restart_body, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            ThemedTextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_force_restart_button"),
            ) {
                Text(
                    text = stringResource(R.string.action_restart_now),
                    color = AppTheme.colors.danger,
                )
            }
        },
        dismissButton = {
            ThemedTextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_force_restart_button"),
            ) {
                Text(stringResource(R.string.action_wait))
            }
        },
    )
}

@Composable
internal fun RollbackConfirmDialog(
    previousBuildHashShort: String?,
    workingTabCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ThemedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dialog_rollback_title),
                modifier = Modifier.testTag("confirm_rollback_dialog"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.dialog_rollback_body,
                        previousBuildHashShort ?: "saved backup",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (workingTabCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.dialog_rollback_active_warning,
                            workingTabCount,
                            workingTabCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.warning,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            ThemedTextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_rollback_button"),
            ) {
                Text(
                    text = stringResource(R.string.action_confirm_revert),
                    color = AppTheme.colors.danger,
                )
            }
        },
        dismissButton = {
            ThemedTextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_rollback_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun BreakglassSshRollbackDialog(
    connectionConfig: ConnectionConfig,
    sshProfile: SshProfile,
    sshRollbackState: SshRollbackUiState,
    onStartRollback: (SshBootstrapConfig) -> Unit,
    onOpenSshSetup: () -> Unit,
    onCopyCommand: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = remember(sshProfile, connectionConfig) {
        sshProfile.host.trim().ifBlank { connectionConfig.host.trim() }
    }
    val daemonPort = remember(sshProfile, connectionConfig) {
        if (connectionConfig.port in 1..65535) connectionConfig.port else sshProfile.daemonPort
    }
    val command = remember(daemonPort) { displayBreakglassRollbackCommand(daemonPort) }
    var username by remember(sshProfile) { mutableStateOf(sshProfile.username) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val needsPassword = sshProfile.authMethod == SshAuthMethod.PASSWORD
    val needsPrivateKey = sshProfile.authMethod == SshAuthMethod.PRIVATE_KEY

    fun buildConfig(): SshBootstrapConfig = SshBootstrapConfig(
        host = host,
        sshPort = sshProfile.sshPort,
        username = username.trim(),
        authMethod = sshProfile.authMethod,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        daemonPort = daemonPort,
    )

    val canRun = !sshRollbackState.isRunning &&
        host.isNotBlank() &&
        username.trim().isNotBlank() &&
        (!needsPassword || password.isNotEmpty()) &&
        (!needsPrivateKey || privateKey.isNotBlank())

    LaunchedEffect(sshRollbackState.succeeded) {
        if (sshRollbackState.succeeded) {
            onDismiss()
        }
    }

    ThemedAlertDialog(
        onDismissRequest = { if (!sshRollbackState.isRunning) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.dialog_breakglass_ssh_title),
                modifier = Modifier.testTag("breakglass_ssh_dialog"),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_breakglass_ssh_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    shape = themedCorner(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("breakglass_command_text"),
                        )
                        IconButton(
                            onClick = { onCopyCommand(command) },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("breakglass_dialog_copy_command_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy_rollback_command),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                if (sshProfile.username.isBlank()) {
                    ThemedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.ssh_username_label)) },
                        singleLine = true,
                        enabled = !sshRollbackState.isRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("breakglass_ssh_username_input"),
                    )
                }
                if (needsPassword) {
                    ThemedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.ssh_password_label)) },
                        singleLine = true,
                        enabled = !sshRollbackState.isRunning,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("breakglass_ssh_password_input"),
                    )
                } else if (needsPrivateKey) {
                    ThemedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text(stringResource(R.string.ssh_private_key_label)) },
                        minLines = 2,
                        maxLines = 4,
                        enabled = !sshRollbackState.isRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("breakglass_ssh_private_key_input"),
                    )
                    ThemedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.ssh_passphrase_label)) },
                        singleLine = true,
                        enabled = !sshRollbackState.isRunning,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (sshRollbackState.isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Running breakglass rollback on $host…",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (!sshRollbackState.errorMessage.isNullOrBlank()) {
                    Text(
                        text = sshRollbackState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("breakglass_ssh_error"),
                    )
                }

                if (sshRollbackState.logs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = themedCorner(8.dp),
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (line in sshRollbackState.logs) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ThemedTextButton(
                onClick = { onStartRollback(buildConfig()) },
                enabled = canRun,
                modifier = Modifier.testTag("breakglass_run_ssh_rollback_button"),
            ) {
                Text(stringResource(R.string.action_run_ssh_rollback))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemedTextButton(
                    onClick = {
                        onDismiss()
                        onOpenSshSetup()
                    },
                    enabled = !sshRollbackState.isRunning,
                    modifier = Modifier.testTag("breakglass_open_ssh_setup_button"),
                ) {
                    Text(stringResource(R.string.action_open_ssh_setup))
                }
                ThemedTextButton(
                    onClick = onDismiss,
                    enabled = !sshRollbackState.isRunning,
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DaemonSelfUpdateSettingsSection(
    isConnected: Boolean,
    daemonCompat: DaemonCompatibilityInfo,
    updateStatus: DaemonUpdateStatus?,
    updateUiState: DaemonUpdateUiState,
    daemonPort: Int,
    onUpdateLegacySsh: () -> Unit,
    onStartSelfUpdate: () -> Unit,
    onRequestForceRestart: () -> Unit,
    onCancelPendingUpdate: () -> Unit,
    onRequestRollback: () -> Unit,
    onDismissUpdateError: () -> Unit,
    onOpenBreakglassDialog: () -> Unit,
    onCopiedRollbackCommand: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val autoRollbackError = daemonCompat.lastAutoRollbackError
    if (isConnected && !autoRollbackError.isNullOrBlank()) {
        Surface(
            shape = themedCorner(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_auto_rollback_card"),
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
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_daemon_auto_rollback_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = autoRollbackError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    val errorText = updateUiState.localError?.takeIf { it.isNotBlank() }
        ?: updateStatus?.takeIf { it.isError }?.error?.takeIf { it.isNotBlank() }
        ?: updateStatus?.takeIf { it.isError }?.detail?.takeIf { it.isNotBlank() }
    val hideTimedOutRestart = updateUiState.reconnectTimedOutAfterUpdate && updateStatus?.isRestarting == true
    val hasActiveStatus = updateStatus?.isActiveOverlay == true && !hideTimedOutRestart
    val showLiveStatus = hasActiveStatus || updateUiState.isSubmitting || errorText != null
    if (showLiveStatus) {
        Surface(
            shape = themedCorner(12.dp),
            color = if (errorText != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_daemon_update_status_card"),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (errorText == null && updateStatus?.isPendingRestart != true) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (errorText != null) Icons.Rounded.ErrorOutline else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (errorText != null) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                AppTheme.colors.warning
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    val statusMsg = when {
                        errorText != null -> errorText

                        updateStatus?.isPendingRestart == true -> {
                            val count = updateStatus.effectiveActiveTurnCount.coerceAtLeast(1)
                            pluralStringResource(R.plurals.status_daemon_update_pending_restart, count, count)
                        }

                        updateStatus != null -> formatUpdatePhaseLabel(updateStatus)

                        else -> stringResource(R.string.status_daemon_update_checking)
                    }
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorText != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (updateStatus?.isPendingRestart == true && errorText == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemedButton(
                            onClick = onRequestForceRestart,
                            shape = themedCorner(10.dp),
                            modifier = Modifier.testTag("settings_restart_now_button"),
                        ) {
                            Text(stringResource(R.string.action_restart_now))
                        }
                        ThemedOutlinedButton(
                            onClick = onCancelPendingUpdate,
                            shape = themedCorner(10.dp),
                            modifier = Modifier.testTag("settings_cancel_update_button"),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                } else if (errorText != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemedOutlinedButton(
                            onClick = onStartSelfUpdate,
                            shape = themedCorner(10.dp),
                            modifier = Modifier.testTag("settings_retry_update_button"),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_retry))
                        }
                        ThemedTextButton(
                            onClick = onDismissUpdateError,
                            modifier = Modifier.testTag("settings_dismiss_update_error_button"),
                        ) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }
            }
        }
    }

    if (daemonCompat.isOutdated) {
        SettingsOutdatedOrMismatchCard(
            daemonCompat = daemonCompat,
            enabled = !updateUiState.isSubmitting && updateStatus?.isCheckingOrApplying != true && updateStatus?.isRestarting != true,
            onUpdate = if (daemonCompat.supportsSelfUpdate) onStartSelfUpdate else onUpdateLegacySsh,
        )
    } else if (isConnected && daemonCompat.supportsSelfUpdate) {
        ThemedOutlinedButton(
            onClick = onStartSelfUpdate,
            enabled = !updateUiState.isSubmitting && updateStatus?.isCheckingOrApplying != true && updateStatus?.isRestarting != true,
            shape = themedCorner(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_manual_update_daemon_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_daemon_manual_update_button))
        }
    }

    val rollbackAvailable = daemonCompat.rollbackAvailable || updateStatus?.rollbackAvailable == true
    val prevShort = daemonCompat.shortPreviousBuildHash
        ?: updateStatus?.previousBuildHash?.trim()?.takeIf { it.isNotEmpty() }?.take(7)
    if (isConnected && daemonCompat.supportsSelfUpdate && rollbackAvailable) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemedOutlinedButton(
                onClick = onRequestRollback,
                enabled = !updateUiState.isSubmitting && updateStatus?.isRestarting != true,
                shape = themedCorner(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_revert_daemon_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_revert_daemon))
            }
            if (prevShort != null) {
                Text(
                    text = stringResource(R.string.settings_daemon_rollback_hint, prevShort),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (updateUiState.reconnectTimedOutAfterUpdate) {
        val cmd = remember(daemonPort) { displayBreakglassRollbackCommand(daemonPort) }
        Surface(
            shape = themedCorner(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_breakglass_card"),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.status_daemon_reconnect_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemedButton(
                        onClick = onOpenBreakglassDialog,
                        shape = themedCorner(10.dp),
                        modifier = Modifier.testTag("settings_revert_via_ssh_button"),
                    ) {
                        Text(stringResource(R.string.action_revert_via_ssh))
                    }
                    ThemedOutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(cmd))
                            onCopiedRollbackCommand()
                        },
                        shape = themedCorner(10.dp),
                        modifier = Modifier.testTag("settings_copy_rollback_command_button"),
                    ) {
                        Text(stringResource(R.string.action_copy_rollback_command))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsOutdatedOrMismatchCard(
    daemonCompat: DaemonCompatibilityInfo,
    enabled: Boolean,
    onUpdate: () -> Unit,
) {
    Surface(
        shape = themedCorner(12.dp),
        color = AppTheme.colors.warning.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, AppTheme.colors.warning.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_outdated_daemon_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = AppTheme.colors.warning,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (daemonCompat.supportsSelfUpdate) {
                        stringResource(R.string.settings_daemon_self_update_title)
                    } else {
                        stringResource(R.string.settings_daemon_outdated_title)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = if (daemonCompat.supportsSelfUpdate) {
                    val targetLabel = daemonCompat.expectedReleaseTag.ifBlank { daemonCompat.appVersionLabel }
                    stringResource(
                        R.string.settings_daemon_self_update_body,
                        daemonCompat.daemonVersionLabel,
                        targetLabel,
                    )
                } else {
                    stringResource(
                        R.string.settings_daemon_outdated_body,
                        daemonCompat.expectedProtocolVersion,
                        daemonCompat.daemonVersionLabel,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThemedButton(
                onClick = onUpdate,
                enabled = enabled,
                shape = themedCorner(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_update_daemon_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdateAlt,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (daemonCompat.supportsSelfUpdate) {
                        stringResource(R.string.settings_daemon_self_update_button)
                    } else {
                        stringResource(R.string.settings_update_daemon_button)
                    },
                )
            }
        }
    }
}
