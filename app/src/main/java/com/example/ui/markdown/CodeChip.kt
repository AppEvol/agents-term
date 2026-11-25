package com.example.ui.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Inline code is drawn as a rounded "chip" behind the text, following the approach of Saket
 * Narayan's extended-spans (which mikepenz/multiplatform-markdown-renderer also uses): the span
 * carries an annotation instead of `SpanStyle.background`, and the background is painted from the
 * text layout, one piece per line.
 *
 * A background span would fill the full line height, so code on consecutive lines fuses into one
 * solid slab. A chip is as tall as the code's own font, whatever the line height.
 */

/**
 * One line's piece of an inline code chip, in the text's coordinates. A chip that wraps is cut
 * flat where the line breaks ([openStart], [openEnd]) and rounded only at its real ends, so the
 * pieces read as one span that continues on the next line.
 */
internal data class CodeChip(val rect: Rect, val openStart: Boolean, val openEnd: Boolean)

// JetBrains Mono's ascent and descent, as fractions of its font size.
private const val CODE_ASCENT = 1.02f
private const val CODE_DESCENT = 0.3f

/**
 * The chips for every inline code span in [layout]. [codeFontSizePx] is the code's font size.
 *
 * Ranges come from the layout's own text, so a layout that is one frame behind the latest
 * streamed text can never be indexed out of bounds.
 */
internal fun inlineCodeChips(layout: TextLayoutResult, codeFontSizePx: Float): List<CodeChip> {
    val text = layout.layoutInput.text
    val chips = mutableListOf<CodeChip>()
    for (range in text.getStringAnnotations(INLINE_CODE_TAG, 0, text.length)) {
        if (range.end <= range.start) continue
        val firstLine = layout.getLineForOffset(range.start)
        val lastLine = layout.getLineForOffset(range.end - 1)
        for (line in firstLine..lastLine) {
            val from = maxOf(range.start, layout.getLineStart(line))
            val to = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
            if (to <= from) continue
            val first = layout.getBoundingBox(from)
            val last = layout.getBoundingBox(to - 1)
            val baseline = layout.getLineBaseline(line)
            chips.add(
                CodeChip(
                    rect = Rect(
                        left = minOf(first.left, last.left),
                        top = baseline - codeFontSizePx * CODE_ASCENT,
                        right = maxOf(first.right, last.right),
                        bottom = baseline + codeFontSizePx * CODE_DESCENT,
                    ),
                    openStart = from > range.start,
                    openEnd = to < range.end,
                ),
            )
        }
    }
    return chips
}

/** Markdown inline content as text, with chips behind inline code. */
@Composable
internal fun MarkdownInlineText(
    content: List<MdInline>,
    textStyle: TextStyle,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val text = remember(content, style.inline) { buildInlineText(content, style.inline) }
    val hasCode = remember(text) { text.getStringAnnotations(INLINE_CODE_TAG, 0, text.length).isNotEmpty() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val path = remember { Path() }
    Text(
        text = text,
        style = textStyle,
        color = style.textColor,
        textAlign = textAlign,
        onTextLayout = { layout = it },
        modifier = if (!hasCode) {
            modifier
        } else {
            modifier.drawBehind {
                val result = layout ?: return@drawBehind
                val radius = CornerRadius(4.dp.toPx())
                val flat = CornerRadius.Zero
                for (chip in inlineCodeChips(result, textStyle.fontSize.toPx() * INLINE_CODE_EM)) {
                    val start = if (chip.openStart) flat else radius
                    val end = if (chip.openEnd) flat else radius
                    path.rewind()
                    path.addRoundRect(
                        RoundRect(
                            rect = chip.rect,
                            topLeft = start,
                            bottomLeft = start,
                            topRight = end,
                            bottomRight = end,
                        ),
                    )
                    drawPath(path, style.codeBackground)
                }
            }
        },
    )
}
