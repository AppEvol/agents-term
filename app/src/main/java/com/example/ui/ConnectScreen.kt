package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.BootstrapPhase
import com.example.data.BootstrapUiState
import com.example.data.ConnectionConfig
import com.example.data.SshAuthMethod
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.SshSetupAutofill
import com.example.data.TailscaleStatus
import com.example.data.defaultAgyBootstrapPrompt
import com.example.data.parseSshSetupInput
import com.example.data.tailscaleStatusFlow
import com.example.ui.theme.AppTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkOnSurface
import com.example.ui.theme.HerdrBlue
import com.example.ui.theme.HerdrEmerald
import com.example.ui.theme.LunaHillBackdrop
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedButton
import com.example.ui.theme.ThemedFilterChip
import com.example.ui.theme.ThemedOutlinedButton
import com.example.ui.theme.ThemedTextButton
import com.example.ui.theme.ThemedTextField
import com.example.ui.theme.TitleBarBackground
import com.example.ui.theme.TitleBarIconButton
import com.example.ui.theme.isLunaControls
import com.example.ui.theme.lunaTab
import com.example.ui.theme.lunaTabBaseline
import com.example.ui.theme.themedCorner
import com.example.ui.theme.titleBarColors
import com.example.ui.theme.titleBarTitleStyle
import kotlinx.coroutines.delay
import java.util.Locale

/** Live Tailscale status of this phone; null until the first reading arrives. */
@Composable
private fun rememberTailscaleStatus(): TailscaleStatus? {
    val context = LocalContext.current.applicationContext
    return produceState<TailscaleStatus?>(initialValue = null, context) {
        tailscaleStatusFlow(context).collect { value = it }
    }.value
}

/** One-line hint telling the user whether this phone can reach a tailnet at all. */
@Composable
private fun TailscaleStatusHint(status: TailscaleStatus) {
    val active = status as? TailscaleStatus.Active
    val tint = if (active != null) AppTheme.colors.success else AppTheme.colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("tailscale_status_hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (active != null) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = if (active != null) {
                stringResource(R.string.tailscale_status_active, active.address)
            } else {
                stringResource(R.string.tailscale_status_not_detected)
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private enum class ConnectMode {
    SSH_BOOTSTRAP,
    DIRECT_URI,
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    initialConfig: ConnectionConfig,
    sshProfile: SshProfile,
    bootstrapState: BootstrapUiState,
    pendingPairing: ConnectionConfig?,
    pendingSshAutofill: SshSetupAutofill? = null,
    isTesting: Boolean,
    errorMessage: String?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onStartSshBootstrap: (SshBootstrapConfig) -> Unit,
    onCancelSshBootstrap: () -> Unit,
    onTrustNewHostKeyAndRetry: (SshBootstrapConfig) -> Unit,
    onConsumedPendingPairing: () -> Unit,
    onConsumedPendingSshAutofill: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    tailscaleStatus: TailscaleStatus? = rememberTailscaleStatus(),
) {
    if (canCancel) {
        BackHandler(onBack = onCancel)
    }

    // Keep the screen awake during SSH bootstrap so Android does not suspend the SSH socket.
    val view = LocalView.current
    val autofillManager = LocalAutofillManager.current
    DisposableEffect(view, bootstrapState.isRunning) {
        val prevKeepScreenOn = view.keepScreenOn
        if (bootstrapState.isRunning) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = prevKeepScreenOn
        }
    }

    var mode by remember {
        mutableStateOf(
            when {
                pendingSshAutofill != null -> ConnectMode.SSH_BOOTSTRAP
                initialConfig.isValid || pendingPairing != null -> ConnectMode.DIRECT_URI
                else -> ConnectMode.SSH_BOOTSTRAP
            },
        )
    }

    // Direct / Pairing URI state
    var host by remember(initialConfig) { mutableStateOf(initialConfig.host) }
    var portText by remember(initialConfig) { mutableStateOf(initialConfig.port.toString()) }
    var token by remember(initialConfig) { mutableStateOf(initialConfig.token) }
    var showToken by remember { mutableStateOf(false) }
    var pasteFeedback by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // SSH + Antigravity (agy) Bootstrap state
    var sshHost by remember(sshProfile) { mutableStateOf(sshProfile.host.ifBlank { initialConfig.host }) }
    var sshUsername by remember(sshProfile) { mutableStateOf(sshProfile.username) }
    var sshPortText by remember(sshProfile) { mutableStateOf(sshProfile.sshPort.toString()) }
    var daemonPortText by remember(sshProfile) { mutableStateOf(sshProfile.daemonPort.toString()) }
    var sshAuthMethod by remember(sshProfile) { mutableStateOf(sshProfile.authMethod) }
    var sshPassword by remember { mutableStateOf("") }
    var showSshPassword by remember { mutableStateOf(false) }
    var sshPrivateKey by remember { mutableStateOf("") }
    var sshPassphrase by remember { mutableStateOf("") }
    var showPromptEditor by remember { mutableStateOf(false) }
    var bootstrapPrompt by remember {
        mutableStateOf(defaultAgyBootstrapPrompt(sshProfile.daemonPort))
    }
    var sshAutofillFeedback by remember { mutableStateOf<String?>(null) }

    val pasteSuccessMsg = stringResource(R.string.connect_paste_success)
    val pasteInvalidMsg = stringResource(R.string.connect_paste_invalid)
    val sshPasteSuccessMsg = stringResource(R.string.ssh_paste_success)
    val sshPasteSwitchedDirectMsg = stringResource(R.string.ssh_paste_switched_direct)
    val sshPasteInvalidMsg = stringResource(R.string.ssh_paste_invalid)
    val sshInlineAutofilledMsg = stringResource(R.string.ssh_inline_autofilled)

    fun applySshAutofill(autofill: SshSetupAutofill) {
        mode = ConnectMode.SSH_BOOTSTRAP
        sshHost = autofill.host
        autofill.username?.let { sshUsername = it }
        autofill.sshPort?.let { sshPortText = it.toString() }
        autofill.daemonPort?.let { dPort ->
            daemonPortText = dPort.toString()
            if (!showPromptEditor) {
                bootstrapPrompt = defaultAgyBootstrapPrompt(dPort)
            }
        }
        autofill.authMethod?.let { sshAuthMethod = it }
    }

    LaunchedEffect(pendingPairing) {
        if (pendingPairing != null) {
            mode = ConnectMode.DIRECT_URI
            host = pendingPairing.host
            portText = pendingPairing.port.toString()
            token = pendingPairing.token
            onConsumedPendingPairing()
        }
    }

    LaunchedEffect(pendingSshAutofill) {
        if (pendingSshAutofill != null) {
            applySshAutofill(pendingSshAutofill)
            sshAutofillFeedback = sshPasteSuccessMsg
            onConsumedPendingSshAutofill()
        }
    }

    fun buildSshConfig(): SshBootstrapConfig {
        val sPort = sshPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        val dPort = daemonPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7501
        return SshBootstrapConfig(
            host = sshHost.trim(),
            sshPort = sPort,
            username = sshUsername.trim(),
            authMethod = sshAuthMethod,
            password = sshPassword,
            privateKey = sshPrivateKey,
            passphrase = sshPassphrase,
            daemonPort = dPort,
            prompt = bootstrapPrompt.trim().ifBlank { defaultAgyBootstrapPrompt(dPort) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TitleBarBackground {
                TopAppBar(
                    colors = titleBarColors(),
                    navigationIcon = {
                        if (canCancel) {
                            TitleBarIconButton(
                                onClick = onCancel,
                                modifier = Modifier.testTag("connect_back_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = titleBarTitleStyle(MaterialTheme.typography.titleLarge),
                        )
                    },
                    actions = {
                        TitleBarIconButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier.testTag("connect_settings_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.action_app_settings),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Atmospheric hero header card. It sits on a dark photo, so its colors are fixed, not themed.
                Surface(
                    shape = themedCorner(22.dp),
                    color = DarkBackground,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isLunaControls) {
                            // The XP look swaps the dark photo for a blue sky over a green hill.
                            LunaHillBackdrop(modifier = Modifier.fillMaxSize())
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.img_herdr_hero_1795442350136),
                                contentDescription = stringResource(R.string.connect_title),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                DarkBackground.copy(alpha = 0.92f),
                                                DarkBackground.copy(alpha = 0.65f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    ),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Surface(
                                shape = themedCorner(10.dp),
                                color = HerdrBlue.copy(alpha = 0.22f),
                                border = BorderStroke(1.dp, HerdrBlue.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Hub,
                                        contentDescription = null,
                                        tint = HerdrEmerald,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "AGENTSTERM · TAILSCALE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFontFamily,
                                        color = DarkOnSurface,
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.connect_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkOnSurface,
                                )
                                Text(
                                    text = stringResource(R.string.connect_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DarkOnSurface.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }

                tailscaleStatus?.let { TailscaleStatusHint(it) }

                // Mode switcher: SSH + agy Auto-Setup vs Direct / Pairing URI
                // In the XP look the two modes are tabs standing on a line; elsewhere, a segmented box.
                val lunaTabs = isLunaControls
                Surface(
                    shape = if (lunaTabs) RectangleShape else themedCorner(16.dp),
                    color = if (lunaTabs) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (lunaTabs) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (lunaTabs) Modifier.lunaTabBaseline() else Modifier),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (lunaTabs) PaddingValues(horizontal = 4.dp) else PaddingValues(4.dp)),
                        horizontalArrangement = Arrangement.spacedBy(if (lunaTabs) 2.dp else 4.dp),
                    ) {
                        ModeTabButton(
                            selected = mode == ConnectMode.SSH_BOOTSTRAP,
                            icon = Icons.Rounded.AutoAwesome,
                            label = stringResource(R.string.connect_mode_ssh_bootstrap),
                            onClick = { mode = ConnectMode.SSH_BOOTSTRAP },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mode_ssh_bootstrap"),
                        )
                        ModeTabButton(
                            selected = mode == ConnectMode.DIRECT_URI,
                            icon = Icons.Rounded.Link,
                            label = stringResource(R.string.connect_mode_direct),
                            onClick = { mode = ConnectMode.DIRECT_URI },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mode_direct_uri"),
                        )
                    }
                }

                if (mode == ConnectMode.SSH_BOOTSTRAP) {
                    // SSH + Antigravity (agy) zero-touch setup card
                    Surface(
                        shape = themedCorner(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(R.string.ssh_bootstrap_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = stringResource(R.string.ssh_bootstrap_banner_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (sshProfile.isBootstrapped && sshProfile.host.equals(sshHost.trim(), ignoreCase = true)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = AppTheme.colors.success,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.ssh_already_bootstrapped),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppTheme.colors.success,
                                    )
                                }
                            }
                            ThemedOutlinedButton(
                                enabled = !bootstrapState.isRunning,
                                onClick = {
                                    val clip = clipboard.getText()?.text.orEmpty()
                                    val parsedSsh = parseSshSetupInput(clip)
                                    if (parsedSsh != null) {
                                        applySshAutofill(parsedSsh)
                                        sshAutofillFeedback = sshPasteSuccessMsg
                                    } else {
                                        val parsedPairing = ConnectionConfig.parsePairingUri(clip)
                                        if (parsedPairing != null) {
                                            mode = ConnectMode.DIRECT_URI
                                            host = parsedPairing.host
                                            portText = parsedPairing.port.toString()
                                            token = parsedPairing.token
                                            pasteFeedback = sshPasteSwitchedDirectMsg
                                            sshAutofillFeedback = null
                                        } else {
                                            sshAutofillFeedback = sshPasteInvalidMsg
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("ssh_autofill_clipboard_button"),
                                shape = themedCorner(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ssh_paste_autofill))
                            }
                            if (!sshAutofillFeedback.isNullOrBlank()) {
                                Text(
                                    text = sshAutofillFeedback!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    ThemedTextField(
                        value = sshHost,
                        onValueChange = { next ->
                            val isSingleCharEdit = kotlin.math.abs(next.length - sshHost.length) <= 1 &&
                                (next.startsWith(sshHost) || sshHost.startsWith(next))
                            val parsed = if (!isSingleCharEdit) parseSshSetupInput(next) else null
                            if (parsed != null) {
                                applySshAutofill(parsed)
                                sshAutofillFeedback = sshInlineAutofilledMsg
                            } else {
                                sshHost = next
                            }
                        },
                        label = { Text(stringResource(R.string.connect_host_label)) },
                        placeholder = { Text(stringResource(R.string.connect_host_placeholder)) },
                        singleLine = true,
                        enabled = !bootstrapState.isRunning,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType("ssh_host") + ContentType("hostname")
                            }
                            .testTag("ssh_host_input"),
                        shape = themedCorner(14.dp),
                    )

                    ThemedTextField(
                        value = sshUsername,
                        onValueChange = { sshUsername = it },
                        label = { Text(stringResource(R.string.ssh_username_label)) },
                        placeholder = { Text(stringResource(R.string.ssh_username_placeholder)) },
                        singleLine = true,
                        enabled = !bootstrapState.isRunning,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType.Username
                            }
                            .testTag("ssh_username_input"),
                        shape = themedCorner(14.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ThemedTextField(
                            value = sshPortText,
                            onValueChange = { sshPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.ssh_port_label)) },
                            placeholder = { Text("22") },
                            singleLine = true,
                            enabled = !bootstrapState.isRunning,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ssh_port_input"),
                            shape = themedCorner(14.dp),
                        )
                        ThemedTextField(
                            value = daemonPortText,
                            onValueChange = { next ->
                                val filtered = next.filter { ch -> ch.isDigit() }.take(5)
                                daemonPortText = filtered
                                val parsedPort = filtered.toIntOrNull()?.takeIf { it in 1..65535 }
                                if (parsedPort != null && !showPromptEditor) {
                                    bootstrapPrompt = defaultAgyBootstrapPrompt(parsedPort)
                                }
                            },
                            label = { Text(stringResource(R.string.ssh_daemon_port_label)) },
                            placeholder = { Text("7501") },
                            singleLine = true,
                            enabled = !bootstrapState.isRunning,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ssh_daemon_port_input"),
                            shape = themedCorner(14.dp),
                        )
                    }

                    // SSH Authentication Method selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.ssh_auth_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemedFilterChip(
                                selected = sshAuthMethod == SshAuthMethod.TAILSCALE_SSH,
                                onClick = { if (!bootstrapState.isRunning) sshAuthMethod = SshAuthMethod.TAILSCALE_SSH },
                                label = { Text(stringResource(R.string.ssh_auth_tailscale)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("auth_tailscale_ssh"),
                            )
                            ThemedFilterChip(
                                selected = sshAuthMethod == SshAuthMethod.PRIVATE_KEY,
                                onClick = { if (!bootstrapState.isRunning) sshAuthMethod = SshAuthMethod.PRIVATE_KEY },
                                label = { Text(stringResource(R.string.ssh_auth_key)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("auth_private_key"),
                            )
                            ThemedFilterChip(
                                selected = sshAuthMethod == SshAuthMethod.PASSWORD,
                                onClick = { if (!bootstrapState.isRunning) sshAuthMethod = SshAuthMethod.PASSWORD },
                                label = { Text(stringResource(R.string.ssh_auth_password)) },
                                modifier = Modifier.testTag("auth_password"),
                            )
                        }
                    }

                    if (sshAuthMethod == SshAuthMethod.PASSWORD) {
                        ThemedTextField(
                            value = sshPassword,
                            onValueChange = { sshPassword = it },
                            label = { Text(stringResource(R.string.ssh_password_label)) },
                            singleLine = true,
                            enabled = !bootstrapState.isRunning,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            visualTransformation = if (showSshPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showSshPassword = !showSshPassword }) {
                                    Icon(
                                        imageVector = if (showSshPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = stringResource(R.string.action_toggle_token),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentType = ContentType.Password
                                }
                                .testTag("ssh_password_input"),
                            shape = themedCorner(14.dp),
                        )
                    } else if (sshAuthMethod == SshAuthMethod.PRIVATE_KEY) {
                        ThemedTextField(
                            value = sshPrivateKey,
                            onValueChange = { sshPrivateKey = it },
                            label = { Text(stringResource(R.string.ssh_private_key_label)) },
                            placeholder = { Text(stringResource(R.string.ssh_private_key_placeholder)) },
                            minLines = 3,
                            maxLines = 5,
                            enabled = !bootstrapState.isRunning,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ssh_private_key_input"),
                            shape = themedCorner(14.dp),
                        )
                        ThemedTextField(
                            value = sshPassphrase,
                            onValueChange = { sshPassphrase = it },
                            label = { Text(stringResource(R.string.ssh_passphrase_label)) },
                            singleLine = true,
                            enabled = !bootstrapState.isRunning,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentType = ContentType.Password
                                }
                                .testTag("ssh_passphrase_input"),
                            shape = themedCorner(14.dp),
                        )
                    }

                    // Expandable agy prompt customization
                    Surface(
                        shape = themedCorner(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPromptEditor = !showPromptEditor }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("toggle_prompt_editor"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Terminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.ssh_prompt_customize),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Icon(
                                    imageVector = if (showPromptEditor) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AnimatedVisibility(visible = showPromptEditor) {
                                Column(
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ThemedTextField(
                                        value = bootstrapPrompt,
                                        onValueChange = { bootstrapPrompt = it },
                                        enabled = !bootstrapState.isRunning,
                                        minLines = 5,
                                        maxLines = 10,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = MonoFontFamily,
                                            lineHeight = 18.sp,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("agy_prompt_input"),
                                        shape = themedCorner(12.dp),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        ThemedTextButton(
                                            enabled = !bootstrapState.isRunning,
                                            onClick = {
                                                val dPort = daemonPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7501
                                                bootstrapPrompt = defaultAgyBootstrapPrompt(dPort)
                                            },
                                        ) {
                                            Text(stringResource(R.string.ssh_prompt_reset))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Live streaming bootstrap console & status
                    if (bootstrapState.phase != BootstrapPhase.IDLE || bootstrapState.logs.isNotEmpty()) {
                        BootstrapConsoleCard(
                            state = bootstrapState,
                            onTrustNewKeyAndRetry = {
                                onTrustNewHostKeyAndRetry(buildSshConfig())
                            },
                        )
                    }

                    // Primary SSH Bootstrap CTA / Cancel button
                    if (bootstrapState.isRunning) {
                        ThemedOutlinedButton(
                            onClick = onCancelSshBootstrap,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.danger),
                            border = BorderStroke(1.dp, AppTheme.colors.danger.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("cancel_bootstrap_button"),
                            shape = themedCorner(14.dp),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ssh_bootstrap_cancel),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        val candidateConfig = buildSshConfig()
                        val authReady = when (sshAuthMethod) {
                            SshAuthMethod.TAILSCALE_SSH -> true
                            SshAuthMethod.PRIVATE_KEY -> sshPrivateKey.isNotBlank()
                            SshAuthMethod.PASSWORD -> sshPassword.isNotEmpty()
                        }
                        ThemedButton(
                            onClick = {
                                autofillManager?.commit()
                                onStartSshBootstrap(candidateConfig)
                            },
                            enabled = candidateConfig.isValid && authReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("ssh_bootstrap_button"),
                            shape = themedCorner(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ssh_bootstrap_button),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    // Direct / Pairing URI mode
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
                            Text(
                                text = stringResource(R.string.connect_quick_setup),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.connect_quick_setup_steps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ThemedOutlinedButton(
                                onClick = {
                                    val clip = clipboard.getText()?.text.orEmpty()
                                    val parsed = ConnectionConfig.parsePairingUri(clip)
                                    if (parsed != null) {
                                        host = parsed.host
                                        portText = parsed.port.toString()
                                        token = parsed.token
                                        pasteFeedback = pasteSuccessMsg
                                    } else {
                                        pasteFeedback = pasteInvalidMsg
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("paste_uri_button"),
                                shape = themedCorner(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.connect_paste_uri))
                            }
                            if (pasteFeedback != null) {
                                Text(
                                    text = pasteFeedback!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    ThemedTextField(
                        value = host,
                        onValueChange = { next ->
                            val isSingleCharEdit = kotlin.math.abs(next.length - host.length) <= 1 &&
                                (next.startsWith(host) || host.startsWith(next))
                            val parsed = if (!isSingleCharEdit) ConnectionConfig.parsePairingUri(next) else null
                            if (parsed != null) {
                                host = parsed.host
                                portText = parsed.port.toString()
                                token = parsed.token
                                pasteFeedback = pasteSuccessMsg
                            } else {
                                host = next
                            }
                        },
                        label = { Text(stringResource(R.string.connect_host_label)) },
                        placeholder = { Text(stringResource(R.string.connect_host_placeholder)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType("vm_host") + ContentType("hostname")
                            }
                            .testTag("host_input"),
                        shape = themedCorner(14.dp),
                    )

                    ThemedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.connect_port_label)) },
                        placeholder = { Text("7501") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("port_input"),
                        shape = themedCorner(14.dp),
                    )

                    ThemedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.connect_token_label)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (showToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showToken = !showToken },
                                modifier = Modifier.testTag("toggle_token_visibility"),
                            ) {
                                Icon(
                                    imageVector = if (showToken) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = stringResource(R.string.action_toggle_token),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType.Password
                            }
                            .testTag("token_input"),
                        shape = themedCorner(14.dp),
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = themedCorner(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }

                    ThemedButton(
                        onClick = {
                            autofillManager?.commit()
                            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7501
                            onConnect(
                                ConnectionConfig(
                                    host = host.trim(),
                                    port = port,
                                    token = token.trim(),
                                ),
                            )
                        },
                        enabled = host.isNotBlank() && !isTesting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("connect_button"),
                        shape = themedCorner(14.dp),
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.connect_button_testing))
                        } else {
                            Text(
                                text = stringResource(R.string.connect_button),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Surface(
                        shape = themedCorner(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.connect_vm_hint),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTabButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val luna = isLunaControls
    val contentColor = when {
        luna -> MaterialTheme.colorScheme.onSurface
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = if (luna) RectangleShape else themedCorner(12.dp),
        color = if (selected && !luna) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = modifier
            .height(44.dp)
            .then(if (luna) Modifier.lunaTab(selected) else Modifier.clip(themedCorner(12.dp)))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BootstrapConsoleCard(
    state: BootstrapUiState,
    onTrustNewKeyAndRetry: () -> Unit,
) {
    val logScrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var copiedLogs by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            elapsedSeconds = 0
            while (true) {
                delay(1_000)
                elapsedSeconds++
            }
        }
    }

    LaunchedEffect(copiedLogs) {
        if (copiedLogs) {
            delay(1_800)
            copiedLogs = false
        }
    }

    val phaseText = when (state.phase) {
        BootstrapPhase.CONNECTING_SSH -> stringResource(R.string.ssh_phase_connecting)
        BootstrapPhase.RUNNING_AGY -> stringResource(R.string.ssh_phase_running_agy)
        BootstrapPhase.VERIFYING_DAEMON -> stringResource(R.string.ssh_phase_verifying)
        BootstrapPhase.SUCCEEDED -> stringResource(R.string.ssh_phase_succeeded)
        BootstrapPhase.ERROR -> state.errorMessage ?: "Bootstrap failed"
        BootstrapPhase.IDLE -> ""
    }

    Surface(
        shape = themedCorner(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            width = 1.dp,
            color = when (state.phase) {
                BootstrapPhase.ERROR -> AppTheme.colors.danger.copy(alpha = 0.6f)
                BootstrapPhase.SUCCEEDED -> AppTheme.colors.success.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.info,
                    )
                }
                Text(
                    text = stringResource(R.string.ssh_console_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (state.isRunning) {
                    val mins = elapsedSeconds / 60
                    val secs = elapsedSeconds % 60
                    Text(
                        text = String.format(Locale.US, "%d:%02d", mins, secs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.logs.isNotEmpty() || !state.errorMessage.isNullOrBlank()) {
                    IconButton(
                        onClick = {
                            val dump = buildString {
                                if (!state.errorMessage.isNullOrBlank()) {
                                    appendLine("ERROR: ${state.errorMessage}")
                                }
                                state.logs.forEach { appendLine(it) }
                            }.trim()
                            clipboard.setText(AnnotatedString(dump))
                            copiedLogs = true
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("copy_bootstrap_logs_button"),
                    ) {
                        Icon(
                            imageVector = if (copiedLogs) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.ssh_copy_logs),
                            tint = if (copiedLogs) AppTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (phaseText.isNotBlank()) {
                Text(
                    text = phaseText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.phase) {
                        BootstrapPhase.ERROR -> MaterialTheme.colorScheme.error
                        BootstrapPhase.SUCCEEDED -> AppTheme.colors.success
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (state.isRunning) {
                Text(
                    text = stringResource(R.string.ssh_keep_screen_on_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.logs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(logScrollState)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = themedCorner(10.dp),
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (line in state.logs) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            ),
                            color = when {
                                line.startsWith("[stderr]") -> AppTheme.colors.warning
                                line.startsWith("[tool done]") -> AppTheme.colors.success
                                line.startsWith("[tool]") -> AppTheme.colors.info
                                line.startsWith(">>>") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }

            if (state.isHostKeyMismatch) {
                ThemedButton(
                    onClick = onTrustNewKeyAndRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.warning,
                        contentColor = AppTheme.colors.onWarning,
                    ),
                    shape = themedCorner(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("trust_new_host_key_button"),
                ) {
                    Text(
                        text = stringResource(R.string.ssh_trust_new_key),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
