package com.example.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AvailableAgentInfo
import com.example.data.DEFAULT_AGENT_SYSTEM_PROMPT
import com.example.data.DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT
import com.example.data.FsListResponse
import com.example.data.MAX_OPEN_TABS
import com.example.data.ModelThroughput
import com.example.data.SHELL_MODE_COMMAND
import com.example.data.SHELL_MODE_PTY
import com.example.data.resolveAgentModelCatalog
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedButton
import com.example.ui.theme.ThemedFilterChip
import com.example.ui.theme.ThemedSwitch
import com.example.ui.theme.ThemedTextButton
import com.example.ui.theme.ThemedTextField
import com.example.ui.theme.themedCorner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun newTabButtonLabel(
    canCreateTab: Boolean,
    @StringRes action: Int = R.string.action_new_tab,
): String = if (canCreateTab) {
    stringResource(action)
} else {
    stringResource(R.string.tab_limit_reached, MAX_OPEN_TABS)
}

internal fun resolveCwdBasename(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ""
    val normalized = trimmed.trimEnd('/')
    if (normalized.isEmpty()) return "/"
    return normalized.substringAfterLast('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTabSheet(
    agents: List<AvailableAgentInfo>,
    defaultCwd: String,
    canCreateTab: Boolean,
    ptySupported: Boolean = false,
    defaultSystemPromptForMode: (String) -> String = { mode ->
        if (mode == "full-access") DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT else DEFAULT_AGENT_SYSTEM_PROMPT
    },
    onLoadDirectories: suspend (String?) -> Result<FsListResponse>,
    onDismiss: () -> Unit,
    onCreate: (
        agent: String,
        cwd: String,
        permissionMode: String,
        title: String,
        model: String?,
        systemPrompt: String?,
        shellMode: String?,
    ) -> Unit,
    onLoadInitialDirectories: (suspend (String?) -> Result<FsListResponse>)? = null,
    createError: String? = null,
    isCreating: Boolean = false,
    throughput: List<ModelThroughput> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        NewTabSheetContent(
            agents = agents,
            throughput = throughput,
            defaultCwd = defaultCwd,
            canCreateTab = canCreateTab,
            ptySupported = ptySupported,
            defaultSystemPromptForMode = defaultSystemPromptForMode,
            onLoadDirectories = onLoadDirectories,
            onCreate = onCreate,
            onLoadInitialDirectories = onLoadInitialDirectories,
            createError = createError,
            isCreating = isCreating,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewTabSheetContent(
    agents: List<AvailableAgentInfo>,
    defaultCwd: String,
    canCreateTab: Boolean,
    ptySupported: Boolean = false,
    defaultSystemPromptForMode: (String) -> String = { mode ->
        if (mode == "full-access") DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT else DEFAULT_AGENT_SYSTEM_PROMPT
    },
    onLoadDirectories: suspend (String?) -> Result<FsListResponse>,
    onCreate: (
        agent: String,
        cwd: String,
        permissionMode: String,
        title: String,
        model: String?,
        systemPrompt: String?,
        shellMode: String?,
    ) -> Unit,
    onLoadInitialDirectories: (suspend (String?) -> Result<FsListResponse>)? = null,
    createError: String? = null,
    isCreating: Boolean = false,
    throughput: List<ModelThroughput> = emptyList(),
) {
    val effectiveAgents = remember(agents) {
        if (agents.isNotEmpty()) {
            agents
        } else {
            listOf(
                AvailableAgentInfo("agy", "Antigravity", "Antigravity CLI", "agy", true),
                AvailableAgentInfo("claude", "Claude Code", "Claude CLI", "claude", true),
                AvailableAgentInfo("codex", "Codex", "Codex CLI", "codex", true),
                AvailableAgentInfo(
                    "shell",
                    "Shell",
                    "Persistent bash (keeps directory, env vars, and stdin)",
                    "bash",
                    true,
                ),
            )
        }
    }

    val initialCwd = remember { defaultCwd }
    var selectedAgent by remember {
        mutableStateOf(effectiveAgents.firstOrNull { it.available }?.type ?: "agy")
    }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var selectedShellMode by remember { mutableStateOf(SHELL_MODE_COMMAND) }
    var cwdField by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialCwd,
                selection = TextRange(initialCwd.length),
            ),
        )
    }
    var permissionMode by remember { mutableStateOf("full-access") }
    var customTitle by remember { mutableStateOf("") }
    var includeSystemPrompt by remember { mutableStateOf(true) }
    var systemPromptText by remember { mutableStateOf(defaultSystemPromptForMode("full-access")) }
    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var fsListing by remember { mutableStateOf<FsListResponse?>(null) }
    var fsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun setCwdWithEndSelection(path: String) {
        cwdField = TextFieldValue(
            text = path,
            selection = TextRange(path.length),
        )
    }

    LaunchedEffect(initialCwd) {
        withContext(Dispatchers.Main.immediate) {
            fsError = null
            val loader = onLoadInitialDirectories ?: onLoadDirectories
            loader(initialCwd).onSuccess {
                fsListing = it
                setCwdWithEndSelection(it.path)
                fsError = null
            }.onFailure { err ->
                fsError = err.message ?: err::class.java.simpleName
            }
        }
    }

    fun browseTo(targetPath: String) {
        setCwdWithEndSelection(targetPath)
        fsError = null
        scope.launch(Dispatchers.Main.immediate) {
            onLoadDirectories(targetPath).onSuccess {
                fsListing = it
                setCwdWithEndSelection(it.path)
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
            text = if (selectedAgent == "shell") {
                stringResource(R.string.new_tab_shell_sheet_title)
            } else {
                stringResource(R.string.new_tab_sheet_title)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("new_tab_sheet_title"),
        )

        // Tab type selector (2-column grid so all choices including Shell are immediately visible)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.new_tab_agent_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (rowAgents in effectiveAgents.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (agent in rowAgents) {
                            val isSelected = selectedAgent == agent.type
                            Surface(
                                shape = themedCorner(14.dp),
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
                                    .weight(1f)
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (agent.available) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                        if (rowAgents.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Shell mode selector (opt-in PTY on capable daemons) and capability description
        if (selectedAgent == "shell") {
            val effectiveShellMode = if (ptySupported) selectedShellMode else SHELL_MODE_COMMAND
            val isPtyMode = effectiveShellMode == SHELL_MODE_PTY
            if (ptySupported) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.new_tab_shell_mode_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemedFilterChip(
                            selected = !isPtyMode,
                            onClick = { selectedShellMode = SHELL_MODE_COMMAND },
                            label = { Text(stringResource(R.string.new_tab_shell_mode_command)) },
                            modifier = Modifier.testTag("new_tab_shell_mode_command"),
                        )
                        ThemedFilterChip(
                            selected = isPtyMode,
                            onClick = { selectedShellMode = SHELL_MODE_PTY },
                            label = { Text(stringResource(R.string.new_tab_shell_mode_pty)) },
                            modifier = Modifier.testTag("new_tab_shell_mode_pty"),
                        )
                    }
                }
            }
            val shellInfo = effectiveAgents.firstOrNull { it.type == "shell" }
            val shellDescription = if (isPtyMode) {
                stringResource(R.string.new_tab_shell_pty_description)
            } else {
                shellInfo?.description?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.new_tab_shell_description_fallback)
            }
            val shellDetail = if (isPtyMode) {
                stringResource(R.string.new_tab_shell_pty_capabilities_detail)
            } else {
                stringResource(R.string.new_tab_shell_capabilities_detail)
            }
            Surface(
                shape = themedCorner(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .testTag("new_tab_shell_description"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = shellDescription,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = shellDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Working directory browser on VM
        val activeFolderName = resolveCwdBasename(cwdField.text)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.new_tab_cwd_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (activeFolderName.isNotEmpty()) {
                    Surface(
                        shape = themedCorner(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .widthIn(max = 180.dp)
                            .semantics(mergeDescendants = true) {}
                            .testTag("new_tab_cwd_basename"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = activeFolderName,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemedTextField(
                    value = cwdField,
                    onValueChange = { cwdField = it },
                    singleLine = true,
                    isError = !createError.isNullOrBlank() &&
                        createError.contains("directory", ignoreCase = true),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_tab_cwd_input"),
                    shape = themedCorner(12.dp),
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
            val rates = rememberThroughputLabels(throughput, selectedAgent)
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
                    ThemedFilterChip(
                        selected = selectedModel == null,
                        onClick = { selectedModel = null },
                        label = { Text(listOfNotNull(defaultChipText, rates[defaultModel]).joinToString(" · "), maxLines = 1) },
                        modifier = Modifier.testTag("new_tab_model_default"),
                    )
                    for (option in availableModels) {
                        ThemedFilterChip(
                            selected = selectedModel == option.id,
                            onClick = { selectedModel = option.id },
                            label = { Text(listOfNotNull(option.name, rates[option.id]).joinToString(" · "), maxLines = 1) },
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
                        ThemedFilterChip(
                            selected = permissionMode == value,
                            onClick = {
                                if (permissionMode != value) {
                                    val prevDefault = defaultSystemPromptForMode(permissionMode).trim()
                                    permissionMode = value
                                    if (systemPromptText.trim() == prevDefault) {
                                        systemPromptText = defaultSystemPromptForMode(value)
                                    }
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.testTag("new_tab_permission_$value"),
                        )
                    }
                }
            }
        }

        // System prompt (coding agents only)
        if (selectedAgent != "shell") {
            val activeDefaultSystemPrompt = defaultSystemPromptForMode(permissionMode)
            val trimmedPrompt = systemPromptText.trim()
            val isEmptyEffective = !includeSystemPrompt || trimmedPrompt.isEmpty()
            val isDefaultPrompt = includeSystemPrompt && trimmedPrompt == activeDefaultSystemPrompt.trim()
            Surface(
                shape = themedCorner(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.new_tab_system_prompt_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when {
                                    isEmptyEffective -> stringResource(R.string.new_tab_system_prompt_empty_hint)
                                    isDefaultPrompt -> stringResource(R.string.new_tab_system_prompt_default_hint)
                                    else -> stringResource(R.string.new_tab_system_prompt_custom_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ThemedSwitch(
                            checked = includeSystemPrompt,
                            onCheckedChange = { checked ->
                                includeSystemPrompt = checked
                                if (checked && systemPromptText.isBlank()) {
                                    systemPromptText = activeDefaultSystemPrompt
                                }
                                if (!checked) {
                                    showSystemPromptEditor = false
                                }
                            },
                            modifier = Modifier.testTag("new_tab_system_prompt_switch"),
                        )
                    }

                    if (includeSystemPrompt) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ThemedTextButton(
                                onClick = { showSystemPromptEditor = !showSystemPromptEditor },
                                modifier = Modifier.testTag("new_tab_system_prompt_toggle_editor"),
                            ) {
                                Text(
                                    text = if (showSystemPromptEditor) {
                                        stringResource(R.string.new_tab_system_prompt_hide)
                                    } else {
                                        stringResource(R.string.new_tab_system_prompt_edit)
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showSystemPromptEditor) {
                                        Icons.Rounded.ExpandLess
                                    } else {
                                        Icons.Rounded.ExpandMore
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (!isDefaultPrompt) {
                                ThemedTextButton(
                                    onClick = { systemPromptText = activeDefaultSystemPrompt },
                                    modifier = Modifier.testTag("new_tab_system_prompt_reset"),
                                ) {
                                    Text(stringResource(R.string.new_tab_system_prompt_reset))
                                }
                            }
                        }

                        AnimatedVisibility(visible = showSystemPromptEditor) {
                            ThemedTextField(
                                value = systemPromptText,
                                onValueChange = { systemPromptText = it },
                                placeholder = {
                                    Text(stringResource(R.string.new_tab_system_prompt_placeholder))
                                },
                                minLines = 3,
                                maxLines = 7,
                                shape = themedCorner(12.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("new_tab_system_prompt_input"),
                            )
                        }
                    }
                }
            }
        }

        ThemedTextField(
            value = customTitle,
            onValueChange = { customTitle = it },
            label = { Text(stringResource(R.string.new_tab_name_label)) },
            placeholder = { Text(stringResource(R.string.new_tab_name_placeholder)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new_tab_name_input"),
            shape = themedCorner(12.dp),
        )

        if (!createError.isNullOrBlank()) {
            Surface(
                shape = themedCorner(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .testTag("new_tab_create_error"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = createError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        ThemedButton(
            onClick = {
                val effectiveSystemPrompt = if (selectedAgent == "shell") {
                    null
                } else if (includeSystemPrompt) {
                    systemPromptText.trim()
                } else {
                    ""
                }
                val effectiveShellMode = if (selectedAgent == "shell" && ptySupported) {
                    selectedShellMode
                } else {
                    null
                }
                onCreate(
                    selectedAgent,
                    cwdField.text.trim(),
                    permissionMode,
                    customTitle.trim(),
                    if (selectedAgent == "shell") null else selectedModel,
                    effectiveSystemPrompt,
                    effectiveShellMode,
                )
            },
            enabled = canCreateTab && !isCreating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("create_tab_button"),
            shape = themedCorner(14.dp),
        ) {
            Text(
                text = if (isCreating) {
                    stringResource(R.string.new_tab_creating_button)
                } else {
                    newTabButtonLabel(canCreateTab, R.string.new_tab_create_button)
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
