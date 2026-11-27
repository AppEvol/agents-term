package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.terminal.GhosttyCellWide
import com.example.terminal.GhosttyCursorStyle
import com.example.terminal.GhosttyKey
import com.example.terminal.GhosttyMods
import com.example.terminal.PtyConnectionState
import com.example.terminal.PtyTabUiState
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalCursor
import com.example.terminal.TerminalFrame
import com.example.terminal.TerminalRow
import com.example.terminal.TerminalScrollbar
import com.example.terminal.TerminalThemePalette
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
class PtyTerminalScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleFrame(): TerminalFrame {
        val row0 = TerminalRow(
            "user@vm:~$ ls".map { ch ->
                TerminalCell(
                    text = ch.toString(),
                    fgArgb = 0xFFE6EDF3.toInt(),
                    bgArgb = 0,
                    styleFlags = 0,
                    wide = GhosttyCellWide.NARROW,
                )
            },
        )
        return TerminalFrame(
            cols = 40,
            rows = 8,
            defaultFgArgb = 0xFFE6EDF3.toInt(),
            defaultBgArgb = 0xFF0D1117.toInt(),
            cursorArgb = 0xFF58A6FF.toInt(),
            cursor = TerminalCursor(
                visible = true,
                inViewport = true,
                x = 13,
                y = 0,
                style = GhosttyCursorStyle.BLOCK,
                blinking = false,
                passwordInput = false,
                wideTail = false,
            ),
            isAlternateScreen = false,
            bellRung = false,
            title = "bash",
            scrollbar = TerminalScrollbar(total = 8, offset = 0, len = 8),
            grid = listOf(row0) + List(7) { TerminalRow.EMPTY },
            revision = 1L,
        )
    }

    @Test
    fun toolbarControlsDispatchExpectedKeysAndCtrlLatch() {
        val sentKeys = mutableListOf<Pair<Int, Int>>()
        var ctrlLatched = false
        var restartCount = 0

        composeRule.setContent {
            HerdrTheme {
                PtyTerminalContent(
                    uiState = PtyTabUiState(
                        connectionState = PtyConnectionState.EXITED,
                        frame = sampleFrame(),
                        exitCode = 0,
                    ),
                    palette = TerminalThemePalette.Dark,
                    fontSizeSp = 13f,
                    ctrlLatched = ctrlLatched,
                    altLatched = false,
                    onToggleCtrl = { ctrlLatched = !ctrlLatched },
                    onToggleAlt = {},
                    onCommitText = {},
                    onSpecialKey = { key, mods, _ -> sentKeys.add(key to mods) },
                    onToolbarKey = { key, mods, _ -> sentKeys.add(key to mods) },
                    onResize = { _, _, _, _ -> },
                    onScrollLines = {},
                    onScrollToBottom = {},
                    onReconnect = {},
                    onRestart = { restartCount++ },
                    onPaste = {},
                    onCopyScreen = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onToggleKeyboard = {},
                    onRequestKeyboard = {},
                    onInputViewBound = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pty_terminal_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("pty_key_tab").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pty_key_ctrl_c").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pty_key_esc").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pty_key_up").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pty_key_ctrl").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pty_restart_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(ctrlLatched)
        assertEquals(1, restartCount)
        assertEquals(
            listOf(
                GhosttyKey.TAB to GhosttyMods.NONE,
                GhosttyKey.C to GhosttyMods.CTRL,
                GhosttyKey.ESCAPE to GhosttyMods.NONE,
                GhosttyKey.ARROW_UP to GhosttyMods.NONE,
            ),
            sentKeys,
        )
    }
}
