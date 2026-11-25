package com.example.ui.markdown

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.isLunaControls

/**
 * Renders Markdown as the app shows it in chat.
 *
 * The pipeline has three parts, each on its own so it can be tested on its own:
 * 1. [MarkdownParser] turns the source into a tree of [MdBlock]s (commonmark-java does the
 *    parsing, so the syntax rules are the CommonMark and GitHub ones, not ours).
 * 2. [buildInlineText] turns inline content into an `AnnotatedString`.
 * 3. The composables in this package lay the blocks out.
 *
 * Nothing here knows about chat: this composable takes Markdown and a width and draws it. That is
 * what the golden screenshot tests render, with no app screen around it.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
) {
    val blocks = rememberParsedMarkdownBlocks(markdown = markdown, isStreaming = isStreaming)
    val style = rememberMarkdownStyle(textColor)
    MarkdownSelectionContainer {
        MarkdownBlocks(blocks = blocks, style = style, modifier = modifier)
    }
}

/** Colors and type for one Markdown rendering. A value, so unchanged blocks skip recomposition. */
@Immutable
internal data class MarkdownStyle(
    val textColor: Color,
    val mutedColor: Color,
    val accentColor: Color,
    val codeBackground: Color,
    val outlineColor: Color,
    val headerBackground: Color,
    val body: TextStyle,
    val headings: List<TextStyle>,
    val inline: InlineTextStyle,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]
}

@Composable
internal fun rememberMarkdownStyle(textColor: Color): MarkdownStyle {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val linkHandler = rememberMarkdownLinkHandler()
    // The XP look sets everything in the system sans-serif, which has real bold weights of its own.
    val systemFont = isLunaControls
    return remember(colors, typography, textColor, linkHandler, systemFont) {
        val bodyFont = if (systemFont) FontFamily.SansSerif else MarkdownBodyFont
        val headingFont = if (systemFont) FontFamily.SansSerif else MarkdownHeadingFont
        val body = typography.bodyLarge.copy(fontFamily = bodyFont, lineHeight = 23.sp)
        val title = typography.titleLarge.copy(fontFamily = headingFont, fontWeight = FontWeight.SemiBold)
        MarkdownStyle(
            textColor = textColor,
            mutedColor = colors.onSurfaceVariant,
            accentColor = colors.primary,
            codeBackground = colors.surfaceContainerHighest,
            outlineColor = colors.outlineVariant,
            headerBackground = colors.surfaceContainerHigh,
            body = body,
            headings = listOf(
                title.copy(fontSize = 21.sp, lineHeight = 27.sp),
                title.copy(fontSize = 18.5.sp, lineHeight = 25.sp),
                title.copy(fontSize = 16.5.sp, lineHeight = 23.sp),
                body.copy(fontWeight = FontWeight.SemiBold),
            ),
            inline = InlineTextStyle(
                codeColor = colors.primary,
                linkColor = colors.primary,
                linkHandler = linkHandler,
            ),
        )
    }
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

private val BlockSpacing = 10.dp
private val TightSpacing = 4.dp

/** Stable `LazyColumn` contentType for slot reuse across virtualized Markdown blocks. */
internal fun MdBlock.contentType(): String = when (this) {
    is MdBlock.Paragraph -> "md_paragraph"
    is MdBlock.Heading -> "md_heading"
    is MdBlock.CodeBlock -> "md_code"
    is MdBlock.Quote -> "md_quote"
    is MdBlock.ItemList -> "md_list"
    is MdBlock.Table -> "md_table"
    MdBlock.Rule -> "md_rule"
}

/**
 * Renders a single top-level [MdBlock] inside a [MarkdownSelectionContainer] so `ChatScreen` can
 * emit each top-level block as its own virtualized `LazyColumn` item while preserving text
 * selection and clean copy behavior.
 */
@Composable
internal fun VirtualizedMarkdownBlock(
    block: MdBlock,
    isFirst: Boolean,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    if (block is MdBlock.Rule) {
        HorizontalDivider(
            color = style.outlineColor,
            modifier = modifier.padding(vertical = 4.dp),
        )
    } else {
        Box(modifier = modifier) {
            MarkdownSelectionContainer {
                MarkdownBlockContent(block = block, isFirst = isFirst, style = style, listDepth = 0)
            }
        }
    }
}

/** [listDepth] is how many lists these blocks are nested in; it picks the bullet shape. */
@Composable
internal fun MarkdownBlocks(
    blocks: List<MdBlock>,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    spacing: Dp = BlockSpacing,
    listDepth: Int = 0,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockContent(
                block = block,
                isFirst = index == 0,
                style = style,
                listDepth = listDepth,
            )
        }
    }
}

@Composable
private fun MarkdownBlockContent(
    block: MdBlock,
    isFirst: Boolean,
    style: MarkdownStyle,
    listDepth: Int,
) {
    when (block) {
        is MdBlock.Paragraph -> MarkdownParagraph(block = block, style = style)

        is MdBlock.Heading -> MarkdownHeading(block = block, isFirst = isFirst, style = style)

        is MdBlock.CodeBlock -> CodeBlockCard(language = block.language, code = block.code)

        is MdBlock.Quote -> MarkdownQuote(block, style, listDepth)

        is MdBlock.ItemList -> MarkdownList(block, style, listDepth)

        is MdBlock.Table -> MarkdownTable(block, style)

        MdBlock.Rule -> HorizontalDivider(
            color = style.outlineColor,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** Wrapper taking `@Immutable` [MdBlock.Paragraph] so unchanged paragraphs skip recomposition. */
@Composable
private fun MarkdownParagraph(block: MdBlock.Paragraph, style: MarkdownStyle) {
    MarkdownInlineText(block.content, style.body, style)
}

/** Wrapper taking `@Immutable` [MdBlock.Heading] so unchanged headings skip recomposition. */
@Composable
private fun MarkdownHeading(block: MdBlock.Heading, isFirst: Boolean, style: MarkdownStyle) {
    MarkdownInlineText(
        content = block.content,
        textStyle = style.heading(block.level),
        style = style,
        // A heading belongs to what follows it, so it sits further from what is above.
        modifier = if (isFirst) Modifier else Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun MarkdownQuote(quote: MdBlock.Quote, style: MarkdownStyle, listDepth: Int) {
    val barColor = style.outlineColor
    MarkdownBlocks(
        blocks = quote.blocks,
        style = remember(style) { style.copy(textColor = style.mutedColor) },
        listDepth = listDepth,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = barColor,
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
            .padding(start = 13.dp),
    )
}

private val bullets = listOf("•", "◦", "▪")

@Composable
private fun MarkdownList(list: MdBlock.ItemList, style: MarkdownStyle, listDepth: Int) {
    val density = LocalDensity.current
    val lineHeight = with(density) { style.body.lineHeight.toDp() }
    val markerStyle = style.body.copy(color = style.accentColor)
    // Every number gets the width of the widest, so the item texts line up and "9." and "10."
    // end at the same place.
    val measurer = rememberTextMeasurer()
    val markerWidth = remember(list.ordered, list.start, list.items.size, markerStyle, density) {
        val widest = if (list.ordered) "${list.start + list.items.size - 1}." else bullets[0]
        with(density) { measurer.measure(widest, markerStyle).size.width.toDp() }
    }
    val spacing = if (list.tight) TightSpacing else BlockSpacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        list.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.task != null) {
                    TaskMarker(checked = item.task, style = style, lineHeight = lineHeight)
                } else {
                    Text(
                        text = if (list.ordered) "${list.start + index}." else bullets[listDepth.coerceAtMost(bullets.lastIndex)],
                        style = markerStyle,
                        textAlign = if (list.ordered) TextAlign.End else TextAlign.Center,
                        modifier = Modifier.width(markerWidth),
                    )
                }
                MarkdownBlocks(
                    blocks = item.blocks,
                    style = style,
                    spacing = spacing,
                    listDepth = listDepth + 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A task list checkbox, as tall as one line of text so it centers on the item's first line. */
@Composable
private fun TaskMarker(checked: Boolean, style: MarkdownStyle, lineHeight: Dp) {
    Box(modifier = Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
            contentDescription = stringResource(if (checked) R.string.markdown_task_done else R.string.markdown_task_open),
            tint = if (checked) style.accentColor else style.mutedColor,
            modifier = Modifier.size(18.dp),
        )
    }
}
