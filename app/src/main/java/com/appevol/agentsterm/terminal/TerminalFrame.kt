package com.appevol.agentsterm.terminal

import androidx.compose.runtime.Immutable

@Immutable
data class TerminalCell(
    val text: String,
    val fgArgb: Int,
    /** `0` means default terminal background (no explicit cell background fill). */
    val bgArgb: Int,
    val styleFlags: Int,
    val wide: Int,
) {
    val isBold: Boolean get() = (styleFlags and GhosttyStyleFlags.BOLD) != 0
    val isItalic: Boolean get() = (styleFlags and GhosttyStyleFlags.ITALIC) != 0
    val isFaint: Boolean get() = (styleFlags and GhosttyStyleFlags.FAINT) != 0
    val isInvisible: Boolean get() = (styleFlags and GhosttyStyleFlags.INVISIBLE) != 0
    val isStrikethrough: Boolean get() = (styleFlags and GhosttyStyleFlags.STRIKETHROUGH) != 0
    val isOverline: Boolean get() = (styleFlags and GhosttyStyleFlags.OVERLINE) != 0
    val hasUnderline: Boolean get() = GhosttyStyleFlags.underlineStyle(styleFlags) != 0
}

@Immutable
data class TerminalRow(
    val cells: List<TerminalCell>,
) {
    /** Plain-text rendering of this row (trailing spaces trimmed), useful for copy/accessibility/tests. */
    fun toPlainText(): String {
        if (cells.isEmpty()) return ""
        val sb = StringBuilder(cells.size)
        for (cell in cells) {
            if (cell.wide == GhosttyCellWide.SPACER_TAIL || cell.wide == GhosttyCellWide.SPACER_HEAD) {
                continue
            }
            if (cell.text.isEmpty()) {
                sb.append(' ')
            } else {
                sb.append(cell.text)
            }
        }
        return sb.toString().trimEnd()
    }

    companion object {
        val EMPTY = TerminalRow(emptyList())
    }
}

@Immutable
data class TerminalCursor(
    val visible: Boolean,
    val inViewport: Boolean,
    val x: Int,
    val y: Int,
    val style: Int,
    val blinking: Boolean,
    val passwordInput: Boolean,
    val wideTail: Boolean,
)

@Immutable
data class TerminalScrollbar(
    val total: Int,
    val offset: Int,
    val len: Int,
) {
    val scrollbackRows: Int get() = (total - len).coerceAtLeast(0)
    val isAtBottom: Boolean get() = offset + len >= total
}

@Immutable
data class TerminalFrame(
    val cols: Int,
    val rows: Int,
    val defaultFgArgb: Int,
    val defaultBgArgb: Int,
    val cursorArgb: Int,
    val cursor: TerminalCursor,
    val isAlternateScreen: Boolean,
    val bellRung: Boolean,
    val title: String?,
    val scrollbar: TerminalScrollbar,
    val grid: List<TerminalRow>,
    val revision: Long,
) {
    /** Plain text of the visible viewport rows joined by newlines (trailing blank lines trimmed). */
    fun toPlainText(): String = grid
        .map { it.toPlainText() }
        .dropLastWhile { it.isEmpty() }
        .joinToString("\n")
}

/** Terminal color palette passed to Ghostty so the PTY canvas harmonizes with AgentsTerm themes. */
@Immutable
data class TerminalThemePalette(
    val foregroundRgb: Int,
    val backgroundRgb: Int,
    val cursorRgb: Int,
    val ansi16Rgb: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalThemePalette) return false
        return foregroundRgb == other.foregroundRgb &&
            backgroundRgb == other.backgroundRgb &&
            cursorRgb == other.cursorRgb &&
            ansi16Rgb.contentEquals(other.ansi16Rgb)
    }

    override fun hashCode(): Int {
        var result = foregroundRgb
        result = 31 * result + backgroundRgb
        result = 31 * result + cursorRgb
        result = 31 * result + ansi16Rgb.contentHashCode()
        return result
    }

    companion object {
        val Dark = TerminalThemePalette(
            foregroundRgb = 0xE6EDF3,
            backgroundRgb = 0x0D1117,
            cursorRgb = 0x58A6FF,
            ansi16Rgb = intArrayOf(
                0x484F58, 0xFF7B72, 0x3FB950, 0xD29922,
                0x58A6FF, 0xBC8CFF, 0x39C5CF, 0xB1BAC4,
                0x6E7681, 0xFFA198, 0x56D364, 0xE3B341,
                0x79C0FF, 0xD2A8FF, 0x56D4DD, 0xF0F6FC,
            ),
        )

        val Light = TerminalThemePalette(
            foregroundRgb = 0x1F2328,
            backgroundRgb = 0xFFFFFF,
            cursorRgb = 0x0969DA,
            ansi16Rgb = intArrayOf(
                0x24292F, 0xCF222E, 0x116329, 0x9A6700,
                0x0969DA, 0x8250DF, 0x1B7C83, 0x6E7781,
                0x57606A, 0xA40E26, 0x1A7F37, 0xBF8700,
                0x218BFF, 0xA475F9, 0x3192AA, 0x8C959F,
            ),
        )
    }
}
