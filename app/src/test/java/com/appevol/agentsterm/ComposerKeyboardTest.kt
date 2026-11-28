package com.appevol.agentsterm

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.ui.AgentKeyboard
import com.appevol.agentsterm.ui.ChatWorkspace
import com.appevol.agentsterm.ui.ComposerKeyboard
import com.appevol.agentsterm.ui.ShellKeyboard
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import com.appevol.agentsterm.ui.theme.BodyFontFamily
import com.appevol.agentsterm.ui.theme.MonoFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ComposerKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val agentTab = TabRecord(
        id = "tab_agent",
        title = "Claude · repo",
        agent = "claude",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    private val shellTab = TabRecord(
        id = "tab_shell",
        title = "Shell · repo",
        agent = "shell",
        cwd = "/home/ned/projects/agents-term",
        status = "idle",
    )

    @Test
    fun forAgentSelectsShellOrAgentKeyboard() {
        assertSame(ShellKeyboard, ComposerKeyboard.forAgent("shell"))
        assertSame(AgentKeyboard, ComposerKeyboard.forAgent("agy"))
        assertSame(AgentKeyboard, ComposerKeyboard.forAgent("claude"))
        assertSame(AgentKeyboard, ComposerKeyboard.forAgent("codex"))
        assertSame(AgentKeyboard, ComposerKeyboard.forAgent("unknown"))
    }

    @Test
    fun agentAndShellKeyboardOptionsAndTextStylesMatchExpectations() {
        val baseStyle = TextStyle(fontFamily = BodyFontFamily, fontSize = 15.sp, color = Color.White)

        assertEquals(KeyboardCapitalization.Sentences, AgentKeyboard.keyboardOptions.capitalization)
        assertEquals(true, AgentKeyboard.keyboardOptions.autoCorrectEnabled)
        assertNull(AgentKeyboard.fontFamily)
        assertEquals(BodyFontFamily, AgentKeyboard.textStyle(baseStyle).fontFamily)

        assertEquals(KeyboardCapitalization.None, ShellKeyboard.keyboardOptions.capitalization)
        assertEquals(false, ShellKeyboard.keyboardOptions.autoCorrectEnabled)
        assertEquals(MonoFontFamily, ShellKeyboard.fontFamily)

        val shellStyle = ShellKeyboard.textStyle(baseStyle)
        assertEquals(MonoFontFamily, shellStyle.fontFamily)
        assertEquals(baseStyle.fontSize, shellStyle.fontSize)
        assertEquals(baseStyle.color, shellStyle.color)
    }

    @Test
    fun shellTabDisablesAutoCapAndAutoCorrectAndUsesMonospaceInput() {
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            AgentsTermTheme {
                ChatWorkspace(
                    tab = shellTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer_input").performClick()
        composeRule.onNodeWithTag("composer_input").performTextInput("git status")
        composeRule.waitForIdle()

        val editorInfo = createEditorInfo(requireNotNull(hostView))
        assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)

        val layoutResult = composerTextLayoutResult()
        assertEquals(MonoFontFamily, layoutResult.layoutInput.style.fontFamily)
    }

    @Test
    fun agentTabKeepsSentenceCapitalizationAutoCorrectAndProseFont() {
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            AgentsTermTheme {
                ChatWorkspace(
                    tab = agentTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer_input").performClick()
        composeRule.onNodeWithTag("composer_input").performTextInput("hello world")
        composeRule.waitForIdle()

        val editorInfo = createEditorInfo(requireNotNull(hostView))
        assertTrue(
            "Agent composer should keep sentence capitalization enabled",
            (editorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
        )
        assertTrue(
            "Agent composer should keep auto-correct enabled",
            (editorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0,
        )

        val layoutResult = composerTextLayoutResult()
        assertFalse(
            "Agent composer should not use monospace font",
            layoutResult.layoutInput.style.fontFamily == MonoFontFamily,
        )
    }

    @Test
    fun switchingBetweenAgentAndShellTabsUpdatesImeAndFont() {
        var hostView: View? = null
        var currentTab by mutableStateOf(agentTab)

        composeRule.setContent {
            hostView = LocalView.current
            AgentsTermTheme {
                ChatWorkspace(
                    tab = currentTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer_input").performClick()
        composeRule.onNodeWithTag("composer_input").performTextInput("Check tests")
        composeRule.waitForIdle()

        val agentEditorInfo = createEditorInfo(requireNotNull(hostView))
        assertTrue((agentEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
        assertFalse(composerTextLayoutResult().layoutInput.style.fontFamily == MonoFontFamily)

        currentTab = shellTab
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer_input").performClick()
        composeRule.onNodeWithTag("composer_input").performTextInput("ls -la")
        composeRule.waitForIdle()

        val shellEditorInfo = createEditorInfo(requireNotNull(hostView))
        assertEquals(0, shellEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(0, shellEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(MonoFontFamily, composerTextLayoutResult().layoutInput.style.fontFamily)
    }

    @Test
    fun customComposerKeyboardImplementationOverridesTabDefault() {
        var hostView: View? = null
        val customKeyboard = object : ComposerKeyboard {
            override val keyboardOptions: KeyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
            )
            override val fontFamily: FontFamily = MonoFontFamily
        }

        composeRule.setContent {
            hostView = LocalView.current
            AgentsTermTheme {
                ChatWorkspace(
                    tab = agentTab,
                    messages = emptyList(),
                    isLoading = false,
                    isConnected = true,
                    onSendMessage = {},
                    onInterrupt = {},
                    onRetryFullAccess = {},
                    keyboard = customKeyboard,
                )
            }
        }

        composeRule.onNodeWithTag("composer_input").performClick()
        composeRule.onNodeWithTag("composer_input").performTextInput("ABC")
        composeRule.waitForIdle()

        val editorInfo = createEditorInfo(requireNotNull(hostView))
        assertTrue((editorInfo.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
        assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(MonoFontFamily, composerTextLayoutResult().layoutInput.style.fontFamily)
    }

    private fun createEditorInfo(view: View): EditorInfo {
        val editorInfo = EditorInfo()
        val connection = composeRule.runOnUiThread {
            view.onCreateInputConnection(editorInfo)
        }
        assertNotNull("Focused composer should provide an InputConnection", connection)
        return editorInfo
    }

    private fun composerTextLayoutResult(): TextLayoutResult {
        val node = composeRule.onNodeWithTag("composer_input").fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult]
        assertTrue(
            "Composer node should expose GetTextLayoutResult",
            action.action?.invoke(results) == true,
        )
        return results.first()
    }
}
