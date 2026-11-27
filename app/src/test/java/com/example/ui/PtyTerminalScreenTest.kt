package com.example.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
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
import org.robolectric.RuntimeEnvironment
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
                    onSpecialKey = { key, mods, _, _ -> sentKeys.add(key to mods) },
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

    @Test
    fun imeEnterRoutesToGhosttyEnterAndComposingEmojiUsesCodepointCount() {
        val view = TerminalImeInputView(RuntimeEnvironment.getApplication())
        val committed = mutableListOf<String>()
        val specialKeys = mutableListOf<Int>()
        view.onCommitText = { committed.add(it) }
        view.onSpecialKey = { key, _, _, _ -> specialKeys.add(key) }

        val editorInfo = EditorInfo()
        val ic = view.onCreateInputConnection(editorInfo)

        // API 26+ must disable personalized keyboard learning.
        assertTrue(
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )

        // Newline commit routes to GhosttyKey.ENTER instead of raw LF.
        ic.commitText("\n", 1)
        assertEquals(emptyList<String>(), committed)
        assertEquals(listOf(GhosttyKey.ENTER), specialKeys)

        // Mixed text + CRLF commits text then GhosttyKey.ENTER.
        specialKeys.clear()
        ic.commitText("ls\r\n", 1)
        assertEquals(listOf("ls"), committed)
        assertEquals(listOf(GhosttyKey.ENTER), specialKeys)

        // performEditorAction routes to GhosttyKey.ENTER.
        specialKeys.clear()
        assertTrue(ic.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertEquals(listOf(GhosttyKey.ENTER), specialKeys)

        // Composing text with surrogate-pair emoji diffs and backspaces by codepoint count.
        committed.clear()
        specialKeys.clear()
        ic.setComposingText("a\uD83D\uDE00", 1)
        assertEquals(listOf("a\uD83D\uDE00"), committed)
        assertEquals(emptyList<Int>(), specialKeys)

        committed.clear()
        ic.setComposingText("a\uD83D\uDE02", 1)
        assertEquals(listOf(GhosttyKey.BACKSPACE), specialKeys)
        assertEquals(listOf("\uD83D\uDE02"), committed)

        committed.clear()
        specialKeys.clear()
        ic.setComposingText("", 1)
        assertEquals(listOf(GhosttyKey.BACKSPACE, GhosttyKey.BACKSPACE), specialKeys)
        assertEquals(emptyList<String>(), committed)

        // deleteSurroundingText handles (0, 0) as no-op and afterLength as DELETE.
        specialKeys.clear()
        ic.deleteSurroundingText(0, 0)
        assertEquals(emptyList<Int>(), specialKeys)
        ic.deleteSurroundingText(0, 1)
        assertEquals(listOf(GhosttyKey.DELETE), specialKeys)

        // deleteSurroundingTextInCodePoints emits exact counts.
        specialKeys.clear()
        ic.deleteSurroundingTextInCodePoints(2, 1)
        assertEquals(
            listOf(GhosttyKey.BACKSPACE, GhosttyKey.BACKSPACE, GhosttyKey.DELETE),
            specialKeys,
        )

        // Handled ACTION_DOWN consumes its matching ACTION_UP without double dispatch.
        specialKeys.clear()
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(GhosttyKey.ENTER), specialKeys)
    }
}
