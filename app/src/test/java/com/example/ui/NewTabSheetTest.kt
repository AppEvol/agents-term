package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.data.AppThemeId
import com.example.data.AvailableAgentInfo
import com.example.data.FsDirectoryEntry
import com.example.data.FsListResponse
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class NewTabSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val defaultAgents = listOf(
        AvailableAgentInfo("agy", "Antigravity", "Antigravity CLI", "agy", true),
        AvailableAgentInfo("claude", "Claude Code", "Claude CLI", "claude", true),
        AvailableAgentInfo("codex", "Codex", "Codex CLI", "codex", true),
        AvailableAgentInfo(
            "shell",
            "Shell",
            "Persistent bash (keeps directory, env vars, and stdin)",
            "bash",
            true,
        ),
    )

    @Test
    fun portraitPhoneShowsAllFourTabOptionsWithoutHorizontalScrolling() {
        composeRule.setContent {
            HerdrTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    NewTabSheetContent(
                        agents = defaultAgents,
                        defaultCwd = "/home/ned",
                        canCreateTab = true,
                        onLoadDirectories = { Result.success(FsListResponse(path = "/home/ned")) },
                        onCreate = { _, _, _, _, _, _ -> },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tab Type").assertIsDisplayed()

        for (type in listOf("agy", "claude", "codex", "shell")) {
            val node = composeRule.onNodeWithTag("agent_option_$type")
            node.assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue(
                "Expected agent_option_$type ($bounds) to fit within 360dp portrait width",
                bounds.left >= 0.dp && bounds.right <= 360.dp,
            )
        }

        val agyBounds = composeRule.onNodeWithTag("agent_option_agy").getBoundsInRoot()
        val claudeBounds = composeRule.onNodeWithTag("agent_option_claude").getBoundsInRoot()
        val codexBounds = composeRule.onNodeWithTag("agent_option_codex").getBoundsInRoot()
        val shellBounds = composeRule.onNodeWithTag("agent_option_shell").getBoundsInRoot()

        // 2-column layout: agy + claude on row 1, codex + shell on row 2
        assertTrue(
            "Expected claude ($claudeBounds) to be to the right of agy ($agyBounds)",
            claudeBounds.left >= agyBounds.right,
        )
        assertTrue(
            "Expected shell ($shellBounds) to be below claude ($claudeBounds)",
            shellBounds.top >= claudeBounds.bottom,
        )
        assertTrue(
            "Expected shell ($shellBounds) to be to the right of codex ($codexBounds)",
            shellBounds.left >= codexBounds.right,
        )
    }

    @Test
    fun selectingShellUpdatesTitleAndShowsCapabilityDescription() {
        composeRule.setContent {
            HerdrTheme {
                NewTabSheetContent(
                    agents = emptyList(),
                    defaultCwd = "/home/ned",
                    canCreateTab = true,
                    onLoadDirectories = { Result.success(FsListResponse(path = "/home/ned")) },
                    onCreate = { _, _, _, _, _, _ -> },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_tab_sheet_title").assertTextEquals("New Persistent Agent Tab")
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("new_tab_shell_description").fetchSemanticsNodes().size,
        )

        composeRule.onNodeWithTag("agent_option_shell").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("new_tab_sheet_title").assertTextEquals("New Persistent Shell Tab")
        composeRule.onNodeWithTag("new_tab_shell_description")
            .assertIsDisplayed()
            .assertTextContains("Persistent bash (keeps directory, env vars, and stdin)", substring = true)
            .assertTextContains("Stdin mode", substring = true)
    }

    @Test
    fun longWorkingDirectoryShowsFolderBasenameAndPlacesCursorAtEnd() {
        val longPath = "/home/nednguyen_google_com/projects/agents-term"
        val parentPath = "/home/nednguyen_google_com/projects"
        composeRule.setContent {
            HerdrTheme {
                NewTabSheetContent(
                    agents = defaultAgents,
                    defaultCwd = longPath,
                    canCreateTab = true,
                    onLoadDirectories = { requested ->
                        when (requested) {
                            parentPath -> Result.success(
                                FsListResponse(
                                    path = parentPath,
                                    parent = "/home/nednguyen_google_com",
                                    directories = listOf(FsDirectoryEntry("agents-term", longPath)),
                                ),
                            )

                            else -> Result.success(
                                FsListResponse(
                                    path = longPath,
                                    parent = parentPath,
                                    directories = listOf(
                                        FsDirectoryEntry("daemon-rs", "$longPath/daemon-rs"),
                                    ),
                                ),
                            )
                        }
                    },
                    onCreate = { _, _, _, _, _, _ -> },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_tab_cwd_basename")
            .assertIsDisplayed()
            .assertTextEquals("agents-term")

        val initialSelection = composeRule.onNodeWithTag("new_tab_cwd_input")
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(longPath.length), initialSelection)

        // Tapping parent folder updates both the basename pill and end-of-path selection
        composeRule.onNodeWithTag("parent_folder_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("new_tab_cwd_basename")
            .assertIsDisplayed()
            .assertTextEquals("projects")
        val parentSelection = composeRule.onNodeWithTag("new_tab_cwd_input")
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(parentPath.length), parentSelection)

        // Manually editing the path updates the basename pill live
        composeRule.onNodeWithTag("new_tab_cwd_input")
            .performTextReplacement("/var/log/custom-app")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_tab_cwd_basename")
            .assertIsDisplayed()
            .assertTextEquals("custom-app")
    }

    @Test
    fun lunaThemeAndOddAgentCountRenderCleanly() {
        composeRule.setContent {
            HerdrTheme(theme = AppThemeId.XP_LUNA) {
                Box(modifier = Modifier.width(360.dp)) {
                    NewTabSheetContent(
                        agents = defaultAgents.take(3),
                        defaultCwd = "/home/ned/work/repo",
                        canCreateTab = true,
                        onLoadDirectories = { Result.success(FsListResponse(path = "/home/ned/work/repo")) },
                        onCreate = { _, _, _, _, _, _ -> },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val agyBounds = composeRule.onNodeWithTag("agent_option_agy").getBoundsInRoot()
        val codexBounds = composeRule.onNodeWithTag("agent_option_codex").getBoundsInRoot()
        assertEquals(
            (agyBounds.right - agyBounds.left).value,
            (codexBounds.right - codexBounds.left).value,
            1f,
        )
        composeRule.onNodeWithTag("new_tab_cwd_basename").assertIsDisplayed().assertTextEquals("repo")
    }

    @Test
    fun createErrorDisplaysBannerAndIsCreatingDisablesButton() {
        val errorMsg = "Working directory does not exist: /bad/path"
        composeRule.setContent {
            HerdrTheme {
                NewTabSheetContent(
                    agents = defaultAgents,
                    defaultCwd = "/bad/path",
                    canCreateTab = true,
                    onLoadDirectories = { Result.success(FsListResponse(path = "/bad/path")) },
                    onCreate = { _, _, _, _, _, _ -> },
                    createError = errorMsg,
                    isCreating = true,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_tab_create_error")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(errorMsg, substring = true)

        composeRule.onNodeWithTag("create_tab_button")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextEquals("Creating…")
    }

    @Test
    fun newTabButtonLabelReturnsNeutralNewTabString() {
        var resolvedLabel = ""
        composeRule.setContent {
            HerdrTheme {
                resolvedLabel = newTabButtonLabel(canCreateTab = true)
            }
        }

        composeRule.waitForIdle()
        assertEquals("New Tab", resolvedLabel)
    }

    @Test
    fun resolveCwdBasenameHandlesEdgeCases() {
        assertEquals("agents-term", resolveCwdBasename("/home/nednguyen_google_com/projects/agents-term"))
        assertEquals("agents-term", resolveCwdBasename("/home/nednguyen_google_com/projects/agents-term/"))
        assertEquals("/", resolveCwdBasename("/"))
        assertEquals("/", resolveCwdBasename("///"))
        assertEquals("~", resolveCwdBasename("~"))
        assertEquals("~", resolveCwdBasename("~/"))
        assertEquals("", resolveCwdBasename("   "))
    }
}
