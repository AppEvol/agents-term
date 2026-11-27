package com.appevol.agentsterm.ui.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Metadata for a trailing unclosed top-level [FencedCodeBlock] used by the O(1) append fast path. */
internal data class OpenFenceInfo(
    val fenceChar: Char,
    val openingLength: Int,
    val indent: Int,
    val language: String,
    /** Line-start offset within the parsed input string where the opening fence begins. */
    val blockStartOffset: Int,
    /** Offset immediately after the opening fence line's `\n`, or `null` if the line is still incomplete. */
    val bodyStartOffset: Int?,
)

/** A top-level parsed [MdBlock] paired with its parser-provided line-start offset in the input string. */
internal data class TopLevelBlock(
    val block: MdBlock,
    /** Line-start char offset in the parsed input string, or `-1` if no source span was attached. */
    val startOffset: Int,
)

/** Result of parsing a Markdown document with top-level source spans and structural metadata. */
internal data class ParsedDocument(
    val blocks: List<TopLevelBlock>,
    val hasLinkReferenceDefinition: Boolean,
    val trailingOpenFence: OpenFenceInfo?,
)

/**
 * Parses chat Markdown into [MdBlock]s with commonmark-java, the JVM reference implementation of
 * the CommonMark spec, plus the GitHub extensions agents use: tables, strikethrough, task lists
 * and bare URLs.
 *
 * Deliberate differences from the spec, because this renders chat messages and not documents:
 * - A single newline is a line break (as in GitHub comments), not a space.
 * - Indented code blocks are off. Agents fence their code, while text that merely happens to be
 *   indented by four spaces is common and must not turn into a code block.
 * - Raw HTML is shown as text; nothing is interpreted except `<br>`.
 * - `•` starts a list item like `-` does, because agents print it.
 */
internal object MarkdownParser {
    // Thread-safe and reusable, per the commonmark-java docs.
    private val parser: Parser = Parser.builder()
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .enabledBlockTypes(
            setOf(
                Heading::class.java,
                FencedCodeBlock::class.java,
                ThematicBreak::class.java,
                ListBlock::class.java,
                BlockQuote::class.java,
            ),
        )
        .build()

    fun parse(markdown: String): List<MdBlock> = parseDocument(markdown).blocks.map { it.block }

    fun parseDocument(markdown: String): ParsedDocument {
        if (markdown.isEmpty()) {
            return ParsedDocument(
                blocks = emptyList(),
                hasLinkReferenceDefinition = false,
                trailingOpenFence = null,
            )
        }
        val normalized = normalizeBullets(markdown)
        val document = parser.parse(normalized)
        val state = ConversionState()
        val topLevel = mutableListOf<TopLevelBlock>()
        var lastAstNode: Node? = null

        for (child in document.children()) {
            lastAstNode = child
            val converted = block(child, state) ?: continue
            topLevel.add(
                TopLevelBlock(
                    block = converted,
                    startOffset = blockLineStartOffset(normalized, child),
                ),
            )
        }

        val openFence = (lastAstNode as? FencedCodeBlock)?.let { fence ->
            extractOpenFenceInfo(normalized, fence)
        }

        return ParsedDocument(
            blocks = topLevel,
            hasLinkReferenceDefinition = state.hasLinkReferenceDefinition,
            trailingOpenFence = openFence,
        )
    }

    private class ConversionState {
        var hasLinkReferenceDefinition: Boolean = false
    }

    private fun extractOpenFenceInfo(source: String, fence: FencedCodeBlock): OpenFenceInfo? {
        val closingLen = fence.closingFenceLength
        if (closingLen != null && closingLen > 0) return null
        val startOffset = blockLineStartOffset(source, fence)
        if (startOffset < 0) return null
        val fenceChar = fence.fenceCharacter?.firstOrNull() ?: return null
        val openingLen = fence.openingFenceLength ?: return null
        if (openingLen < MIN_FENCE_LEN) return null
        val firstNewline = source.indexOf('\n', startOffset)
        val bodyStart = if (firstNewline == -1) null else firstNewline + 1
        return OpenFenceInfo(
            fenceChar = fenceChar,
            openingLength = openingLen,
            indent = fence.fenceIndent,
            language = fence.info.orEmpty().trim().substringBefore(' '),
            blockStartOffset = startOffset,
            bodyStartOffset = bodyStart,
        )
    }

    private fun blockLineStartOffset(source: String, node: Node): Int {
        val firstSpan = node.sourceSpans.firstOrNull() ?: return -1
        val idx = firstSpan.inputIndex.coerceIn(0, source.length)
        if (idx == 0) return 0
        val prevNewline = source.lastIndexOf('\n', idx - 1)
        return if (prevNewline == -1) 0 else prevNewline + 1
    }

    /**
     * Rewrites leading `• ` to `- ` outside fenced code while preserving exact string length and
     * line endings (`\r\n` vs `\n`) so parser [org.commonmark.node.SourceSpan] input indices match
     * the original [markdown] string 1:1.
     */
    private fun normalizeBullets(markdown: String): String {
        if ('•' !in markdown) return markdown
        var out: StringBuilder? = null
        var inFence = false
        var pos = 0
        val len = markdown.length
        while (pos < len) {
            val nlIdx = markdown.indexOf('\n', pos)
            val lineEnd = if (nlIdx == -1) len else nlIdx
            val contentEnd = trimTrailingCr(markdown, pos, lineEnd)
            val contentStart = skipLeadingIndent(markdown, pos, contentEnd)
            if (isFenceToggleLine(markdown, contentStart, contentEnd)) {
                inFence = !inFence
            } else if (!inFence && isDotBullet(markdown, contentStart, contentEnd)) {
                val builder = out ?: StringBuilder(markdown).also { out = it }
                builder.setCharAt(contentStart, '-')
            }
            pos = if (nlIdx == -1) len else nlIdx + 1
        }
        return out?.toString() ?: markdown
    }

    private fun trimTrailingCr(text: String, start: Int, end: Int): Int = if (end > start && text[end - 1] == '\r') end - 1 else end

    private fun skipLeadingIndent(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun isDotBullet(text: String, start: Int, end: Int): Boolean = start + 1 < end && text[start] == '•' && text[start + 1] == ' '

    private fun isFenceToggleLine(text: String, start: Int, end: Int): Boolean {
        if (end - start < MIN_FENCE_LEN) return false
        val ch = text[start]
        return (ch == '`' || ch == '~') && text[start + 1] == ch && text[start + 2] == ch
    }

    private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

    private fun blocks(parent: Node, state: ConversionState): List<MdBlock> = parent.children().mapNotNull { block(it, state) }.toList()

    private fun block(node: Node, state: ConversionState): MdBlock? = when (node) {
        is Paragraph -> MdBlock.Paragraph(inlines(node))

        is Heading -> MdBlock.Heading(node.level, inlines(node))

        is FencedCodeBlock -> MdBlock.CodeBlock(
            language = node.info.orEmpty().trim().substringBefore(' '),
            code = node.literal.orEmpty().trimEnd('\n'),
        )

        is IndentedCodeBlock -> MdBlock.CodeBlock("", node.literal.orEmpty().trimEnd('\n'))

        is BlockQuote -> MdBlock.Quote(blocks(node, state))

        is BulletList -> itemList(node, ordered = false, start = 1, state = state)

        is OrderedList -> itemList(node, ordered = true, start = node.markerStartNumber ?: 1, state = state)

        is ThematicBreak -> MdBlock.Rule

        is TableBlock -> table(node)

        is HtmlBlock -> MdBlock.Paragraph(listOf(MdInline.Text(node.literal.orEmpty().trimEnd('\n'))))

        is LinkReferenceDefinition -> {
            state.hasLinkReferenceDefinition = true
            null
        }

        // An extension block this renderer does not know: keep its text rather than drop it.
        else -> MdBlock.Paragraph(inlines(node)).takeIf { it.content.isNotEmpty() }
    }

    private fun itemList(
        list: ListBlock,
        ordered: Boolean,
        start: Int,
        state: ConversionState,
    ): MdBlock.ItemList {
        val items = list.children().filterIsInstance<ListItem>().map { item ->
            val marker = item.firstChild as? TaskListItemMarker
            MdListItem(task = marker?.isChecked, blocks = blocks(item, state))
        }
        return MdBlock.ItemList(ordered, start, list.isTight, items.toList())
    }

    private fun table(table: TableBlock): MdBlock.Table {
        // TableBlock > TableHead/TableBody > TableRow > TableCell
        val rows = table.children().flatMap { it.children() }.filterIsInstance<TableRow>().toList()
        val cells = rows.map { row -> row.children().filterIsInstance<TableCell>().toList() }
        val header = cells.firstOrNull().orEmpty()
        val alignments = header.map {
            when (it.alignment) {
                TableCell.Alignment.CENTER -> MdAlign.Center
                TableCell.Alignment.RIGHT -> MdAlign.End
                else -> MdAlign.Start
            }
        }
        val converted = cells.map { row -> MdTableRow(row.map(::inlines)) }
        return MdBlock.Table(
            alignments = alignments,
            header = converted.firstOrNull() ?: MdTableRow(emptyList()),
            rows = converted.drop(1),
        )
    }

    private fun inlines(parent: Node): List<MdInline> {
        val out = mutableListOf<MdInline>()
        for (child in parent.children()) appendInline(child, out)
        return out
    }

    private fun appendInline(node: Node, out: MutableList<MdInline>) {
        when (node) {
            is Text -> out.addText(node.literal.orEmpty())

            is Code -> out.add(MdInline.Code(node.literal.orEmpty()))

            is Emphasis -> out.add(MdInline.Styled(MdEmphasis.Italic, inlines(node)))

            is StrongEmphasis -> out.add(MdInline.Styled(MdEmphasis.Bold, inlines(node)))

            is Strikethrough -> out.add(MdInline.Styled(MdEmphasis.Strikethrough, inlines(node)))

            is Link -> out.add(MdInline.Link(node.destination.orEmpty(), inlines(node)))

            // No image loading in chat: an image is a link to itself, labelled with its alt text.
            is Image -> {
                val alt = plainText(inlines(node)).ifBlank { node.destination.orEmpty() }
                out.add(MdInline.Link(node.destination.orEmpty(), listOf(MdInline.Text(alt))))
            }

            is SoftLineBreak, is HardLineBreak -> out.add(MdInline.LineBreak)

            is HtmlInline -> {
                val html = node.literal.orEmpty()
                if (htmlBreakRegex.matches(html)) out.add(MdInline.LineBreak) else out.addText(html)
            }

            is TaskListItemMarker -> Unit

            else -> for (child in node.children()) appendInline(child, out)
        }
    }

    private const val MIN_FENCE_LEN = 3
    private val htmlBreakRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** Adjacent text nodes (commonmark splits at every `[`, `_`, …) become one. */
    private fun MutableList<MdInline>.addText(text: String) {
        if (text.isEmpty()) return
        val last = lastOrNull()
        if (last is MdInline.Text) set(lastIndex, MdInline.Text(last.text + text)) else add(MdInline.Text(text))
    }

    private fun plainText(inlines: List<MdInline>): String = inlines.joinToString("") {
        when (it) {
            is MdInline.Text -> it.text
            is MdInline.Code -> it.code
            is MdInline.Styled -> plainText(it.children)
            is MdInline.Link -> plainText(it.children)
            MdInline.LineBreak -> " "
        }
    }
}
