package com.example.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import java.net.URLDecoder

/** Marks an inline code span, padding included. [MarkdownInlineText] draws a chip behind it. */
internal const val INLINE_CODE_TAG = "md-inline-code"

/** Tag of the [LinkAnnotation.Clickable] on links that point at a VM path rather than the web. */
internal const val FILE_LINK_TAG = "file-link"

/** Inline code size relative to the surrounding text, so code in a heading scales with it. */
internal const val INLINE_CODE_EM = 0.84f
private const val FILE_LINK_EM = 0.9f

/*
 * Some characters in the text built here are not part of the message. They only steer layout:
 * chip padding, places where a line may break, places where it must not. All of them are
 * invisible, and [withoutLayoutOnlyChars] takes them out of copied text.
 */

/**
 * Word joiner: invisible, and forbids a line break where it stands. Android breaks lines after
 * `/` and `-` by itself, which strands the `/` of `/tmp/s.png` or the `--` of `--flag` at the end
 * of a line. In code, lines break only where [codeBreakPoints] says.
 */
private const val NO_BREAK = '\u2060'

/**
 * The chip's side padding is a real character, so the text around the chip makes room for it and
 * nothing overlaps. A no-break space keeps the padding attached to the code when lines wrap; the
 * small font size sets its width (0.6 of an em in a monospace font, so about 3sp in body text).
 *
 * The padding is written as a no-break space next to a word joiner. Nobody types that pair, so
 * copied text can be cleaned of the padding without touching a no-break space the author wrote.
 */
private const val CHIP_PAD = '\u00A0'
private const val CHIP_PAD_START = "$CHIP_PAD$NO_BREAK"
private const val CHIP_PAD_END = "$NO_BREAK$CHIP_PAD"
private const val CHIP_PAD_EM = 0.32f

/** Zero-width space: an invisible place where a line may break. */
private const val BREAK_HINT = '​'

/**
 * Code "words" at least this long get [BREAK_HINT]s. Shorter ones stay whole and move to the next
 * line instead, as a word would.
 */
private const val BREAKABLE_WORD_LENGTH = 20

/** A hint is never this close to either end of a word, so no stub of 1-3 characters is split off. */
private const val MIN_BREAK_FRAGMENT = 4

private const val BREAK_AFTER = "/\\._-:,;=&?|@#"

/** Android would break a line after these; inside a code word [NO_BREAK] stops it. */
private const val NATIVE_BREAK_AFTER = "/-"

/** Called when a link is tapped: web links get the URL, file links get the VM path. */
internal fun interface MarkdownLinkHandler {
    fun onLink(target: String, isWeb: Boolean)
}

internal class InlineTextStyle(
    val codeColor: Color,
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
        path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
    }
    return path.substringBefore('#')
}

/**
 * Where a long code word may break: after a separator (`/`, `.`, `_`, …) and at camelCase humps.
 * Returns the indices a break may come before.
 */
internal fun codeBreakPoints(word: String): List<Int> {
    if (word.length < BREAKABLE_WORD_LENGTH) return emptyList()
    return (MIN_BREAK_FRAGMENT..word.length - MIN_BREAK_FRAGMENT).filter { i ->
        val prev = word[i - 1]
        val cur = word[i]
        (prev in BREAK_AFTER && cur.isLetterOrDigit()) || (prev.isLowerCase() && cur.isUpperCase())
    }
}

/**
 * Appends code so that a word too long for one line breaks at a sensible place instead of
 * wherever the line happens to end.
 */
private fun AnnotatedString.Builder.appendBreakableCode(code: String) {
    var i = 0
    while (i < code.length) {
        if (code[i].isWhitespace()) {
            append(code[i])
            i++
            continue
        }
        var end = i
        while (end < code.length && !code[end].isWhitespace()) end++
        val word = code.substring(i, end)
        val breakPoints = codeBreakPoints(word)
        for (at in word.indices) {
            if (at in breakPoints) {
                append(BREAK_HINT)
            } else if (at > 0 && word[at - 1] in NATIVE_BREAK_AFTER) {
                append(NO_BREAK)
            }
            append(word[at])
        }
        i = end
    }
}

private fun AnnotatedString.Builder.appendCode(code: String, style: InlineTextStyle) {
    if (code.isEmpty()) return
    val pad = SpanStyle(fontFamily = MarkdownMonoFont, fontSize = CHIP_PAD_EM.em)
    pushStringAnnotation(INLINE_CODE_TAG, "")
    withStyle(pad) { append(CHIP_PAD_START) }
    withStyle(SpanStyle(fontFamily = MarkdownMonoFont, color = style.codeColor, fontSize = INLINE_CODE_EM.em)) {
        appendBreakableCode(code)
    }
    withStyle(pad) { append(CHIP_PAD_END) }
    pop()
}

private fun AnnotatedString.Builder.appendLink(link: MdInline.Link, style: InlineTextStyle) {
    val target = link.target
    val web = isWebUrl(target)
    val styles = TextLinkStyles(
        style = SpanStyle(
            color = style.linkColor,
            textDecoration = if (web) TextDecoration.Underline else null,
            // A path is shown the way code is: monospace and slightly smaller.
            fontFamily = if (web) null else MarkdownMonoFont,
            fontSize = if (web) TextUnit.Unspecified else FILE_LINK_EM.em,
        ),
        pressedStyle = SpanStyle(background = style.linkColor.copy(alpha = 0.16f)),
    )
    val handler = style.linkHandler
    val annotation = if (web) {
        LinkAnnotation.Url(
            url = target,
            styles = styles,
            linkInteractionListener = handler?.let { h -> LinkInteractionListener { h.onLink(target, true) } },
        )
    } else {
        // A path on the VM cannot be opened on the phone; the handler gets the path instead.
        val path = vmPathFromLink(target)
        LinkAnnotation.Clickable(
            tag = FILE_LINK_TAG,
            styles = styles,
            linkInteractionListener = handler?.let { h -> LinkInteractionListener { h.onLink(path, false) } },
        )
    }
    withLink(annotation) { appendInlines(link.children, style) }
}

private fun AnnotatedString.Builder.appendInlines(content: List<MdInline>, style: InlineTextStyle) {
    for (inline in content) {
        when (inline) {
            is MdInline.Text -> append(inline.text)

            is MdInline.Code -> appendCode(inline.code, style)

            is MdInline.Styled -> withStyle(
                when (inline.emphasis) {
                    MdEmphasis.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    MdEmphasis.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    MdEmphasis.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                },
            ) { appendInlines(inline.children, style) }

            is MdInline.Link -> if (inline.target.isEmpty()) {
                appendInlines(inline.children, style)
            } else {
                appendLink(inline, style)
            }

            MdInline.LineBreak -> append('\n')
        }
    }
}

internal fun buildInlineText(content: List<MdInline>, style: InlineTextStyle): AnnotatedString = buildAnnotatedString {
    appendInlines(content, style)
}

/** What the user should get when copying [text]: the message without padding and break hints. */
internal fun withoutLayoutOnlyChars(text: String): String = text
    .replace(CHIP_PAD_START, "")
    .replace(CHIP_PAD_END, "")
    .filterNot { it == BREAK_HINT || it == NO_BREAK }
