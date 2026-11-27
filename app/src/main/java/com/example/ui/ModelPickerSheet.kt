package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AgentModelOption
import com.example.data.AgentUsageItem
import com.example.data.AgentUsageUiState
import com.example.data.findUsageForModel
import com.example.data.formatTimeUntilReset
import com.example.data.formatUsagePercent
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedButton
import com.example.ui.theme.ThemedOutlinedButton
import com.example.ui.theme.ThemedTextField
import com.example.ui.theme.themedCorner
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    agentLabel: String,
    currentModel: String?,
    defaultModel: String?,
    models: List<AgentModelOption>,
    isRefreshing: Boolean,
    usageState: AgentUsageUiState = AgentUsageUiState(),
    onCheckUsage: () -> Unit = {},
    onUpdateDaemon: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onSelectModel: (String?) -> Unit,
    onDismiss: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ModelPickerSheetContent(
            agentLabel = agentLabel,
            currentModel = currentModel,
            defaultModel = defaultModel,
            models = models,
            isRefreshing = isRefreshing,
            usageState = usageState,
            onCheckUsage = onCheckUsage,
            onUpdateDaemon = onUpdateDaemon,
            onRefresh = onRefresh,
            onSelectModel = onSelectModel,
            nowMs = nowMs,
        )
    }
}

@Composable
internal fun ModelPickerSheetContent(
    agentLabel: String,
    currentModel: String?,
    defaultModel: String?,
    models: List<AgentModelOption>,
    isRefreshing: Boolean,
    usageState: AgentUsageUiState = AgentUsageUiState(),
    onCheckUsage: () -> Unit = {},
    onUpdateDaemon: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onSelectModel: (String?) -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    var filterQuery by remember { mutableStateOf("") }
    var customModelInput by remember {
        val isCustom = !currentModel.isNullOrBlank() && models.none { it.id == currentModel }
        mutableStateOf(if (isCustom) currentModel.orEmpty() else "")
    }

    val filteredModels = remember(models, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) {
            models
        } else {
            models.filter {
                it.id.contains(q, ignoreCase = true) ||
                    it.name.contains(q, ignoreCase = true) ||
                    (it.description?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    val defaultDisplayName = remember(defaultModel, models) {
        defaultModel?.let { defId ->
            models.firstOrNull { it.id == defId }?.name ?: defId
        }
    }

    val usageItems = usageState.response?.items.orEmpty()
    val showUsagePanel = usageState.isLoading || usageState.response != null || usageState.error != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.model_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.model_picker_subtitle, agentLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThemedOutlinedButton(
                onClick = onCheckUsage,
                enabled = !usageState.isLoading,
                shape = themedCorner(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("model_picker_usage_button"),
            ) {
                if (usageState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DataUsage,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.size(5.dp))
                Text(
                    text = stringResource(R.string.model_picker_usage_button),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.testTag("model_picker_refresh_button"),
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.model_picker_refresh),
                    )
                }
            }
        }

        if (showUsagePanel) {
            ModelUsagePanel(
                usageState = usageState,
                nowMs = nowMs,
                onUpdateDaemon = onUpdateDaemon,
            )
        }

        if (models.size > 5) {
            ThemedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
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
                    .testTag("model_picker_search_input"),
            )
        }

        // Default (CLI default) option
        ModelOptionCard(
            title = stringResource(R.string.model_picker_default_title),
            subtitle = if (defaultDisplayName != null) {
                stringResource(R.string.model_picker_default_with_name, defaultDisplayName)
            } else {
                stringResource(R.string.model_picker_default_subtitle)
            },
            modelId = null,
            usageBadge = defaultModel?.let { defId ->
                formatInlineUsageBadge(findUsageForModel(defId, usageItems), nowMs)
            },
            selected = currentModel.isNullOrBlank(),
            onClick = { onSelectModel(null) },
            modifier = Modifier.testTag("model_option_default"),
        )

        // Active custom model indicator if user previously set an unlisted slug
        if (!currentModel.isNullOrBlank() && models.none { it.id == currentModel }) {
            ModelOptionCard(
                title = currentModel,
                subtitle = stringResource(R.string.model_picker_custom_active),
                modelId = currentModel,
                usageBadge = formatInlineUsageBadge(findUsageForModel(currentModel, usageItems), nowMs),
                selected = true,
                onClick = { onSelectModel(currentModel) },
                modifier = Modifier.testTag("model_option_custom_active"),
            )
        }

        for (model in filteredModels) {
            ModelOptionCard(
                title = model.name,
                subtitle = model.description,
                modelId = model.id.takeIf { it != model.name },
                usageBadge = formatInlineUsageBadge(findUsageForModel(model.id, usageItems), nowMs),
                selected = currentModel == model.id,
                onClick = { onSelectModel(model.id) },
                modifier = Modifier.testTag("model_option_${model.id}"),
            )
        }

        if (filteredModels.isEmpty() && filterQuery.isNotBlank()) {
            Text(
                text = stringResource(R.string.model_picker_no_matches, filterQuery.trim()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(2.dp))

        Text(
            text = stringResource(R.string.model_picker_custom_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemedTextField(
                value = customModelInput,
                onValueChange = { customModelInput = it },
                placeholder = { Text(stringResource(R.string.model_picker_custom_placeholder)) },
                singleLine = true,
                shape = themedCorner(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                modifier = Modifier
                    .weight(1f)
                    .testTag("custom_model_input"),
            )
            ThemedButton(
                onClick = {
                    val trimmed = customModelInput.trim()
                    if (trimmed.isNotEmpty()) {
                        onSelectModel(trimmed)
                    }
                },
                enabled = customModelInput.trim().isNotEmpty(),
                shape = themedCorner(12.dp),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("custom_model_apply_button"),
            ) {
                Text(stringResource(R.string.action_apply))
            }
        }
    }
}

@Composable
private fun ModelUsagePanel(
    usageState: AgentUsageUiState,
    nowMs: Long,
    onUpdateDaemon: (() -> Unit)? = null,
) {
    val resp = usageState.response
    val items = resp?.items.orEmpty()
    val plan = resp?.plan?.trim()?.takeIf { it.isNotEmpty() }

    Surface(
        shape = themedCorner(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_picker_usage_panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.model_picker_usage_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (plan != null) {
                        Surface(
                            shape = themedCorner(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = plan.uppercase(Locale.US),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (usageState.isLoading) {
                    Text(
                        text = stringResource(R.string.model_picker_usage_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!usageState.error.isNullOrBlank()) {
                Text(
                    text = usageState.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("model_picker_usage_error"),
                )
                if (usageState.needsDaemonUpdate && onUpdateDaemon != null) {
                    ThemedOutlinedButton(
                        onClick = onUpdateDaemon,
                        shape = themedCorner(10.dp),
                        modifier = Modifier.testTag("model_picker_usage_update_daemon_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.action_update_vm_daemon),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (item in items) {
                        ModelUsageRow(item = item, nowMs = nowMs)
                    }
                }
            } else if (!usageState.isLoading && usageState.error.isNullOrBlank() && resp != null) {
                Text(
                    text = stringResource(R.string.model_picker_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelUsageRow(
    item: AgentUsageItem,
    nowMs: Long,
) {
    val barColor = usageAccentColor(item.remainingPercent)
    val pctText = stringResource(R.string.model_picker_usage_left, formatUsagePercent(item.remainingPercent))
    val resetText = formatTimeUntilReset(item.resetsAtMs, nowMs)
        ?: if (item.usedPercent <= 0.0) stringResource(R.string.model_picker_usage_window_inactive) else null
    val progress = (item.remainingPercent / 100.0).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_usage_row_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = pctText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(themedCorner(4.dp)),
        )
        if (!resetText.isNullOrBlank()) {
            Text(
                text = resetText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun usageAccentColor(remainingPercent: Double): Color = when {
    remainingPercent < 20.0 -> MaterialTheme.colorScheme.error
    remainingPercent < 50.0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun formatInlineUsageBadge(item: AgentUsageItem?, nowMs: Long): String? {
    if (item == null) return null
    val left = "${formatUsagePercent(item.remainingPercent)} left"
    val reset = formatTimeUntilReset(item.resetsAtMs, nowMs)
    return if (reset != null) "$left · $reset" else left
}

@Composable
private fun ModelOptionCard(
    title: String,
    subtitle: String?,
    modelId: String?,
    usageBadge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = themedCorner(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(themedCorner(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!modelId.isNullOrBlank()) {
                    Text(
                        text = modelId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!usageBadge.isNullOrBlank()) {
                    Text(
                        text = usageBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
