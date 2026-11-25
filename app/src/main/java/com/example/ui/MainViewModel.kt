package com.example.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.data.AndroidUiDriver
import com.example.data.AppStateCommandException
import com.example.data.AppStateReporter
import com.example.data.AppStateSources
import com.example.data.AppThemeId
import com.example.data.AppUiController
import com.example.data.BootstrapPhase
import com.example.data.BootstrapUiState
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.ConnectionState
import com.example.data.CrashLogStore
import com.example.data.CreateTabRequest
import com.example.data.DebugDumpUploadRequest
import com.example.data.DebugDumpUploadResponse
import com.example.data.DebugDumpUploadState
import com.example.data.DebugEvent
import com.example.data.DebugRecorder
import com.example.data.DebugSharingSettings
import com.example.data.ForegroundActivityTracker
import com.example.data.FsListResponse
import com.example.data.HerdrApiClient
import com.example.data.HerdrRepository
import com.example.data.HostKeyMismatchException
import com.example.data.SettingsStore
import com.example.data.SshBootstrapClient
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.SshSetupAutofill
import com.example.data.UpdateTabRequest
import com.example.data.WorkspaceState
import com.example.data.buildDeviceInfoSummary
import com.example.data.buildSanitizedWorkspaceJson
import com.example.data.captureWindowScreenshotBase64
import com.example.data.cleanHost
import com.example.data.collectAppLogcat
import com.example.data.exportDebugBundleZip
import com.example.data.generateDebugDumpId
import com.example.data.parseSshSetupUri
import com.example.data.sanitizeDebugText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    val debugRecorder = DebugRecorder()

    private val lastAutoUploadMs = AtomicLong(0L)
    private val lastControlNoticeMs = AtomicLong(0L)

    private val api = HerdrApiClient(debugRecorder)
    private val repo = HerdrRepository(
        api = api,
        settings = settings,
        debugRecorder = debugRecorder,
        onOperationalError = { reason -> maybeTriggerAutoUpload(reason) },
    )
    private val sshClient = SshBootstrapClient(settings, debugRecorder)

    val state: StateFlow<WorkspaceState> = repo.state

    /**
     * Workspace chrome state (tabs, connection, banners, finished flags) without `messagesByTab`,
     * so streaming text deltas do not recompose the top bar, quick tab strip, or drawer.
     */
    val chromeState: StateFlow<WorkspaceState> = repo.state
        .map { ws ->
            ws.copy(
                messagesByTab = emptyMap(),
                precomputedFinishedTabIds = ws.finishedTabIds,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = repo.state.value.let { ws ->
                ws.copy(
                    messagesByTab = emptyMap(),
                    precomputedFinishedTabIds = ws.finishedTabIds,
                )
            },
        )

    /** Messages for the currently selected tab only; background-tab updates do not emit here. */
    val activeMessages: StateFlow<List<ChatMessage>> = repo.state
        .map { it.activeMessages }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = repo.state.value.activeMessages,
        )

    private val _showConnectScreen = MutableStateFlow(!settings.loadConnection().isValid)
    val showConnectScreen: StateFlow<Boolean> = _showConnectScreen.asStateFlow()

    private val _showSettingsScreen = MutableStateFlow(false)
    val showSettingsScreen: StateFlow<Boolean> = _showSettingsScreen.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _pendingPairingConfig = MutableStateFlow<ConnectionConfig?>(null)
    val pendingPairingConfig: StateFlow<ConnectionConfig?> = _pendingPairingConfig.asStateFlow()

    private val _pendingSshAutofill = MutableStateFlow<SshSetupAutofill?>(null)
    val pendingSshAutofill: StateFlow<SshSetupAutofill?> = _pendingSshAutofill.asStateFlow()

    private val _sshProfile = MutableStateFlow(settings.loadSshProfile())
    val sshProfile: StateFlow<SshProfile> = _sshProfile.asStateFlow()

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState.asStateFlow()

    private val _debugSettings = MutableStateFlow(settings.loadDebugSharingSettings())
    val debugSettings: StateFlow<DebugSharingSettings> = _debugSettings.asStateFlow()

    private val _appTheme = MutableStateFlow(settings.loadAppTheme())
    val appTheme: StateFlow<AppThemeId> = _appTheme.asStateFlow()

    private val _debugUploadState = MutableStateFlow(settings.loadLastDebugUpload())
    val debugUploadState: StateFlow<DebugDumpUploadState> = _debugUploadState.asStateFlow()

    private val _debugEventCount = MutableStateFlow(0)
    val debugEventCount: StateFlow<Int> = _debugEventCount.asStateFlow()

    private val _hasLastCrash = MutableStateFlow(false)
    val hasLastCrash: StateFlow<Boolean> = _hasLastCrash.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _isRefreshingClosedTabs = MutableStateFlow(false)
    val isRefreshingClosedTabs: StateFlow<Boolean> = _isRefreshingClosedTabs.asStateFlow()

    private val _revivingTabId = MutableStateFlow<String?>(null)
    val revivingTabId: StateFlow<String?> = _revivingTabId.asStateFlow()

    private var bootstrapJob: Job? = null

    private val appVersionLabel =
        "${BuildConfig.VERSION_NAME} (code=${BuildConfig.VERSION_CODE}, debug=${BuildConfig.DEBUG})"

    private val appStateReporter = AppStateReporter(
        recorder = debugRecorder,
        sources = ViewModelAppStateSources(),
        isEnabled = { _debugSettings.value.remoteInspectionActive },
        appVersion = appVersionLabel,
        device = buildDeviceInfoSummary(),
        ui = AppUiController(
            driver = AndroidUiDriver(activity = { ForegroundActivityTracker.current() }),
            onControl = { notifyRemoteControl() },
        ),
        isControlEnabled = { _debugSettings.value.remoteControlActive },
    )

    init {
        repo.appStateHandler = appStateReporter
        latestDebugRecorder = debugRecorder
        CrashLogStore.install(application)
        _hasLastCrash.value = CrashLogStore.hasLastCrash(application)
        debugRecorder.setCountListener { count -> _debugEventCount.value = count }
        debugRecorder.record(
            category = "lifecycle",
            event = "app_init",
            detail = "AgentsTerm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started; crashLog=${_hasLastCrash.value}",
        )
        repo.initializeAutoConnect()
    }

    fun onForeground() {
        debugRecorder.record("lifecycle", "on_foreground", "App resumed to foreground")
        repo.ensureConnectedOnForeground()
    }

    override fun onCleared() {
        repo.disconnect()
        super.onCleared()
    }

    fun handleIncomingPairingUri(uri: String) {
        val sshAutofill = parseSshSetupUri(uri)
        if (sshAutofill != null) {
            debugRecorder.record(
                category = "ui",
                event = "ssh_setup_uri_received",
                detail = "Parsed SSH setup URI for ${sshAutofill.username ?: "(no-user)"}@${sshAutofill.host}",
            )
            _pendingSshAutofill.value = sshAutofill
            _showSettingsScreen.value = false
            _showConnectScreen.value = true
            return
        }
        val parsed = ConnectionConfig.parsePairingUri(uri) ?: return
        debugRecorder.record("ui", "pairing_uri_received", "Parsed pairing URI for ${parsed.host}:${parsed.port}")
        _pendingPairingConfig.value = parsed
        _showSettingsScreen.value = false
        _showConnectScreen.value = true
        connectToVm(parsed)
    }

    fun clearPendingPairingConfig() {
        _pendingPairingConfig.value = null
    }

    fun clearPendingSshAutofill() {
        _pendingSshAutofill.value = null
    }

    fun openConnectionSettings() {
        _sshProfile.value = settings.loadSshProfile()
        _showSettingsScreen.value = false
        _showConnectScreen.value = true
    }

    fun closeConnectionSettingsIfConfigured() {
        if (state.value.config.isValid) {
            _showConnectScreen.value = false
        }
    }

    fun openAppSettings() {
        _hasLastCrash.value = CrashLogStore.hasLastCrash(getApplication())
        _debugEventCount.value = debugRecorder.count
        _showSettingsScreen.value = true
    }

    fun closeAppSettings() {
        _showSettingsScreen.value = false
    }

    fun setAppTheme(theme: AppThemeId) {
        if (_appTheme.value == theme) return
        _appTheme.value = theme
        settings.saveAppTheme(theme)
        debugRecorder.record(category = "ui", event = "app_theme_changed", detail = theme.storageKey)
    }

    fun updateDebugSettings(transform: (DebugSharingSettings) -> DebugSharingSettings) {
        val previous = _debugSettings.value
        val updated = transform(previous)
        _debugSettings.value = updated
        settings.saveDebugSharingSettings(updated)
        debugRecorder.record(
            category = "ui",
            event = "debug_settings_updated",
            detail = "enabled=${updated.enabled} screenshot=${updated.includeScreenshot} logcat=${updated.includeLogcat} " +
                "autoUpload=${updated.autoUploadOnError} remoteInspection=${updated.remoteInspectionEnabled} " +
                "remoteControl=${updated.remoteControlEnabled}",
        )
        if (previous.remoteInspectionActive != updated.remoteInspectionActive ||
            previous.remoteControlActive != updated.remoteControlActive
        ) {
            repo.sendAppHello()
        }
    }

    fun clearDebugLogs() {
        debugRecorder.clear()
        CrashLogStore.clearLastCrash(getApplication())
        _hasLastCrash.value = false
    }

    fun recentDebugEvents(limit: Int = 30): List<DebugEvent> = debugRecorder.snapshot().takeLast(limit).reversed()

    private suspend fun buildDebugDumpRequest(
        note: String?,
        screenshotBase64: String?,
    ): DebugDumpUploadRequest {
        val app = getApplication<Application>()
        val cfg = _debugSettings.value
        val wsState = repo.snapshotStateWithPendingDeltas()
        val logcat = if (cfg.includeLogcat) collectAppLogcat(maxLines = 400) else null
        val lastCrash = CrashLogStore.readLastCrash(app)
        val connectionSummary = if (wsState.config.isValid) {
            "${wsState.config.host}:${wsState.config.port} (${wsState.connectionState.name})"
        } else {
            "unconfigured (${wsState.connectionState.name})"
        }
        return DebugDumpUploadRequest(
            dumpId = generateDebugDumpId(),
            createdAt = System.currentTimeMillis(),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            appVersion = appVersionLabel,
            deviceInfo = buildDeviceInfoSummary(),
            connectionSummary = connectionSummary,
            workspaceStateJson = buildSanitizedWorkspaceJson(wsState),
            eventsNdjson = debugRecorder.toNdjson(),
            logcat = logcat,
            lastCrash = lastCrash,
            screenshotPngBase64 = if (cfg.includeScreenshot) screenshotBase64 else null,
        )
    }

    fun uploadDebugDumpToVm(
        note: String? = null,
        screenshotBase64: String? = null,
        onComplete: ((Result<DebugDumpUploadResponse>) -> Unit)? = null,
    ) {
        if (_debugUploadState.value.isUploading) return
        viewModelScope.launch {
            val result = performDebugDumpUpload(note = note, screenshotBase64 = screenshotBase64)
            onComplete?.invoke(result)
        }
    }

    /** Builds and uploads a dump, updating [debugUploadState]. Shared by the UI and app state reporter. */
    private suspend fun performDebugDumpUpload(
        note: String?,
        screenshotBase64: String?,
    ): Result<DebugDumpUploadResponse> {
        _debugUploadState.update { it.copy(isUploading = true, lastError = null) }
        val result = runCatching {
            val payload = buildDebugDumpRequest(note = note, screenshotBase64 = screenshotBase64)
            repo.uploadDebugDump(payload).getOrThrow()
        }
        result.onSuccess { resp ->
            val now = System.currentTimeMillis()
            settings.saveLastDebugUpload(resp, now)
            _debugUploadState.value = DebugDumpUploadState(
                isUploading = false,
                lastDumpId = resp.dumpId,
                lastRemoteDir = resp.remoteDir,
                lastLatestSymlink = resp.latestSymlink,
                lastUploadedAt = now,
                lastError = null,
            )
            debugRecorder.record(
                category = "ui",
                event = "debug_dump_uploaded",
                detail = "Uploaded dump ${resp.dumpId} to ${resp.remoteDir}",
            )
        }.onFailure { err ->
            val msg = err.message ?: "Failed to upload debug dump to VM"
            _debugUploadState.update { it.copy(isUploading = false, lastError = msg) }
            debugRecorder.record(
                category = "error",
                event = "debug_dump_upload_failed",
                detail = msg,
            )
        }
        return result
    }

    /** Tells the user an agent is driving the app: a toast, at most one per [CONTROL_NOTICE_INTERVAL_MS]. */
    private fun notifyRemoteControl() {
        val now = System.currentTimeMillis()
        val prev = lastControlNoticeMs.get()
        if (now - prev < CONTROL_NOTICE_INTERVAL_MS || !lastControlNoticeMs.compareAndSet(prev, now)) return
        val activity = ForegroundActivityTracker.current() ?: return
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(activity, R.string.app_state_control_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** Android-backed data for [AppStateReporter]; respects the user's debug sharing toggles. */
    private inner class ViewModelAppStateSources : AppStateSources {
        override fun uiState(): Map<String, Any?> {
            val debug = _debugSettings.value
            val bootstrap = _bootstrapState.value
            return mapOf(
                "screen" to when {
                    _showSettingsScreen.value -> "settings"
                    _showConnectScreen.value -> "connect"
                    else -> "workspace"
                },
                "theme" to _appTheme.value.storageKey,
                "appInForeground" to (ForegroundActivityTracker.current() != null),
                "isTestingConnection" to _isTestingConnection.value,
                "hasPendingPairing" to (_pendingPairingConfig.value != null),
                "bootstrap" to mapOf(
                    "phase" to bootstrap.phase.name,
                    "isRunning" to bootstrap.isRunning,
                    "errorMessage" to bootstrap.errorMessage?.let { sanitizeDebugText(it) },
                    "isHostKeyMismatch" to bootstrap.isHostKeyMismatch,
                    "recentLogs" to bootstrap.logs.takeLast(20).map { sanitizeDebugText(it) },
                ),
                "debugSettings" to mapOf(
                    "enabled" to debug.enabled,
                    "includeScreenshot" to debug.includeScreenshot,
                    "includeLogcat" to debug.includeLogcat,
                    "autoUploadOnError" to debug.autoUploadOnError,
                    "remoteInspectionEnabled" to debug.remoteInspectionEnabled,
                    "remoteControlEnabled" to debug.remoteControlEnabled,
                ),
                "debugEventCount" to debugRecorder.count,
                "hasLastCrash" to CrashLogStore.hasLastCrash(getApplication()),
                "lastDebugUpload" to _debugUploadState.value.let {
                    mapOf(
                        "isUploading" to it.isUploading,
                        "lastDumpId" to it.lastDumpId,
                        "lastRemoteDir" to it.lastRemoteDir,
                        "lastUploadedAt" to it.lastUploadedAt,
                        "lastError" to it.lastError?.let { e -> sanitizeDebugText(e) },
                    )
                },
            )
        }

        override fun workspaceJson(): String = buildSanitizedWorkspaceJson(repo.snapshotStateWithPendingDeltas())

        override suspend fun logcat(lines: Int): String {
            if (!_debugSettings.value.includeLogcat) {
                throw AppStateCommandException("Logcat sharing is turned off in the app (Settings > Debug sharing).")
            }
            return collectAppLogcat(maxLines = lines)
                ?: throw AppStateCommandException("Logcat is unavailable on this device.")
        }

        override fun lastCrash(): String? = CrashLogStore.readLastCrash(getApplication())

        override suspend fun screenshotPngBase64(): String {
            if (!_debugSettings.value.includeScreenshot) {
                throw AppStateCommandException("Screenshot sharing is turned off in the app (Settings > Debug sharing).")
            }
            val activity = ForegroundActivityTracker.current()
                ?: throw AppStateCommandException("The app is in the background; screenshots need it on screen.")
            val shot = captureWindowScreenshotBase64(activity)
                ?: throw AppStateCommandException("Screenshot capture failed.")
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, R.string.app_state_screenshot_toast, Toast.LENGTH_SHORT).show()
            }
            return shot
        }

        override suspend fun uploadDump(note: String?, includeScreenshot: Boolean): Map<String, Any?> {
            val shot = if (includeScreenshot && _debugSettings.value.includeScreenshot) {
                ForegroundActivityTracker.current()?.let { captureWindowScreenshotBase64(it) }
            } else {
                null
            }
            val resp = performDebugDumpUpload(
                note = note?.trim()?.takeIf { it.isNotEmpty() } ?: "Requested by a VM agent (agents_term_app_cli dump)",
                screenshotBase64 = shot,
            ).getOrElse { err ->
                throw AppStateCommandException("Dump upload failed: ${err.message ?: err::class.java.simpleName}")
            }
            return mapOf(
                "dumpId" to resp.dumpId,
                "remoteDir" to resp.remoteDir,
                "latestSymlink" to resp.latestSymlink,
                "files" to resp.files,
                "includedScreenshot" to (shot != null),
            )
        }
    }

    suspend fun createOfflineDebugZip(
        note: String? = null,
        screenshotBase64: String? = null,
    ): Result<File> = runCatching {
        val payload = buildDebugDumpRequest(note = note, screenshotBase64 = screenshotBase64)
        exportDebugBundleZip(getApplication(), payload)
    }

    private fun maybeTriggerAutoUpload(reason: String) {
        val cfg = _debugSettings.value
        if (!cfg.enabled || !cfg.autoUploadOnError) return
        if (state.value.connectionState != ConnectionState.CONNECTED) return
        val now = System.currentTimeMillis()
        val prev = lastAutoUploadMs.get()
        if (now - prev < AUTO_UPLOAD_COOLDOWN_MS) return
        if (!lastAutoUploadMs.compareAndSet(prev, now)) return
        uploadDebugDumpToVm(note = "Auto-captured on error: $reason", screenshotBase64 = null)
    }

    fun connectToVm(config: ConnectionConfig) {
        viewModelScope.launch {
            _isTestingConnection.value = true
            val result = repo.testAndConnect(config)
            _isTestingConnection.value = false
            if (result.isSuccess) {
                _showConnectScreen.value = false
            }
        }
    }

    fun startSshBootstrap(config: SshBootstrapConfig) {
        bootstrapJob?.cancel()
        val nonSecretProfile = SshProfile(
            host = config.host.trim(),
            sshPort = config.sshPort,
            username = config.username.trim(),
            authMethod = config.authMethod,
            daemonPort = config.daemonPort,
            isBootstrapped = settings.isHostBootstrapped(config.host),
        )
        settings.saveSshProfile(nonSecretProfile)
        _sshProfile.value = nonSecretProfile

        _bootstrapState.value = BootstrapUiState(
            phase = BootstrapPhase.CONNECTING_SSH,
            logs = emptyList(),
        )

        bootstrapJob = viewModelScope.launch {
            try {
                val result = sshClient.bootstrapVm(
                    config = config,
                    onPhase = { phase ->
                        _bootstrapState.update { it.copy(phase = phase) }
                    },
                    onHostFingerprint = { fp ->
                        _bootstrapState.update { it.copy(hostFingerprint = fp) }
                    },
                    onLog = { line ->
                        appendBootstrapLog(line)
                    },
                )

                _bootstrapState.update {
                    it.copy(
                        phase = BootstrapPhase.VERIFYING_DAEMON,
                        detectedPairingUri = result.rawPairingUri,
                    )
                }

                // Retry connecting up to 3 times to give a newly started daemon a moment to bind
                var connected = false
                var lastErr: String? = null
                for (attempt in 1..3) {
                    val connRes = repo.testAndConnect(result.connectionConfig)
                    if (connRes.isSuccess) {
                        connected = true
                        break
                    }
                    lastErr = connRes.exceptionOrNull()?.message
                    if (attempt < 3) {
                        appendBootstrapLog("Waiting for daemon on ${result.connectionConfig.host}:${result.connectionConfig.port} (attempt $attempt/3)…")
                        delay(1500)
                    }
                }

                if (connected) {
                    settings.markHostBootstrapped(config.host)
                    _sshProfile.value = settings.loadSshProfile()
                    _bootstrapState.update {
                        it.copy(
                            phase = BootstrapPhase.SUCCEEDED,
                            errorMessage = null,
                        )
                    }
                    _showConnectScreen.value = false
                } else {
                    _bootstrapState.update {
                        it.copy(
                            phase = BootstrapPhase.ERROR,
                            errorMessage = "VM bootstrap finished, but could not reach ${result.connectionConfig.httpBaseUrl()}: ${lastErr ?: "connection refused"}. Verify Tailscale is active on your phone.",
                        )
                    }
                }
            } catch (_: CancellationException) {
                appendBootstrapLog("Bootstrap cancelled.")
                _bootstrapState.update {
                    it.copy(
                        phase = BootstrapPhase.IDLE,
                        errorMessage = null,
                    )
                }
            } catch (mismatch: HostKeyMismatchException) {
                _bootstrapState.update {
                    it.copy(
                        phase = BootstrapPhase.ERROR,
                        errorMessage = mismatch.message,
                        isHostKeyMismatch = true,
                    )
                }
            } catch (err: Throwable) {
                _bootstrapState.update {
                    it.copy(
                        phase = BootstrapPhase.ERROR,
                        errorMessage = err.message ?: "SSH bootstrap failed",
                        isHostKeyMismatch = false,
                    )
                }
            }
        }
    }

    fun cancelSshBootstrap() {
        bootstrapJob?.cancel()
        bootstrapJob = null
    }

    fun trustChangedHostKeyAndRetry(config: SshBootstrapConfig) {
        settings.clearTrustedHostFingerprint(cleanHost(config.host), config.sshPort)
        startSshBootstrap(config)
    }

    private fun appendBootstrapLog(line: String) {
        debugRecorder.record("ssh", "bootstrap_log", line)
        _bootstrapState.update { cur ->
            val nextLogs = (cur.logs + line).takeLast(MAX_BOOTSTRAP_LOG_LINES)
            cur.copy(logs = nextLogs)
        }
    }

    fun reconnectNow() {
        debugRecorder.record("ui", "reconnect_now", "User tapped Reconnect")
        repo.connect()
    }

    fun selectTab(tabId: String) {
        repo.selectTab(tabId)
    }

    fun createTab(
        agent: String,
        cwd: String?,
        permissionMode: String,
        title: String?,
        model: String? = null,
        systemPrompt: String? = null,
        onCreated: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val res = repo.createTab(
                CreateTabRequest(
                    agent = agent,
                    title = title?.takeIf { it.isNotBlank() },
                    cwd = cwd?.takeIf { it.isNotBlank() },
                    permissionMode = permissionMode,
                    model = model?.trim()?.takeIf { it.isNotEmpty() },
                    systemPrompt = if (agent == "shell") null else systemPrompt?.trim().orEmpty(),
                ),
            )
            if (res.isSuccess) {
                onCreated()
            }
        }
    }

    fun renameTab(tabId: String, newTitle: String) {
        viewModelScope.launch {
            repo.updateTab(tabId, UpdateTabRequest(title = newTitle))
        }
    }

    fun updatePermissionMode(tabId: String, mode: String) {
        viewModelScope.launch {
            repo.updateTab(tabId, UpdateTabRequest(permissionMode = mode))
        }
    }

    fun updateTabModel(
        tabId: String,
        model: String?,
        onUpdated: ((com.example.data.TabRecord) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = repo.updateTabModel(tabId, model)
            res.onSuccess { updated ->
                onUpdated?.invoke(updated)
            }
        }
    }

    fun updateTabSystemPrompt(
        tabId: String,
        systemPrompt: String?,
        onUpdated: ((com.example.data.TabRecord) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = repo.updateTabSystemPrompt(tabId, systemPrompt)
            res.onSuccess { updated ->
                onUpdated?.invoke(updated)
            }
        }
    }

    fun refreshModelsForAgent(agent: String, forceRefresh: Boolean = false) {
        if (agent == "shell") return
        viewModelScope.launch {
            _isRefreshingModels.value = true
            try {
                repo.refreshAgentModels(agent, forceRefresh = forceRefresh)
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    fun deleteTab(
        tabId: String,
        onDeleted: ((com.example.data.TabRecord?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = repo.deleteTab(tabId)
            res.onSuccess { archived ->
                onDeleted?.invoke(archived)
            }
        }
    }

    fun refreshClosedTabs() {
        viewModelScope.launch {
            _isRefreshingClosedTabs.value = true
            try {
                repo.refreshClosedTabs()
            } finally {
                _isRefreshingClosedTabs.value = false
            }
        }
    }

    fun reviveClosedTab(
        tabId: String,
        onRevived: ((com.example.data.TabRecord) -> Unit)? = null,
    ) {
        if (_revivingTabId.value != null) return
        viewModelScope.launch {
            _revivingTabId.value = tabId
            try {
                val res = repo.reviveClosedTab(tabId)
                res.onSuccess { revived ->
                    onRevived?.invoke(revived)
                }
            } finally {
                _revivingTabId.value = null
            }
        }
    }

    fun updateTabStatus(
        tabId: String,
        status: String,
        onUpdated: ((com.example.data.TabRecord) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = repo.updateTabStatus(tabId, status)
            res.onSuccess { updated ->
                onUpdated?.invoke(updated)
            }
        }
    }

    fun closeFinishedTabs(
        onClosed: ((List<com.example.data.TabRecord>) -> Unit)? = null,
    ) {
        val finishedIds = state.value.finishedTabIds.toList()
        if (finishedIds.isEmpty()) return
        viewModelScope.launch {
            val archived = mutableListOf<com.example.data.TabRecord>()
            for (tabId in finishedIds) {
                repo.deleteTab(tabId).onSuccess { closedTab ->
                    if (closedTab != null) {
                        archived += closedTab
                    }
                }
            }
            if (archived.isNotEmpty()) {
                onClosed?.invoke(archived)
            }
        }
    }

    fun sendMessage(text: String, overridePermissionMode: String? = null) {
        val tabId = state.value.activeTab?.id ?: return
        viewModelScope.launch {
            repo.sendMessage(tabId, text, overridePermissionMode)
        }
    }

    fun retryLastUserPromptWithFullAccess() {
        val activeTab = state.value.activeTab ?: return
        val lastUserText = state.value.activeMessages
            .lastOrNull { it.role == "user" }
            ?.text
            ?: return
        viewModelScope.launch {
            repo.sendMessage(activeTab.id, lastUserText, overridePermissionMode = "full-access")
        }
    }

    fun interruptActiveTab(processQueue: Boolean = false, text: String? = null) {
        val tabId = state.value.activeTab?.id ?: return
        viewModelScope.launch {
            repo.interruptTab(tabId, processQueue = processQueue, text = text)
        }
    }

    fun removeQueuedMessage(queuedId: String) {
        val tabId = state.value.activeTab?.id ?: return
        viewModelScope.launch {
            repo.removeQueuedMessage(tabId, queuedId)
        }
    }

    suspend fun loadInitialDirectories(preferredPath: String?): Result<FsListResponse> = repo.loadInitialDirectories(preferredPath)

    suspend fun loadDirectories(path: String?): Result<FsListResponse> = repo.listDirectories(path)

    fun clearBanner() {
        repo.clearBanner()
    }

    companion object {
        private const val MAX_BOOTSTRAP_LOG_LINES = 400
        private const val AUTO_UPLOAD_COOLDOWN_MS = 60_000L
        private const val CONTROL_NOTICE_INTERVAL_MS = 30_000L

        @Volatile
        internal var latestDebugRecorder: DebugRecorder? = null
    }
}
