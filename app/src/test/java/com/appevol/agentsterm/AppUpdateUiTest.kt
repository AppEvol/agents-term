package com.appevol.agentsterm

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DebugDumpUploadState
import com.appevol.agentsterm.data.DebugSharingSettings
import com.appevol.agentsterm.data.SettingsStore
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.appReleaseInfoFromTag
import com.appevol.agentsterm.ui.AppUpdateDialog
import com.appevol.agentsterm.ui.AppUpdateUiState
import com.appevol.agentsterm.ui.SettingsScreen
import com.appevol.agentsterm.ui.WorkspaceScaffold
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class AppUpdateUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleRelease = appReleaseInfoFromTag("v0.3.457-cc4da24")!!

    private val connectedState = WorkspaceState(
        config = ConnectionConfig(host = "100.71.219.26", port = 7501),
        connectionState = ConnectionState.CONNECTED,
    )

    @Test
    fun settingsStorePersistsAppUpdateCacheAndDismissal() {
        val store = SettingsStore(ApplicationProvider.getApplicationContext())
        store.saveLastAppUpdateCheck("v0.3.457-cc4da24", 123_456_789L)
        store.saveDismissedAppUpdateTag("v0.3.457-cc4da24")

        val loaded = store.loadAppUpdateState()
        assertEquals("v0.3.457-cc4da24", loaded.latestTag)
        assertEquals(123_456_789L, loaded.lastSuccessCheckMs)
        assertEquals("v0.3.457-cc4da24", loaded.dismissedTag)

        store.saveDismissedAppUpdateTag(null)
        assertNull(store.loadAppUpdateState().dismissedTag)
    }

    @Test
    fun overflowIconShowsBadgeAndMenuItemWhenUpdateAvailable() {
        var openedUpdateDialog = false
        composeRule.setContent {
            var showOverflow by remember { mutableStateOf(false) }
            AgentsTermTheme {
                WorkspaceScaffold(
                    state = connectedState,
                    isExpanded = false,
                    debugSharingEnabled = false,
                    snackbarHostState = remember { SnackbarHostState() },
                    showOverflowMenu = showOverflow,
                    onToggleOverflow = { showOverflow = it },
                    appUpdateState = AppUpdateUiState(
                        currentVersionLabel = "v0.3.450-1111111",
                        isLocalDevBuild = false,
                        latestRelease = sampleRelease,
                        updateAvailable = true,
                        badgeDismissed = false,
                    ),
                    onOpenAppUpdate = { openedUpdateDialog = true },
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

        composeRule.onNodeWithTag("overflow_update_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_update").assertIsDisplayed()
        composeRule.onNodeWithText("v0.3.457 ready to download").assertIsDisplayed()

        composeRule.onNodeWithTag("menu_app_update").performClick()
        assertTrue(openedUpdateDialog)
    }

    @Test
    fun overflowMenuKeepsUpdateItemAfterBadgeDismissed() {
        composeRule.setContent {
            var showOverflow by remember { mutableStateOf(false) }
            AgentsTermTheme {
                WorkspaceScaffold(
                    state = connectedState,
                    isExpanded = false,
                    debugSharingEnabled = false,
                    snackbarHostState = remember { SnackbarHostState() },
                    showOverflowMenu = showOverflow,
                    onToggleOverflow = { showOverflow = it },
                    appUpdateState = AppUpdateUiState(
                        currentVersionLabel = "v0.3.450-1111111",
                        isLocalDevBuild = false,
                        latestRelease = sampleRelease,
                        updateAvailable = true,
                        badgeDismissed = true,
                    ),
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

        assertEquals(
            0,
            composeRule.onAllNodesWithTag("overflow_update_badge", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_update").assertIsDisplayed()
    }

    @Test
    fun overflowMenuHidesBadgeWhenDismissedOrUpToDate() {
        composeRule.setContent {
            var showOverflow by remember { mutableStateOf(false) }
            AgentsTermTheme {
                WorkspaceScaffold(
                    state = connectedState,
                    isExpanded = false,
                    debugSharingEnabled = false,
                    snackbarHostState = remember { SnackbarHostState() },
                    showOverflowMenu = showOverflow,
                    onToggleOverflow = { showOverflow = it },
                    appUpdateState = AppUpdateUiState(
                        currentVersionLabel = "v0.3.457-cc4da24",
                        isLocalDevBuild = false,
                        latestRelease = sampleRelease,
                        updateAvailable = false,
                    ),
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

        assertEquals(0, composeRule.onAllNodesWithTag("overflow_update_badge").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        assertEquals(0, composeRule.onAllNodesWithTag("menu_app_update").fetchSemanticsNodes().size)
    }

    @Test
    fun appUpdateDialogTriggersDownloadReleaseAndLaterActions() {
        var downloadedUrl: String? = null
        var releaseUrl: String? = null
        var laterClicked = false

        composeRule.setContent {
            AgentsTermTheme {
                AppUpdateDialog(
                    currentVersionLabel = "v0.3.450-1111111",
                    release = sampleRelease,
                    onDownloadApk = { downloadedUrl = it },
                    onViewRelease = { releaseUrl = it },
                    onLater = { laterClicked = true },
                )
            }
        }

        composeRule.onNodeWithTag("app_update_dialog_versions").assertIsDisplayed()
        composeRule.onNodeWithText("v0.3.450-1111111 → v0.3.457-cc4da24").assertIsDisplayed()

        composeRule.onNodeWithTag("app_update_download_button").performClick()
        assertEquals(sampleRelease.apkDownloadUrl, downloadedUrl)

        composeRule.onNodeWithTag("app_update_view_release_button").performClick()
        assertEquals(sampleRelease.releasePageUrl, releaseUrl)

        composeRule.onNodeWithTag("app_update_later_button").performClick()
        assertTrue(laterClicked)
    }

    @Test
    fun settingsScreenRendersAppUpdateCardAndActions() {
        var checkClicks = 0
        val openedUrls = mutableListOf<String>()

        composeRule.setContent {
            AgentsTermTheme {
                SettingsScreen(
                    workspaceState = connectedState,
                    debugSettings = DebugSharingSettings(),
                    uploadState = DebugDumpUploadState(),
                    eventCount = 0,
                    hasLastCrash = false,
                    recentEventsProvider = { emptyList() },
                    onBack = {},
                    onOpenVmConnection = {},
                    appUpdateState = AppUpdateUiState(
                        currentVersionLabel = "v0.3.450-1111111",
                        isLocalDevBuild = false,
                        latestRelease = sampleRelease,
                        updateAvailable = true,
                    ),
                    onCheckForAppUpdates = { checkClicks += 1 },
                    onOpenUpdateUrl = { openedUrls += it },
                    onUpdateDebugSettings = {},
                    onUploadDumpToVm = { _, _ -> },
                    onExportOfflineZip = { _, _ -> Result.failure(IllegalStateException("unused")) },
                    onClearDebugBuffer = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_app_update_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Installed: v0.3.450-1111111").assertIsDisplayed()
        composeRule.onNodeWithText("New release available: v0.3.457-cc4da24").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_app_update_download_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_app_update_release_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_app_update_check_button").performScrollTo().performClick()

        assertEquals(listOf(sampleRelease.apkDownloadUrl, sampleRelease.releasePageUrl), openedUrls)
        assertEquals(1, checkClicks)
    }
}
