package com.appevol.agentsterm

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.ui.WorkspaceNavigationShell
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DrawerBackNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleState = WorkspaceState(
        config = ConnectionConfig(host = "100.71.219.26", port = 7501),
        connectionState = ConnectionState.CONNECTED,
        tabs = listOf(
            TabRecord(
                id = "tab_1",
                title = "Antigravity · agents-term",
                agent = "agy",
                cwd = "/home/ned/projects/agents-term",
                status = "idle",
            ),
        ),
        selectedTabId = "tab_1",
    )

    @Test
    fun backPressClosesOpenDrawerInsteadOfExitingApp() {
        var outerBackPresses by mutableIntStateOf(0)
        lateinit var capturedDrawerState: DrawerState

        composeRule.setContent {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            capturedDrawerState = drawerState
            val scope = rememberCoroutineScope()

            AgentsTermTheme {
                BackHandler { outerBackPresses++ }
                WorkspaceNavigationShell(
                    isExpanded = false,
                    drawerState = drawerState,
                    state = sampleState,
                    debugSharingEnabled = false,
                    onSelectTab = {},
                    onNewTab = {},
                    onDeleteTab = {},
                    onCloseFinishedTabs = {},
                    onOpenSettings = {},
                    onOpenAppSettings = {},
                    onReconnect = {},
                ) {
                    Button(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.testTag("open_drawer_button"),
                    ) {
                        Text("Open drawer")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // When the drawer is closed, Back passes through to the activity/outer handler.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, outerBackPresses)

        // Open the drawer, then press Back: drawer closes and outer handler is not invoked.
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        assertTrue("Drawer should be open after tapping open button", capturedDrawerState.isOpen)

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertFalse("Drawer should close on Back press", capturedDrawerState.isOpen)
        assertTrue("Drawer should settle in closed state", capturedDrawerState.isClosed)
        assertEquals("Back press while drawer was open must not exit the app", 1, outerBackPresses)

        // Pressing Back again now that the drawer is closed passes through normally.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(2, outerBackPresses)
    }

    @Test
    fun backPressPassesThroughInExpandedLayout() {
        var isExpanded by mutableStateOf(false)
        var outerBackPresses by mutableIntStateOf(0)
        lateinit var capturedDrawerState: DrawerState

        composeRule.setContent {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            capturedDrawerState = drawerState
            val scope = rememberCoroutineScope()

            AgentsTermTheme {
                BackHandler { outerBackPresses++ }
                WorkspaceNavigationShell(
                    isExpanded = isExpanded,
                    drawerState = drawerState,
                    state = sampleState,
                    debugSharingEnabled = false,
                    onSelectTab = {},
                    onNewTab = {},
                    onDeleteTab = {},
                    onCloseFinishedTabs = {},
                    onOpenSettings = {},
                    onOpenAppSettings = {},
                    onReconnect = {},
                ) {
                    Button(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.testTag("open_drawer_button"),
                    ) {
                        Text("Open drawer")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Open drawer in compact layout, then switch to expanded layout (e.g. rotation/unfold).
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        assertTrue(capturedDrawerState.isOpen)

        isExpanded = true
        composeRule.waitForIdle()

        // BackHandler must no longer intercept Back once the modal drawer is replaced by the rail.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, outerBackPresses)
    }
}
