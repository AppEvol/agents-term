package com.appevol.agentsterm

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.appevol.agentsterm.data.BootstrapPhase
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DAEMON_BREAKGLASS_ROLLBACK_COMMAND
import com.appevol.agentsterm.data.DaemonCompatibilityInfo
import com.appevol.agentsterm.data.DaemonCompatibilityStatus
import com.appevol.agentsterm.data.DaemonRollbackRequest
import com.appevol.agentsterm.data.DaemonSelfUpdateInfo
import com.appevol.agentsterm.data.DaemonUpdateRequest
import com.appevol.agentsterm.data.DaemonUpdateStatus
import com.appevol.agentsterm.data.DebugDumpUploadState
import com.appevol.agentsterm.data.DebugSharingSettings
import com.appevol.agentsterm.data.EXPECTED_DAEMON_PROTOCOL_VERSION
import com.appevol.agentsterm.data.HerdrApiClient
import com.appevol.agentsterm.data.HerdrRepository
import com.appevol.agentsterm.data.HttpStatusException
import com.appevol.agentsterm.data.ServerInfo
import com.appevol.agentsterm.data.SettingsStore
import com.appevol.agentsterm.data.SshAuthMethod
import com.appevol.agentsterm.data.SshBootstrapClient
import com.appevol.agentsterm.data.SshBootstrapConfig
import com.appevol.agentsterm.data.SshBootstrapResult
import com.appevol.agentsterm.data.SshProfile
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.WsServerEnvelope
import com.appevol.agentsterm.data.buildDaemonRollbackCommand
import com.appevol.agentsterm.data.daemonIdentityKey
import com.appevol.agentsterm.data.decodeHerdrJson
import com.appevol.agentsterm.data.displayBreakglassRollbackCommand
import com.appevol.agentsterm.data.evaluateDaemonCompatibility
import com.appevol.agentsterm.data.isAuthError
import com.appevol.agentsterm.data.isUnknownEndpointError
import com.appevol.agentsterm.data.reduceWorkspaceEvent
import com.appevol.agentsterm.ui.DaemonUpdateActions
import com.appevol.agentsterm.ui.DaemonUpdateController
import com.appevol.agentsterm.ui.DaemonUpdateUiState
import com.appevol.agentsterm.ui.SettingsScreen
import com.appevol.agentsterm.ui.WorkspaceScaffold
import com.appevol.agentsterm.ui.theme.HerdrTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class DaemonSelfUpdateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buildConfigExpectedDaemonBuildHashMatchesPythonScript() {
        val hash = BuildConfig.EXPECTED_DAEMON_BUILD_HASH
        assertTrue("Expected 40-char lowercase hex hash, got '$hash'", Regex("^[0-9a-f]{40}$").matches(hash))

        val script = listOf(File("scripts/daemon-build-hash.py"), File("../scripts/daemon-build-hash.py"))
            .firstOrNull { it.isFile }
        requireNotNull(script) { "Could not locate scripts/daemon-build-hash.py" }
        val proc = ProcessBuilder("python3", script.canonicalPath).redirectErrorStream(true).start()
        val scriptOutput = proc.inputStream.bufferedReader().readText().trim()
        assertEquals("scripts/daemon-build-hash.py exited non-zero: $scriptOutput", 0, proc.waitFor())
        assertEquals(scriptOutput, hash)
    }

    @Test
    fun evaluateDaemonCompatibilityDetectsPinnedBuildHashMismatchAndMetadata() {
        val expectedHash = "a".repeat(40)
        val otherHash = "b".repeat(40)

        val pinnedMismatch = evaluateDaemonCompatibility(
            serverInfo = ServerInfo(
                version = "0.3.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
                buildHash = otherHash,
                selfUpdate = DaemonSelfUpdateInfo(
                    supported = true,
                    rollbackAvailable = true,
                    previousBuildHash = "c".repeat(40),
                    lastError = "watchdog timeout",
                ),
            ),
            expectedBuildHash = expectedHash,
            expectedReleaseTag = "v0.3.350-1234567",
        )
        assertEquals(DaemonCompatibilityStatus.BUILD_MISMATCH, pinnedMismatch.status)
        assertTrue(pinnedMismatch.isOutdated && pinnedMismatch.isBuildMismatch && !pinnedMismatch.isProtocolOutdated)
        assertTrue(pinnedMismatch.supportsSelfUpdate && pinnedMismatch.rollbackAvailable)
        assertEquals("ccccccc", pinnedMismatch.shortPreviousBuildHash)
        assertEquals("watchdog timeout", pinnedMismatch.lastAutoRollbackError)
        assertEquals("v0.3.350-1234567", pinnedMismatch.targetUpdateVersion)
        assertEquals(expectedHash, pinnedMismatch.targetExpectedHash)

        val pinnedMatch = evaluateDaemonCompatibility(
            serverInfo = ServerInfo(
                version = "0.3.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
                buildHash = expectedHash,
            ),
            expectedBuildHash = expectedHash,
            expectedReleaseTag = "v0.3.350-1234567",
        )
        assertEquals(DaemonCompatibilityStatus.COMPATIBLE, pinnedMatch.status)
        assertFalse(pinnedMatch.isOutdated)
    }

    @Test
    fun evaluateDaemonCompatibilitySkipsUnpinnedLocalAndNewerProtocol() {
        val expectedHash = "a".repeat(40)
        val otherHash = "b".repeat(40)

        val unpinnedLocal = evaluateDaemonCompatibility(
            serverInfo = ServerInfo(
                version = "0.3.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
                buildHash = otherHash,
                selfUpdate = DaemonSelfUpdateInfo(supported = true),
            ),
            expectedBuildHash = expectedHash,
            expectedReleaseTag = "",
        )
        assertEquals(DaemonCompatibilityStatus.COMPATIBLE, unpinnedLocal.status)
        assertEquals("latest", unpinnedLocal.targetUpdateVersion)
        assertNull(unpinnedLocal.targetExpectedHash)

        val newerDaemon = evaluateDaemonCompatibility(
            serverInfo = ServerInfo(
                version = "0.4.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION + 1,
                buildHash = otherHash,
            ),
            expectedBuildHash = expectedHash,
            expectedReleaseTag = "v0.3.350-1234567",
        )
        assertEquals(DaemonCompatibilityStatus.COMPATIBLE, newerDaemon.status)
        assertFalse(newerDaemon.isOutdated)

        val infoA = ServerInfo(version = "0.3.0", protocolVersion = 1, commit = "same123", buildHash = expectedHash)
        assertNotEquals(
            infoA.daemonIdentityKey("vm", 7501),
            infoA.copy(buildHash = otherHash).daemonIdentityKey("vm", 7501),
        )
    }

    @Test
    fun wsReducerTracksDaemonUpdateStatusAndClearsRestartingOnReconnectSnapshot() {
        val wsJson = """
            {
              "type": "daemon_update_status",
              "update": {
                "state": "pending_restart",
                "targetVersion": "v0.3.355-abcdef1",
                "targetBuildHash": "${"d".repeat(40)}",
                "currentBuildHash": "${"a".repeat(40)}",
                "activeTabIds": ["tab_1", "tab_2"],
                "activeTurnsCount": 2,
                "whenBusy": "defer",
                "detail": "Waiting for active turns",
                "rollbackAvailable": true,
                "previousBuildHash": "${"0".repeat(40)}",
                "updatedAt": 1700000000000
              },
              "ts": 1700000000000
            }
        """.trimIndent()
        val env = decodeHerdrJson<WsServerEnvelope>(wsJson)
        val pending = reduceWorkspaceEvent(WorkspaceState(), env)
        assertTrue(pending.daemonUpdateStatus?.isPendingRestart == true)
        assertEquals(2, pending.daemonUpdateStatus?.effectiveActiveTurnCount)

        val restarting = reduceWorkspaceEvent(
            pending,
            WsServerEnvelope(type = "daemon_update_status", update = DaemonUpdateStatus(state = "restarting")),
        )
        assertTrue(restarting.daemonUpdateStatus?.isRestarting == true)

        val reconnected = reduceWorkspaceEvent(
            restarting,
            WsServerEnvelope(
                type = "snapshot",
                server = ServerInfo(
                    version = "0.3.0",
                    protocolVersion = 1,
                    buildHash = "d".repeat(40),
                    selfUpdate = DaemonSelfUpdateInfo(supported = true, rollbackAvailable = true),
                ),
            ),
        )
        assertNull(reconnected.daemonUpdateStatus)
        assertEquals("d".repeat(40), reconnected.serverInfo?.buildHash)
    }

    @Test
    fun apiClientExercisesDaemonUpdateAndRollbackEndpoints() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"idle"}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"state":"downloading"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"idle"}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"state":"restarting"}"""))
        server.start()
        try {
            val api = HerdrApiClient()
            val cfg = ConnectionConfig(host = server.hostName, port = server.port, token = "tok-123")

            assertEquals("idle", api.getDaemonUpdateStatus(cfg).state)
            assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))

            val reqBody = DaemonUpdateRequest(version = "v0.3.360-abcdef1", expectedHash = "2".repeat(40), whenBusy = "defer")
            assertEquals("downloading", api.startDaemonUpdate(cfg, reqBody).state)
            val startReq = server.takeRequest()
            assertEquals("POST", startReq.method)
            assertEquals("/api/daemon/update", startReq.path)
            assertTrue(startReq.body.readUtf8().contains("v0.3.360-abcdef1"))

            assertEquals("idle", api.cancelDaemonUpdate(cfg).state)
            assertEquals("DELETE", server.takeRequest().method)

            assertEquals("restarting", api.rollbackDaemon(cfg, DaemonRollbackRequest(whenBusy = "force")).state)
            val rbReq = server.takeRequest()
            assertEquals("POST", rbReq.method)
            assertEquals("/api/daemon/rollback", rbReq.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun apiClientPreservesNonAuthConflictErrorsFromUpdateEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Self-update is disabled"}"""))
        server.start()
        try {
            val api = HerdrApiClient()
            val cfg = ConnectionConfig(host = server.hostName, port = server.port, token = "tok")
            try {
                api.startDaemonUpdate(cfg, DaemonUpdateRequest(version = "latest"))
                fail("Expected 409 HttpStatusException")
            } catch (err: HttpStatusException) {
                assertEquals(409, err.statusCode)
                assertFalse(err.isAuthError)
                assertFalse(err.isUnknownEndpointError)
                assertEquals("Self-update is disabled", err.message)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sshRollbackCommandBuildersFormatAndValidatePort() {
        assertEquals(DAEMON_BREAKGLASS_ROLLBACK_COMMAND, displayBreakglassRollbackCommand(7501))
        assertEquals("$DAEMON_BREAKGLASS_ROLLBACK_COMMAND --port 7599", displayBreakglassRollbackCommand(7599))
        val remote = buildDaemonRollbackCommand(7599)
        assertTrue(remote.contains("agentsterm-daemon.prev"))
        assertTrue(remote.contains("rollback --port 7599"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun controllerHandlesDeferCancelForceRestartTimeoutAndSshRollback() = runTest {
        val fakeApi = FakeUpdateApiClient()
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        settings.saveConnection(ConnectionConfig(host = "127.0.0.1", port = 7501, token = "saved-token"))
        val repo = HerdrRepository(api = fakeApi, settings = settings, ioDispatcher = Dispatchers.Unconfined)
        val fakeSsh = FakeRollbackSshClient(settings)
        val controllerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val controller = DaemonUpdateController(
                api = fakeApi,
                repo = repo,
                sshClient = fakeSsh,
                scope = controllerScope,
                reconnectGraceTimeoutMs = 1_000L,
            )
            val compat = sampleMismatchCompat()

            controller.startUpdate(compat)
            advanceUntilIdle()
            assertEquals("defer", fakeApi.startedRequests.last().whenBusy)
            assertTrue(repo.state.value.daemonUpdateStatus?.isPendingRestart == true)

            controller.cancelPendingUpdate()
            advanceUntilIdle()
            assertEquals(1, fakeApi.cancelCalls)
            assertNull(repo.state.value.daemonUpdateStatus)

            controller.restartNow(DaemonUpdateStatus(state = "pending_restart", targetVersion = "v0.3.360-abcdef1"), compat)
            advanceUntilIdle()
            assertEquals("force", fakeApi.startedRequests.last().whenBusy)
            assertTrue(controller.uiState.value.awaitingReconnectAfterRestart)

            advanceTimeBy(1_500L)
            advanceUntilIdle()
            assertTrue(controller.uiState.value.reconnectTimedOutAfterUpdate)

            controller.rollbackDaemon()
            advanceUntilIdle()
            assertEquals(1, fakeApi.rollbackCalls)

            controller.startSshBreakglassRollback(
                SshBootstrapConfig(host = "127.0.0.1", username = "ned", authMethod = SshAuthMethod.TAILSCALE_SSH),
            )
            advanceUntilIdle()
            assertTrue(fakeSsh.rollbackRan)
            assertTrue(controller.uiState.value.sshRollback.logs.any { it.contains("Restored previous daemon") })
        } finally {
            controllerScope.cancel()
            repo.disconnect()
        }
    }

    @Test
    fun workspaceUiShowsPendingRestartConfirmAutoRollbackAndBreakglass() {
        var forceRestartCalls = 0
        var cancelCalls = 0
        var dismissAutoRollbackKey: String? = null
        var sshRollbackConfig: SshBootstrapConfig? = null

        val compat = sampleMismatchCompat(lastError = "New binary crashed during startup")
        var wsState by mutableStateOf(
            WorkspaceState(
                config = ConnectionConfig(host = "dev-vm", port = 7501, token = "tok"),
                connectionState = ConnectionState.CONNECTED,
                serverInfo = sampleMismatchServer(lastError = "New binary crashed during startup"),
                daemonUpdateStatus = DaemonUpdateStatus(state = "pending_restart", activeTabIds = listOf("t1", "t2")),
            ),
        )
        var updateUi by mutableStateOf(DaemonUpdateUiState())

        composeRule.setContent {
            HerdrTheme {
                TestUpdateWorkspaceScaffold(
                    state = wsState,
                    compat = compat,
                    updateUi = updateUi,
                    actions = DaemonUpdateActions(
                        onRestartNow = { forceRestartCalls++ },
                        onCancelPendingUpdate = { cancelCalls++ },
                        onDismissAutoRollbackError = { dismissAutoRollbackKey = it },
                        onStartSshBreakglassRollback = { sshRollbackConfig = it },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("daemon_update_status_banner").assertIsDisplayed()
        composeRule.onNodeWithText("2 active tabs", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("banner_restart_now_button").performClick()
        composeRule.onNodeWithTag("confirm_force_restart_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_force_restart_button").performClick()
        assertEquals(1, forceRestartCalls)

        composeRule.onNodeWithTag("banner_cancel_update_button").performClick()
        assertEquals(1, cancelCalls)

        wsState = wsState.copy(daemonUpdateStatus = null)
        composeRule.onNodeWithTag("daemon_auto_rollback_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_dismiss_auto_rollback_button").performClick()
        assertTrue(dismissAutoRollbackKey?.contains("New binary crashed during startup") == true)

        wsState = wsState.copy(
            connectionState = ConnectionState.ERROR,
            connectionError = "Connection refused",
            daemonUpdateStatus = DaemonUpdateStatus(state = "restarting"),
        )
        updateUi = updateUi.copy(awaitingReconnectAfterRestart = true, reconnectTimedOutAfterUpdate = true)
        composeRule.onNodeWithTag("daemon_breakglass_recovery_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("daemon_update_status_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("breakglass_revert_ssh_button").performClick()
        composeRule.onNodeWithTag("breakglass_ssh_dialog").assertIsDisplayed()
        assertEquals("ned", sshRollbackConfig?.username)
    }

    // Wider screen avoids a known Robolectric + Material3 AlertDialog/OutlinedTextField
    // measure oscillation at 340dp..480dp portrait widths.
    @Test
    @Config(sdk = [36], qualifiers = "w600dp-h915dp")
    fun breakglassDialogKeepsUsernameFieldVisibleWhileTypingWhenProfileHasNoUsername() {
        var submittedConfig: SshBootstrapConfig? = null
        val wsState = WorkspaceState(
            config = ConnectionConfig(host = "dev-vm", port = 7501, token = "tok"),
            connectionState = ConnectionState.ERROR,
            connectionError = "Connection refused",
            daemonUpdateStatus = DaemonUpdateStatus(state = "restarting"),
        )
        val updateUi = DaemonUpdateUiState(
            awaitingReconnectAfterRestart = true,
            reconnectTimedOutAfterUpdate = true,
        )

        composeRule.setContent {
            HerdrTheme {
                TestUpdateWorkspaceScaffold(
                    state = wsState,
                    compat = sampleMismatchCompat(),
                    updateUi = updateUi,
                    actions = DaemonUpdateActions(
                        onStartSshBreakglassRollback = { submittedConfig = it },
                    ),
                    sshProfile = SshProfile(host = "dev-vm", username = "", authMethod = SshAuthMethod.TAILSCALE_SSH),
                )
            }
        }

        composeRule.onNodeWithTag("daemon_update_status_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("breakglass_revert_ssh_button").performClick()
        composeRule.onNodeWithTag("breakglass_ssh_username_input").assertIsDisplayed().performTextReplacement("a")
        composeRule.onNodeWithTag("breakglass_ssh_username_input").assertIsDisplayed().performTextReplacement("alice")
        composeRule.onNodeWithTag("breakglass_ssh_username_input").assertIsDisplayed()
        composeRule.onNodeWithTag("breakglass_run_ssh_rollback_button").performClick()
        assertEquals("alice", submittedConfig?.username)
    }

    @Test
    fun settingsScreenExposesManualUpdateAndConfirmedRollbackWithActiveTabWarning() {
        var manualUpdateClicks = 0
        var rollbackCalls = 0
        val server = ServerInfo(
            version = "0.3.0",
            protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
            buildHash = BuildConfig.EXPECTED_DAEMON_BUILD_HASH,
            hostname = "dev-vm",
            selfUpdate = DaemonSelfUpdateInfo(
                supported = true,
                rollbackAvailable = true,
                previousBuildHash = "9".repeat(40),
                lastError = "Auto-rolled back after failed health check",
            ),
        )
        val workspaceState = WorkspaceState(
            config = ConnectionConfig(host = "dev-vm", port = 7501, token = "tok"),
            connectionState = ConnectionState.CONNECTED,
            serverInfo = server,
            tabs = listOf(TabRecord(id = "tab_1", title = "Working", agent = "claude", cwd = "/tmp", status = "working")),
        )

        composeRule.setContent {
            HerdrTheme {
                SettingsScreen(
                    workspaceState = workspaceState,
                    debugSettings = DebugSharingSettings(),
                    uploadState = DebugDumpUploadState(),
                    eventCount = 0,
                    hasLastCrash = false,
                    recentEventsProvider = { emptyList() },
                    onBack = {},
                    onOpenVmConnection = {},
                    daemonUpdateActions = DaemonUpdateActions(
                        onStartSelfUpdate = { manualUpdateClicks++ },
                        onRollbackDaemon = { rollbackCalls++ },
                    ),
                    onUpdateDebugSettings = {},
                    onUploadDumpToVm = { _, _ -> },
                    onExportOfflineZip = { _, _ -> Result.failure(IllegalStateException("unused")) },
                    onClearDebugBuffer = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_auto_rollback_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_manual_update_daemon_button").performScrollTo().performClick()
        assertEquals(1, manualUpdateClicks)

        composeRule.onNodeWithTag("settings_revert_daemon_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_rollback_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Restore the previously saved daemon binary (9999999)", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("1 active tab", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_rollback_button").performClick()
        assertEquals(1, rollbackCalls)
    }

    private fun sampleMismatchServer(lastError: String? = null): ServerInfo = ServerInfo(
        version = "0.3.0",
        protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
        buildHash = "1".repeat(40),
        hostname = "dev-vm",
        selfUpdate = DaemonSelfUpdateInfo(
            supported = true,
            rollbackAvailable = true,
            previousBuildHash = "0".repeat(40),
            lastError = lastError,
        ),
    )

    private fun sampleMismatchCompat(lastError: String? = null): DaemonCompatibilityInfo = evaluateDaemonCompatibility(
        serverInfo = sampleMismatchServer(lastError),
        expectedBuildHash = "2".repeat(40),
        expectedReleaseTag = "v0.3.360-abcdef1",
    )

    private class FakeUpdateApiClient : HerdrApiClient() {
        val startedRequests = mutableListOf<DaemonUpdateRequest>()
        var cancelCalls = 0
        var rollbackCalls = 0

        override suspend fun startDaemonUpdate(
            config: ConnectionConfig,
            body: DaemonUpdateRequest,
        ): DaemonUpdateStatus {
            startedRequests += body
            return if (body.whenBusy == "force") {
                DaemonUpdateStatus(state = "restarting", targetVersion = body.version)
            } else {
                DaemonUpdateStatus(
                    state = "pending_restart",
                    targetVersion = body.version,
                    targetBuildHash = body.expectedHash,
                    activeTabIds = listOf("tab_1"),
                )
            }
        }

        override suspend fun cancelDaemonUpdate(config: ConnectionConfig): DaemonUpdateStatus {
            cancelCalls++
            return DaemonUpdateStatus(state = "idle")
        }

        override suspend fun rollbackDaemon(
            config: ConnectionConfig,
            body: DaemonRollbackRequest,
        ): DaemonUpdateStatus {
            rollbackCalls++
            return DaemonUpdateStatus(state = "restarting")
        }
    }

    private class FakeRollbackSshClient(settings: SettingsStore) : SshBootstrapClient(settings) {
        var rollbackRan = false

        override suspend fun rollbackVmDaemon(
            config: SshBootstrapConfig,
            onPhase: (BootstrapPhase) -> Unit,
            onHostFingerprint: (String) -> Unit,
            onLog: (String) -> Unit,
        ): SshBootstrapResult {
            rollbackRan = true
            onLog("Restored previous daemon")
            return SshBootstrapResult(
                connectionConfig = ConnectionConfig(host = config.host, port = config.daemonPort, token = ""),
                rawPairingUri = "herdr://connect?host=${config.host}&port=${config.daemonPort}",
                hostFingerprint = "SHA256:test",
            )
        }
    }

    @Composable
    private fun TestUpdateWorkspaceScaffold(
        state: WorkspaceState,
        compat: DaemonCompatibilityInfo,
        updateUi: DaemonUpdateUiState,
        actions: DaemonUpdateActions,
        sshProfile: SshProfile = SshProfile(
            host = "dev-vm",
            username = "ned",
            authMethod = SshAuthMethod.TAILSCALE_SSH,
            daemonPort = 7501,
        ),
    ) {
        WorkspaceScaffold(
            state = state,
            isExpanded = false,
            debugSharingEnabled = false,
            snackbarHostState = remember { SnackbarHostState() },
            showOverflowMenu = false,
            onToggleOverflow = {},
            onOpenDrawer = {},
            onNewTab = {},
            onQuickDebugDump = {},
            onSelectTab = {},
            onRenameTab = {},
            onChangePermission = {},
            onChangeSystemPrompt = {},
            onChangeModel = {},
            onToggleTaskDone = { _, _ -> },
            defaultSystemPrompt = "",
            onDeleteTab = {},
            onOpenSettings = {},
            onUpdateDaemon = {},
            sshProfile = sshProfile,
            daemonUpdateUiState = updateUi,
            daemonUpdateActions = actions,
            daemonCompatibilityOverride = compat,
            onOpenAppSettings = {},
            onReconnect = {},
            onSendMessage = {},
            onInterrupt = {},
            onForceInterrupt = {},
            onRemoveQueuedMessage = {},
            onRetryFullAccess = {},
        )
    }
}
