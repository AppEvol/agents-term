package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.example.data.BootstrapUiState
import com.example.data.ConnectionConfig
import com.example.data.ConnectionState
import com.example.data.SettingsStore
import com.example.data.SshAuthMethod
import com.example.data.SshProfile
import com.example.data.TailscaleStatus
import com.example.data.WorkspaceState
import com.example.ui.ConnectScreen
import com.example.ui.WorkspaceScaffold
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsStoreAndAuthUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun clearPrefs() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) { clear() }
        context.getSharedPreferences(SettingsStore.LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) { clear() }
    }

    @Test
    fun saveConnectionStoresCredentialsOnlyInLocalPrefsAndKeepsBackupPrefsClean() {
        val store = SettingsStore(context)
        val cfg = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "secret_tok_123")
        store.saveConnection(cfg)

        assertEquals(cfg, store.loadConnection())

        val backedUpPrefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val localPrefs = context.getSharedPreferences(SettingsStore.LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(backedUpPrefs.contains("vm_token"))
        assertFalse(backedUpPrefs.contains("vm_host"))
        assertFalse(backedUpPrefs.contains("vm_port"))
        assertEquals("secret_tok_123", localPrefs.getString("vm_token", null))
        assertEquals("100.71.219.26", localPrefs.getString("vm_host", null))
    }

    @Test
    fun freshReinstallWithRestoredBackupPrefsRequiresRepairWhilePreservingSshProfile() {
        // Simulate Android Auto Backup restoring non-secret SSH profile while local prefs are empty.
        val backedUpPrefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        backedUpPrefs.edit(commit = true) {
            putString("ssh_host", "example-vm")
            putInt("ssh_port", 22)
            putString("ssh_username", "ned")
            putString("ssh_auth_method", SshAuthMethod.TAILSCALE_SSH.storageKey)
            putInt("ssh_daemon_port", 7501)
        }

        val store = SettingsStore(context)
        val loadedConnection = store.loadConnection()
        assertFalse(loadedConnection.isValid)
        assertEquals("", loadedConnection.token)

        val sshProfile = store.loadSshProfile()
        assertEquals("example-vm", sshProfile.host)
        assertEquals("ned", sshProfile.username)
        assertEquals(SshAuthMethod.TAILSCALE_SSH, sshProfile.authMethod)
    }

    @Test
    fun inPlaceUpgradeMigratesLegacyConnectionOutOfBackupPrefs() {
        // Simulate an existing install upgrading from an older APK that stored vm_token in backed-up prefs.
        val backedUpPrefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        backedUpPrefs.edit(commit = true) {
            putString("vm_host", "100.71.219.26")
            putInt("vm_port", 7501)
            putString("vm_token", "legacy_tok_456")
        }

        val store = SettingsStore(context)
        assertEquals(
            ConnectionConfig(host = "100.71.219.26", port = 7501, token = "legacy_tok_456"),
            store.loadConnection(),
        )
        assertFalse(backedUpPrefs.contains("vm_token"))
        assertFalse(backedUpPrefs.contains("vm_host"))
        assertFalse(backedUpPrefs.contains("vm_port"))
        assertEquals("100.71.219.26", store.loadSshProfile().host)
    }

    @Test
    fun workspaceScaffoldShowsRepairBannerAndPlaceholderOnAuthError() {
        var openSettingsClicks = 0
        var reconnectClicks = 0

        composeRule.setContent {
            HerdrTheme {
                WorkspaceScaffold(
                    state = WorkspaceState(
                        config = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "stale"),
                        connectionState = ConnectionState.AUTH_ERROR,
                        connectionError = "HTTP 401: Unauthorized",
                    ),
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
                    onOpenSettings = { openSettingsClicks++ },
                    onOpenAppSettings = {},
                    onReconnect = { reconnectClicks++ },
                    onSendMessage = {},
                    onInterrupt = {},
                    onForceInterrupt = {},
                    onRemoveQueuedMessage = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("connection_banner").assertIsDisplayed()
        composeRule.onNodeWithText("Pairing token rejected — re-pair with your VM").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_reconnect_button").assertDoesNotExist()
        composeRule.onNodeWithTag("banner_repair_button").assertIsDisplayed().performClick()
        assertEquals(1, openSettingsClicks)
        assertEquals(0, reconnectClicks)

        composeRule.onNodeWithTag("empty_repair_button").assertIsDisplayed().performClick()
        assertEquals(2, openSettingsClicks)
    }

    @Test
    fun connectScreenPrefersSshModeWhenRepairingWithSavedSshProfile() {
        composeRule.setContent {
            HerdrTheme {
                ConnectScreen(
                    initialConfig = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "stale"),
                    sshProfile = SshProfile(
                        host = "example-vm",
                        username = "ned",
                        authMethod = SshAuthMethod.TAILSCALE_SSH,
                    ),
                    bootstrapState = BootstrapUiState(),
                    pendingPairing = null,
                    isTesting = false,
                    errorMessage = "HTTP 401: Unauthorized",
                    canCancel = true,
                    onCancel = {},
                    onConnect = {},
                    onStartSshBootstrap = {},
                    onCancelSshBootstrap = {},
                    onTrustNewHostKeyAndRetry = {},
                    onConsumedPendingPairing = {},
                    preferSshMode = true,
                    tailscaleStatus = TailscaleStatus.Active("100.80.14.113"),
                )
            }
        }

        // SSH bootstrap mode is shown directly with the saved host and username pre-filled.
        composeRule.onNodeWithText("SSH & Bootstrap VM").assertIsDisplayed()
        composeRule.onNodeWithText("example-vm").assertExists()
        composeRule.onNodeWithText("ned").assertExists()
    }
}
