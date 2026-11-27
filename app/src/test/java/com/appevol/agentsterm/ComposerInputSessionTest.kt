package com.appevol.agentsterm

import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.appevol.agentsterm.data.AttachmentLimits
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.ui.ComposerAttachments
import com.appevol.agentsterm.ui.WorkspaceScaffold
import com.appevol.agentsterm.ui.theme.HerdrTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ComposerInputSessionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tabA = TabRecord(
        id = "tab_a",
        title = "Claude · repo",
        agent = "claude",
        cwd = "/home/ned",
        status = "idle",
    )

    private val tabB = TabRecord(
        id = "tab_b",
        title = "Antigravity · repo",
        agent = "agy",
        cwd = "/home/ned",
        status = "idle",
    )

    private val tabC = TabRecord(
        id = "tab_c",
        title = "Claude · repo",
        agent = "claude",
        cwd = "/home/ned",
        status = "idle",
    )

    @Composable
    private fun TestComposerWorkspace(
        tabs: List<TabRecord>,
        selectedId: String,
        onSelectTab: (String) -> Unit,
        onHostView: (View) -> Unit,
        attachments: ComposerAttachments? = null,
    ) {
        onHostView(LocalView.current)
        val state = WorkspaceState(
            config = ConnectionConfig(host = "100.71.219.26", port = 7501),
            connectionState = ConnectionState.CONNECTED,
            tabs = tabs,
            selectedTabId = selectedId,
        )
        HerdrTheme {
            WorkspaceScaffold(
                state = state,
                composerAttachments = attachments,
                isExpanded = false,
                debugSharingEnabled = false,
                snackbarHostState = remember { SnackbarHostState() },
                showOverflowMenu = false,
                onToggleOverflow = {},
                onOpenDrawer = {},
                onNewTab = {},
                onQuickDebugDump = {},
                onSelectTab = onSelectTab,
                showTaskSummary = false,
                showTaskSummaryWhenEmpty = false,
                onToggleTaskSummary = {},
                onDismissTaskSummary = {},
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

    private fun typeViaIme(view: View, text: String) {
        assertTrue("Expected composer to be an active text editor", view.onCheckIsTextEditor())
        val ic = view.onCreateInputConnection(EditorInfo())
        assertNotNull("Expected active InputConnection", ic)
        composeRule.runOnIdle {
            requireNotNull(ic).commitText(text, 1)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun composerAcceptsImeInputWhenNewTabOpensFromBottomSheetWhileFocused() {
        var hostView: View? = null
        var tabsState by mutableStateOf(listOf(tabA))
        var selectedIdState by mutableStateOf(tabA.id)

        composeRule.setContent {
            TestComposerWorkspace(
                tabs = tabsState,
                selectedId = selectedIdState,
                onSelectTab = { selectedIdState = it },
                onHostView = { hostView = it },
            )
        }

        val input = composeRule.onNodeWithTag("composer_input")
        val view = requireNotNull(hostView)

        input.performClick()
        composeRule.waitForIdle()
        typeViaIme(view, "draft on A")
        input.assertTextEquals("draft on A")

        // Simulate NewTabSheet (ModalBottomSheet) taking window focus and creating tabB before
        // dismissing back to the workspace window.
        composeRule.runOnIdle { view.dispatchWindowFocusChanged(false) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            tabsState = listOf(tabA, tabB)
            selectedIdState = tabB.id
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { view.dispatchWindowFocusChanged(true) }
        composeRule.waitForIdle()

        // The new tab starts with a fresh unfocused composer and accepts IME input directly.
        input.assertIsNotFocused()
        input.performClick()
        composeRule.waitForIdle()
        typeViaIme(view, "draft on B")
        input.assertTextEquals("draft on B")

        // Returning to tabA preserves its untouched draft.
        composeRule.onNodeWithTag("quick_tab_tab_a").performClick()
        composeRule.waitForIdle()
        input.assertTextEquals("draft on A")
    }

    @Test
    fun composerAcceptsImeInputAfterDeletingActiveTabWhileWindowUnfocused() {
        var hostView: View? = null
        var tabsState by mutableStateOf(listOf(tabA, tabB))
        var selectedIdState by mutableStateOf(tabA.id)

        composeRule.setContent {
            TestComposerWorkspace(
                tabs = tabsState,
                selectedId = selectedIdState,
                onSelectTab = { selectedIdState = it },
                onHostView = { hostView = it },
            )
        }

        val input = composeRule.onNodeWithTag("composer_input")
        val view = requireNotNull(hostView)

        input.performClick()
        composeRule.waitForIdle()
        typeViaIme(view, "old tab")
        input.assertTextEquals("old tab")

        // Simulate a menu/dialog taking window focus and deleting the active tab.
        composeRule.runOnIdle {
            view.dispatchWindowFocusChanged(false)
            tabsState = listOf(tabB, tabC)
            selectedIdState = tabB.id
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { view.dispatchWindowFocusChanged(true) }
        composeRule.waitForIdle()

        input.performClick()
        composeRule.waitForIdle()
        typeViaIme(view, "new tab text")
        input.assertTextEquals("new tab text")
    }

    @Test
    fun composerAcceptsImeInputOnSameTabAfterWindowFocusCycleAndAttachmentsChange() {
        var hostView: View? = null
        var attachmentsState by mutableStateOf<ComposerAttachments?>(null)

        composeRule.setContent {
            TestComposerWorkspace(
                tabs = listOf(tabA),
                selectedId = tabA.id,
                onSelectTab = {},
                onHostView = { hostView = it },
                attachments = attachmentsState,
            )
        }

        val input = composeRule.onNodeWithTag("composer_input")
        val view = requireNotNull(hostView)

        input.performClick()
        composeRule.waitForIdle()
        val ic1 = view.onCreateInputConnection(EditorInfo())
        assertNotNull(ic1)
        composeRule.runOnIdle { requireNotNull(ic1).commitText("hi", 1) }
        composeRule.waitForIdle()
        input.assertTextEquals("hi")

        composeRule.runOnIdle {
            requireNotNull(ic1).closeConnection()
            view.dispatchWindowFocusChanged(false)
            attachmentsState = ComposerAttachments(
                limits = AttachmentLimits(maxBytes = 10_000_000, maxPerMessage = 5),
                pending = emptyList(),
                onAttach = { _: List<Uri> -> },
                onRemove = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { view.dispatchWindowFocusChanged(true) }
        composeRule.waitForIdle()

        input.performClick()
        composeRule.waitForIdle()
        typeViaIme(view, " there")
        input.assertTextEquals("hi there")
    }
}
