package com.appevol.agentsterm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appevol.agentsterm.R
import com.appevol.agentsterm.data.MAX_OPEN_TABS
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.formatRelativeClosedTime
import com.appevol.agentsterm.ui.theme.AppTheme
import com.appevol.agentsterm.ui.theme.MonoFontFamily
import com.appevol.agentsterm.ui.theme.ThemedButton
import com.appevol.agentsterm.ui.theme.ThemedTextField
import com.appevol.agentsterm.ui.theme.themedCorner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyClosedTabsSheet(
    closedTabs: List<TabRecord>,
    canReviveTab: Boolean,
    isRefreshing: Boolean,
    revivingTabId: String?,
    onRefresh: () -> Unit,
    onRevive: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        RecentlyClosedTabsContent(
            closedTabs = closedTabs,
            canReviveTab = canReviveTab,
            isRefreshing = isRefreshing,
            revivingTabId = revivingTabId,
            onRefresh = onRefresh,
            onRevive = onRevive,
        )
    }
}

@Composable
internal fun RecentlyClosedTabsContent(
    closedTabs: List<TabRecord>,
    canReviveTab: Boolean,
    isRefreshing: Boolean,
    revivingTabId: String?,
    onRefresh: () -> Unit,
    onRevive: (String) -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    var filterQuery by remember { mutableStateOf("") }

    val filteredTabs = remember(closedTabs, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) {
            closedTabs
        } else {
            closedTabs.filter { tab ->
                tab.title.contains(q, ignoreCase = true) ||
                    tab.agentLabel.contains(q, ignoreCase = true) ||
                    tab.folderName.contains(q, ignoreCase = true) ||
                    tab.taskSummary?.contains(q, ignoreCase = true) == true ||
                    tab.lastMessagePreview?.contains(q, ignoreCase = true) == true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recently_closed_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.recently_closed_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.testTag("recently_closed_refresh_button"),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.recently_closed_refresh),
                    )
                }
            }
        }

        if (!canReviveTab) {
            Surface(
                shape = themedCorner(12.dp),
                color = AppTheme.colors.warning.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, AppTheme.colors.warning.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recently_closed_limit_banner"),
            ) {
                Text(
                    text = stringResource(R.string.recently_closed_limit_reached, MAX_OPEN_TABS),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        if (closedTabs.size > 5) {
            ThemedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text(stringResource(R.string.recently_closed_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
                shape = themedCorner(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recently_closed_search_input"),
            )
        }

        when {
            closedTabs.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp)
                        .testTag("recently_closed_empty"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.recently_closed_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            filteredTabs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.recently_closed_no_matches, filterQuery.trim()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .testTag("recently_closed_no_matches"),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredTabs, key = { it.id }) { tab ->
                        val isReviving = revivingTabId == tab.id
                        val actionEnabled = canReviveTab && revivingTabId == null
                        ClosedTabCard(
                            tab = tab,
                            closedAgoLabel = stringResource(
                                R.string.recently_closed_time_ago,
                                formatRelativeClosedTime(tab.closedAt, nowMs),
                            ),
                            isReviving = isReviving,
                            enabled = actionEnabled,
                            onRevive = { onRevive(tab.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClosedTabCard(
    tab: TabRecord,
    closedAgoLabel: String,
    isReviving: Boolean,
    enabled: Boolean,
    onRevive: () -> Unit,
) {
    Surface(
        shape = themedCorner(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(themedCorner(14.dp))
            .clickable(enabled = enabled, onClick = onRevive)
            .testTag("closed_tab_${tab.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${tab.agentLabel} · ${tab.folderName} · $closedAgoLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    )
                }
            }
            ThemedButton(
                onClick = onRevive,
                enabled = enabled,
                isDefault = false,
                shape = themedCorner(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("revive_tab_${tab.id}"),
            ) {
                if (isReviving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_revive_tab),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
