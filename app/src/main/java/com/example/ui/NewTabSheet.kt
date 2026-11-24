package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AvailableAgentInfo
import com.example.data.FsListResponse
import com.example.data.resolveAgentModelCatalog
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTabSheet(
    agents: List<AvailableAgentInfo>,
    defaultCwd: String,
    onLoadDirectories: suspend (String?) -> Result<FsListResponse>,
    onDismiss: () -> Unit,
    onCreate: (agent: String, cwd: String, permissionMode: String, title: String, model: String?) -> Unit,
    onLoadInitialDirectories: (suspend (String?) -> Result<FsListResponse>)? = null,
) {
    val effectiveAgents = remember(agents) {
        if (agents.isNotEmpty()) {
            agents
        } else {
            listOf(
                AvailableAgentInfo("agy", "Antigravity", "Antigravity CLI", "agy", true),
                AvailableAgentInfo("claude", "Claude Code", "Claude CLI", "claude", true),
                AvailableAgentInfo("codex", "Codex", "Codex CLI", "codex", true),
                AvailableAgentInfo("shell", "Shell", "Persistent bash", "bash", true),
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val initialCwd = remember { defaultCwd }
        var selectedAgent by remember {
            mutableStateOf(effectiveAgents.firstOrNull { it.available }?.type ?: "agy")
        }
        var selectedModel by remember { mutableStateOf<String?>(null) }
        var cwd by remember { mutableStateOf(initialCwd) }
        var permissionMode by remember { mutableStateOf("full-access") }
        var customTitle by remember { mutableStateOf("") }
        var fsListing by remember { mutableStateOf<FsListResponse?>(null) }
        var fsError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(initialCwd) {
            fsError = null
            val loader = onLoadInitialDirectories ?: onLoadDirectories
            loader(initialCwd).onSuccess {
                fsListing = it
                cwd = it.path
                fsError = null
            }.onFailure { err ->
                fsError = err.message ?: err::class.java.simpleName
            }
        }

        fun browseTo(targetPath: String) {
            cwd = targetPath
            fsError = null
            scope.launch {
                onLoadDirectories(targetPath).onSuccess {
                    fsListing = it
                    cwd = it.path
                    fsError = null
                }.onFailure { err ->
                    fsError = err.message ?: err::class.java.simpleName
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.new_tab_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Agent selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.new_tab_agent_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (agent in effectiveAgents) {
                        val isSelected = selectedAgent == agent.type
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            },
                            modifier = Modifier
                                .width(142.dp)
                                .clickable {
                                    if (selectedAgent != agent.type) {
                                        selectedAgent = agent.type
                                        selectedModel = null
                                    }
                                }
                                .testTag("agent_option_${agent.type}"),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Icon(
                                    imageVector = when (agent.type) {
                                        "agy" -> Icons.Rounded.AutoAwesome
                                        "claude" -> Icons.Rounded.AutoAwesome
                                        "codex" -> Icons.Rounded.Code
                                        else -> Icons.Rounded.Terminal
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = agent.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (agent.available) agent.binary else "not found",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFontFamily,
                                    color = if (agent.available) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Working directory browser on VM
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.new_tab_cwd_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_tab_cwd_input"),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    )
                    if (fsListing?.parent != null) {
                        IconButton(
                            onClick = { browseTo(fsListing!!.parent!!) },
                            modifier = Modifier.testTag("parent_folder_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = stringResource(R.string.action_parent_folder),
                            )
                        }
                    }
                }
                if (!fsError.isNullOrBlank()) {
                    Text(
                        text = "Directory listing error: $fsError",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val dirs = fsListing?.directories.orEmpty().take(18)
                if (dirs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (dir in dirs) {
                            AssistChip(
                                onClick = { browseTo(dir.path) },
                                label = {
                                    Text(
                                        text = dir.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Model selector (coding agents only)
            if (selectedAgent != "shell") {
                val (availableModels, defaultModel) = remember(selectedAgent, effectiveAgents) {
                    resolveAgentModelCatalog(selectedAgent, effectiveAgents)
                }
                val defaultChipText = remember(defaultModel, availableModels) {
                    val defName = defaultModel?.let { defId ->
                        availableModels.firstOrNull { it.id == defId }?.name ?: defId
                    }
                    if (defName != null) "Default ($defName)" else "Default"
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.new_tab_model_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedModel == null,
                            onClick = { selectedModel = null },
                            label = { Text(defaultChipText, maxLines = 1) },
                            modifier = Modifier.testTag("new_tab_model_default"),
                        )
                        for (option in availableModels) {
                            FilterChip(
                                selected = selectedModel == option.id,
                                onClick = { selectedModel = option.id },
                                label = { Text(option.name, maxLines = 1) },
                                modifier = Modifier.testTag("new_tab_model_${option.id}"),
                            )
                        }
                    }
                }
            }

            // Permission mode (coding agents only)
            if (selectedAgent != "shell") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.new_tab_permission_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val modes = listOf(
                        "full-access" to "Full Access",
                        "accept-edits" to "Accept Edits",
                        "plan" to "Plan Only",
                        "default" to "Ask / Safe",
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in modes) {
                            FilterChip(
                                selected = permissionMode == value,
                                onClick = { permissionMode = value },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                label = { Text(stringResource(R.string.new_tab_name_label)) },
                placeholder = { Text(stringResource(R.string.new_tab_name_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_tab_name_input"),
                shape = RoundedCornerShape(12.dp),
            )

            Button(
                onClick = {
                    onCreate(
                        selectedAgent,
                        cwd.trim(),
                        permissionMode,
                        customTitle.trim(),
                        if (selectedAgent == "shell") null else selectedModel,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("create_tab_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.new_tab_create_button),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
