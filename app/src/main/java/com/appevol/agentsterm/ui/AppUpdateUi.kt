package com.appevol.agentsterm.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.appevol.agentsterm.R
import com.appevol.agentsterm.data.AppReleaseInfo
import com.appevol.agentsterm.ui.theme.AppTheme
import com.appevol.agentsterm.ui.theme.MonoFontFamily
import com.appevol.agentsterm.ui.theme.ThemedAlertDialog
import com.appevol.agentsterm.ui.theme.ThemedButton
import com.appevol.agentsterm.ui.theme.ThemedOutlinedButton
import com.appevol.agentsterm.ui.theme.ThemedTextButton
import com.appevol.agentsterm.ui.theme.sectionTitleColor
import com.appevol.agentsterm.ui.theme.themedCorner

internal fun openExternalUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

@Composable
internal fun AppUpdateDialog(
    currentVersionLabel: String,
    release: AppReleaseInfo,
    onDownloadApk: (String) -> Unit,
    onViewRelease: (String) -> Unit,
    onLater: () -> Unit,
) {
    ThemedAlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.dialog_app_update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = themedCorner(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.dialog_app_update_versions,
                            currentVersionLabel,
                            release.tagName,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("app_update_dialog_versions"),
                    )
                }
                Text(
                    text = stringResource(R.string.dialog_app_update_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            ThemedTextButton(
                onClick = { onDownloadApk(release.apkDownloadUrl) },
                modifier = Modifier.testTag("app_update_download_button"),
            ) {
                Text(stringResource(R.string.action_download_apk))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemedTextButton(
                    onClick = { onViewRelease(release.releasePageUrl) },
                    modifier = Modifier.testTag("app_update_view_release_button"),
                ) {
                    Text(stringResource(R.string.action_view_release))
                }
                ThemedTextButton(
                    onClick = onLater,
                    modifier = Modifier.testTag("app_update_later_button"),
                ) {
                    Text(stringResource(R.string.action_later))
                }
            }
        },
    )
}

@Composable
internal fun AppUpdateSettingsCard(
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadApk: (String) -> Unit,
    onViewRelease: (String) -> Unit,
) {
    val latest = updateState.latestRelease
    Surface(
        shape = themedCorner(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (updateState.updateAvailable) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_app_update_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings_app_update_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = sectionTitleColor,
                )
            }

            Text(
                text = if (updateState.isLocalDevBuild) {
                    stringResource(R.string.settings_app_update_installed_dev, updateState.currentVersionLabel)
                } else {
                    stringResource(R.string.settings_app_update_installed_version, updateState.currentVersionLabel)
                },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_app_version_text"),
            )

            val (statusText, statusColor) = when {
                updateState.isChecking ->
                    stringResource(R.string.settings_app_update_checking) to MaterialTheme.colorScheme.primary

                !updateState.lastCheckError.isNullOrBlank() ->
                    updateState.lastCheckError to AppTheme.colors.danger

                updateState.updateAvailable && latest != null ->
                    stringResource(R.string.settings_app_update_available, latest.tagName) to AppTheme.colors.warning

                latest != null && updateState.isLocalDevBuild ->
                    stringResource(R.string.settings_app_update_latest_dev, latest.tagName) to
                        MaterialTheme.colorScheme.onSurfaceVariant

                latest != null ->
                    stringResource(R.string.settings_app_update_up_to_date, latest.tagName) to AppTheme.colors.success

                else ->
                    stringResource(R.string.settings_app_update_not_checked) to
                        MaterialTheme.colorScheme.onSurfaceVariant
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                modifier = Modifier.testTag("settings_app_update_status"),
            )

            if (updateState.updateAvailable && latest != null) {
                ThemedButton(
                    onClick = { onDownloadApk(latest.apkDownloadUrl) },
                    shape = themedCorner(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_app_update_download_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_download_apk))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemedOutlinedButton(
                        onClick = { onViewRelease(latest.releasePageUrl) },
                        shape = themedCorner(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_app_update_release_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_view_release))
                    }
                    ThemedOutlinedButton(
                        onClick = onCheckForUpdates,
                        enabled = !updateState.isChecking,
                        shape = themedCorner(12.dp),
                        modifier = Modifier.testTag("settings_app_update_check_button"),
                    ) {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.action_check_for_updates),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            } else {
                ThemedOutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !updateState.isChecking,
                    shape = themedCorner(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_app_update_check_button"),
                ) {
                    if (updateState.isChecking) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_check_for_updates))
                }
            }
        }
    }
}
