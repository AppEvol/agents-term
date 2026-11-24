package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

private sealed class MarkdownBlock {
    data class Paragraph(val lines: List<String>) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<Pair<String, String>>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
}

private val headingRegex = Regex("^(#{1,4})\\s+(.*)$")
private val numberedItemRegex = Regex("^(\\d+\\.)\\s+(.*)$")

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block ```lang
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // Heading (#, ##, ###, ####)
        val headingMatch = headingRegex.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // Bullet list (-, *, •)
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) {
                    items.add(t.drop(2))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Numbered list (1. ..., 2. ...)
        if (numberedItemRegex.matches(trimmed)) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val m = numberedItemRegex.matchEntire(lines[i].trim()) ?: break
                items.add(m.groupValues[1] to m.groupValues[2])
                i++
            }
            blocks.add(MarkdownBlock.NumberedList(items))
            continue
        }

        // Paragraph
        val para = mutableListOf<String>()
        while (i < lines.size) {
            val t = lines[i].trim()
            if (
                t.isEmpty() ||
                t.startsWith("```") ||
                t.startsWith("- ") ||
                t.startsWith("* ") ||
                t.startsWith("• ") ||
                headingRegex.matches(t) ||
                numberedItemRegex.matches(t)
            ) {
                break
            }
            para.add(lines[i])
            i++
        }
        if (para.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(para))
        }
    }
    return blocks
}

internal const val INLINE_CODE_TAG = "inline-code"

/** Tag of the [LinkAnnotation.Clickable] on links that point at a VM path rather than the web. */
internal const val FILE_LINK_TAG = "file-link"

/** Called when a link is tapped: web links get the URL, file links get the VM path. */
internal fun interface MarkdownLinkHandler {
    fun onLink(target: String, isWeb: Boolean)
}

private class InlineContext(
    val inlineCodeFg: Color,
    val linkColor: Color,
    val linkHandler: MarkdownLinkHandler?,
)

private val webSchemes = listOf("https://", "http://", "mailto:")

private fun isWebUrl(url: String): Boolean = webSchemes.any { url.startsWith(it, ignoreCase = true) }

/** `file:///abs/path#L10-L20` → `/abs/path`; other non-web targets are returned as-is. */
internal fun vmPathFromLink(url: String): String {
    var path = url
    if (path.startsWith("file://", ignoreCase = true)) {
        path = path.substring("file://".length)
        path = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
    }
    return path.substringBefore('#')
}

/**
 * Parses `[label](target)` starting at [start] (which must be `[`). Returns label, target and the
 * index just past the closing `)`, or null if this is not a link.
 */
private fun matchMarkdownLink(text: String, start: Int): Triple<String, String, Int>? {
    var depth = 0
    var i = start
    var labelEnd = -1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    labelEnd = i
                    break
                }
            }
            '\n' -> if (i + 1 < text.length && text[i + 1] == '\n') return null
        }
        i++
    }
    if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    var j = labelEnd + 2
    var parens = 1
    while (j < text.length) {
        val c = text[j]
        if (c.isWhitespace()) return null
        if (c == '(') parens++
        if (c == ')') {
            parens--
            if (parens == 0) break
        }
        j++
    }
    if (j >= text.length) return null
    val target = text.substring(labelEnd + 2, j).removeSurrounding("<", ">")
    if (target.isEmpty()) return null
    return Triple(text.substring(start + 1, labelEnd), target, j + 1)
}

private val trailingUrlPunctuation = ".,;:!?'\"*_~"

/** Length of a bare `http(s)://` URL starting at [start], or 0 if there is none. */
private fun matchBareUrl(text: String, start: Int): Int {
    val scheme = when {
        text.startsWith("https://", start, ignoreCase = true) -> 8
        text.startsWith("http://", start, ignoreCase = true) -> 7
        else -> return 0
    }
    if (start > 0 && text[start - 1].isLetterOrDigit()) return 0
    var end = start
    while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"`") end++
    // Drop trailing sentence punctuation and a `)` that closes text around the URL.
    while (end > start + scheme) {
        val last = text[end - 1]
        if (last in trailingUrlPunctuation) {
            end--
        } else if (last == ')' || last == ']') {
            val open = if (last == ')') '(' else '['
            val segment = text.substring(start, end)
            if (segment.count { it == last } > segment.count { it == open }) end-- else break
        } else {
            break
        }
    }
    return if (end - start > scheme) end - start else 0
}

private fun AnnotatedString.Builder.appendLink(
    label: String,
    target: String,
    ctx: InlineContext,
) {
    val web = isWebUrl(target)
    val styles = TextLinkStyles(
        style = SpanStyle(
            color = ctx.linkColor,
            textDecoration = if (web) TextDecoration.Underline else null,
            fontFamily = if (web) null else MonoFontFamily,
        ),
        pressedStyle = SpanStyle(background = ctx.linkColor.copy(alpha = 0.16f)),
    )
    val handler = ctx.linkHandler
    val link = if (web) {
        LinkAnnotation.Url(
            url = target,
            styles = styles,
            linkInteractionListener = handler?.let { h -> LinkInteractionListener { h.onLink(target, true) } },
        )
    } else {
        val path = vmPathFromLink(target)
        LinkAnnotation.Clickable(
            tag = FILE_LINK_TAG,
            styles = styles,
            linkInteractionListener = handler?.let { h -> LinkInteractionListener { h.onLink(path, false) } },
        )
    }
    withLink(link) { appendInline(label, ctx, inLink = true) }
}

private fun AnnotatedString.Builder.appendInline(text: String, ctx: InlineContext, inLink: Boolean) {
    var idx = 0
    val len = text.length
    while (idx < len) {
        val ch = text[idx]
        // Inline code `...`
        if (ch == '`') {
            val end = text.indexOf('`', idx + 1)
            if (end > idx) {
                pushStringAnnotation(INLINE_CODE_TAG, "")
                withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        color = ctx.inlineCodeFg,
                        fontSize = 13.sp,
                    )
                ) {
                    // The surrounding spaces are the chip's inner padding.
                    append(" ${text.substring(idx + 1, end)} ")
                }
                pop()
                idx = end + 1
                continue
            }
        }
        // Link [label](target). Links cannot nest, so inside a label `[` is plain text.
        if (ch == '[' && !inLink) {
            val link = matchMarkdownLink(text, idx)
            if (link != null) {
                appendLink(link.first, link.second, ctx)
                idx = link.third
                continue
            }
        }
        // Bare web URL
        if ((ch == 'h' || ch == 'H') && !inLink) {
            val urlLen = matchBareUrl(text, idx)
            if (urlLen > 0) {
                val url = text.substring(idx, idx + urlLen)
                appendLink(url, url, ctx)
                idx += urlLen
                continue
            }
        }
        // Bold **...**
        if (idx + 1 < len && ch == '*' && text[idx + 1] == '*') {
            val end = text.indexOf("**", idx + 2)
            if (end > idx + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInline(text.substring(idx + 2, end), ctx, inLink)
                }
                idx = end + 2
                continue
            }
        }
        // Italic *...*
        if (ch == '*') {
            val end = text.indexOf('*', idx + 1)
            if (end > idx + 1 && !text.substring(idx + 1, end).contains('\n')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(text.substring(idx + 1, end), ctx, inLink)
                }
                idx = end + 1
                continue
            }
        }
        append(ch)
        idx++
    }
}

/**
 * Inline code is marked with an [INLINE_CODE_TAG] annotation (not `SpanStyle.background`), and
 * [InlineMarkdownText] draws its background as a rounded chip per line.
 *
 * A background span fills the full line height, so code on consecutive lines fused into one solid
 * slab. Plain text with code to its left, above and below then looked like it sat in a dark
 * cut-out box.
 *
 * `[label](url)` links and bare `http(s)://` URLs show as tappable links. Web links open in the
 * browser; other targets (agents' `file:///vm/path#L10` links) show only the label, and tapping
 * passes the VM path to [linkHandler], since those paths cannot be opened on the phone.
 */
internal fun parseInlineStyles(
    text: String,
    inlineCodeFg: Color,
    linkColor: Color = inlineCodeFg,
    linkHandler: MarkdownLinkHandler? = null,
): AnnotatedString = buildAnnotatedString {
    appendInline(text, InlineContext(inlineCodeFg, linkColor, linkHandler), inLink = false)
}

/**
 * One rectangle per visual line of each inline-code range, in the text's local coordinates.
 * Ranges come from the layout's own text, so a layout that is one frame behind the latest
 * streamed text can never be indexed out of bounds.
 */
internal fun inlineCodeLineRects(layout: TextLayoutResult): List<Rect> {
    val text = layout.layoutInput.text
    val rects = mutableListOf<Rect>()
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
            rects.add(
                Rect(
                    left = minOf(first.left, last.left),
                    top = layout.getLineTop(line),
                    right = maxOf(first.right, last.right),
                    bottom = layout.getLineBottom(line),
                )
            )
        }
    }
    return rects
}

/** Text with rounded "chip" backgrounds behind inline code. */
@Composable
private fun InlineMarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val result = layout ?: return@drawBehind
            val padX = 1.dp.toPx()
            val insetY = 2.dp.toPx()
            val radius = CornerRadius(4.dp.toPx())
            for (rect in inlineCodeLineRects(result)) {
                drawRoundRect(
                    color = codeBackground,
                    topLeft = Offset(rect.left - padX, rect.top + insetY),
                    size = Size(rect.width + 2 * padX, (rect.height - 2 * insetY).coerceAtLeast(0f)),
                    cornerRadius = radius,
                )
            }
        },
    )
}

/** Web links open in the browser; VM file links copy their path, since the phone can't open them. */
@Composable
private fun rememberMarkdownLinkHandler(): MarkdownLinkHandler {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(uriHandler, clipboard, context) {
        MarkdownLinkHandler { target, isWeb ->
            if (isWeb) {
                // No app can handle some URIs; that must not crash the chat.
                runCatching { uriHandler.openUri(target) }
            } else {
                clipboard.setText(AnnotatedString(target))
                Toast.makeText(context, context.getString(R.string.markdown_path_copied, target), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeFg = MaterialTheme.colorScheme.primary
    val linkHandler = rememberMarkdownLinkHandler()

    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in blocks) {
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        InlineMarkdownText(
                            text = parseInlineStyles(block.text, codeFg, codeFg, linkHandler),
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor,
                            codeBackground = codeBg,
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        InlineMarkdownText(
                            text = parseInlineStyles(block.lines.joinToString("\n"), codeFg, codeFg, linkHandler),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                            color = textColor,
                            codeBackground = codeBg,
                        )
                    }

                    is MarkdownBlock.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (item in block.items) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    InlineMarkdownText(
                                        text = parseInlineStyles(item, codeFg, codeFg, linkHandler),
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                                        color = textColor,
                                        codeBackground = codeBg,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.NumberedList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((num, item) in block.items) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = num,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    InlineMarkdownText(
                                        text = parseInlineStyles(item, codeFg, codeFg, linkHandler),
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                                        color = textColor,
                                        codeBackground = codeBg,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockCard(language = block.language, code = block.code)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_code),
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = code,
                fontFamily = MonoFontFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
