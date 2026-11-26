package com.example

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.ConnectionState
import com.example.data.TabRecord
import com.example.data.WorkspaceState
import com.example.data.resolveTabTaskSummary
import com.example.ui.TabSidebarBody
import com.example.ui.WorkspaceScaffold
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class TabSummaryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tabA = TabRecord(
        id = "tab_a",
        title = "Antigravity · agents-term",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
        lastMessagePreview = "Merged PR #23 and cleaned up worktree.",
        taskSummary = "Clean up app-state leftovers and update AGENTS.md",
    )

    private val tabB = TabRecord(
        id = "tab_b",
        title = "Antigravity · agents-term",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        status = "working",
        statusDetail = "Running ./gradlew testDebugUnitTest",
        lastMessagePreview = "I meant properly",
        taskSummary = "Fix daemon restart survival over SSH",
    )

    private val tabEmpty = TabRecord(
        id = "tab_empty",
        title = "Claude · agents-term",
        agent = "claude",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    @Composable
    private fun TestWorkspaceHost(
        tabs: List<TabRecord>,
        initialSelectedId: String,
        messagesByTab: Map<String, List<ChatMessage>> = emptyMap(),
        initialSummaryTabId: String? = null,
    ) {
        var selectedId by remember { mutableStateOf(initialSelectedId) }
        var summaryTabId by remember { mutableStateOf(initialSummaryTabId) }
        var allowEmpty by remember { mutableStateOf(false) }
        val state = WorkspaceState(
            config = ConnectionConfig(host = "100.71.219.26", port = 7501),
            connectionState = ConnectionState.CONNECTED,
            tabs = tabs,
            selectedTabId = selectedId,
            messagesByTab = messagesByTab,
        )
        fun isVisibleFor(tabId: String): Boolean {
            if (summaryTabId != tabId) return false
            val tab = tabs.firstOrNull { it.id == tabId } ?: return false
            return resolveTabTaskSummary(tab, messagesByTab[tabId].orEmpty()) != null || allowEmpty
        }
        HerdrTheme {
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
                onSelectTab = { tappedId ->
                    if (tappedId == selectedId) {
                        if (isVisibleFor(tappedId)) {
                            summaryTabId = null
                            allowEmpty = false
                        } else {
                            summaryTabId = tappedId
                            allowEmpty = true
                        }
                    } else {
                        selectedId = tappedId
                        summaryTabId = tappedId
                        allowEmpty = false
                    }
                },
                showTaskSummary = summaryTabId == selectedId,
                showTaskSummaryWhenEmpty = allowEmpty,
                onToggleTaskSummary = {
                    if (isVisibleFor(selectedId)) {
                        summaryTabId = null
                        allowEmpty = false
                    } else {
                        summaryTabId = selectedId
                        allowEmpty = true
                    }
                },
                onDismissTaskSummary = {
                    summaryTabId = null
                    allowEmpty = false
                },
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

    @Test
    fun switchingTabsRestoresEachDraftAndSendingClearsOnlyItsTab() {
        composeRule.setContent {
            TestWorkspaceHost(tabs = listOf(tabA, tabB), initialSelectedId = tabA.id)
        }
        val input = composeRule.onNodeWithTag("composer_input")
        val first = "Review this change.\nKeep the second line too."
        input.performTextInput(first)
        composeRule.onNodeWithTag("quick_tab_tab_b").performClick()
        input.performTextInput("Follow up")
        composeRule.onNodeWithTag("quick_tab_tab_a").performClick()
        input.assertTextEquals(first)
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.onNodeWithTag("quick_tab_tab_b").performClick()
        input.assertTextEquals("Follow up")
        composeRule.onNodeWithTag("quick_tab_tab_a").performClick()
        input.performTextInput("New message")
        input.assertTextEquals("New message")
    }

    @Test
    fun tappingTabShowsSummaryCardAndReTappingOrDismissingHidesIt() {
        composeRule.setContent {
            TestWorkspaceHost(tabs = listOf(tabA, tabB, tabEmpty), initialSelectedId = "tab_a")
        }

        // Hidden initially before user taps a tab
        assertTrue(composeRule.onAllNodesWithTag("tab_task_summary_card").fetchSemanticsNodes().isEmpty())

        // Tapping another tab switches to it and displays its task summary + live status detail
        composeRule.onNodeWithTag("quick_tab_tab_b").performClick()
        composeRule.onNodeWithTag("tab_task_summary_card").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_task_summary_text")
            .assertIsDisplayed()
            .assertTextEquals("Fix daemon restart survival over SSH")
        composeRule.onNodeWithTag("tab_task_summary_latest")
            .assertIsDisplayed()
            .assertTextEquals("Running ./gradlew testDebugUnitTest")

        // Tapping tab_a immediately updates the summary card to tab_a's task & latest outcome
        composeRule.onNodeWithTag("quick_tab_tab_a").performClick()
        composeRule.onNodeWithTag("tab_task_summary_text")
            .assertIsDisplayed()
            .assertTextEquals("Clean up app-state leftovers and update AGENTS.md")
        composeRule.onNodeWithTag("tab_task_summary_latest")
            .assertIsDisplayed()
            .assertTextEquals("Latest: Merged PR #23 and cleaned up worktree.")

        // Tapping the already-active tab hides the summary card; tapping top bar title re-opens it
        composeRule.onNodeWithTag("quick_tab_tab_a").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("tab_task_summary_card").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("top_bar_tab_title").performClick()
        composeRule.onNodeWithTag("tab_task_summary_card").assertIsDisplayed()

        // Tapping dismiss button hides the card
        composeRule.onNodeWithTag("dismiss_task_summary_button").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("tab_task_summary_card").fetchSemanticsNodes().isEmpty())

        // Switching to a brand-new empty tab keeps the card hidden until explicitly toggled
        composeRule.onNodeWithTag("quick_tab_tab_empty").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("tab_task_summary_card").fetchSemanticsNodes().isEmpty())

        // Tapping the active empty tab explicitly reveals the empty-state hint
        composeRule.onNodeWithTag("quick_tab_tab_empty").performClick()
        composeRule.onNodeWithTag("tab_task_summary_empty").assertIsDisplayed()
    }

    @Test
    fun summaryCardFallsBackToFirstUserMessageWhenDaemonOmitsTaskSummary() {
        val legacyTab = tabA.copy(taskSummary = null, lastMessagePreview = "Ok done")
        val legacyMessages = mapOf(
            legacyTab.id to listOf(
                ChatMessage(id = "u1", tabId = legacyTab.id, role = "user", text = "Add offline cache for models"),
                ChatMessage(id = "a1", tabId = legacyTab.id, role = "assistant", text = "Ok done"),
            ),
        )
        composeRule.setContent {
            TestWorkspaceHost(
                tabs = listOf(legacyTab),
                initialSelectedId = legacyTab.id,
                messagesByTab = legacyMessages,
                initialSummaryTabId = legacyTab.id,
            )
        }

        composeRule.onNodeWithTag("tab_task_summary_card").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_task_summary_text")
            .assertIsDisplayed()
            .assertTextEquals("Add offline cache for models")
    }

    @Test
    fun topBarDoneBadgeRemainsVisibleWhenTitleIsClickable() {
        val doneTab = tabA.copy(status = "done")
        composeRule.setContent {
            TestWorkspaceHost(tabs = listOf(doneTab), initialSelectedId = doneTab.id)
        }

        composeRule.onNodeWithTag("topbar_done_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("top_bar_tab_title").performClick()
        composeRule.onNodeWithTag("tab_task_summary_card").assertIsDisplayed()
    }

    @Test
    fun drawerPrefersTaskSummaryOverLastMessagePreview() {
        val legacyWithPreviewOnly = TabRecord(
            id = "tab_c",
            title = "Shell · agents-term",
            agent = "shell",
            cwd = "/home/ned/projects/agents-term",
            lastMessagePreview = "git status",
        )
        composeRule.setContent {
            HerdrTheme {
                TabSidebarBody(
                    state = WorkspaceState(
                        config = ConnectionConfig(host = "100.71.219.26", port = 7501),
                        connectionState = ConnectionState.CONNECTED,
                        tabs = listOf(tabB, legacyWithPreviewOnly),
                        selectedTabId = tabB.id,
                    ),
                    onSelectTab = {},
                    onNewTab = {},
                    onDeleteTab = {},
                    onOpenSettings = {},
                    onReconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("drawer_tab_summary_tab_b", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("Fix daemon restart survival over SSH")
        composeRule.onNodeWithTag("drawer_tab_summary_tab_c", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("git status")
    }
}
