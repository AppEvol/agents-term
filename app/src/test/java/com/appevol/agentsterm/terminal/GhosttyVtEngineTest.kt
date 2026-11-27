package com.appevol.agentsterm.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real `libghostty-vt` 1.0.2 native library through [GhosttyTerminalEngine]
 * on the host JVM: VT parsing, styled cells, dirty-row batching, WRITE_PTY reply suppression
 * on replay, mode-synchronized key encoding, bracketed paste, alternate screen, and snapshots.
 */
class GhosttyVtEngineTest {

    @Test
    fun rendersStyledCellsAndBatchesDirtyRows() {
        GhosttyTerminalEngine(initialCols = 40, initialRows = 6).use { engine ->
            engine.setThemeColors(TerminalThemePalette.Dark)

            val payload = "Hello, \u001b[1;38;2;255;128;0mworld\u001b[0m!\r\n" +
                "\u001b[4munderlined\u001b[0m \u001b[7minverse\u001b[0m"
            engine.writeVt(payload.encodeToByteArray())

            val frame = engine.captureFrame(forceFull = false)
            assertNotNull("Expected initial dirty frame", frame)
            assertEquals(40, frame!!.cols)
            assertEquals(6, frame.rows)
            assertEquals("Hello, world!", frame.grid[0].toPlainText())
            assertEquals("underlined inverse", frame.grid[1].toPlainText())

            // Verify bold + 24-bit RGB orange on 'w' at col 7 of row 0.
            val wCell = frame.grid[0].cells[7]
            assertEquals("w", wCell.text)
            assertTrue("Expected bold style", wCell.isBold)
            assertEquals(0xFFFF8000.toInt(), wCell.fgArgb)

            // Verify underline on row 1 col 0.
            assertTrue(frame.grid[1].cells[0].hasUnderline)

            // Verify inverse swapped default fg into bg on 'i' at row 1 col 11.
            val invCell = frame.grid[1].cells[11]
            assertEquals("i", invCell.text)
            assertEquals(frame.defaultFgArgb, invCell.bgArgb)
            assertEquals(frame.defaultBgArgb, invCell.fgArgb)

            // Clean state returns null when forceFull is false.
            assertNull(engine.captureFrame(forceFull = false))

            // Writing to a single row updates only that row while preserving row 0.
            engine.writeVt("\r\nline 3".encodeToByteArray())
            val partial = engine.captureFrame(forceFull = false)
            assertNotNull(partial)
            assertEquals("Hello, world!", partial!!.grid[0].toPlainText())
            assertEquals("line 3", partial.grid[2].toPlainText())
        }
    }

    @Test
    fun writePtyRepliesWorkLiveAndAreSuppressedDuringReplay() {
        GhosttyTerminalEngine(initialCols = 80, initialRows = 24).use { engine ->
            // DSR cursor position query (CSI 6 n) generates a CSI row;col R reply.
            val dsr = "\u001b[6n".encodeToByteArray()

            val suppressed = engine.writeVt(dsr, suppressReplies = true)
            assertNull("Replaying historical VT output must suppress outbound WRITE_PTY replies", suppressed)

            val live = engine.writeVt(dsr, suppressReplies = false)
            assertNotNull("Live DSR query must produce a WRITE_PTY reply", live)
            assertEquals("\u001b[1;1R", live!!.decodeToString())
        }
    }

    @Test
    fun keyEncoderSynchronizesWithTerminalModes() {
        GhosttyTerminalEngine(initialCols = 80, initialRows = 24).use { engine ->
            // Normal cursor key mode -> CSI A.
            val normalUp = engine.encodeKey(GhosttyKey.ARROW_UP)
            assertEquals("\u001b[A", normalUp?.decodeToString())

            // Application cursor keys (DECCKM, CSI ? 1 h) -> SS3 A (used by less/vim).
            engine.writeVt("\u001b[?1h".encodeToByteArray())
            val appUp = engine.encodeKey(GhosttyKey.ARROW_UP)
            assertEquals("\u001bOA", appUp?.decodeToString())

            // Ctrl+C -> ETX (0x03), Tab -> 0x09, Esc -> 0x1b, Enter -> CR (0x0D).
            assertArrayEquals(
                byteArrayOf(0x03),
                engine.encodeKey(GhosttyKey.C, mods = GhosttyMods.CTRL, utf8 = "c", unshiftedCodepoint = 'c'.code),
            )
            assertArrayEquals(byteArrayOf(0x09), engine.encodeKey(GhosttyKey.TAB))
            assertArrayEquals(byteArrayOf(0x1B), engine.encodeKey(GhosttyKey.ESCAPE))
            assertArrayEquals(byteArrayOf(0x0D), engine.encodeKey(GhosttyKey.ENTER))

            // Ctrl+@ -> NUL (0x00), Ctrl+^ -> RS (0x1E), Ctrl+_ -> US (0x1F), Ctrl+Shift+- -> US (0x1F).
            assertEquals(GhosttyKey.DIGIT_2, GhosttyKey.fromAsciiChar('@'))
            assertEquals(GhosttyKey.DIGIT_6, GhosttyKey.fromAsciiChar('^'))
            assertEquals(GhosttyKey.MINUS, GhosttyKey.fromAsciiChar('_'))
            assertArrayEquals(
                byteArrayOf(0x00),
                engine.encodeKey(
                    GhosttyKey.fromAsciiChar('@'),
                    mods = GhosttyMods.CTRL,
                    utf8 = "@",
                    unshiftedCodepoint = '@'.code,
                ),
            )
            assertArrayEquals(
                byteArrayOf(0x1E),
                engine.encodeKey(
                    GhosttyKey.fromAsciiChar('^'),
                    mods = GhosttyMods.CTRL,
                    utf8 = "^",
                    unshiftedCodepoint = '^'.code,
                ),
            )
            assertArrayEquals(
                byteArrayOf(0x1F),
                engine.encodeKey(
                    GhosttyKey.fromAsciiChar('_'),
                    mods = GhosttyMods.CTRL,
                    utf8 = "_",
                    unshiftedCodepoint = '_'.code,
                ),
            )
            assertArrayEquals(
                byteArrayOf(0x1F),
                engine.encodeKey(
                    GhosttyKey.MINUS,
                    mods = GhosttyMods.CTRL or GhosttyMods.SHIFT,
                    utf8 = "_",
                    unshiftedCodepoint = '-'.code,
                ),
            )
        }
    }

    @Test
    fun jniBoundsChecksRejectInvalidResizeAndWriteVtArgs() {
        GhosttyTerminalEngine(initialCols = 40, initialRows = 10).use { engine ->
            assertFalse(engine.resize(cols = 0, rows = 10))
            assertFalse(engine.resize(cols = 70_000, rows = 10))
            assertFalse(engine.resize(cols = 40, rows = 70_000))
            // Overflowing offset + length must be rejected safely without crashing.
            assertNull(engine.writeVt(byteArrayOf(0x41, 0x42), offset = Int.MAX_VALUE, length = 2))
        }
    }

    @Test
    fun pasteApiEnforcesSafetyAndBracketedPaste() {
        GhosttyTerminalEngine(initialCols = 80, initialRows = 24).use { engine ->
            assertTrue(engine.isPasteSafe("hello world"))
            assertFalse(engine.isPasteSafe("echo hi\n"))

            // Safe plain paste writes bytes directly.
            assertEquals("hello", engine.encodePaste("hello", allowUnsafe = false)?.decodeToString())

            // Multiline paste outside bracketed paste is rejected unless confirmed.
            assertNull(engine.encodePaste("line1\nline2", allowUnsafe = false))
            assertNotNull(engine.encodePaste("line1\nline2", allowUnsafe = true))

            // With bracketed paste (mode 2004), multiline paste is framed and allowed safely.
            engine.writeVt("\u001b[?2004h".encodeToByteArray())
            val bracketed = engine.encodePaste("line1\nline2", allowUnsafe = false)?.decodeToString()
            assertEquals("\u001b[200~line1\nline2\u001b[201~", bracketed)
        }
    }

    @Test
    fun alternateScreenTitleScrollbackAndSnapshotRoundTrip() {
        GhosttyTerminalEngine(initialCols = 40, initialRows = 4).use { engine ->
            engine.writeVt("\u001b]2;My Shell\u0007".encodeToByteArray())
            for (i in 1..10) {
                engine.writeVt("row-$i\r\n".encodeToByteArray())
            }
            val primaryFrame = engine.captureFrame(forceFull = true)!!
            assertFalse(primaryFrame.isAlternateScreen)
            assertEquals("My Shell", primaryFrame.title)
            assertTrue("Expected scrollback rows", primaryFrame.scrollbar.scrollbackRows > 0)
            assertTrue(primaryFrame.scrollbar.isAtBottom)

            // Scroll up into history, then return to bottom.
            engine.scrollViewportDelta(-4)
            val scrolled = engine.captureFrame(forceFull = false)!!
            assertFalse(scrolled.scrollbar.isAtBottom)
            engine.scrollViewportBottom()
            val atBottom = engine.captureFrame(forceFull = false)!!
            assertTrue(atBottom.scrollbar.isAtBottom)

            // Enter alternate screen (like less), verify flag & content, snapshot round-trip.
            engine.writeVt("\u001b[?1049hALT SCREEN CONTENT".encodeToByteArray())
            val altFrame = engine.captureFrame(forceFull = true)!!
            assertTrue(altFrame.isAlternateScreen)
            assertTrue(altFrame.toPlainText().contains("ALT SCREEN CONTENT"))

            val snap = engine.snapshotEncode()
            assertNotNull(snap)
            assertTrue(snap!!.isNotEmpty())

            GhosttyTerminalEngine(initialCols = 20, initialRows = 2).use { restored ->
                assertTrue(restored.snapshotDecode(snap))
                val restoredFrame = restored.captureFrame(forceFull = true)!!
                assertTrue(restoredFrame.isAlternateScreen)
                assertTrue(restoredFrame.toPlainText().contains("ALT SCREEN CONTENT"))
                assertEquals(40, restoredFrame.cols)
                assertEquals(4, restoredFrame.rows)

                // Exit alternate screen returns to the original primary screen.
                restored.writeVt("\u001b[?1049l".encodeToByteArray())
                val backToPrimary = restored.captureFrame(forceFull = true)!!
                assertFalse(backToPrimary.isAlternateScreen)
                assertTrue(backToPrimary.toPlainText().contains("row-10"))
            }
        }
    }
}
