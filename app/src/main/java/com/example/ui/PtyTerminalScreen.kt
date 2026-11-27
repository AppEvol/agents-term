package com.example.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.TabRecord
import com.example.terminal.GhosttyCellWide
import com.example.terminal.GhosttyCursorStyle
import com.example.terminal.GhosttyKey
import com.example.terminal.GhosttyMods
import com.example.terminal.PtyConnectionState
import com.example.terminal.PtyPasteOutcome
import com.example.terminal.PtyTabSession
import com.example.terminal.PtyTabUiState
import com.example.terminal.TerminalFrame
import com.example.terminal.TerminalThemePalette
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.ThemedAlertDialog
import com.example.ui.theme.ThemedTextButton
import com.example.ui.theme.themedCorner
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val DEFAULT_TERMINAL_FONT_SP = 13f
private const val MIN_TERMINAL_FONT_SP = 9f
private const val MAX_TERMINAL_FONT_SP = 22f
private const val TERMINAL_PADDING_DP = 6

@Composable
fun rememberTerminalThemePalette(): TerminalThemePalette {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) TerminalThemePalette.Dark else TerminalThemePalette.Light
}

@Composable
fun PtyTerminalWorkspace(
    tab: TabRecord,
    session: PtyTabSession,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by session.uiState.collectAsStateWithLifecycle()
    val palette = rememberTerminalThemePalette()
    val clipboard = LocalClipboardManager.current

    var fontSizeSp by rememberSaveable(tab.id) { mutableFloatStateOf(DEFAULT_TERMINAL_FONT_SP) }
    var ctrlLatched by remember(tab.id) { mutableStateOf(false) }
    var altLatched by remember(tab.id) { mutableStateOf(false) }
    var pendingUnsafePaste by remember(tab.id) { mutableStateOf<String?>(null) }
    var inputViewRef by remember(tab.id) { mutableStateOf<TerminalImeInputView?>(null) }

    LaunchedEffect(tab.id, palette) {
        session.updatePalette(palette)
        session.ensureConnected()
    }

    fun consumeLatchedMods(): Int {
        var mods = GhosttyMods.NONE
        if (ctrlLatched) mods = mods or GhosttyMods.CTRL
        if (altLatched) mods = mods or GhosttyMods.ALT
        if (mods != GhosttyMods.NONE) {
            ctrlLatched = false
            altLatched = false
        }
        return mods
    }

    fun sendToolbarKey(key: Int, extraMods: Int = GhosttyMods.NONE, unshiftedCodepoint: Int = 0) {
        val mods = extraMods or consumeLatchedMods()
        session.sendKey(key = key, mods = mods, unshiftedCodepoint = unshiftedCodepoint)
    }

    fun handlePasteClick() {
        val clipText = clipboard.getText()?.text.orEmpty()
        if (clipText.isEmpty()) return
        when (val outcome = session.pasteText(clipText, allowUnsafe = false)) {
            PtyPasteOutcome.Sent -> Unit
            PtyPasteOutcome.Empty -> Unit
            is PtyPasteOutcome.NeedsConfirmation -> pendingUnsafePaste = outcome.text
        }
    }

    fun handleCopyScreen() {
        val text = uiState.frame?.toPlainText().orEmpty()
        if (text.isNotEmpty()) {
            clipboard.setText(AnnotatedString(text))
            onShowMessage("Copied terminal screen")
        }
    }

    PtyTerminalContent(
        uiState = uiState,
        palette = palette,
        fontSizeSp = fontSizeSp,
        ctrlLatched = ctrlLatched,
        altLatched = altLatched,
        onToggleCtrl = { ctrlLatched = !ctrlLatched },
        onToggleAlt = { altLatched = !altLatched },
        onCommitText = { committed ->
            val mods = consumeLatchedMods()
            session.sendText(committed, latchedMods = mods)
        },
        onSpecialKey = { key, mods, codepoint ->
            val combinedMods = mods or consumeLatchedMods()
            session.sendKey(key = key, mods = combinedMods, unshiftedCodepoint = codepoint)
        },
        onToolbarKey = { key, mods, codepoint ->
            sendToolbarKey(key = key, extraMods = mods, unshiftedCodepoint = codepoint)
        },
        onResize = { cols, rows, cw, ch ->
            session.resize(cols = cols, rows = rows, cellWidthPx = cw, cellHeightPx = ch)
        },
        onScrollLines = { delta -> session.scrollLines(delta) },
        onScrollToBottom = { session.scrollToBottom() },
        onReconnect = { session.connect() },
        onRestart = { session.restart() },
        onPaste = { handlePasteClick() },
        onCopyScreen = { handleCopyScreen() },
        onZoomIn = { fontSizeSp = (fontSizeSp + 1.5f).coerceAtMost(MAX_TERMINAL_FONT_SP) },
        onZoomOut = { fontSizeSp = (fontSizeSp - 1.5f).coerceAtLeast(MIN_TERMINAL_FONT_SP) },
        onToggleKeyboard = { inputViewRef?.toggleKeyboard() },
        onRequestKeyboard = { inputViewRef?.focusAndShowKeyboard() },
        onInputViewBound = { inputViewRef = it },
        modifier = modifier,
    )

    val unsafeText = pendingUnsafePaste
    if (unsafeText != null) {
        ThemedAlertDialog(
            onDismissRequest = { pendingUnsafePaste = null },
            title = { Text(stringResource(R.string.pty_unsafe_paste_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.pty_unsafe_paste_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = themedCorner(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = unsafeText.take(240),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFontFamily,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                ThemedTextButton(
                    onClick = {
                        pendingUnsafePaste = null
                        session.pasteText(unsafeText, allowUnsafe = true)
                    },
                    modifier = Modifier.testTag("pty_confirm_unsafe_paste"),
                ) {
                    Text(stringResource(R.string.pty_unsafe_paste_confirm))
                }
            },
            dismissButton = {
                ThemedTextButton(onClick = { pendingUnsafePaste = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun PtyTerminalContent(
    uiState: PtyTabUiState,
    palette: TerminalThemePalette,
    fontSizeSp: Float,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onCommitText: (String) -> Unit,
    onSpecialKey: (key: Int, mods: Int, unshiftedCodepoint: Int) -> Unit,
    onToolbarKey: (key: Int, mods: Int, unshiftedCodepoint: Int) -> Unit,
    onResize: (cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    onScrollLines: (Int) -> Unit,
    onScrollToBottom: () -> Unit,
    onReconnect: () -> Unit,
    onRestart: () -> Unit,
    onPaste: () -> Unit,
    onCopyScreen: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onRequestKeyboard: () -> Unit,
    onInputViewBound: (TerminalImeInputView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = remember(uiState.frame?.defaultBgArgb, palette) {
        Color(uiState.frame?.defaultBgArgb ?: (0xFF000000.toInt() or palette.backgroundRgb))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .navigationBarsPadding()
            .imePadding()
            .testTag("pty_terminal_workspace"),
    ) {
        PtyStatusBanner(
            uiState = uiState,
            onReconnect = onReconnect,
            onRestart = onRestart,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // Hidden Android IME anchor view for direct soft-keyboard + hardware-key input.
            TerminalImeInputAnchor(
                onCommitText = onCommitText,
                onSpecialKey = onSpecialKey,
                onBound = onInputViewBound,
                modifier = Modifier
                    .size(1.dp)
                    .align(Alignment.TopStart),
            )

            TerminalGridCanvas(
                frame = uiState.frame,
                fallbackBgColor = bgColor,
                fontSizeSp = fontSizeSp,
                onResize = onResize,
                onTapTerminal = onRequestKeyboard,
                onScrollLines = onScrollLines,
                onAltScreenScrollKey = { isUp ->
                    onToolbarKey(
                        if (isUp) GhosttyKey.ARROW_UP else GhosttyKey.ARROW_DOWN,
                        GhosttyMods.NONE,
                        0,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )

            val frame = uiState.frame
            if (frame != null && !frame.isAlternateScreen && !frame.scrollbar.isAtBottom) {
                SmallFloatingActionButton(
                    onClick = onScrollToBottom,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .testTag("pty_scroll_bottom_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (frame?.cursor?.passwordInput == true) {
                Surface(
                    shape = themedCorner(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .testTag("pty_password_indicator"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(R.string.pty_password_input_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PtyControlToolbar(
            ctrlLatched = ctrlLatched,
            altLatched = altLatched,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onKey = onToolbarKey,
            onPaste = onPaste,
            onCopyScreen = onCopyScreen,
            onZoomOut = onZoomOut,
            onZoomIn = onZoomIn,
            onToggleKeyboard = onToggleKeyboard,
        )
    }
}

@Composable
private fun PtyStatusBanner(
    uiState: PtyTabUiState,
    onReconnect: () -> Unit,
    onRestart: () -> Unit,
) {
    when (uiState.connectionState) {
        PtyConnectionState.CONNECTING, PtyConnectionState.RECONNECTING -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pty_status_connecting"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = if (uiState.connectionState == PtyConnectionState.RECONNECTING) {
                            stringResource(R.string.pty_status_reconnecting)
                        } else {
                            stringResource(R.string.pty_status_connecting)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    ThemedTextButton(
                        onClick = onReconnect,
                        modifier = Modifier.testTag("pty_reconnect_button"),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }

        PtyConnectionState.EXITED -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pty_status_exited"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val codeLabel = uiState.exitCode?.let { " (exit $it)" }
                        ?: uiState.exitSignal?.let { " (signal $it)" }
                        ?: ""
                    Text(
                        text = stringResource(R.string.pty_status_exited, codeLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ThemedTextButton(
                        onClick = onRestart,
                        modifier = Modifier.testTag("pty_restart_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pty_action_restart))
                    }
                }
            }
        }

        PtyConnectionState.ERROR -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pty_status_error"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = uiState.errorMessage ?: stringResource(R.string.pty_status_error_default),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ThemedTextButton(
                        onClick = onReconnect,
                        modifier = Modifier.testTag("pty_retry_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }

        PtyConnectionState.IDLE, PtyConnectionState.CONNECTED -> Unit
    }
}

private data class TerminalFontMetrics(
    val regularTypeface: Typeface,
    val boldTypeface: Typeface,
    val italicTypeface: Typeface,
    val boldItalicTypeface: Typeface,
    val textSizePx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val baselineOffsetPx: Float,
)

@Composable
private fun rememberTerminalFontMetrics(fontSizeSp: Float): TerminalFontMetrics {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSizeSp.sp.toPx() }

    val baseRegular = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
            ?: Typeface.MONOSPACE
    }
    val baseBold = remember(context, baseRegular) {
        runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold) }.getOrNull()
            ?: Typeface.create(baseRegular, Typeface.BOLD)
    }
    val baseItalic = remember(baseRegular) { Typeface.create(baseRegular, Typeface.ITALIC) }
    val baseBoldItalic = remember(baseBold) { Typeface.create(baseBold, Typeface.BOLD_ITALIC) }

    return remember(baseRegular, baseBold, baseItalic, baseBoldItalic, textSizePx) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = baseRegular
            textSize = textSizePx
        }
        val fm = paint.fontMetrics
        val cellWidth = max(4f, paint.measureText("M"))
        val cellHeight = max(8f, ceil((fm.descent - fm.ascent + fm.leading).toDouble()).toFloat())
        val baselineOffset = -fm.ascent
        TerminalFontMetrics(
            regularTypeface = baseRegular,
            boldTypeface = baseBold,
            italicTypeface = baseItalic,
            boldItalicTypeface = baseBoldItalic,
            textSizePx = textSizePx,
            cellWidthPx = cellWidth,
            cellHeightPx = cellHeight,
            baselineOffsetPx = baselineOffset,
        )
    }
}

@Composable
private fun TerminalGridCanvas(
    frame: TerminalFrame?,
    fallbackBgColor: Color,
    fontSizeSp: Float,
    onResize: (cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    onTapTerminal: () -> Unit,
    onScrollLines: (Int) -> Unit,
    onAltScreenScrollKey: (isUp: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberTerminalFontMetrics(fontSizeSp)
    val density = LocalDensity.current
    val padPx = with(density) { TERMINAL_PADDING_DP.dp.toPx() }
    val latestOnResize by rememberUpdatedState(onResize)
    val latestFrame by rememberUpdatedState(frame)
    val latestScrollLines by rememberUpdatedState(onScrollLines)
    val latestAltScroll by rememberUpdatedState(onAltScreenScrollKey)

    val textPaint = remember(metrics) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = metrics.regularTypeface
            textSize = metrics.textSizePx
        }
    }
    val bgPaint = remember { Paint() }
    val decoPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    val screenAccessibilityText = remember(frame?.revision) {
        frame?.toPlainText().orEmpty()
    }

    BoxWithConstraints(
        modifier = modifier
            .background(fallbackBgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTapTerminal() })
            }
            .pointerInput(metrics.cellHeightPx) {
                var accumPx = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumPx = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        accumPx += dragAmount
                        val threshold = metrics.cellHeightPx.coerceAtLeast(12f)
                        val steps = (accumPx / threshold).toInt()
                        if (steps != 0) {
                            accumPx -= steps * threshold
                            val isAlt = latestFrame?.isAlternateScreen == true
                            if (isAlt) {
                                val count = abs(steps).coerceAtMost(5)
                                repeat(count) {
                                    latestAltScroll(steps > 0)
                                }
                            } else {
                                // Dragging finger down reveals older lines (negative Ghostty delta).
                                latestScrollLines(-steps)
                            }
                        }
                    },
                )
            }
            .semantics {
                contentDescription = "Interactive terminal"
                if (screenAccessibilityText.isNotEmpty()) {
                    text = AnnotatedString(screenAccessibilityText)
                }
            }
            .testTag("pty_terminal_canvas"),
    ) {
        val availWidthPx = (constraints.maxWidth - (padPx * 2f)).coerceAtLeast(metrics.cellWidthPx)
        val availHeightPx = (constraints.maxHeight - (padPx * 2f)).coerceAtLeast(metrics.cellHeightPx)
        val computedCols = floor((availWidthPx / metrics.cellWidthPx).toDouble()).toInt()
            .coerceIn(PtyTabSession.MIN_COLS, PtyTabSession.MAX_COLS)
        val computedRows = floor((availHeightPx / metrics.cellHeightPx).toDouble()).toInt()
            .coerceIn(PtyTabSession.MIN_ROWS, PtyTabSession.MAX_ROWS)

        LaunchedEffect(computedCols, computedRows, metrics.cellWidthPx, metrics.cellHeightPx) {
            latestOnResize(
                computedCols,
                computedRows,
                metrics.cellWidthPx.roundToInt().coerceAtLeast(1),
                metrics.cellHeightPx.roundToInt().coerceAtLeast(1),
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val f = frame ?: return@Canvas
            val canvas = drawContext.canvas.nativeCanvas
            val cw = metrics.cellWidthPx
            val ch = metrics.cellHeightPx
            val baselineOffset = metrics.baselineOffsetPx

            bgPaint.color = f.defaultBgArgb
            canvas.drawRect(0f, 0f, size.width, size.height, bgPaint)

            val runBuilder = StringBuilder(f.cols)

            for (rowIdx in f.grid.indices) {
                val row = f.grid[rowIdx]
                val cells = row.cells
                val topY = padPx + rowIdx * ch
                val bottomY = topY + ch

                // Pass 1: coalesce contiguous cell backgrounds.
                var col = 0
                while (col < cells.size) {
                    val bg = cells[col].bgArgb
                    if (bg == 0) {
                        col++
                        continue
                    }
                    val startCol = col
                    while (col < cells.size && cells[col].bgArgb == bg) {
                        col++
                    }
                    bgPaint.color = bg
                    canvas.drawRect(
                        padPx + startCol * cw,
                        topY,
                        padPx + col * cw,
                        bottomY,
                        bgPaint,
                    )
                }

                // Pass 2: draw coalesced styled text runs and decorations.
                val baselineY = topY + baselineOffset
                col = 0
                while (col < cells.size) {
                    val cell = cells[col]
                    if (cell.wide == GhosttyCellWide.SPACER_TAIL ||
                        cell.wide == GhosttyCellWide.SPACER_HEAD ||
                        cell.isInvisible
                    ) {
                        col++
                        continue
                    }

                    val hasText = cell.text.isNotEmpty()
                    val hasLineDeco = cell.hasUnderline || cell.isStrikethrough || cell.isOverline
                    if (!hasText && !hasLineDeco) {
                        col++
                        continue
                    }

                    val fg = cell.fgArgb
                    val flags = cell.styleFlags
                    val startCol = col

                    textPaint.typeface = when {
                        cell.isBold && cell.isItalic -> metrics.boldItalicTypeface
                        cell.isBold -> metrics.boldTypeface
                        cell.isItalic -> metrics.italicTypeface
                        else -> metrics.regularTypeface
                    }
                    textPaint.color = if (cell.isFaint) applyFaintAlpha(fg) else fg

                    if (cell.wide == GhosttyCellWide.WIDE || cell.text.length > 1) {
                        if (hasText) {
                            canvas.drawText(cell.text, padPx + startCol * cw, baselineY, textPaint)
                        }
                        val spanCols = if (cell.wide == GhosttyCellWide.WIDE) 2 else 1
                        if (hasLineDeco) {
                            drawCellDecorations(
                                canvas = canvas,
                                paint = decoPaint,
                                fg = textPaint.color,
                                flags = flags,
                                bounds = CellDecorationBounds(
                                    left = padPx + startCol * cw,
                                    right = padPx + (startCol + spanCols) * cw,
                                    topY = topY,
                                    baselineY = baselineY,
                                    cellHeight = ch,
                                ),
                            )
                        }
                        col += 1
                    } else {
                        runBuilder.setLength(0)
                        while (col < cells.size && cells[col].canCoalesceWith(fg, flags)) {
                            val next = cells[col]
                            runBuilder.append(if (next.text.isEmpty()) ' ' else next.text[0])
                            col++
                        }
                        val runText = runBuilder.toString()
                        val left = padPx + startCol * cw
                        val right = padPx + col * cw
                        canvas.drawText(runText, left, baselineY, textPaint)
                        if (hasLineDeco) {
                            drawCellDecorations(
                                canvas = canvas,
                                paint = decoPaint,
                                fg = textPaint.color,
                                flags = flags,
                                bounds = CellDecorationBounds(
                                    left = left,
                                    right = right,
                                    topY = topY,
                                    baselineY = baselineY,
                                    cellHeight = ch,
                                ),
                            )
                        }
                    }
                }
            }

            // Pass 3: cursor.
            val cur = f.cursor
            if (cur.visible && cur.inViewport && cur.y in f.grid.indices) {
                val cx = padPx + cur.x * cw
                val cy = padPx + cur.y * ch
                val cursorWidth = if (cur.wideTail) cw * 2f else cw
                val stroke = max(2f, cw * 0.14f)
                when (cur.style) {
                    GhosttyCursorStyle.BAR -> {
                        bgPaint.color = f.cursorArgb
                        canvas.drawRect(cx, cy, cx + stroke, cy + ch, bgPaint)
                    }

                    GhosttyCursorStyle.UNDERLINE -> {
                        bgPaint.color = f.cursorArgb
                        canvas.drawRect(cx, cy + ch - stroke, cx + cursorWidth, cy + ch, bgPaint)
                    }

                    GhosttyCursorStyle.BLOCK_HOLLOW -> {
                        decoPaint.style = Paint.Style.STROKE
                        decoPaint.strokeWidth = stroke
                        decoPaint.color = f.cursorArgb
                        canvas.drawRect(cx, cy, cx + cursorWidth, cy + ch, decoPaint)
                        decoPaint.style = Paint.Style.FILL
                    }

                    else -> {
                        // Semi-transparent block cursor keeps underlying glyph legible.
                        bgPaint.color = (f.cursorArgb and 0x00FFFFFF) or (0x88 shl 24)
                        canvas.drawRect(cx, cy, cx + cursorWidth, cy + ch, bgPaint)
                    }
                }
            }
        }
    }
}

private fun applyFaintAlpha(argb: Int): Int {
    val alpha = ((argb ushr 24) * 145) / 255
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}

private data class CellDecorationBounds(
    val left: Float,
    val right: Float,
    val topY: Float,
    val baselineY: Float,
    val cellHeight: Float,
)

private fun com.example.terminal.TerminalCell.canCoalesceWith(fg: Int, flags: Int): Boolean {
    val sameStyle = fgArgb == fg && styleFlags == flags
    return sameStyle && wide == GhosttyCellWide.NARROW && !isInvisible && text.length <= 1
}

private fun drawCellDecorations(
    canvas: android.graphics.Canvas,
    paint: Paint,
    fg: Int,
    flags: Int,
    bounds: CellDecorationBounds,
) {
    paint.color = fg
    paint.style = Paint.Style.FILL
    val thickness = max(1.5f, bounds.cellHeight * 0.07f)
    if (com.example.terminal.GhosttyStyleFlags.underlineStyle(flags) != 0) {
        val y = (bounds.baselineY + thickness * 1.4f).coerceAtMost(bounds.topY + bounds.cellHeight - thickness)
        canvas.drawRect(bounds.left, y, bounds.right, y + thickness, paint)
    }
    if ((flags and com.example.terminal.GhosttyStyleFlags.STRIKETHROUGH) != 0) {
        val y = bounds.topY + bounds.cellHeight * 0.52f
        canvas.drawRect(bounds.left, y, bounds.right, y + thickness, paint)
    }
    if ((flags and com.example.terminal.GhosttyStyleFlags.OVERLINE) != 0) {
        canvas.drawRect(bounds.left, bounds.topY, bounds.right, bounds.topY + thickness, paint)
    }
}

@Composable
private fun PtyControlToolbar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (key: Int, mods: Int, unshiftedCodepoint: Int) -> Unit,
    onPaste: () -> Unit,
    onCopyScreen: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pty_control_toolbar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PtyKeyCap(
                label = "Tab",
                tag = "pty_key_tab",
                onClick = { onKey(GhosttyKey.TAB, GhosttyMods.NONE, 0) },
            )
            PtyKeyCap(
                label = "Ctrl",
                tag = "pty_key_ctrl",
                active = ctrlLatched,
                onClick = onToggleCtrl,
            )
            PtyKeyCap(
                label = "^C",
                tag = "pty_key_ctrl_c",
                onClick = { onKey(GhosttyKey.C, GhosttyMods.CTRL, 'c'.code) },
            )
            PtyKeyCap(
                label = "Esc",
                tag = "pty_key_esc",
                onClick = { onKey(GhosttyKey.ESCAPE, GhosttyMods.NONE, 0) },
            )
            PtyIconKeyCap(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                description = "Left arrow",
                tag = "pty_key_left",
                onClick = { onKey(GhosttyKey.ARROW_LEFT, GhosttyMods.NONE, 0) },
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = "Down arrow",
                tag = "pty_key_down",
                onClick = { onKey(GhosttyKey.ARROW_DOWN, GhosttyMods.NONE, 0) },
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.KeyboardArrowUp,
                description = "Up arrow",
                tag = "pty_key_up",
                onClick = { onKey(GhosttyKey.ARROW_UP, GhosttyMods.NONE, 0) },
            )
            PtyIconKeyCap(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                description = "Right arrow",
                tag = "pty_key_right",
                onClick = { onKey(GhosttyKey.ARROW_RIGHT, GhosttyMods.NONE, 0) },
            )
            PtyKeyCap(
                label = "Alt",
                tag = "pty_key_alt",
                active = altLatched,
                onClick = onToggleAlt,
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.ContentPaste,
                description = stringResource(R.string.pty_action_paste),
                tag = "pty_key_paste",
                onClick = onPaste,
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.ContentCopy,
                description = stringResource(R.string.pty_action_copy_screen),
                tag = "pty_key_copy",
                onClick = onCopyScreen,
            )
            PtyKeyCap(
                label = "Home",
                tag = "pty_key_home",
                onClick = { onKey(GhosttyKey.HOME, GhosttyMods.NONE, 0) },
            )
            PtyKeyCap(
                label = "End",
                tag = "pty_key_end",
                onClick = { onKey(GhosttyKey.END, GhosttyMods.NONE, 0) },
            )
            PtyKeyCap(
                label = "PgUp",
                tag = "pty_key_pgup",
                onClick = { onKey(GhosttyKey.PAGE_UP, GhosttyMods.NONE, 0) },
            )
            PtyKeyCap(
                label = "PgDn",
                tag = "pty_key_pgdn",
                onClick = { onKey(GhosttyKey.PAGE_DOWN, GhosttyMods.NONE, 0) },
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.ZoomOut,
                description = stringResource(R.string.pty_action_zoom_out),
                tag = "pty_key_zoom_out",
                onClick = onZoomOut,
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.ZoomIn,
                description = stringResource(R.string.pty_action_zoom_in),
                tag = "pty_key_zoom_in",
                onClick = onZoomIn,
            )
            PtyIconKeyCap(
                icon = Icons.Rounded.Keyboard,
                description = stringResource(R.string.pty_action_keyboard),
                tag = "pty_key_keyboard",
                onClick = onToggleKeyboard,
            )
        }
    }
}

@Composable
private fun PtyKeyCap(
    label: String,
    tag: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = themedCorner(8.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 36.dp)
            .clip(themedCorner(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun PtyIconKeyCap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = themedCorner(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .defaultMinSize(minWidth = 38.dp, minHeight = 36.dp)
            .clip(themedCorner(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TerminalImeInputAnchor(
    onCommitText: (String) -> Unit,
    onSpecialKey: (key: Int, mods: Int, unshiftedCodepoint: Int) -> Unit,
    onBound: (TerminalImeInputView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestCommit by rememberUpdatedState(onCommitText)
    val latestSpecial by rememberUpdatedState(onSpecialKey)

    AndroidView(
        factory = { ctx ->
            TerminalImeInputView(ctx).also { view ->
                view.onCommitText = { latestCommit(it) }
                view.onSpecialKey = { k, m, cp -> latestSpecial(k, m, cp) }
                onBound(view)
            }
        },
        update = { view ->
            view.onCommitText = { latestCommit(it) }
            view.onSpecialKey = { k, m, cp -> latestSpecial(k, m, cp) }
        },
        onRelease = { onBound(null) },
        modifier = modifier,
    )
}

/**
 * Lightweight Android [View] that hosts a raw terminal [InputConnection] without
 * autocorrect, predictive suggestions, or keyboard learning, forwarding committed text
 * and hardware/soft keys straight to Ghostty's key encoder.
 */
class TerminalImeInputView(context: Context) : View(context) {
    var onCommitText: (String) -> Unit = {}
    var onSpecialKey: (key: Int, mods: Int, unshiftedCodepoint: Int) -> Unit = { _, _, _ -> }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (!hasFocus()) {
            focusAndShowKeyboard()
        } else {
            @Suppress("DEPRECATION")
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchAndroidKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    internal fun dispatchAndroidKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val mods = androidMetaToGhosttyMods(event.metaState)
        val special = mapAndroidSpecialKey(event.keyCode)
        if (special != null) {
            onSpecialKey(special, mods, 0)
            return true
        }
        val unicode = event.unicodeChar
        if (mods != GhosttyMods.NONE && (mods and (GhosttyMods.CTRL or GhosttyMods.ALT)) != 0) {
            val baseChar = event.getUnicodeChar(0)
            if (baseChar in 1..127) {
                val ch = baseChar.toChar()
                val gKey = GhosttyKey.fromAsciiChar(ch)
                if (gKey != GhosttyKey.UNIDENTIFIED) {
                    onSpecialKey(gKey, mods, ch.lowercaseChar().code)
                    return true
                }
            }
        }
        if (unicode != 0 && (unicode and android.view.KeyCharacterMap.COMBINING_ACCENT) == 0) {
            onCommitText(String(Character.toChars(unicode)))
            return true
        }
        return false
    }

    private class TerminalInputConnection(
        private val target: TerminalImeInputView,
    ) : BaseInputConnection(target, false) {
        private var composingText: String = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committed = text?.toString().orEmpty()
            replaceComposingWith(committed, keepComposing = false)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val next = text?.toString().orEmpty()
            replaceComposingWith(next, keepComposing = true)
            return true
        }

        override fun finishComposingText(): Boolean {
            composingText = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val count = beforeLength.coerceAtLeast(1)
            if (composingText.isNotEmpty()) {
                val drop = count.coerceAtMost(composingText.length)
                composingText = composingText.dropLast(drop)
            }
            repeat(count) {
                target.onSpecialKey(GhosttyKey.BACKSPACE, GhosttyMods.NONE, 0)
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && target.dispatchAndroidKeyEvent(event)) {
                return true
            }
            return super.sendKeyEvent(event)
        }

        private fun replaceComposingWith(next: String, keepComposing: Boolean) {
            var common = 0
            val maxCommon = minOf(composingText.length, next.length)
            while (common < maxCommon && composingText[common] == next[common]) {
                common++
            }
            val backspaces = composingText.length - common
            repeat(backspaces) {
                target.onSpecialKey(GhosttyKey.BACKSPACE, GhosttyMods.NONE, 0)
            }
            val suffix = next.substring(common)
            if (suffix.isNotEmpty()) {
                target.onCommitText(suffix)
            }
            composingText = if (keepComposing) next else ""
        }
    }
}

internal fun androidMetaToGhosttyMods(metaState: Int): Int {
    var mods = GhosttyMods.NONE
    if ((metaState and KeyEvent.META_SHIFT_ON) != 0) mods = mods or GhosttyMods.SHIFT
    if ((metaState and KeyEvent.META_CTRL_ON) != 0) mods = mods or GhosttyMods.CTRL
    if ((metaState and KeyEvent.META_ALT_ON) != 0) mods = mods or GhosttyMods.ALT
    if ((metaState and KeyEvent.META_META_ON) != 0) mods = mods or GhosttyMods.SUPER
    return mods
}

internal fun mapAndroidSpecialKey(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> GhosttyKey.ENTER
    KeyEvent.KEYCODE_DEL -> GhosttyKey.BACKSPACE
    KeyEvent.KEYCODE_FORWARD_DEL -> GhosttyKey.DELETE
    KeyEvent.KEYCODE_TAB -> GhosttyKey.TAB
    KeyEvent.KEYCODE_ESCAPE -> GhosttyKey.ESCAPE
    KeyEvent.KEYCODE_DPAD_UP -> GhosttyKey.ARROW_UP
    KeyEvent.KEYCODE_DPAD_DOWN -> GhosttyKey.ARROW_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> GhosttyKey.ARROW_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> GhosttyKey.ARROW_RIGHT
    KeyEvent.KEYCODE_MOVE_HOME -> GhosttyKey.HOME
    KeyEvent.KEYCODE_MOVE_END -> GhosttyKey.END
    KeyEvent.KEYCODE_PAGE_UP -> GhosttyKey.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> GhosttyKey.PAGE_DOWN
    KeyEvent.KEYCODE_INSERT -> GhosttyKey.INSERT
    else -> null
}
