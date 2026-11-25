package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ConnectionState
import com.example.data.TabActivity
import com.example.data.TabRecord
import com.example.data.WorkspaceState
import com.example.ui.theme.HerdrBlue
import com.example.ui.theme.HerdrEmerald
import com.example.ui.theme.HerdrIdleSlate
import com.example.ui.theme.HerdrRed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.statusColor
import com.example.ui.theme.statusLabel

@Composable
fun TabDrawerContent(
    state: WorkspaceState,
    debugSharingEnabled: Boolean = false,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDeleteTab: (String) -> Unit,
    onCloseFinishedTabs: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    onReconnect: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(320.dp),
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
}

@Composable
fun TabSidebarBody(
    state: WorkspaceState,
    debugSharingEnabled: Boolean = false,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDeleteTab: (String) -> Unit,
    onCloseFinishedTabs: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // VM connection status card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val connColor = when (state.connectionState) {
                    ConnectionState.CONNECTED -> HerdrEmerald
                    ConnectionState.CONNECTING -> HerdrBlue
                    ConnectionState.ERROR -> HerdrRed
                    ConnectionState.DISCONNECTED -> HerdrIdleSlate
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(connColor),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.serverInfo?.hostname?.ifBlank { state.config.host }
                            ?: state.config.host.ifBlank { stringResource(R.string.drawer_not_connected) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.config.host.isNotBlank()) {
                            "${state.config.host}:${state.config.port}"
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onReconnect,
                    modifier = Modifier.testTag("drawer_reconnect_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_reconnect),
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("drawer_settings_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.action_vm_settings),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onNewTab,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("drawer_new_tab_button"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_new_tab),
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(16.dp))

        val finishedTabIds = state.finishedTabIds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.drawer_persistent_tabs, state.tabs.size),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (finishedTabIds.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = HerdrEmerald.copy(alpha = 0.16f),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("drawer_finished_count_badge"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = HerdrEmerald,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.drawer_finished_count,
                                    finishedTabIds.size,
                                    finishedTabIds.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HerdrEmerald,
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, HerdrEmerald.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCloseFinishedTabs)
                            .testTag("drawer_close_finished_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_close_finished),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = HerdrEmerald,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        if (state.tabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.drawer_empty_tabs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.tabs, key = { it.id }) { tab ->
                    DrawerTabRow(
                        tab = tab,
                        selected = tab.id == state.activeTab?.id,
                        activity = state.activityOf(tab),
                        onClick = { onSelectTab(tab.id) },
                        onDelete = { onDeleteTab(tab.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenAppSettings)
                .testTag("drawer_app_settings_button"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (debugSharingEnabled) Icons.Rounded.BugReport else Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = if (debugSharingEnabled) HerdrEmerald else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.action_app_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (debugSharingEnabled) {
                        Text(
                            text = "VM debug sharing enabled",
                            style = MaterialTheme.typography.labelSmall,
                            color = HerdrEmerald,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerTabRow(
    tab: TabRecord,
    selected: Boolean,
    activity: TabActivity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val isFinished = activity == TabActivity.FINISHED
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        isFinished -> HerdrEmerald.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val border = when {
        selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        isFinished -> BorderStroke(1.dp, HerdrEmerald.copy(alpha = 0.35f))
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("drawer_tab_${tab.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFinished) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = statusLabel("done"),
                    tint = HerdrEmerald,
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("drawer_tab_done_icon_${tab.id}"),
                )
            } else {
                TabStatusDot(
                    status = tab.status,
                    dotSize = 9.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected || tab.unread || isFinished) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tab.unread && !selected) {
                        UnreadDot(modifier = Modifier.testTag("drawer_tab_unread_${tab.id}"))
                    }
                    if (isFinished) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = HerdrEmerald.copy(alpha = 0.18f),
                            modifier = Modifier.testTag("drawer_tab_done_badge_${tab.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = HerdrEmerald,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = statusLabel("done"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HerdrEmerald,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${tab.agentLabel} · ${tab.folderName}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isFinished) {
                    // Finished tabs say so in the hint below; every other tab is one of these two.
                    val working = activity == TabActivity.WORKING
                    Text(
                        text = stringResource(
                            if (working) R.string.tab_activity_working else R.string.tab_activity_waiting,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (working) FontWeight.SemiBold else FontWeight.Normal,
                        color = statusColor(tab.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("drawer_tab_activity_${tab.id}"),
                    )
                }
                if (isFinished) {
                    Text(
                        text = stringResource(R.string.drawer_task_finished_hint),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = HerdrEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("drawer_tab_done_hint_${tab.id}"),
                    )
                }
                if (tab.agent != "shell" && !tab.model.isNullOrBlank()) {
                    Text(
                        text = tab.model,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val summaryText = tab.taskSummary?.trim()?.takeIf { it.isNotEmpty() }
                    ?: tab.lastMessagePreview?.trim()?.takeIf { it.isNotEmpty() }
                if (summaryText != null) {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("drawer_tab_summary_${tab.id}"),
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_tab_${tab.id}"),
            ) {
                Icon(
                    imageVector = if (isFinished) Icons.Rounded.Close else Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(
                        if (isFinished) R.string.action_close_finished_tab else R.string.action_close_tab,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = if (isFinished) HerdrEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
