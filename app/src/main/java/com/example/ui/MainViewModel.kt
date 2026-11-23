package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BootstrapPhase
import com.example.data.BootstrapUiState
import com.example.data.ConnectionConfig
import com.example.data.CreateTabRequest
import com.example.data.FsListResponse
import com.example.data.HerdrApiClient
import com.example.data.HerdrRepository
import com.example.data.HostKeyMismatchException
import com.example.data.SettingsStore
import com.example.data.SshBootstrapClient
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.UpdateTabRequest
import com.example.data.WorkspaceState
import com.example.data.cleanHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val api = HerdrApiClient()
    private val repo = HerdrRepository(api, settings)
    private val sshClient = SshBootstrapClient(settings)

    val state: StateFlow<WorkspaceState> = repo.state

    private val _showConnectScreen = MutableStateFlow(!settings.loadConnection().isValid)
    val showConnectScreen: StateFlow<Boolean> = _showConnectScreen.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _pendingPairingConfig = MutableStateFlow<ConnectionConfig?>(null)
    val pendingPairingConfig: StateFlow<ConnectionConfig?> = _pendingPairingConfig.asStateFlow()

    private val _sshProfile = MutableStateFlow(settings.loadSshProfile())
    val sshProfile: StateFlow<SshProfile> = _sshProfile.asStateFlow()

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState.asStateFlow()

    private var bootstrapJob: Job? = null

    init {
        repo.initializeAutoConnect()
    }

    fun onForeground() {
        repo.ensureConnectedOnForeground()
    }

    fun handleIncomingPairingUri(uri: String) {
        val parsed = ConnectionConfig.parsePairingUri(uri) ?: return
        _pendingPairingConfig.value = parsed
        _showConnectScreen.value = true
        connectToVm(parsed)
    }

    fun clearPendingPairingConfig() {
        _pendingPairingConfig.value = null
    }

    fun openConnectionSettings() {
        _sshProfile.value = settings.loadSshProfile()
        _showConnectScreen.value = true
    }

    fun closeConnectionSettingsIfConfigured() {
        if (state.value.config.isValid) {
            _showConnectScreen.value = false
        }
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
                val result = sshClient.bootstrapWithAgy(
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
                            errorMessage = "Antigravity finished setup, but could not reach ${result.connectionConfig.httpBaseUrl()}: ${lastErr ?: "connection refused"}. Verify Tailscale is active on your phone.",
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
        _bootstrapState.update { cur ->
            val nextLogs = (cur.logs + line).takeLast(MAX_BOOTSTRAP_LOG_LINES)
            cur.copy(logs = nextLogs)
        }
    }

    fun reconnectNow() {
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
        onCreated: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val res = repo.createTab(
                CreateTabRequest(
                    agent = agent,
                    title = title?.takeIf { it.isNotBlank() },
                    cwd = cwd?.takeIf { it.isNotBlank() },
                    permissionMode = permissionMode,
                )
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

    fun deleteTab(tabId: String) {
        viewModelScope.launch {
            repo.deleteTab(tabId)
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

    fun interruptActiveTab() {
        val tabId = state.value.activeTab?.id ?: return
        viewModelScope.launch {
            repo.interruptTab(tabId)
        }
    }

    suspend fun loadDirectories(path: String?): Result<FsListResponse> {
        return repo.listDirectories(path)
    }

    fun clearBanner() {
        repo.clearBanner()
    }

    companion object {
        private const val MAX_BOOTSTRAP_LOG_LINES = 400
    }
}
