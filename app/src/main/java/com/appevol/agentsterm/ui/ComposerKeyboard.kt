package com.appevol.agentsterm.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.appevol.agentsterm.ui.theme.MonoFontFamily

/**
 * Defines keyboard and text-input styling behavior for the chat composer.
 *
 * Implementations encapsulate tab-specific input ergonomics (such as capitalization,
 * auto-correct, and input font family) so composer UI components remain agnostic of
 * the underlying tab type.
 */
@Immutable
interface ComposerKeyboard {
    val keyboardOptions: KeyboardOptions
    val fontFamily: FontFamily?

    fun textStyle(base: TextStyle): TextStyle = if (fontFamily != null) base.copy(fontFamily = fontFamily) else base

    companion object {
        fun forAgent(agent: String): ComposerKeyboard = when (agent) {
            "shell" -> ShellKeyboard
            else -> AgentKeyboard
        }
    }
}

/** Standard prose keyboard for coding-agent tabs. */
@Immutable
object AgentKeyboard : ComposerKeyboard {
    override val keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrectEnabled = true,
    )
    override val fontFamily: FontFamily? = null
}

/** Command-line keyboard for shell tabs: no auto-capitalization, no auto-correct, monospace text. */
@Immutable
object ShellKeyboard : ComposerKeyboard {
    override val keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Text,
    )
    override val fontFamily: FontFamily = MonoFontFamily
}
