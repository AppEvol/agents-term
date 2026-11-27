package com.example.ui

import com.example.data.ConnectionConfig
import com.example.data.ConnectionState
import com.example.data.DaemonCompatibilityInfo
import com.example.data.DaemonRollbackRequest
import com.example.data.DaemonUpdateRequest
import com.example.data.DaemonUpdateStatus
import com.example.data.DebugRecorder
import com.example.data.HerdrApiClient
import com.example.data.HerdrRepository
import com.example.data.SshBootstrapClient
import com.example.data.SshBootstrapConfig
import com.example.data.SshBootstrapResult
import com.example.data.evaluateDaemonCompatibility
import com.example.data.formatDaemonRequestError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SshRollbackUiState(
    val isRunning: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val succeeded: Boolean = false,
)

data class DaemonUpdateUiState(
    val isSubmitting: Boolean = false,
    val localError: String? = null,
    val awaitingReconnectAfterRestart: Boolean = false,
    val reconnectTimedOutAfterUpdate: Boolean = false,
    val dismissedAutoRollbackErrorKey: String? = null,
    val sshRollback: SshRollbackUiState = SshRollbackUiState(),
)

data class DaemonUpdateActions(
    val onStartSelfUpdate: () -> Unit = {},
    val onRestartNow: () -> Unit = {},
    val onCancelPendingUpdate: () -> Unit = {},
    val onRollbackDaemon: () -> Unit = {},
    val onDismissUpdateError: () -> Unit = {},
    val onDismissAutoRollbackError: (String) -> Unit = {},
    val onOpenSshSetup: () -> Unit = {},
    val onStartSshBreakglassRollback: (SshBootstrapConfig) -> Unit = {},
    val onClearSshRollbackState: () -> Unit = {},
)

/**
 * Coordinates wait-for-tap in-place VM daemon updates, active-turn deferred restarts, in-app
 * rollback, post-update reconnect-failure detection, and breakglass SSH rollback.
 */
class DaemonUpdateController(
    private val api: HerdrApiClient,
    private val repo: HerdrRepository,
    private val sshClient: SshBootstrapClient?,
    private val debugRecorder: DebugRecorder? = null,
    private val scope: CoroutineScope,
    private val reconnectGraceTimeoutMs: Long = DEFAULT_RECONNECT_GRACE_TIMEOUT_MS,
) {
    private val _uiState = MutableStateFlow(DaemonUpdateUiState())
    val uiState: StateFlow<DaemonUpdateUiState> = _uiState.asStateFlow()

    private var reconnectWatchdogJob: Job? = null
    private var sshRollbackJob: Job? = null
    private var lastSyncedServerIdentity: String? = null

    init {
        scope.launch {
            repo.state.collect { ws ->
                onWorkspaceStateChanged(
                    connectionState = ws.connectionState,
                    hasServerInfo = ws.serverInfo != null,
                    updateStatus = ws.daemonUpdateStatus,
                )
                maybeSyncDaemonUpdateStatus(ws.connectionState, ws.config, ws.serverInfo)
            }
        }
    }

    private fun maybeSyncDaemonUpdateStatus(
        connectionState: ConnectionState,
        config: ConnectionConfig,
        serverInfo: com.example.data.ServerInfo?,
    ) {
        if (connectionState != ConnectionState.CONNECTED || serverInfo?.supportsSelfUpdate != true || !config.isValid) {
            if (connectionState != ConnectionState.CONNECTED) {
                lastSyncedServerIdentity = null
            }
            return
        }
        val key = "${config.host}:${config.port}|${serverInfo.buildHash.orEmpty()}|${serverInfo.protocolVersion ?: 0}"
        if (key == lastSyncedServerIdentity) return
        lastSyncedServerIdentity = key
        scope.launch {
            runCatching { api.getDaemonUpdateStatus(config) }
                .onSuccess { repo.updateDaemonUpdateStatus(it) }
        }
    }

    fun openOrStartUpdate(onFallbackSsh: () -> Unit) {
        val compat = evaluateDaemonCompatibility(repo.state.value.serverInfo)
        if (compat.supportsSelfUpdate) {
            startUpdate(compat)
        } else {
            onFallbackSsh()
        }
    }

    internal fun onWorkspaceStateChanged(
        connectionState: ConnectionState,
        hasServerInfo: Boolean,
        updateStatus: DaemonUpdateStatus?,
    ) {
        if (updateStatus?.isRestarting == true) {
            markAwaitingReconnect()
            return
        }
        val cur = _uiState.value
        if (cur.awaitingReconnectAfterRestart &&
            connectionState == ConnectionState.CONNECTED &&
            hasServerInfo
        ) {
            clearReconnectWatchdog()
        }
    }

    fun startUpdateForCurrentDaemon(whenBusy: String = "defer") {
        val compat = evaluateDaemonCompatibility(repo.state.value.serverInfo)
        startUpdate(compat, whenBusy = whenBusy)
    }

    fun forceRestartForCurrentDaemon() {
        val ws = repo.state.value
        val compat = evaluateDaemonCompatibility(ws.serverInfo)
        restartNow(ws.daemonUpdateStatus, compat)
    }

    fun startUpdate(
        compat: DaemonCompatibilityInfo,
        whenBusy: String = "defer",
    ) {
        startUpdate(
            version = compat.targetUpdateVersion,
            expectedHash = compat.targetExpectedHash,
            whenBusy = whenBusy,
        )
    }

    fun startUpdate(
        version: String,
        expectedHash: String? = null,
        whenBusy: String = "defer",
    ) {
        if (_uiState.value.isSubmitting) return
        val cfg = repo.state.value.config
        if (!cfg.isValid) {
            _uiState.update { it.copy(localError = "Not connected to a VM daemon") }
            return
        }
        scope.launch {
            _uiState.update { it.copy(isSubmitting = true, localError = null) }
            debugRecorder?.record(
                "ui",
                "daemon_update_start",
                "Requesting daemon update version=$version whenBusy=$whenBusy",
            )
            val result = runCatching {
                api.startDaemonUpdate(
                    cfg,
                    DaemonUpdateRequest(
                        version = version,
                        expectedHash = expectedHash?.trim()?.takeIf { it.isNotEmpty() },
                        whenBusy = whenBusy,
                    ),
                )
            }
            result.onSuccess { status ->
                repo.updateDaemonUpdateStatus(status)
                _uiState.update { it.copy(isSubmitting = false, localError = null) }
                if (status.isRestarting) {
                    markAwaitingReconnect()
                } else if (status.isUpToDate) {
                    repo.showBanner("VM daemon is already up to date.")
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        localError = formatDaemonRequestError(err, "Failed to start VM daemon update"),
                    )
                }
            }
        }
    }

    fun restartNow(
        currentStatus: DaemonUpdateStatus?,
        compat: DaemonCompatibilityInfo,
    ) {
        val version = currentStatus?.targetVersion?.takeIf { it.isNotBlank() } ?: compat.targetUpdateVersion
        val expectedHash = currentStatus?.targetBuildHash?.takeIf { it.isNotBlank() } ?: compat.targetExpectedHash
        startUpdate(version = version, expectedHash = expectedHash, whenBusy = "force")
    }

    fun cancelPendingUpdate() {
        if (_uiState.value.isSubmitting) return
        val cfg = repo.state.value.config
        if (!cfg.isValid) return
        scope.launch {
            _uiState.update { it.copy(isSubmitting = true, localError = null) }
            debugRecorder?.record("ui", "daemon_update_cancel", "Cancelling pending daemon update")
            runCatching { api.cancelDaemonUpdate(cfg) }
                .onSuccess { status ->
                    repo.updateDaemonUpdateStatus(status)
                    _uiState.update { it.copy(isSubmitting = false, localError = null) }
                }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            localError = formatDaemonRequestError(err, "Failed to cancel pending daemon update"),
                        )
                    }
                }
        }
    }

    fun rollbackDaemon(whenBusy: String = "force") {
        if (_uiState.value.isSubmitting) return
        val cfg = repo.state.value.config
        if (!cfg.isValid) return
        scope.launch {
            _uiState.update { it.copy(isSubmitting = true, localError = null) }
            debugRecorder?.record("ui", "daemon_rollback_start", "Requesting daemon rollback whenBusy=$whenBusy")
            runCatching { api.rollbackDaemon(cfg, DaemonRollbackRequest(whenBusy = whenBusy)) }
                .onSuccess { status ->
                    repo.updateDaemonUpdateStatus(status)
                    _uiState.update { it.copy(isSubmitting = false, localError = null) }
                    if (status.isRestarting) {
                        markAwaitingReconnect()
                    }
                }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            localError = formatDaemonRequestError(err, "Failed to revert VM daemon"),
                        )
                    }
                }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(localError = null) }
        if (repo.state.value.daemonUpdateStatus?.isDismissibleTerminal == true) {
            repo.updateDaemonUpdateStatus(null)
        }
    }

    fun dismissAutoRollbackNotice(errorKey: String) {
        _uiState.update { it.copy(dismissedAutoRollbackErrorKey = errorKey) }
    }

    fun clearSshRollbackState() {
        sshRollbackJob?.cancel()
        sshRollbackJob = null
        _uiState.update { it.copy(sshRollback = SshRollbackUiState()) }
    }

    fun startSshBreakglassRollback(config: SshBootstrapConfig) {
        val client = sshClient ?: return
        sshRollbackJob?.cancel()
        _uiState.update { it.copy(sshRollback = SshRollbackUiState(isRunning = true)) }
        sshRollbackJob = scope.launch {
            val result = runCatching {
                client.rollbackVmDaemon(
                    config = config,
                    onPhase = {},
                    onHostFingerprint = {},
                    onLog = { line -> appendSshRollbackLog(line) },
                )
            }
            result.onSuccess { sshResult ->
                finishSshRollback(sshResult)
            }.onFailure { err ->
                handleSshRollbackFailure(err)
            }
        }
    }

    private suspend fun finishSshRollback(result: SshBootstrapResult) {
        val existingToken = repo.state.value.config.token
        val targetConfig = if (result.connectionConfig.token.isBlank() && existingToken.isNotBlank()) {
            result.connectionConfig.copy(token = existingToken)
        } else {
            result.connectionConfig
        }
        val connected = tryReconnectAfterRollback(targetConfig)
        if (connected) {
            clearReconnectWatchdog()
            _uiState.update {
                it.copy(sshRollback = it.sshRollback.copy(isRunning = false, errorMessage = null, succeeded = true))
            }
            repo.showBanner("Reverted VM daemon via SSH and reconnected.")
        } else {
            _uiState.update {
                it.copy(
                    sshRollback = it.sshRollback.copy(
                        isRunning = false,
                        errorMessage = "SSH rollback finished, but could not reconnect to " +
                            "${targetConfig.host}:${targetConfig.port}. Tap Retry once the daemon is up.",
                    ),
                )
            }
        }
    }

    private fun handleSshRollbackFailure(err: Throwable) {
        if (err is CancellationException) {
            appendSshRollbackLog("SSH rollback cancelled.")
            _uiState.update { it.copy(sshRollback = it.sshRollback.copy(isRunning = false)) }
            return
        }
        _uiState.update {
            it.copy(
                sshRollback = it.sshRollback.copy(
                    isRunning = false,
                    errorMessage = err.message ?: "SSH rollback failed",
                ),
            )
        }
    }

    private suspend fun tryReconnectAfterRollback(targetConfig: ConnectionConfig): Boolean {
        for (attempt in 1..3) {
            if (repo.testAndConnect(targetConfig).isSuccess) {
                return true
            }
            if (attempt < 3) {
                appendSshRollbackLog("Waiting for reverted daemon (attempt $attempt/3)…")
                delay(RECONNECT_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun appendSshRollbackLog(line: String) {
        debugRecorder?.record("ssh", "rollback_log", line)
        _uiState.update { cur ->
            val nextLogs = (cur.sshRollback.logs + line).takeLast(MAX_ROLLBACK_LOG_LINES)
            cur.copy(sshRollback = cur.sshRollback.copy(logs = nextLogs))
        }
    }

    internal fun markAwaitingReconnect() {
        val alreadyWatching = _uiState.value.awaitingReconnectAfterRestart && reconnectWatchdogJob?.isActive == true
        _uiState.update { it.copy(awaitingReconnectAfterRestart = true) }
        if (alreadyWatching) return
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = scope.launch {
            delay(reconnectGraceTimeoutMs)
            val ws = repo.state.value
            val stillDown = ws.connectionState != ConnectionState.CONNECTED ||
                ws.daemonUpdateStatus?.isRestarting == true
            if (stillDown) {
                debugRecorder?.record(
                    "error",
                    "daemon_update_reconnect_timeout",
                    "VM daemon did not reconnect within ${reconnectGraceTimeoutMs}ms after restart",
                )
                _uiState.update { it.copy(reconnectTimedOutAfterUpdate = true) }
            }
        }
    }

    private fun clearReconnectWatchdog() {
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = null
        _uiState.update {
            it.copy(
                awaitingReconnectAfterRestart = false,
                reconnectTimedOutAfterUpdate = false,
            )
        }
    }

    companion object {
        const val DEFAULT_RECONNECT_GRACE_TIMEOUT_MS = 22_000L
        private const val RECONNECT_RETRY_DELAY_MS = 1_500L
        private const val MAX_ROLLBACK_LOG_LINES = 200
    }
}
