package com.appevol.agentsterm.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Ghostty physical key constants matching `ghostty/vt/key/event.h`. */
object GhosttyKey {
    const val UNIDENTIFIED = 0
    const val BACKQUOTE = 1
    const val BACKSLASH = 2
    const val BRACKET_LEFT = 3
    const val BRACKET_RIGHT = 4
    const val COMMA = 5
    const val DIGIT_0 = 6
    const val DIGIT_1 = 7
    const val DIGIT_2 = 8
    const val DIGIT_3 = 9
    const val DIGIT_4 = 10
    const val DIGIT_5 = 11
    const val DIGIT_6 = 12
    const val DIGIT_7 = 13
    const val DIGIT_8 = 14
    const val DIGIT_9 = 15
    const val EQUAL = 16
    const val A = 20
    const val B = 21
    const val C = 22
    const val D = 23
    const val E = 24
    const val F = 25
    const val G = 26
    const val H = 27
    const val I = 28
    const val J = 29
    const val K = 30
    const val L = 31
    const val M = 32
    const val N = 33
    const val O = 34
    const val P = 35
    const val Q = 36
    const val R = 37
    const val S = 38
    const val T = 39
    const val U = 40
    const val V = 41
    const val W = 42
    const val X = 43
    const val Y = 44
    const val Z = 45
    const val MINUS = 46
    const val PERIOD = 47
    const val QUOTE = 48
    const val SEMICOLON = 49
    const val SLASH = 50
    const val BACKSPACE = 53
    const val ENTER = 58
    const val SPACE = 63
    const val TAB = 64
    const val DELETE = 68
    const val END = 69
    const val HOME = 71
    const val INSERT = 72
    const val PAGE_DOWN = 73
    const val PAGE_UP = 74
    const val ARROW_DOWN = 75
    const val ARROW_LEFT = 76
    const val ARROW_RIGHT = 77
    const val ARROW_UP = 78
    const val ESCAPE = 120

    /** Maps an ASCII character to its physical [GhosttyKey] for Ctrl/Alt chord encoding. */
    fun fromAsciiChar(ch: Char): Int = when (ch.lowercaseChar()) {
        in 'a'..'z' -> A + (ch.lowercaseChar() - 'a')
        in '0'..'9' -> DIGIT_0 + (ch - '0')
        '!' -> DIGIT_1
        '@' -> DIGIT_2
        '#' -> DIGIT_3
        '$' -> DIGIT_4
        '%' -> DIGIT_5
        '^' -> DIGIT_6
        '&' -> DIGIT_7
        '*' -> DIGIT_8
        '(' -> DIGIT_9
        ')' -> DIGIT_0
        '`', '~' -> BACKQUOTE
        '\\', '|' -> BACKSLASH
        '[', '{' -> BRACKET_LEFT
        ']', '}' -> BRACKET_RIGHT
        ',', '<' -> COMMA
        '=', '+' -> EQUAL
        '-', '_' -> MINUS
        '.', '>' -> PERIOD
        '\'', '"' -> QUOTE
        ';', ':' -> SEMICOLON
        '/', '?' -> SLASH
        ' ' -> SPACE
        '\t' -> TAB
        '\n', '\r' -> ENTER
        else -> UNIDENTIFIED
    }
}

/** Ghostty modifier bitmask matching `ghostty/vt/key/event.h`. */
object GhosttyMods {
    const val NONE = 0
    const val SHIFT = 1 shl 0
    const val CTRL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
}

/** Ghostty key action matching `ghostty/vt/key/event.h`. */
object GhosttyKeyAction {
    const val RELEASE = 0
    const val PRESS = 1
    const val REPEAT = 2
}

/** Ghostty style flag masks matching `ghostty/vt/style.h`. */
object GhosttyStyleFlags {
    const val BOLD = 1 shl 0
    const val ITALIC = 1 shl 1
    const val FAINT = 1 shl 2
    const val BLINK = 1 shl 3
    const val INVERSE = 1 shl 4
    const val INVISIBLE = 1 shl 5
    const val STRIKETHROUGH = 1 shl 6
    const val OVERLINE = 1 shl 7
    const val UNDERLINE_SHIFT = 8
    const val UNDERLINE_MASK = 0x7 shl UNDERLINE_SHIFT

    fun underlineStyle(flags: Int): Int = (flags and UNDERLINE_MASK) ushr UNDERLINE_SHIFT
}

/** Ghostty cursor visual styles matching `ghostty/vt/types.h`. */
object GhosttyCursorStyle {
    const val BAR = 0
    const val BLOCK = 1
    const val UNDERLINE = 2
    const val BLOCK_HOLLOW = 3
}

/** Ghostty cell width tags matching `ghostty/vt/screen.h`. */
object GhosttyCellWide {
    const val NARROW = 0
    const val WIDE = 1
    const val SPACER_TAIL = 2
    const val SPACER_HEAD = 3
}

/** Thin JNI declaration class bound to `app/src/main/jni/ghostty_vt_jni.c`. */
object GhosttyVtNative {
    private val loaded = AtomicBoolean(false)

    fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            System.loadLibrary("ghostty_vt_jni")
            loaded.set(true)
        }
    }

    @JvmStatic
    external fun nativeCreate(cols: Int, rows: Int, maxScrollbackBytes: Long): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): Boolean

    @JvmStatic
    external fun nativeSetColors(
        handle: Long,
        fgRgb: Int,
        bgRgb: Int,
        cursorRgb: Int,
        paletteRgb: IntArray?,
    )

    @JvmStatic
    external fun nativeWriteVt(
        handle: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        suppressReplies: Boolean,
    ): ByteArray?

    // JNI entry point mirrors the flat C parameter list to avoid allocating a parameter struct per keystroke.
    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeEncodeKey(
        handle: Long,
        key: Int,
        mods: Int,
        action: Int,
        utf8Bytes: ByteArray?,
        unshiftedCodepoint: Int,
    ): ByteArray?

    @JvmStatic
    external fun nativeIsPasteSafe(data: ByteArray): Boolean

    @JvmStatic
    external fun nativeEncodePaste(
        handle: Long,
        data: ByteArray,
        allowUnsafe: Boolean,
    ): ByteArray?

    @JvmStatic
    external fun nativeScrollViewport(handle: Long, tag: Int, delta: Int)

    @JvmStatic
    external fun nativeGetTitleUtf8(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeSnapshotEncode(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeSnapshotDecode(handle: Long, data: ByteArray): Boolean

    @JvmStatic
    external fun nativeCaptureFrame(handle: Long, forceFull: Boolean): ByteArray?
}

/**
 * Thread-safe, `AutoCloseable` wrapper around a native `libghostty-vt` terminal instance.
 *
 * Maintains an incremental row cache so partial dirty updates only decode modified rows.
 */
class GhosttyTerminalEngine(
    initialCols: Int = 80,
    initialRows: Int = 24,
    maxScrollbackBytes: Long = DEFAULT_SCROLLBACK_BYTES,
) : AutoCloseable {
    private val lock = Any()
    private var handle: Long
    private var cachedCols = initialCols
    private var cachedRows = initialRows
    private var cachedGrid: Array<TerminalRow> = Array(initialRows) { TerminalRow.EMPTY }
    private var cachedTitle: String? = null
    private var lastPalette: TerminalThemePalette? = null
    private var frameRevision = 0L

    init {
        GhosttyVtNative.ensureLoaded()
        val created = GhosttyVtNative.nativeCreate(initialCols, initialRows, maxScrollbackBytes)
        require(created != 0L) { "Failed to create libghostty-vt terminal (${initialCols}x$initialRows)" }
        handle = created
    }

    fun writeVt(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        suppressReplies: Boolean = false,
    ): ByteArray? = synchronized(lock) {
        if (handle == 0L || length <= 0) return null
        GhosttyVtNative.nativeWriteVt(handle, data, offset, length, suppressReplies)
    }

    fun resize(
        cols: Int,
        rows: Int,
        cellWidthPx: Int = 10,
        cellHeightPx: Int = 20,
    ): Boolean = synchronized(lock) {
        if (handle == 0L) return false
        GhosttyVtNative.nativeResize(handle, cols, rows, cellWidthPx, cellHeightPx)
    }

    fun reset() {
        synchronized(lock) {
            if (handle == 0L) return
            GhosttyVtNative.nativeReset(handle)
            cachedTitle = null
        }
    }

    fun setThemeColors(palette: TerminalThemePalette) {
        synchronized(lock) {
            if (handle == 0L) return
            lastPalette = palette
            GhosttyVtNative.nativeSetColors(
                handle,
                palette.foregroundRgb,
                palette.backgroundRgb,
                palette.cursorRgb,
                palette.ansi16Rgb,
            )
        }
    }

    fun encodeKey(
        key: Int,
        mods: Int = GhosttyMods.NONE,
        action: Int = GhosttyKeyAction.PRESS,
        utf8: String? = null,
        unshiftedCodepoint: Int = 0,
    ): ByteArray? = synchronized(lock) {
        if (handle == 0L) return null
        val utf8Bytes = utf8?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
        GhosttyVtNative.nativeEncodeKey(handle, key, mods, action, utf8Bytes, unshiftedCodepoint)
    }

    fun isPasteSafe(text: String): Boolean {
        GhosttyVtNative.ensureLoaded()
        return GhosttyVtNative.nativeIsPasteSafe(text.encodeToByteArray())
    }

    fun encodePaste(text: String, allowUnsafe: Boolean = false): ByteArray? = synchronized(lock) {
        if (handle == 0L || text.isEmpty()) return null
        GhosttyVtNative.nativeEncodePaste(handle, text.encodeToByteArray(), allowUnsafe)
    }

    fun scrollViewportTop() = synchronized(lock) {
        if (handle != 0L) GhosttyVtNative.nativeScrollViewport(handle, SCROLL_TOP, 0)
    }

    fun scrollViewportBottom() = synchronized(lock) {
        if (handle != 0L) GhosttyVtNative.nativeScrollViewport(handle, SCROLL_BOTTOM, 0)
    }

    fun scrollViewportDelta(deltaLines: Int) = synchronized(lock) {
        if (handle != 0L && deltaLines != 0) {
            GhosttyVtNative.nativeScrollViewport(handle, SCROLL_DELTA, deltaLines)
        }
    }

    fun snapshotEncode(): ByteArray? = synchronized(lock) {
        if (handle == 0L) return null
        GhosttyVtNative.nativeSnapshotEncode(handle)
    }

    fun snapshotDecode(snapshot: ByteArray): Boolean = synchronized(lock) {
        if (handle == 0L || snapshot.isEmpty()) return false
        val ok = GhosttyVtNative.nativeSnapshotDecode(handle, snapshot)
        if (ok) {
            lastPalette?.let { palette ->
                GhosttyVtNative.nativeSetColors(
                    handle,
                    palette.foregroundRgb,
                    palette.backgroundRgb,
                    palette.cursorRgb,
                    palette.ansi16Rgb,
                )
            }
        }
        ok
    }

    /**
     * Captures the latest Ghostty render state into an immutable [TerminalFrame].
     * Returns `null` when nothing is dirty and [forceFull] is `false`.
     */
    fun captureFrame(forceFull: Boolean = false): TerminalFrame? = synchronized(lock) {
        if (handle == 0L) return null
        val raw = GhosttyVtNative.nativeCaptureFrame(handle, forceFull) ?: return null
        decodeFrameLocked(raw)
    }

    private fun decodeFrameLocked(raw: ByteArray): TerminalFrame {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val cols = buf.short.toInt() and 0xFFFF
        val rows = buf.short.toInt() and 0xFFFF
        val dirty = buf.get().toInt() and 0xFF
        val defaultFg = buf.int
        val defaultBg = buf.int
        val cursorColor = buf.int
        val cursorFlags = buf.get().toInt() and 0xFF
        val cursorStyle = buf.get().toInt() and 0xFF
        val cursorX = buf.short.toInt() and 0xFFFF
        val cursorY = buf.short.toInt() and 0xFFFF
        val scrollTotal = buf.int
        val scrollOffset = buf.int
        val scrollLen = buf.int
        val dirtyRowCount = buf.short.toInt() and 0xFFFF

        val titleChanged = (cursorFlags and (1 shl 7)) != 0
        if (titleChanged) {
            cachedTitle = GhosttyVtNative.nativeGetTitleUtf8(handle)?.decodeToString()
        }

        if (cachedRows != rows || cachedCols != cols || dirty == DIRTY_FULL) {
            val nextGrid = Array(rows) { i ->
                if (dirty != DIRTY_FULL && i < cachedGrid.size) cachedGrid[i] else TerminalRow.EMPTY
            }
            cachedGrid = nextGrid
            cachedCols = cols
            cachedRows = rows
        }

        for (i in 0 until dirtyRowCount) {
            val rowY = buf.short.toInt() and 0xFFFF
            val cellCount = buf.short.toInt() and 0xFFFF
            val row = decodeRow(buf, cellCount)
            if (rowY in cachedGrid.indices) {
                cachedGrid[rowY] = row
            }
        }

        val cursor = decodeCursor(cursorFlags, cursorStyle, cursorX, cursorY)
        frameRevision++
        return TerminalFrame(
            cols = cols,
            rows = rows,
            defaultFgArgb = defaultFg,
            defaultBgArgb = defaultBg,
            cursorArgb = if (cursorColor != 0) cursorColor else defaultFg,
            cursor = cursor,
            isAlternateScreen = (cursorFlags and (1 shl 5)) != 0,
            bellRung = (cursorFlags and (1 shl 6)) != 0,
            title = cachedTitle,
            scrollbar = TerminalScrollbar(total = scrollTotal, offset = scrollOffset, len = scrollLen),
            grid = cachedGrid.toList(),
            revision = frameRevision,
        )
    }

    private fun decodeRow(buf: ByteBuffer, cellCount: Int): TerminalRow {
        val cells = ArrayList<TerminalCell>(cellCount)
        repeat(cellCount) {
            val fg = buf.int
            val bg = buf.int
            val styleFlags = buf.short.toInt() and 0xFFFF
            val wide = buf.get().toInt() and 0xFF
            val utf8Len = buf.get().toInt() and 0xFF
            val text = if (utf8Len > 0) {
                val bytes = ByteArray(utf8Len)
                buf.get(bytes)
                bytes.decodeToString()
            } else {
                ""
            }
            cells.add(TerminalCell(text = text, fgArgb = fg, bgArgb = bg, styleFlags = styleFlags, wide = wide))
        }
        return TerminalRow(cells)
    }

    private fun decodeCursor(flags: Int, style: Int, x: Int, y: Int): TerminalCursor = TerminalCursor(
        visible = (flags and (1 shl 0)) != 0,
        inViewport = (flags and (1 shl 1)) != 0,
        x = x,
        y = y,
        style = style,
        blinking = (flags and (1 shl 2)) != 0,
        passwordInput = (flags and (1 shl 3)) != 0,
        wideTail = (flags and (1 shl 4)) != 0,
    )

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            GhosttyVtNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val DEFAULT_SCROLLBACK_BYTES = 4L * 1024L * 1024L
        private const val DIRTY_FULL = 2
        private const val SCROLL_TOP = 0
        private const val SCROLL_BOTTOM = 1
        private const val SCROLL_DELTA = 2
    }
}
