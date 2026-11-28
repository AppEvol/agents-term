package com.appevol.agentsterm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.appevol.agentsterm.data.AppThemeId
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.ui.QuickTabStrip
import com.appevol.agentsterm.ui.WorkspaceScaffold
import com.appevol.agentsterm.ui.computeQuickTabScrollTarget
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
class QuickTabStripUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makeTabs(count: Int): List<TabRecord> = (1..count).map { index ->
        TabRecord(
            id = "tab_$index",
            title = "Workspace tab $index",
            agent = "agy",
            cwd = "/home/ned/projects/agents-term",
            status = if (index % 2 == 0) "working" else "idle",
            unread = index == 2,
        )
    }

    @Test
    fun computeScrollTargetBringsOffScreenAndClippedTabsIntoView() {
        // Completely off-screen on the right -> clamps to maxScroll for last tab
        assertEquals(
            385,
            computeQuickTabScrollTarget(
                tabLeft = 636,
                tabRight = 784,
                viewportWidth = 411,
                currentScroll = 0,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )

        // Completely off-screen on the left -> clamps to 0 for first tab
        assertEquals(
            0,
            computeQuickTabScrollTarget(
                tabLeft = 12,
                tabRight = 160,
                viewportWidth = 411,
                currentScroll = 385,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )

        // Partially clipped middle tab on the right -> centers cleanly
        assertEquals(
            193,
            computeQuickTabScrollTarget(
                tabLeft = 324,
                tabRight = 472,
                viewportWidth = 411,
                currentScroll = 0,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )
    }

    @Test
    fun computeScrollTargetReturnsNullWhenTabIsAlreadyVisible() {
        // Already comfortably visible -> no scroll needed
        assertNull(
            computeQuickTabScrollTarget(
                tabLeft = 168,
                tabRight = 316,
                viewportWidth = 411,
                currentScroll = 0,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )

        // First tab at scroll 0 -> no scroll needed
        assertNull(
            computeQuickTabScrollTarget(
                tabLeft = 12,
                tabRight = 160,
                viewportWidth = 411,
                currentScroll = 0,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )

        // Last tab already at maxScroll -> no scroll needed
        assertNull(
            computeQuickTabScrollTarget(
                tabLeft = 636,
                tabRight = 784,
                viewportWidth = 411,
                currentScroll = 385,
                maxScroll = 385,
                edgeMarginPx = 12,
            ),
        )

        // No overflow -> no scroll needed
        assertNull(
            computeQuickTabScrollTarget(
                tabLeft = 12,
                tabRight = 160,
                viewportWidth = 411,
                currentScroll = 0,
                maxScroll = 0,
                edgeMarginPx = 12,
            ),
        )
    }

    @Test
    fun newlyCreatedActiveTabAtEndAutoScrollsIntoView() {
        var tabs by mutableStateOf(makeTabs(4))
        var activeId by mutableStateOf("tab_1")

        composeRule.setContent {
            AgentsTermTheme(theme = AppThemeId.XP_LUNA) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    QuickTabStrip(
                        tabs = tabs,
                        activeTabId = activeId,
                        onSelect = { activeId = it },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val stripBounds = composeRule.onNodeWithTag("quick_tab_strip").getBoundsInRoot()
        composeRule.onNodeWithTag("quick_tab_tab_1").assertIsSelected().assertIsDisplayed()

        // Append a new 5th and 6th tab and activate the newest tab (simulating New Tab creation)
        composeRule.runOnIdle {
            tabs = makeTabs(6)
            activeId = "tab_6"
        }
        composeRule.waitForIdle()

        val activeBounds = composeRule.onNodeWithTag("quick_tab_tab_6").getBoundsInRoot()
        composeRule.onNodeWithTag("quick_tab_tab_6").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_6", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Expected newly created tab_6 ($activeBounds) to be within strip bounds ($stripBounds)",
            activeBounds.left >= stripBounds.left - 1.dp && activeBounds.right <= stripBounds.right + 1.dp,
        )
    }

    @Test
    fun initialOffScreenSelectedTabIsScrolledIntoViewImmediately() {
        val tabs = makeTabs(6)
        composeRule.setContent {
            AgentsTermTheme(theme = AppThemeId.MIDNIGHT) {
                QuickTabStrip(
                    tabs = tabs,
                    activeTabId = "tab_6",
                    onSelect = {},
                )
            }
        }

        composeRule.waitForIdle()
        val stripBounds = composeRule.onNodeWithTag("quick_tab_strip").getBoundsInRoot()
        val activeBounds = composeRule.onNodeWithTag("quick_tab_tab_6").getBoundsInRoot()
        composeRule.onNodeWithTag("quick_tab_tab_6").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_6", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Expected initial active tab_6 ($activeBounds) to be within strip ($stripBounds)",
            activeBounds.left >= stripBounds.left - 1.dp && activeBounds.right <= stripBounds.right + 1.dp,
        )
    }

    @Test
    fun selectingEarlierTabScrollsBackAndOnlySelectedTabShowsBoldIndicator() {
        val tabs = makeTabs(6)
        var activeId by mutableStateOf("tab_6")

        composeRule.setContent {
            AgentsTermTheme(theme = AppThemeId.DAYLIGHT) {
                QuickTabStrip(
                    tabs = tabs,
                    activeTabId = activeId,
                    finishedTabIds = setOf("tab_3"),
                    onSelect = { activeId = it },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_6", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("quick_tab_indicator_tab_1", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )

        // Simulate selecting tab_1 from the drawer while scrolled at the far right
        composeRule.runOnIdle { activeId = "tab_1" }
        composeRule.waitForIdle()

        val stripBounds = composeRule.onNodeWithTag("quick_tab_strip").getBoundsInRoot()
        val tab1Bounds = composeRule.onNodeWithTag("quick_tab_tab_1").getBoundsInRoot()
        composeRule.onNodeWithTag("quick_tab_tab_1").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_tab_6").assertIsNotSelected()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_1", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("quick_tab_indicator_tab_6", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            "Expected tab_1 ($tab1Bounds) to scroll back into strip ($stripBounds)",
            tab1Bounds.left >= stripBounds.left - 1.dp && tab1Bounds.right <= stripBounds.right + 1.dp,
        )

        // Switching to a middle finished tab centers it into view and keeps both done icon & indicator
        composeRule.runOnIdle { activeId = "tab_3" }
        composeRule.waitForIdle()
        val tab3Bounds = composeRule.onNodeWithTag("quick_tab_tab_3").getBoundsInRoot()
        composeRule.onNodeWithTag("quick_tab_tab_3").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_done_icon_tab_3", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_3", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Expected middle tab_3 ($tab3Bounds) to be fully within strip ($stripBounds)",
            tab3Bounds.left >= stripBounds.left - 1.dp && tab3Bounds.right <= stripBounds.right + 1.dp,
        )
    }

    @Test
    fun manualHorizontalScrollIsNotOverriddenWhileActiveTabStaysUnchanged() {
        val tabs = makeTabs(6)
        composeRule.setContent {
            AgentsTermTheme(theme = AppThemeId.XP_LUNA) {
                QuickTabStrip(
                    tabs = tabs,
                    activeTabId = "tab_6",
                    onSelect = {},
                )
            }
        }

        composeRule.waitForIdle()
        val beforeLeft = composeRule.onNodeWithTag("quick_tab_tab_1").getBoundsInRoot().left

        // User swipes right to peek at earlier tabs while staying on tab_6
        composeRule.onNodeWithTag("quick_tab_strip").performTouchInput {
            swipeRight()
        }
        composeRule.waitForIdle()

        val afterLeft = composeRule.onNodeWithTag("quick_tab_tab_1").getBoundsInRoot().left
        assertTrue(
            "Expected manual swipe right to reveal earlier tabs (before=$beforeLeft, after=$afterLeft)",
            afterLeft > beforeLeft,
        )
    }

    @Test
    fun workspaceScaffoldShowsSelectedTabAndUpdatesOnTap() {
        val tabs = makeTabs(5)
        var selectedId by mutableStateOf("tab_5")

        composeRule.setContent {
            AgentsTermTheme(theme = AppThemeId.XP_LUNA) {
                Column {
                    WorkspaceScaffold(
                        state = WorkspaceState(
                            config = ConnectionConfig(host = "100.71.219.26", port = 7501),
                            connectionState = ConnectionState.CONNECTED,
                            tabs = tabs,
                            selectedTabId = selectedId,
                        ),
                        isExpanded = false,
                        debugSharingEnabled = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        showOverflowMenu = false,
                        onToggleOverflow = {},
                        onOpenDrawer = {},
                        onNewTab = {},
                        onQuickDebugDump = {},
                        onSelectTab = { selectedId = it },
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
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_tab_tab_5").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_5", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag("quick_tab_tab_4").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_tab_tab_4").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithTag("quick_tab_indicator_tab_4", useUnmergedTree = true).assertIsDisplayed()
    }
}
