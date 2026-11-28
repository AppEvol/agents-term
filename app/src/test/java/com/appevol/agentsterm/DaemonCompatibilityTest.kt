package com.appevol.agentsterm

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.appevol.agentsterm.data.AgentModelOption
import com.appevol.agentsterm.data.AgentUsageUiState
import com.appevol.agentsterm.data.AgentsTermApiClient
import com.appevol.agentsterm.data.AgentsTermRepository
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DaemonCompatibilityStatus
import com.appevol.agentsterm.data.DebugDumpUploadState
import com.appevol.agentsterm.data.DebugSharingSettings
import com.appevol.agentsterm.data.EXPECTED_DAEMON_PROTOCOL_VERSION
import com.appevol.agentsterm.data.HTTP_NOT_FOUND
import com.appevol.agentsterm.data.HttpStatusException
import com.appevol.agentsterm.data.OUTDATED_DAEMON_ENDPOINT_ERROR
import com.appevol.agentsterm.data.ServerInfo
import com.appevol.agentsterm.data.SettingsStore
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.decodeAgentsTermJson
import com.appevol.agentsterm.data.displayVersionLabel
import com.appevol.agentsterm.data.evaluateDaemonCompatibility
import com.appevol.agentsterm.data.formatDaemonRequestError
import com.appevol.agentsterm.data.isUnknownEndpointError
import com.appevol.agentsterm.data.isUnknownEndpointResponse
import com.appevol.agentsterm.ui.AgentUsageController
import com.appevol.agentsterm.ui.ModelPickerSheetContent
import com.appevol.agentsterm.ui.SettingsScreen
import com.appevol.agentsterm.ui.WorkspaceScaffold
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class DaemonCompatibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun serverInfoDeserializesNewAndLegacyPayloads() {
        val currentJson =
            """{"version":"0.3.0","protocolVersion":1,"commit":"ed7da85123456",""" +
                """"buildHash":"abcdef0123456","runtime":"rust","hostname":"dev-vm"}"""
        val current = decodeAgentsTermJson<ServerInfo>(currentJson)
        assertEquals("0.3.0", current.version)
        assertEquals(1, current.protocolVersion)
        assertEquals("ed7da85123456", current.commit)
        assertEquals("abcdef0123456", current.buildHash)
        assertEquals("rust", current.runtime)
        assertEquals("v0.3.0 (ed7da85) · proto 1", current.displayVersionLabel())

        val legacy = decodeAgentsTermJson<ServerInfo>(
            """{"version":"0.3.0","commit":"9988776655","hostname":"old-vm"}""",
        )
        assertNull(legacy.protocolVersion)
        assertEquals("v0.3.0 (9988776)", legacy.displayVersionLabel())
    }

    @Test
    fun evaluateDaemonCompatibilityDetectsOlderWithoutFalseFlaggingNewer() {
        val unknown = evaluateDaemonCompatibility(null, appVersionName = "0.3.262-abcdef1")
        assertEquals(DaemonCompatibilityStatus.UNKNOWN, unknown.status)
        assertFalse(unknown.isOutdated)

        val legacy = evaluateDaemonCompatibility(
            ServerInfo(version = "0.3.0", commit = "1122334455"),
            appVersionName = "0.3.262-abcdef1",
            expectedProtocolVersion = 1,
        )
        assertEquals(DaemonCompatibilityStatus.OUTDATED, legacy.status)
        assertTrue(legacy.isOutdated)
        assertEquals("v0.3.262-abcdef1", legacy.appVersionLabel)
        assertEquals("v0.3.0 (1122334)", legacy.daemonVersionLabel)

        val olderProto = evaluateDaemonCompatibility(
            ServerInfo(version = "0.3.0", protocolVersion = 1, commit = "1122334"),
            expectedProtocolVersion = 2,
        )
        assertTrue(olderProto.isOutdated)

        val matching = evaluateDaemonCompatibility(
            ServerInfo(version = "0.3.0", protocolVersion = 1, commit = "abcdef1"),
            expectedProtocolVersion = 1,
        )
        assertEquals(DaemonCompatibilityStatus.COMPATIBLE, matching.status)
        assertFalse(matching.isOutdated)

        val newer = evaluateDaemonCompatibility(
            ServerInfo(version = "0.4.0", protocolVersion = 3, commit = "fedcba9"),
            expectedProtocolVersion = 1,
        )
        assertEquals(DaemonCompatibilityStatus.COMPATIBLE, newer.status)
        assertFalse(newer.isOutdated)
    }

    @Test
    fun unknownEndpointDetectionDistinguishesMissingRoutesFromResourceNotFound() {
        assertTrue(isUnknownEndpointResponse(HTTP_NOT_FOUND, "Unknown endpoint"))
        assertTrue(isUnknownEndpointResponse(HTTP_NOT_FOUND, "Unknown endpoint GET /api/agents/agy/usage"))
        assertTrue(isUnknownEndpointResponse(HTTP_NOT_FOUND, "HTTP 404: Not Found"))
        assertFalse(isUnknownEndpointResponse(HTTP_NOT_FOUND, "Tab not found"))
        assertFalse(isUnknownEndpointResponse(HTTP_NOT_FOUND, "Closed tab not found or expired"))
        assertFalse(isUnknownEndpointResponse(500, "Unknown endpoint"))

        val unknownErr = HttpStatusException(HTTP_NOT_FOUND, "Unknown endpoint")
        assertTrue(unknownErr.isUnknownEndpointError)
        assertEquals(
            OUTDATED_DAEMON_ENDPOINT_ERROR,
            formatDaemonRequestError(unknownErr, "Fallback"),
        )

        val tabMissingErr = HttpStatusException(HTTP_NOT_FOUND, "Tab not found")
        assertFalse(tabMissingErr.isUnknownEndpointError)
        assertEquals("Tab not found", formatDaemonRequestError(tabMissingErr, "Fallback"))
    }

    @Test
    fun apiClientTranslatesUnknownEndpoint404AndPreservesResourceNotFound() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HTTP_NOT_FOUND)
                .setBody("""{"error":"Unknown endpoint"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(HTTP_NOT_FOUND)
                .setBody("""{"error":"Tab not found"}"""),
        )
        server.start()
        try {
            val api = AgentsTermApiClient()
            val cfg = ConnectionConfig(host = server.hostName, port = server.port, token = "tok")

            try {
                api.getAgentUsage(cfg, "claude")
                fail("Expected HttpStatusException for unknown usage endpoint")
            } catch (err: HttpStatusException) {
                assertEquals(HTTP_NOT_FOUND, err.statusCode)
                assertTrue(err.isUnknownEndpoint)
                assertEquals(OUTDATED_DAEMON_ENDPOINT_ERROR, err.message)
            }

            try {
                api.getTabMessages(cfg, "missing_tab")
                fail("Expected HttpStatusException for missing tab")
            } catch (err: HttpStatusException) {
                assertEquals(HTTP_NOT_FOUND, err.statusCode)
                assertFalse(err.isUnknownEndpoint)
                assertEquals("Tab not found", err.message)
            }
        } finally {
            server.shutdown()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun agentUsageControllerFlagsNeedsDaemonUpdateOn404UnknownEndpoint() = runTest {
        val fakeApi = object : AgentsTermApiClient() {
            override suspend fun getAgentUsage(
                config: ConnectionConfig,
                agent: String,
            ) = throw HttpStatusException(
                statusCode = HTTP_NOT_FOUND,
                message = OUTDATED_DAEMON_ENDPOINT_ERROR,
                isUnknownEndpoint = true,
            )
        }
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        settings.saveConnection(ConnectionConfig(host = "127.0.0.1", port = 7501, token = "tok"))
        val repo = AgentsTermRepository(api = fakeApi, settings = settings)
        repo.initializeAutoConnect()

        val controller = AgentUsageController(repo = repo, scope = this)
        controller.checkUsage("claude")
        advanceUntilIdle()

        val state = controller.usageByAgent.value["claude"]
        assertEquals(OUTDATED_DAEMON_ENDPOINT_ERROR, state?.error)
        assertEquals(true, state?.needsDaemonUpdate)
        repo.disconnect()
    }

    @Test
    fun workspaceScaffoldShowsDismissibleOutdatedDaemonBannerWithUpdateAction() {
        var updateClicks = 0
        var workspaceState by mutableStateOf(
            WorkspaceState(
                config = ConnectionConfig(host = "dev-vm", port = 7501, token = "tok"),
                connectionState = ConnectionState.CONNECTED,
                serverInfo = ServerInfo(
                    version = "0.3.0",
                    protocolVersion = null,
                    commit = "1234567abcdef",
                    hostname = "dev-vm",
                ),
            ),
        )

        composeRule.setContent {
            AgentsTermTheme {
                TestWorkspaceScaffold(
                    state = workspaceState,
                    onUpdateDaemon = { updateClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("outdated_daemon_banner").assertIsDisplayed()
        composeRule.onNodeWithText("v0.3.0 (1234567)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("banner_update_daemon_button").assertIsDisplayed().performClick()
        assertEquals(1, updateClicks)

        // Dismissing hides the banner for this daemon instance.
        composeRule.onNodeWithTag("banner_dismiss_outdated_daemon_button").performClick()
        composeRule.onNodeWithTag("outdated_daemon_banner").assertDoesNotExist()

        // Connecting to a compatible daemon keeps the banner hidden.
        workspaceState = workspaceState.copy(
            serverInfo = ServerInfo(
                version = "0.3.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
                commit = "abcdef1234567",
                hostname = "dev-vm",
            ),
        )
        composeRule.onNodeWithTag("outdated_daemon_banner").assertDoesNotExist()
    }

    @Test
    fun settingsScreenShowsVersionSummaryAndUpdatePromptWhenOutdated() {
        var updateClicks = 0
        var workspaceState by mutableStateOf(
            WorkspaceState(
                config = ConnectionConfig(host = "dev-vm", port = 7501, token = "tok"),
                connectionState = ConnectionState.CONNECTED,
                serverInfo = ServerInfo(
                    version = "0.3.0",
                    protocolVersion = null,
                    commit = "old1234567",
                    hostname = "dev-vm",
                ),
            ),
        )

        composeRule.setContent {
            AgentsTermTheme {
                SettingsScreen(
                    workspaceState = workspaceState,
                    debugSettings = DebugSharingSettings(),
                    uploadState = DebugDumpUploadState(),
                    eventCount = 0,
                    hasLastCrash = false,
                    recentEventsProvider = { emptyList() },
                    onBack = {},
                    onOpenVmConnection = {},
                    onUpdateDaemon = { updateClicks++ },
                    onUpdateDebugSettings = {},
                    onUploadDumpToVm = { _, _ -> },
                    onExportOfflineZip = { _, _ -> Result.failure(IllegalStateException("unused")) },
                    onClearDebugBuffer = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_version_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_outdated_daemon_card").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_update_daemon_button").assertIsDisplayed().performClick()
        assertEquals(1, updateClicks)

        workspaceState = workspaceState.copy(
            serverInfo = ServerInfo(
                version = "0.3.0",
                protocolVersion = EXPECTED_DAEMON_PROTOCOL_VERSION,
                commit = "new9876543",
                hostname = "dev-vm",
            ),
        )
        composeRule.onNodeWithTag("settings_version_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_outdated_daemon_card").assertDoesNotExist()
    }

    @Test
    fun modelPickerUsagePanelShowsUpdateButtonWhenEndpointIsUnsupported() {
        var updateClicks = 0
        composeRule.setContent {
            AgentsTermTheme {
                ModelPickerSheetContent(
                    agentLabel = "Claude",
                    currentModel = null,
                    defaultModel = "opus",
                    models = listOf(AgentModelOption("opus", "Opus")),
                    isRefreshing = false,
                    usageState = AgentUsageUiState(
                        error = OUTDATED_DAEMON_ENDPOINT_ERROR,
                        needsDaemonUpdate = true,
                    ),
                    onCheckUsage = {},
                    onUpdateDaemon = { updateClicks++ },
                    onRefresh = {},
                    onSelectModel = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_picker_usage_error").assertIsDisplayed()
        composeRule.onNodeWithTag("model_picker_usage_update_daemon_button")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, updateClicks)
    }

    @Composable
    private fun TestWorkspaceScaffold(
        state: WorkspaceState,
        onUpdateDaemon: () -> Unit,
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
            onUpdateDaemon = onUpdateDaemon,
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
